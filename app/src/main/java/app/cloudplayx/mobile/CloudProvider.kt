package app.cloudplayx.mobile

import android.app.AuthenticationRequiredException
import android.app.PendingIntent
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's supported way to expose files to other apps (Storage Access Framework). Two roots appear in the system
 * Files app and in every app's file picker:
 *
 *  - "CloudPlayX" (cloud): the live cloud drive. Nothing is downloaded when you browse; contents are fetched block by
 *    block, and cached, when an app reads a file. Read-only.
 *  - "CloudPlayX Library" (local): files already downloaded/extracted by CloudPlayX. These are real local files, so
 *    apps that need a normal file descriptor (emulators, installers, importers) can use them. Files can be deleted
 *    from a file manager (this is the only write operation offered).
 *
 * Android cannot mount a drive letter or let apps see arbitrary private folders; a file manager that does not
 * implement the document-provider interface will not list these roots.
 */
class CloudProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "app.cloudplayx.mobile.documents"
        private const val CLOUD_ROOT_ID = "cloudplayx"
        private const val CLOUD_ROOT_DOC = "root"
        private const val LIB_ROOT_ID = "library"
        const val LIB_ROOT_DOC = "lib:"
        private const val LIST_TTL_MS = 30_000L

        private val ROOT_COLS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOC_COLS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )

        fun libraryDocId(store: LibraryStore, file: File): String = LIB_ROOT_DOC + (store.relativePath(file) ?: "")
    }

    private lateinit var settings: Settings
    private var remoteCache: Remote? = null
    private var remoteVersion = -1
    private val entries = ConcurrentHashMap<String, RemoteEntry>()
    private val listings = ConcurrentHashMap<String, Pair<Long, List<RemoteEntry>>>()

    private val store: LibraryStore get() = LibraryHolder.get(context!!)

    override fun onCreate(): Boolean {
        settings = Settings(context!!)
        return true
    }

    // ---- roots ----

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLS)
        val summary = when (settings.backend) {
            "gdrive" -> "Google Drive · read-only · streamed on demand"
            "webdav" -> "WebDAV · read-only · streamed on demand"
            else -> "Not set up: open the CloudPlayX app"
        }
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, CLOUD_ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, CLOUD_ROOT_DOC)
            add(Root.COLUMN_TITLE, "CloudPlayX")
            add(Root.COLUMN_SUMMARY, summary)
            add(Root.COLUMN_ICON, R.drawable.ic_cloud)
            add(Root.COLUMN_FLAGS, 0)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        val lib = store
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, LIB_ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, LIB_ROOT_DOC)
            add(Root.COLUMN_TITLE, "CloudPlayX Library")
            add(Root.COLUMN_SUMMARY, "Downloaded files on this phone")
            add(Root.COLUMN_ICON, R.drawable.ic_cloud)
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, lib.root.usableSpace)
        }
        return cursor
    }

    // ---- documents ----

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = guard {
        val cursor = MatrixCursor(projection ?: DOC_COLS)
        if (isLib(documentId)) addLibRow(cursor, libFile(documentId))
        else addRow(cursor, entryFor(documentId), documentId)
        cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?,
    ): Cursor = guard {
        val cursor = MatrixCursor(projection ?: DOC_COLS)
        cursor.setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        if (isLib(parentDocumentId)) {
            val dir = libFile(parentDocumentId)
            if (!dir.isDirectory) throw FileNotFoundException("Not a folder.")
            val kids = (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            for (f in kids) addLibRow(cursor, f)
        } else {
            for (e in children(parentDocumentId)) addRow(cursor, e, e.id)
        }
        cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        guard {
            if (mode != "r") throw FileNotFoundException("CloudPlayX is read-only in this version.")
            if (isLib(documentId)) {
                val f = libFile(documentId)
                if (!f.isFile) throw FileNotFoundException("Not a file.")
                return@guard ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            val entry = entryFor(documentId)
            if (entry.isDir) throw FileNotFoundException("This is a folder.")
            val remote = remote()
            val cache = CacheHolder.get(context!!)
            val thread = HandlerThread("cpx-io").apply { start() }
            try {
                val storage = context!!.getSystemService(StorageManager::class.java)
                storage.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    FileCallback(entry, remote, cache, thread),
                    Handler(thread.looper),
                )
            } catch (e: Exception) {
                thread.quitSafely() // do not leak the I/O thread if the descriptor could not be created
                throw e
            }
        }

    /** The only write operation: lets the user delete downloaded files from a file manager. Cloud files are never touched. */
    override fun deleteDocument(documentId: String) {
        guard {
            if (!isLib(documentId)) throw FileNotFoundException("Cloud files are read-only.")
            val f = libFile(documentId)
            if (f.canonicalPath == store.root.canonicalPath) throw FileNotFoundException("The library folder itself cannot be deleted.")
            if (!store.delete(f)) throw FileNotFoundException("Could not delete ${f.name}.")
            Transfers.libraryChanged(context!!)
        }
    }

    /** Answers the kernel's read() calls for one open cloud file, straight from the block cache. */
    private class FileCallback(
        private val entry: RemoteEntry,
        private val remote: Remote,
        private val cache: BlockCache,
        private val thread: HandlerThread,
    ) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = entry.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                return cache.read(entry, remote, offset, data, 0, size)
            } catch (e: IOException) {
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            thread.quitSafely()
        }
    }

    // ---- helpers ----

    private fun isLib(docId: String) = docId.startsWith(LIB_ROOT_DOC)

    private fun libFile(docId: String): File {
        val rel = docId.removePrefix(LIB_ROOT_DOC)
        if (rel.isEmpty()) return store.root
        return store.resolve(rel)?.takeIf { it.exists() } ?: throw FileNotFoundException("Not found: $docId")
    }

    private fun mimeFor(name: String) = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"

    private fun addLibRow(cursor: MatrixCursor, f: File) {
        val isRoot = f.canonicalPath == store.root.canonicalPath
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, libraryDocId(store, f))
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) "CloudPlayX Library" else f.name)
            add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else mimeFor(f.name))
            add(Document.COLUMN_SIZE, if (f.isDirectory) null else f.length())
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, if (isRoot) 0 else Document.FLAG_SUPPORTS_DELETE)
        }
    }

    private fun addRow(cursor: MatrixCursor, e: RemoteEntry, docId: String) {
        val mime = when {
            e.isDir -> Document.MIME_TYPE_DIR
            else -> e.mime ?: mimeFor(e.name)
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, e.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, if (e.isDir) null else e.size)
            add(Document.COLUMN_LAST_MODIFIED, if (e.modified > 0) e.modified else null)
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    private fun entryFor(docId: String): RemoteEntry {
        if (docId == CLOUD_ROOT_DOC) return RemoteEntry(remote().rootId, "CloudPlayX", true, 0, 0)
        entries[docId]?.let { return it }
        val found = remote().stat(docId) ?: throw FileNotFoundException("Not found: $docId")
        entries[docId] = found
        return found
    }

    private fun children(parentDoc: String): List<RemoteEntry> {
        val r = remote()
        val id = if (parentDoc == CLOUD_ROOT_DOC) r.rootId else parentDoc
        val now = System.currentTimeMillis()
        val hit = listings[id]
        if (hit != null && now - hit.first < LIST_TTL_MS) return hit.second
        val list = r.list(id).sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
        for (e in list) entries[e.id] = e
        listings[id] = now to list
        return list
    }

    @Synchronized
    private fun remote(): Remote {
        val current = remoteCache
        if (current != null && remoteVersion == settings.configVersion) return current
        val created = RemoteFactory.create(context!!, settings)
            ?: throw authRequired("Open the CloudPlayX app and choose a cloud first.")
        remoteCache = created
        remoteVersion = settings.configVersion
        entries.clear()
        listings.clear()
        return created
    }

    private fun authRequired(message: String): AuthenticationRequiredException {
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return AuthenticationRequiredException(IllegalStateException(message), pending)
    }

    /** Binder can only carry a few exception types: turn cloud errors into ones the Files app understands. */
    private inline fun <T> guard(block: () -> T): T {
        try {
            return block()
        } catch (e: RemoteException) {
            if (e.needsSignIn) throw authRequired(e.message ?: "Sign-in needed")
            throw FileNotFoundException(e.message ?: "Cloud error")
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw FileNotFoundException(e.message ?: "I/O error")
        }
    }
}
