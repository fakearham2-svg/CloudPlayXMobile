package app.cloudplayx.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A cloud file backed by bytes; counts how many bytes were actually "downloaded". */
private class BytesRemote(var data: ByteArray) : Remote {
    var served = 0L
    override val rootId = "/"
    override fun list(dirId: String) = emptyList<RemoteEntry>()
    override fun stat(id: String): RemoteEntry? = null
    override fun readRange(entry: RemoteEntry, offset: Long, length: Int): ByteArray {
        val end = minOf(data.size.toLong(), offset + length).toInt()
        served += end - offset
        return data.copyOfRange(offset.toInt(), end)
    }
    override fun openStream(entry: RemoteEntry, offset: Long): InputStream {
        val base = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        return object : InputStream() {
            override fun read(): Int = base.read().also { if (it >= 0) served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = base.read(b, off, len).also { if (it > 0) served += it }
        }
    }
}

class RemoteZipTest {
    private fun dir(): File = Files.createTempDirectory("cpx-rz").toFile()
    private val rnd = Random(3)
    private fun noise(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private val noMargin: (Long) -> Long = { 0 }

    private fun zip(vararg e: Triple<String, ByteArray, Boolean>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, bytes, stored) in e) {
                val ze = ZipEntry(name)
                if (stored) {
                    ze.method = ZipEntry.STORED; ze.size = bytes.size.toLong(); ze.compressedSize = bytes.size.toLong()
                    ze.crc = CRC32().also { it.update(bytes) }.value
                }
                z.putNextEntry(ze); z.write(bytes); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun entryOf(b: ByteArray) = RemoteEntry("z", "bundle.apks", false, b.size.toLong(), 1L)

    @Test fun lists_names_and_sizes_without_downloading_the_archive() {
        val big = noise(500_000)
        val data = zip(Triple("base.apk", noise(1000), false), Triple("split_arm64.apk", noise(2000), true), Triple("split_x86.apk", big, true), Triple("dir/", ByteArray(0), false))
        val r = BytesRemote(data)
        val items = RemoteZip.list(r, entryOf(data))
        assertEquals(listOf("base.apk", "split_arm64.apk", "split_x86.apk", "dir/"), items.map { it.name })
        assertEquals(500_000L, items[2].size)
        assertTrue("index read must be tiny, served=${r.served}", r.served < data.size / 2)
    }

    @Test fun extracts_only_the_selected_files_and_does_not_download_the_rest() {
        val base = noise(3000); val arm = ByteArray(40_000) { (it % 7).toByte() }; val x86 = noise(600_000)
        val data = zip(Triple("base.apk", base, false), Triple("split_arm.apk", arm, false), Triple("split_x86.apk", x86, true))
        val r = BytesRemote(data)
        val e = entryOf(data)
        val items = RemoteZip.list(r, e)
        r.served = 0
        val out = RemoteZip.extract(r, e, items.filter { it.name != "split_x86.apk" }, File(dir(), "Game"), spaceMargin = noMargin)
        assertEquals(2, out.files)
        assertArrayEquals(base, File(out.directory, "base.apk").readBytes())
        assertArrayEquals(arm, File(out.directory, "split_arm.apk").readBytes())
        assertFalse(File(out.directory, "split_x86.apk").exists())
        assertTrue("the 600 KB entry must not be fetched (served=${r.served})", r.served < 100_000)
    }

    @Test fun corrupt_data_is_caught_by_the_checksum_and_nothing_is_left() {
        val payload = noise(5000)
        val data = zip(Triple("a.bin", payload, true))
        val r = BytesRemote(data)
        val items = RemoteZip.list(r, entryOf(data))
        val at = data.indexOfFirst { true }.let { 30 + "a.bin".length + 100 } // inside the stored data
        data[at] = (data[at].toInt() xor 0x55).toByte()
        val base = dir()
        try { RemoteZip.extract(r, entryOf(data), items, File(base, "G"), spaceMargin = noMargin); fail() }
        catch (e: ExtractException) { assertTrue(e.message!!.contains("checksum")) }
        assertTrue(base.listFiles()!!.isEmpty())
    }

    @Test fun unsafe_paths_are_refused() {
        val data = zip(Triple("ok.txt", noise(10), false), Triple("../evil.txt", noise(10), false))
        val r = BytesRemote(data); val base = dir()
        val items = RemoteZip.list(r, entryOf(data))
        try { RemoteZip.extract(r, entryOf(data), items, File(base, "G"), spaceMargin = noMargin); fail() } catch (e: ExtractException) { assertTrue(e.message!!.contains("unsafe")) }
        assertTrue(base.listFiles()!!.isEmpty())
    }

    @Test fun not_enough_space_is_reported_before_any_file_is_fetched() {
        val data = zip(Triple("a.bin", noise(5000), true))
        val r = BytesRemote(data); val items = RemoteZip.list(r, entryOf(data)); r.served = 0
        try { RemoteZip.extract(r, entryOf(data), items, File(dir(), "G"), freeSpace = { 10 }, spaceMargin = noMargin); fail() }
        catch (e: DownloadException) { assertEquals(DownloadException.Kind.NO_SPACE, e.kind) }
        assertEquals(0L, r.served)
    }

    @Test fun cancel_leaves_nothing_behind() {
        val data = zip(Triple("a.bin", noise(5000), true), Triple("b.bin", noise(5000), true))
        val r = BytesRemote(data); val items = RemoteZip.list(r, entryOf(data)); val t = CancelToken(); val base = dir()
        try { RemoteZip.extract(r, entryOf(data), items, File(base, "G"), cancel = t, spaceMargin = noMargin) { t.cancel() }; fail() } catch (_: OperationCancelled) { }
        assertTrue(base.listFiles()!!.isEmpty())
    }

    @Test fun non_zip_data_is_a_clean_error() {
        val r = BytesRemote(ByteArray(1000) { 7 })
        try { RemoteZip.list(r, entryOf(r.data)); fail() } catch (e: ExtractException) { assertTrue(e.message!!.contains("not a readable ZIP")) }
        try { RemoteZip.list(BytesRemote(ByteArray(5)), RemoteEntry("z", "x", false, 5, 1)); fail() } catch (e: ExtractException) { }
    }

    @Test fun nothing_selected_is_rejected() {
        val data = zip(Triple("a", noise(10), false))
        val r = BytesRemote(data)
        try { RemoteZip.extract(r, entryOf(data), emptyList(), File(dir(), "G")); fail() } catch (e: ExtractException) { }
    }
}
