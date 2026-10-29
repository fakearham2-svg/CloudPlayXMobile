package app.cloudplayx.mobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.net.URLConnection

/** The single process-wide job queue (2 transfers at a time). */
object Jobs {
    val center = JobCenter(threads = 2)
}

/** Starts background transfers. Each one runs in [Jobs.center] and is kept alive by [DownloadService]. */
object Transfers {

    /** Downloads [entry] into the library: resumable, verified, atomic. Returns the existing job if it is already queued. */
    fun download(ctx: Context, entry: RemoteEntry): Job {
        val app = ctx.applicationContext
        val settings = Settings(app)
        val backend = settings.backend
        val store = LibraryHolder.get(app)
        val submitted = Jobs.center.submit(JobKind.DOWNLOAD, entry.name, key = "dl|$backend|${entry.id}") { job ->
            val remote = RemoteFactory.create(app, settings) ?: throw RemoteException("No cloud is selected.")
            val plan = store.plan(backend, entry)
            if (plan.upToDate) {
                "Already downloaded and unchanged: ${plan.target.name}"
            } else {
                Downloader().download(remote, entry, plan.target, store.partFor(backend, entry.id), job.token) { job.update(it) }
                store.record(plan.target, backend, entry)
                libraryChanged(app)
                "Saved ${plan.target.name} (${GameCompat.human(entry.size)}${if (entry.md5 != null) ", checksum verified" else ""})"
            }
        }
        DownloadService.ensureRunning(app)
        return submitted
    }

    /** Extracts a ZIP (or ZIP-based bundle) from the library into a new folder next to it. */
    fun extract(ctx: Context, file: File): Job {
        val app = ctx.applicationContext
        val store = LibraryHolder.get(app)
        val submitted = Jobs.center.submit(JobKind.EXTRACT, "Extract ${file.name}", key = "x|${file.path}") { job ->
            val dest = File(store.root, SafeNames.sanitize(file.nameWithoutExtension))
            val r = ZipExtractor.extract(file, dest, job.token, onProgress = { job.update(it) })
            libraryChanged(app)
            "Extracted ${r.files} file(s) (${GameCompat.human(r.bytes)}) to ${r.directory.name}"
        }
        DownloadService.ensureRunning(app)
        return submitted
    }

    /** Copies a library file or folder into a folder the user picked with the system folder picker. Never overwrites. */
    fun export(ctx: Context, source: File, treeUri: Uri): Job {
        val app = ctx.applicationContext
        val submitted = Jobs.center.submit(JobKind.EXPORT, "Copy ${source.name}", key = "e|${source.path}|$treeUri") { job ->
            val resolver = app.contentResolver
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val total = sizeOf(source)
            var copiedBytes = 0L
            val meter = SpeedMeter()
            fun copy(src: File, parent: Uri) {
                job.token.check()
                if (src.isDirectory) {
                    val dir = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, src.name)
                        ?: throw IOException("Could not create the folder \"${src.name}\" there.")
                    for (c in (src.listFiles() ?: emptyArray()).sortedBy { it.name }) copy(c, dir)
                } else {
                    val mime = URLConnection.guessContentTypeFromName(src.name) ?: "application/octet-stream"
                    val doc = DocumentsContract.createDocument(resolver, parent, mime, src.name)
                        ?: throw IOException("Could not create \"${src.name}\" there.")
                    val out = resolver.openOutputStream(doc, "w") ?: throw IOException("Could not write \"${src.name}\" there.")
                    out.use { o ->
                        src.inputStream().use { i ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                job.token.check()
                                val n = i.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                copiedBytes += n
                                val sp = meter.update(copiedBytes)
                                job.update(TransferProgress(copiedBytes, total, sp, SpeedMeter.eta(total - copiedBytes, sp)))
                            }
                        }
                    }
                }
            }
            copy(source, rootDoc)
            "Copied ${source.name} (${GameCompat.human(total)}) to the folder you chose"
        }
        DownloadService.ensureRunning(app)
        return submitted
    }

    fun sizeOf(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { sizeOf(it) } else f.length()

    /** Tells file managers that the library listing changed. */
    fun libraryChanged(ctx: Context) {
        try {
            ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(CloudProvider.AUTHORITY, CloudProvider.LIB_ROOT_DOC), null)
        } catch (_: Exception) { }
    }
}
