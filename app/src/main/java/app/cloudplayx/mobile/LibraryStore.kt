package app.cloudplayx.mobile

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties

/**
 * The on-device library of downloaded files. Layout under [root]:
 *   <name>            downloaded files and extracted folders (what the user sees)
 *   .meta/<hash>      which cloud file each library file came from (for "already downloaded" checks)
 *   .partial/<hash>   unfinished downloads (resumable)
 * Nothing is ever deleted unless the user asks for that specific item (or for "clear unfinished downloads").
 */
class LibraryStore(val root: File) {
    private val metaDir = File(root, ".meta")
    private val partialDir = File(root, ".partial")

    data class Meta(val backend: String, val remoteId: String, val size: Long, val modified: Long, val md5: String?, val name: String, val completedAt: Long)
    data class Plan(val target: File, val upToDate: Boolean)
    data class Item(val file: File, val isDir: Boolean, val size: Long, val modified: Long, val meta: Meta?)

    init { root.mkdirs() }

    fun partFor(backend: String, remoteId: String): File = File(partialDir, sha1("$backend|$remoteId") + ".part")

    /** Where [entry] should be stored, and whether a verified, unchanged copy is already there. */
    fun plan(backend: String, entry: RemoteEntry): Plan {
        val existing = allMeta().firstOrNull { it.backend == backend && it.remoteId == entry.id && File(root, it.name).isFile }
        if (existing != null) {
            val f = File(root, existing.name)
            val same = existing.size == entry.size && existing.modified == entry.modified &&
                (existing.md5 == null || entry.md5 == null || existing.md5.equals(entry.md5, true)) &&
                f.length() == entry.size
            return Plan(f, same) // a changed cloud file replaces the old copy atomically
        }
        return Plan(SafeNames.unique(root, SafeNames.sanitize(entry.name)), false)
    }

    fun record(file: File, backend: String, entry: RemoteEntry) {
        metaDir.mkdirs()
        val p = Properties().apply {
            setProperty("backend", backend); setProperty("remoteId", entry.id)
            setProperty("size", entry.size.toString()); setProperty("modified", entry.modified.toString())
            setProperty("md5", entry.md5 ?: ""); setProperty("name", file.name)
            setProperty("completedAt", System.currentTimeMillis().toString())
        }
        val dest = File(metaDir, sha1(file.name))
        val tmp = File(metaDir, dest.name + ".tmp")
        FileOutputStream(tmp).use { p.store(it, null) }
        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
    }

    fun metaOf(file: File): Meta? = readMeta(File(metaDir, sha1(file.name)))

    fun list(): List<Item> =
        (root.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .map { f -> Item(f, f.isDirectory, if (f.isDirectory) dirSize(f) else f.length(), f.lastModified(), if (f.isFile) metaOf(f) else null) }
            .sortedByDescending { it.modified }

    /** Resolves a library-relative path ("a/b.txt") to a file, refusing anything that escapes the library or is hidden. */
    fun resolve(relative: String): File? {
        val parts = relative.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." || it.startsWith(".") }) return null
        val f = File(root, parts.joinToString("/"))
        return if (SafeNames.isInside(root, f)) f else null
    }

    fun relativePath(file: File): String? =
        if (SafeNames.isInside(root, file) && file.canonicalPath != root.canonicalPath)
            file.canonicalPath.removePrefix(root.canonicalPath + File.separator).replace(File.separatorChar, '/')
        else null

    /** Deletes one library item (file or folder). Returns false if it is not inside the library. */
    fun delete(file: File): Boolean {
        if (!SafeNames.isInside(root, file) || file.canonicalPath == root.canonicalPath) return false
        val top = file.canonicalFile.parentFile?.canonicalPath == root.canonicalPath
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (ok && top) File(metaDir, sha1(file.name)).delete()
        return ok
    }

    /** Removes unfinished downloads only. Completed files are untouched. Returns bytes freed. */
    fun clearPartials(): Long {
        var freed = 0L
        for (f in partialDir.listFiles() ?: emptyArray()) { freed += f.length(); f.delete() }
        return freed
    }

    fun partialBytes(): Long = (partialDir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".part") }.sumOf { it.length() }

    fun usedBytes(): Long = dirSize(root)

    private fun allMeta(): List<Meta> = (metaDir.listFiles() ?: emptyArray()).filter { !it.name.endsWith(".tmp") }.mapNotNull { readMeta(it) }

    private fun readMeta(f: File): Meta? {
        if (!f.isFile) return null
        return try {
            val p = Properties().also { pr -> f.inputStream().use { pr.load(it) } }
            Meta(
                backend = p.getProperty("backend") ?: return null,
                remoteId = p.getProperty("remoteId") ?: return null,
                size = p.getProperty("size")?.toLongOrNull() ?: return null,
                modified = p.getProperty("modified")?.toLongOrNull() ?: 0L,
                md5 = p.getProperty("md5")?.ifEmpty { null },
                name = p.getProperty("name") ?: return null,
                completedAt = p.getProperty("completedAt")?.toLongOrNull() ?: 0L,
            )
        } catch (e: IOException) {
            null // a corrupt index entry just means "not known"; the download is repeated
        }
    }

    private fun dirSize(d: File): Long {
        var t = 0L
        for (f in d.listFiles() ?: return d.length()) t += if (f.isDirectory) dirSize(f) else f.length()
        return t
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
