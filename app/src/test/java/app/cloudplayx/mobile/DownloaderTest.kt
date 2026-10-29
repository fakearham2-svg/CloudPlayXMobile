package app.cloudplayx.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random

/** A remote whose stream can be made to break, lie, or ignore ranges. */
private class StreamRemote(val data: ByteArray) : Remote {
    var opens = 0
    val offsets = mutableListOf<Long>()
    /** Fail with a network error after this many bytes on the first N opens. */
    var failAfter = -1
    var failures = 0
    var ignoreRange = false
    var error: RemoteException? = null
    override val rootId = "/"
    override fun list(dirId: String) = emptyList<RemoteEntry>()
    override fun stat(id: String): RemoteEntry? = null
    override fun readRange(entry: RemoteEntry, offset: Long, length: Int) = data.copyOfRange(offset.toInt(), offset.toInt() + length)
    override fun openStream(entry: RemoteEntry, offset: Long): InputStream {
        opens++; offsets.add(offset)
        error?.let { throw it }
        if (ignoreRange && offset > 0) throw RangeNotSupportedException()
        val base = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        if (failures > 0 && failAfter >= 0) {
            failures--
            return object : InputStream() {
                var n = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n >= failAfter) throw IOException("connection reset")
                    val r = base.read(b, off, minOf(len, failAfter - n))
                    if (r > 0) n += r
                    return r
                }
            }
        }
        return base
    }
}

class DownloaderTest {
    private fun dir(): File = Files.createTempDirectory("cpx-dl").toFile()
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private fun data(n: Int) = ByteArray(n).also { Random(7).nextBytes(it) }
    private fun entry(d: ByteArray, md5: String? = md5(d), modified: Long = 1L) = RemoteEntry("id1", "game.zip", false, d.size.toLong(), modified, md5 = md5)
    private fun dl(free: Long = Long.MAX_VALUE, retries: Int = 3) =
        Downloader(freeSpace = { free }, maxRetries = retries, backoffMs = { 0 }, sleep = {}, bufferSize = 64, spaceMargin = { 0 })

    @Test fun downloads_verifies_and_publishes_atomically() {
        val d = data(1000); val remote = StreamRemote(d); val root = dir()
        val target = File(root, "game.zip"); val part = File(root, ".p/x.part")
        val seen = mutableListOf<TransferProgress>()
        val out = dl().download(remote, entry(d), target, part, CancelToken()) { seen.add(it) }
        assertEquals(DownloadOutcome.DOWNLOADED, out)
        assertArrayEquals(d, target.readBytes())
        assertFalse("part must be gone after publish", part.exists())
        assertFalse(File(part.path + ".meta").exists())
        assertEquals(1000L, seen.last().done)
    }

    @Test fun network_drop_resumes_from_bytes_on_disk_instead_of_restarting() {
        val d = data(1000); val remote = StreamRemote(d).apply { failAfter = 400; failures = 2 }
        val root = dir(); val target = File(root, "g"); val part = File(root, "g.part")
        dl().download(remote, entry(d), target, part, CancelToken())
        assertArrayEquals(d, target.readBytes())
        assertEquals(listOf(0L, 400L, 800L), remote.offsets)
    }

    @Test fun gives_up_after_retries_and_keeps_partial_file_but_never_publishes() {
        val d = data(1000); val remote = StreamRemote(d).apply { failAfter = 100; failures = 99 }
        val root = dir(); val target = File(root, "g"); val part = File(root, "g.part")
        try { dl(retries = 2).download(remote, entry(d), target, part, CancelToken()); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("reset")) }
        assertFalse("incomplete download must never appear as complete", target.exists())
        assertTrue(part.exists())
    }

    @Test fun a_later_run_resumes_the_partial_file() {
        val d = data(1000); val root = dir(); val target = File(root, "g"); val part = File(root, "g.part")
        val bad = StreamRemote(d).apply { failAfter = 300; failures = 99 }
        try { dl(retries = 0).download(bad, entry(d), target, part, CancelToken()); fail() } catch (_: IOException) { }
        val good = StreamRemote(d)
        dl().download(good, entry(d), target, part, CancelToken())
        assertEquals(listOf(300L), good.offsets)
        assertArrayEquals(d, target.readBytes())
    }

    @Test fun checksum_mismatch_discards_the_file() {
        val d = data(500); val root = dir(); val target = File(root, "g"); val part = File(root, "g.part")
        try { dl().download(StreamRemote(d), entry(d, md5 = "0".repeat(32)), target, part, CancelToken()); fail() }
        catch (e: DownloadException) { assertEquals(DownloadException.Kind.CHECKSUM, e.kind) }
        assertFalse(target.exists()); assertFalse(part.exists())
    }

    @Test fun resume_still_verifies_the_checksum_of_the_whole_file() {
        val d = data(500); val root = dir(); val target = File(root, "g"); val part = File(root, "g.part")
        val bad = StreamRemote(d).apply { failAfter = 200; failures = 99 }
        try { dl(retries = 0).download(bad, entry(d), target, part, CancelToken()); fail() } catch (_: IOException) { }
        // corrupt the bytes already on disk: the final checksum must catch it
        part.writeBytes(ByteArray(200) { 1 })
        try { dl().download(StreamRemote(d), entry(d), target, part, CancelToken()); fail() }
        catch (e: DownloadException) { assertEquals(DownloadException.Kind.CHECKSUM, e.kind) }
        assertFalse(target.exists())
    }

    @Test fun changed_cloud_file_does_not_resume_stale_bytes() {
        val old = data(600); val root = dir(); val target = File(root, "g"); val part = File(root, "g.part")
        val bad = StreamRemote(old).apply { failAfter = 250; failures = 99 }
        try { dl(retries = 0).download(bad, entry(old, modified = 1), target, part, CancelToken()); fail() } catch (_: IOException) { }
        val fresh = ByteArray(600) { (it % 251).toByte() }
        val remote = StreamRemote(fresh)
        dl().download(remote, entry(fresh, modified = 2), target, part, CancelToken())
        assertEquals(listOf(0L), remote.offsets)
        assertArrayEquals(fresh, target.readBytes())
    }

    @Test fun server_without_range_support_restarts_from_zero() {
        val d = data(600); val root = dir(); val target = File(root, "g"); val part = File(root, "g.part")
        val bad = StreamRemote(d).apply { failAfter = 250; failures = 99 }
        try { dl(retries = 0).download(bad, entry(d), target, part, CancelToken()); fail() } catch (_: IOException) { }
        val remote = StreamRemote(d).apply { ignoreRange = true }
        dl().download(remote, entry(d), target, part, CancelToken())
        assertEquals(listOf(250L, 0L), remote.offsets)
        assertArrayEquals(d, target.readBytes())
    }

    @Test fun cancel_stops_and_keeps_partial_for_resume_without_publishing() {
        val d = data(5000); val root = dir(); val target = File(root, "g"); val part = File(root, "g.part")
        val token = CancelToken()
        val remote = object : Remote by StreamRemote(d) {
            override fun openStream(entry: RemoteEntry, offset: Long): InputStream = object : InputStream() {
                val base = ByteArrayInputStream(d, offset.toInt(), d.size - offset.toInt()); var n = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int { n += len; if (n > 640) token.cancel(); return base.read(b, off, len) }
            }
        }
        try { dl().download(remote, entry(d), target, part, token); fail() } catch (_: OperationCancelled) { }
        assertFalse(target.exists()); assertTrue(part.exists()); assertTrue(part.length() in 1..4999)
        // and it can be resumed afterwards
        dl().download(StreamRemote(d), entry(d), target, part, CancelToken())
        assertArrayEquals(d, target.readBytes())
    }

    @Test fun refuses_when_there_is_not_enough_space_before_downloading() {
        val d = data(1000); val remote = StreamRemote(d); val root = dir()
        try { dl(free = 10).download(remote, entry(d), File(root, "g"), File(root, "g.part"), CancelToken()); fail() }
        catch (e: DownloadException) { assertEquals(DownloadException.Kind.NO_SPACE, e.kind) }
        assertEquals(0, remote.opens)
    }

    @Test fun deleted_cloud_file_gives_a_clear_error_and_no_leftovers() {
        val d = data(100); val remote = StreamRemote(d).apply { error = RemoteException("gone", httpCode = 404) }
        val root = dir(); val part = File(root, "g.part")
        try { dl().download(remote, entry(d), File(root, "g"), part, CancelToken()); fail() }
        catch (e: DownloadException) { assertEquals(DownloadException.Kind.NOT_FOUND, e.kind) }
        assertFalse(part.exists())
    }

    @Test fun sign_in_errors_are_not_retried() {
        val d = data(100); val remote = StreamRemote(d).apply { error = RemoteException("auth", needsSignIn = true, httpCode = 401) }
        val root = dir()
        try { dl().download(remote, entry(d), File(root, "g"), File(root, "g.part"), CancelToken()); fail() } catch (e: RemoteException) { assertTrue(e.needsSignIn) }
        assertEquals(1, remote.opens)
    }

    @Test fun retryable_http_errors_are_retried() {
        val d = data(100); val root = dir()
        val remote = object : Remote by StreamRemote(d) {
            var n = 0
            override fun openStream(entry: RemoteEntry, offset: Long): InputStream {
                if (n++ < 2) throw RemoteException("503", retryable = true, httpCode = 503)
                return ByteArrayInputStream(d, offset.toInt(), d.size - offset.toInt())
            }
        }
        dl().download(remote, entry(d), File(root, "g"), File(root, "g.part"), CancelToken())
        assertArrayEquals(d, File(root, "g").readBytes())
    }

    @Test fun empty_files_download() {
        val d = ByteArray(0); val root = dir()
        dl().download(StreamRemote(d), entry(d), File(root, "e"), File(root, "e.part"), CancelToken())
        assertEquals(0L, File(root, "e").length())
    }

    @Test fun speed_meter_and_eta() {
        var t = 0L
        val m = SpeedMeter { t }
        t += 1_000_000_000L
        assertEquals(1000L, m.update(1000))
        assertEquals(10L, SpeedMeter.eta(10_000, 1000))
        assertEquals(-1L, SpeedMeter.eta(10, 0))
    }
}
