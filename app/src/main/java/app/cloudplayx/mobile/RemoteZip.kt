package app.cloudplayx.mobile

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** One file inside a ZIP, as described by the archive's central directory. */
class ZipItem(
    val name: String,
    val compressedSize: Long,
    val size: Long,
    val method: Int,
    val localOffset: Long,
    val crc: Long,
    val encrypted: Boolean,
) {
    val isDir: Boolean get() = name.endsWith("/")
}

/**
 * Reads the index of a ZIP that lives in the cloud and extracts ONLY the chosen files, using HTTP range requests.
 * The rest of the archive is never downloaded. This genuinely saves storage and data for bundles that contain many
 * optional parts (split APKs for other CPUs/languages, extra data packs).
 *
 * Supported: stored and deflated entries, ZIP64 sizes/offsets. Not supported: encrypted entries, other compression
 * methods, multi-part archives. Every extracted file is checked against the CRC-32 recorded in the archive.
 * Extraction is all-or-nothing (temporary folder, then rename) and rejects unsafe paths like [ZipExtractor].
 */
object RemoteZip {
    private const val MAX_DIRECTORY = 32L * 1024 * 1024
    const val MAX_ITEMS = 5000

    fun list(remote: Remote, entry: RemoteEntry): List<ZipItem> {
        val total = entry.size
        if (total < 22) throw ExtractException("This file is too small to be a ZIP archive.")
        val tailLen = minOf(total, 66_000L).toInt() // 22-byte end record + up to 65535 bytes of comment
        val tail = readFully(remote, entry, total - tailLen, tailLen)
        var end = -1
        for (i in tailLen - 22 downTo 0) {
            if (u32(tail, i) == 0x06054b50L) { end = i; break }
        }
        if (end < 0) throw ExtractException("This is not a readable ZIP archive (end record not found).")
        var count = u16(tail, end + 10).toLong()
        var cdSize = u32(tail, end + 12)
        var cdOff = u32(tail, end + 16)
        if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
            if (end < 20 || u32(tail, end - 20) != 0x07064b50L) throw ExtractException("Unsupported ZIP64 layout.")
            val rec = readFully(remote, entry, u64(tail, end - 20 + 8), 56)
            if (u32(rec, 0) != 0x06064b50L) throw ExtractException("Unsupported ZIP64 layout.")
            count = u64(rec, 32); cdSize = u64(rec, 40); cdOff = u64(rec, 48)
        }
        if (cdSize < 0 || cdSize > MAX_DIRECTORY || cdOff < 0 || cdOff + cdSize > total) {
            throw ExtractException("The ZIP index is invalid or too large to read remotely. Download the whole file instead.")
        }
        if (count > MAX_ITEMS) throw ExtractException("The archive has $count files, too many to choose from. Download the whole file instead.")
        val cd = readFully(remote, entry, cdOff, cdSize.toInt())
        val out = ArrayList<ZipItem>()
        var p = 0
        while (p + 46 <= cd.size && out.size < count) {
            if (u32(cd, p) != 0x02014b50L) throw ExtractException("The ZIP index is damaged.")
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            val crc = u32(cd, p + 16)
            var csize = u32(cd, p + 20)
            var usize = u32(cd, p + 24)
            val nlen = u16(cd, p + 28)
            val elen = u16(cd, p + 30)
            val clen = u16(cd, p + 32)
            var loff = u32(cd, p + 42)
            if (p + 46 + nlen + elen + clen > cd.size) throw ExtractException("The ZIP index is damaged.")
            val name = String(cd, p + 46, nlen, Charsets.UTF_8)
            var x = p + 46 + nlen
            val xEnd = x + elen
            while (x + 4 <= xEnd) { // ZIP64 extended information
                val id = u16(cd, x); val len = u16(cd, x + 2)
                if (id == 1) {
                    var q = x + 4
                    if (usize == 0xFFFFFFFFL && q + 8 <= xEnd) { usize = u64(cd, q); q += 8 }
                    if (csize == 0xFFFFFFFFL && q + 8 <= xEnd) { csize = u64(cd, q); q += 8 }
                    if (loff == 0xFFFFFFFFL && q + 8 <= xEnd) { loff = u64(cd, q) }
                }
                x += 4 + len
            }
            out.add(ZipItem(name, csize, usize, method, loff, crc, (flags and 1) != 0))
            p += 46 + nlen + elen + clen
        }
        return out
    }

    /** Extracts [items] into a new folder next to [destDir]'s name. Returns what was written. */
    fun extract(
        remote: Remote,
        entry: RemoteEntry,
        items: List<ZipItem>,
        destDir: File,
        cancel: CancelToken = CancelToken(),
        freeSpace: (File) -> Long = { it.usableSpace },
        spaceMargin: (Long) -> Long = GameCompat::defaultMargin,
        retries: Int = 2,
        onProgress: (TransferProgress) -> Unit = {},
    ): ExtractResult {
        val files = items.filter { !it.isDir }
        if (files.isEmpty()) throw ExtractException("Nothing selected.")
        val parent = destDir.absoluteFile.parentFile ?: throw ExtractException("Invalid destination.")
        parent.mkdirs()
        val tmp = File(parent, ".extract-${destDir.name}-${System.nanoTime()}")
        tmp.mkdirs()
        try {
            val root = tmp.canonicalFile
            var total = 0L
            for (it in files) {
                if (it.encrypted) throw ExtractException("\"${it.name.take(80)}\" is password-protected, which is not supported.")
                if (it.method != 0 && it.method != 8) throw ExtractException("\"${it.name.take(80)}\" uses a compression method that is not supported.")
                val rel = ZipExtractor.safeRelative(it.name) ?: throw ExtractException("Refused: unsafe path in archive (\"${it.name.take(80)}\").")
                val out = File(root, rel).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw ExtractException("Refused: the archive tries to write outside its folder.")
                total += it.size
            }
            val verdict = GameCompat.storage(total, freeSpace(parent), spaceMargin(total))
            if (verdict.level == StorageLevel.INSUFFICIENT) throw DownloadException(DownloadException.Kind.NO_SPACE, verdict.message)

            var done = 0L
            val meter = SpeedMeter()
            var last = 0L
            for (item in files) {
                var attempt = 0
                while (true) {
                    cancel.check()
                    val target = File(root, ZipExtractor.safeRelative(item.name)!!)
                    target.parentFile?.mkdirs()
                    val before = done
                    try {
                        copyItem(remote, entry, item, target, cancel) { n ->
                            done += n
                            val t = System.nanoTime()
                            if (t - last > 250_000_000L) {
                                last = t
                                val sp = meter.update(done)
                                onProgress(TransferProgress(done, total, sp, SpeedMeter.eta(total - done, sp)))
                            }
                        }
                        break
                    } catch (e: OperationCancelled) {
                        throw e
                    } catch (e: ExtractException) {
                        throw e
                    } catch (e: RemoteException) {
                        if (!e.retryable || attempt >= retries) throw e
                    } catch (e: IOException) {
                        if (attempt >= retries) throw e
                    }
                    attempt++
                    done = before // this file starts over
                    target.delete()
                }
            }
            onProgress(TransferProgress(done, total))
            val finalDir = SafeNames.unique(parent, destDir.name)
            if (!tmp.renameTo(finalDir)) throw ExtractException("Could not move the extracted files into place.")
            return ExtractResult(files.size, done, finalDir)
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    private fun copyItem(remote: Remote, entry: RemoteEntry, item: ZipItem, target: File, cancel: CancelToken, onBytes: (Int) -> Unit) {
        val header = readFully(remote, entry, item.localOffset, 30)
        if (u32(header, 0) != 0x04034b50L) throw ExtractException("The ZIP entry \"${item.name.take(80)}\" is damaged.")
        val dataStart = item.localOffset + 30 + u16(header, 26) + u16(header, 28)
        val raw = remote.openStream(entry, dataStart)
        val limited = Limited(raw, item.compressedSize)
        val inflater = if (item.method == 8) Inflater(true) else null
        val source: InputStream = if (inflater != null) InflaterInputStream(limited, inflater) else limited
        val crc = CRC32()
        var written = 0L
        try {
            source.use { input ->
                target.outputStream().use { os ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        cancel.check()
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > item.size) throw ExtractException("\"${item.name.take(80)}\" is larger than the archive says.")
                        crc.update(buf, 0, n)
                        os.write(buf, 0, n)
                        onBytes(n)
                    }
                }
            }
        } finally {
            inflater?.end()
        }
        if (written != item.size) throw IOException("\"${item.name.take(80)}\" ended early ($written of ${item.size} bytes).")
        if (crc.value != item.crc) throw ExtractException("\"${item.name.take(80)}\" is corrupt (checksum mismatch).")
    }

    /** At most [limit] bytes of [src]; closing it closes the underlying network stream. */
    private class Limited(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else src.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() = src.close()
    }

    private fun readFully(remote: Remote, entry: RemoteEntry, offset: Long, len: Int): ByteArray {
        val b = remote.readRange(entry, offset, len)
        if (b.size != len) throw IOException("Short read at $offset: got ${b.size} of $len bytes.")
        return b
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    private fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)
}
