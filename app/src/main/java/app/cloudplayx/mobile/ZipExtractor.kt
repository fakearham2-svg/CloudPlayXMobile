package app.cloudplayx.mobile

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

class ExtractException(message: String) : IOException(message)

data class ExtractResult(val files: Int, val bytes: Long, val directory: File)

/**
 * Safe ZIP extraction.
 *  - Rejects the whole archive if any entry would land outside the destination ("zip slip"): absolute paths, "..",
 *    drive letters, backslash tricks, NUL bytes.
 *  - Bounds entry count, total size and compression ratio (zip bombs) and checks free disk space first.
 *  - Extracts into a temporary directory and renames it only when everything succeeded, so a failed or cancelled
 *    extraction leaves nothing half-written behind.
 */
object ZipExtractor {
    const val MAX_ENTRIES = 200_000

    fun extract(
        zip: File,
        destDir: File,
        cancel: CancelToken = CancelToken(),
        freeSpace: (File) -> Long = { it.usableSpace },
        maxEntries: Int = MAX_ENTRIES,
        maxRatio: Long = 500,
        ratioCheckAbove: Long = 1L shl 30,
        spaceMargin: (Long) -> Long = GameCompat::defaultMargin,
        onProgress: (TransferProgress) -> Unit = {},
    ): ExtractResult {
        val parent = destDir.absoluteFile.parentFile ?: throw ExtractException("Invalid destination.")
        parent.mkdirs()
        val tmp = File(parent, ".extract-${destDir.name}-${System.nanoTime()}")
        try {
            val result = try {
                ZipFile(zip).use { zf -> doExtract(zf, zip, tmp, cancel, freeSpace, maxEntries, maxRatio, ratioCheckAbove, spaceMargin, onProgress) }
            } catch (e: ZipException) {
                throw ExtractException("This is not a readable ZIP file (${e.message}). Password-protected or damaged archives are not supported.")
            }
            val finalDir = SafeNames.unique(parent, destDir.name)
            if (!tmp.renameTo(finalDir)) throw ExtractException("Could not move the extracted files into place.")
            return result.copy(directory = finalDir)
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    private fun doExtract(
        zf: ZipFile, zip: File, tmp: File, cancel: CancelToken, freeSpace: (File) -> Long,
        maxEntries: Int, maxRatio: Long, ratioCheckAbove: Long, spaceMargin: (Long) -> Long,
        onProgress: (TransferProgress) -> Unit,
    ): ExtractResult {
        tmp.mkdirs()
        val root = tmp.canonicalFile

        // Pass 1: validate everything before writing a single byte.
        var declared = 0L
        var count = 0
        val entries = zf.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (++count > maxEntries) throw ExtractException("The archive has too many entries (limit $maxEntries).")
            val rel = safeRelative(e.name) ?: throw ExtractException("Refused: the archive contains an unsafe path (\"${e.name.take(80)}\").")
            val out = File(root, rel).canonicalFile
            if (!(out.path == root.path || out.path.startsWith(root.path + File.separator))) {
                throw ExtractException("Refused: the archive tries to write outside its folder (\"${e.name.take(80)}\").")
            }
            if (!e.isDirectory) {
                if (e.size < 0) throw ExtractException("The archive does not declare the size of \"${e.name.take(80)}\".")
                declared += e.size
            }
        }
        if (declared > ratioCheckAbove && zip.length() > 0 && declared / zip.length() > maxRatio) {
            throw ExtractException("Refused: the archive expands to ${GameCompat.human(declared)} from ${GameCompat.human(zip.length())}, which looks like a zip bomb.")
        }
        val verdict = GameCompat.storage(declared, freeSpace(tmp.parentFile ?: tmp), spaceMargin(declared))
        if (verdict.level == StorageLevel.INSUFFICIENT) throw DownloadException(DownloadException.Kind.NO_SPACE, verdict.message)

        // Pass 2: extract, counting the bytes that really come out (a lying header cannot exceed the budget).
        var done = 0L
        var files = 0
        val meter = SpeedMeter()
        var lastReport = 0L
        val buf = ByteArray(128 * 1024)
        val all = zf.entries()
        while (all.hasMoreElements()) {
            cancel.check()
            val e = all.nextElement()
            val rel = safeRelative(e.name)!!
            val out = File(root, rel)
            if (e.isDirectory) { out.mkdirs(); continue }
            out.parentFile?.mkdirs()
            var entryBytes = 0L
            zf.getInputStream(e).use { input ->
                out.outputStream().use { os ->
                    while (true) {
                        cancel.check()
                        val n = input.read(buf)
                        if (n < 0) break
                        entryBytes += n
                        done += n
                        if (entryBytes > e.size || done > declared) throw ExtractException("Refused: \"${e.name.take(80)}\" is larger than the archive declared.")
                        os.write(buf, 0, n)
                        val t = System.nanoTime()
                        if (t - lastReport > 250_000_000L) {
                            lastReport = t
                            val sp = meter.update(done)
                            onProgress(TransferProgress(done, declared, sp, SpeedMeter.eta(declared - done, sp)))
                        }
                    }
                }
            }
            files++
        }
        onProgress(TransferProgress(done, declared))
        return ExtractResult(files, done, tmp)
    }

    /** Normalised relative path, or null when the entry name is not acceptable. */
    fun safeRelative(raw: String): String? {
        if (raw.isEmpty() || raw.indexOf('\u0000') >= 0) return null
        val n = raw.replace('\\', '/')
        if (n.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(n)) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
