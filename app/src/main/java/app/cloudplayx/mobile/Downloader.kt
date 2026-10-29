package app.cloudplayx.mobile

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** A download failed in a way the caller can describe to the user. */
class DownloadException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { NO_SPACE, CHECKSUM, SIZE_MISMATCH, REMOTE_CHANGED, NOT_FOUND }
}

enum class DownloadOutcome { DOWNLOADED, ALREADY_UP_TO_DATE }

/**
 * Whole-file, streaming, resumable download. Pure JVM so it can be unit tested.
 *
 *  - Data goes to [partFile] and only becomes [target] (atomic rename) after the size, and the MD5 when the cloud
 *    reports one, have been verified. An incomplete file is never published under the final name.
 *  - A small sidecar (`<part>.meta`) records which cloud version the partial file belongs to; resume is refused if the
 *    cloud file changed.
 *  - Network failures resume from the bytes already on disk, with exponential back-off.
 *  - Nothing is held in memory except one [bufferSize] buffer.
 */
class Downloader(
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val maxRetries: Int = 4,
    private val backoffMs: (Int) -> Long = { 1000L shl it.coerceAtMost(4) },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val bufferSize: Int = 256 * 1024,
    private val spaceMargin: (Long) -> Long = GameCompat::defaultMargin,
) {
    fun download(
        remote: Remote,
        entry: RemoteEntry,
        target: File,
        partFile: File,
        cancel: CancelToken,
        onProgress: (TransferProgress) -> Unit = {},
    ): DownloadOutcome {
        require(!entry.isDir) { "Folders cannot be downloaded as one file." }
        partFile.parentFile?.mkdirs()
        target.parentFile?.mkdirs()
        val metaFile = File(partFile.path + ".meta")

        var restartedOnce = false
        var attempt = 0
        var digest = newDigest(entry)
        var have = prepareResume(entry, partFile, metaFile, digest)

        // Disk space for what is still missing, checked before any bytes are fetched.
        val missing = entry.size - have
        val free = freeSpace(partFile.parentFile ?: partFile.absoluteFile.parentFile)
        val verdict = GameCompat.storage(missing, free, spaceMargin(missing))
        if (verdict.level == StorageLevel.INSUFFICIENT) { // LOW is only a warning shown by the UI
            throw DownloadException(DownloadException.Kind.NO_SPACE, verdict.message)
        }

        val meter = SpeedMeter()
        var lastReport = 0L
        fun report(force: Boolean) {
            val t = System.nanoTime()
            if (force || t - lastReport > 250_000_000L) {
                lastReport = t
                val speed = meter.update(have)
                onProgress(TransferProgress(have, entry.size, speed, SpeedMeter.eta(entry.size - have, speed)))
            }
        }
        report(true)

        while (have < entry.size) {
            cancel.check()
            try {
                val input: InputStream = remote.openStream(entry, have)
                input.use { stream ->
                    FileOutputStream(partFile, true).use { out ->
                        val buf = ByteArray(bufferSize)
                        while (have < entry.size) {
                            cancel.check()
                            val n = stream.read(buf, 0, minOf(buf.size.toLong(), entry.size - have).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest?.update(buf, 0, n)
                            have += n
                            report(false)
                        }
                    }
                }
                if (have < entry.size) throw IOException("The connection closed after $have of ${entry.size} bytes.")
            } catch (e: OperationCancelled) {
                throw e // keep the .part for resuming later
            } catch (e: RangeNotSupportedException) {
                if (restartedOnce) throw e
                restartedOnce = true
                discardPartial(partFile, metaFile)
                digest = newDigest(entry)
                have = prepareResume(entry, partFile, metaFile, digest)
            } catch (e: RemoteException) {
                if (e.httpCode == 404) {
                    discardPartial(partFile, metaFile)
                    throw DownloadException(DownloadException.Kind.NOT_FOUND, e.message ?: "The file no longer exists.")
                }
                if (e.httpCode == 416 && !restartedOnce) {
                    restartedOnce = true
                    discardPartial(partFile, metaFile)
                    digest = newDigest(entry)
                    have = prepareResume(entry, partFile, metaFile, digest)
                    continue
                }
                if (!e.retryable || attempt >= maxRetries) throw e
                attempt++
                backoff(attempt, cancel)
                have = syncWithDisk(partFile)
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw e
                attempt++
                backoff(attempt, cancel)
                have = syncWithDisk(partFile)
            }
        }

        // ---- verification before the file becomes visible ----
        val onDisk = partFile.length()
        if (onDisk != entry.size) {
            discardPartial(partFile, metaFile)
            throw DownloadException(
                if (onDisk > entry.size) DownloadException.Kind.REMOTE_CHANGED else DownloadException.Kind.SIZE_MISMATCH,
                "The downloaded size ($onDisk) does not match the expected size (${entry.size}).",
            )
        }
        val expectedMd5 = entry.md5?.lowercase()
        if (expectedMd5 != null && digest != null) {
            val actual = hex(digest.digest())
            if (actual != expectedMd5) {
                discardPartial(partFile, metaFile)
                throw DownloadException(DownloadException.Kind.CHECKSUM, "The download is corrupt (checksum mismatch). It was discarded; try again.")
            }
        }
        publish(partFile, target)
        metaFile.delete()
        report(true)
        return DownloadOutcome.DOWNLOADED
    }

    /** Deletes the unfinished file and its sidecar. */
    fun discardPartial(partFile: File, metaFile: File = File(partFile.path + ".meta")) {
        partFile.delete()
        metaFile.delete()
    }

    private fun backoff(attempt: Int, cancel: CancelToken) {
        val total = backoffMs(attempt)
        var left = total
        while (left > 0) { // sleep in slices so Cancel reacts quickly
            cancel.check()
            val s = minOf(left, 250L)
            sleep(s)
            left -= s
        }
    }

    /** After a failed attempt the file length on disk is the truth, not our counter. */
    private fun syncWithDisk(part: File): Long = if (part.exists()) part.length() else 0L

    private fun newDigest(entry: RemoteEntry): MessageDigest? = if (entry.md5 != null) MessageDigest.getInstance("MD5") else null

    /**
     * Decides whether an existing partial file can be continued. Returns the number of valid bytes on disk
     * (already fed to [digest]). Anything suspicious is thrown away and the download starts over.
     */
    private fun prepareResume(entry: RemoteEntry, part: File, metaFile: File, digest: MessageDigest?): Long {
        val key = "${entry.id}|${entry.size}|${entry.modified}|${entry.md5 ?: ""}"
        val saved = readMeta(metaFile)
        val usable = part.exists() && saved == key && part.length() <= entry.size
        if (!usable) {
            part.delete()
            writeMeta(metaFile, key)
            if (entry.size == 0L) part.createNewFile()
            return 0L
        }
        if (digest != null) { // re-hash what is already there so the final checksum covers the whole file
            part.inputStream().use { input ->
                val buf = ByteArray(bufferSize)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
        }
        return part.length()
    }

    private fun readMeta(f: File): String? =
        try {
            if (!f.exists()) null else Properties().also { p -> f.inputStream().use { p.load(it) } }.getProperty("key")
        } catch (e: IOException) { null }

    private fun writeMeta(f: File, key: String) {
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { Properties().apply { setProperty("key", key) }.store(it, null) }
    }

    private fun publish(part: File, target: File) {
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
