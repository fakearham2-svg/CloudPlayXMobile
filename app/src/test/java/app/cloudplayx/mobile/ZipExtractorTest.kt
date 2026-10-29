package app.cloudplayx.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExtractorTest {
    private fun dir(): File = Files.createTempDirectory("cpx-zip").toFile()
    private fun zip(vararg e: Pair<String, ByteArray>): File {
        val f = File(dir(), "a.zip")
        ZipOutputStream(f.outputStream()).use { z -> for ((n, b) in e) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }
    private val noMargin: (Long) -> Long = { 0 }

    @Test fun extracts_files_and_folders() {
        val z = zip("a.txt" to "hi".toByteArray(), "sub/dir/b.bin" to ByteArray(300) { 5 })
        val out = File(dir(), "Game")
        val r = ZipExtractor.extract(z, out, spaceMargin = noMargin)
        assertEquals(2, r.files)
        assertEquals("hi", File(r.directory, "a.txt").readText())
        assertEquals(300, File(r.directory, "sub/dir/b.bin").length().toInt())
    }

    @Test fun zip_slip_dot_dot_is_rejected_and_nothing_is_written() {
        val base = dir(); val z = zip("ok.txt" to "x".toByteArray(), "../evil.txt" to "x".toByteArray())
        val out = File(base, "Game")
        try { ZipExtractor.extract(z, out, spaceMargin = noMargin); fail() } catch (e: ExtractException) { assertTrue(e.message!!.contains("unsafe")) }
        assertFalse(File(base, "evil.txt").exists()); assertFalse(out.exists())
        assertTrue("no temp dirs left", base.listFiles()!!.none { it.name.startsWith(".extract") })
    }

    @Test fun absolute_drive_and_backslash_paths_are_rejected() {
        for (bad in listOf("/etc/passwd", "C:\\win\\x", "..\\..\\x", "a/../../x", "")) {
            assertNull("'$bad'", ZipExtractor.safeRelative(bad))
        }
        assertEquals("a/b", ZipExtractor.safeRelative("./a//b"))
        assertEquals("a/b", ZipExtractor.safeRelative("a\\b"))
    }

    @Test fun too_many_entries_is_rejected() {
        val z = zip("a" to ByteArray(1), "b" to ByteArray(1), "c" to ByteArray(1))
        try { ZipExtractor.extract(z, File(dir(), "G"), maxEntries = 2, spaceMargin = noMargin); fail() } catch (e: ExtractException) { assertTrue(e.message!!.contains("too many")) }
    }

    @Test fun zip_bomb_ratio_is_rejected() {
        val z = zip("zeros.bin" to ByteArray(2_000_000))
        try { ZipExtractor.extract(z, File(dir(), "G"), maxRatio = 50, ratioCheckAbove = 1000, spaceMargin = noMargin); fail() } catch (e: ExtractException) { assertTrue(e.message!!.contains("zip bomb")) }
    }

    @Test fun not_enough_space_is_reported_before_extracting() {
        val z = zip("a" to ByteArray(5000))
        val base = dir()
        try { ZipExtractor.extract(z, File(base, "G"), freeSpace = { 100 }, spaceMargin = noMargin); fail() } catch (e: DownloadException) { assertEquals(DownloadException.Kind.NO_SPACE, e.kind) }
        assertFalse(File(base, "G").exists())
    }

    @Test fun cancel_leaves_nothing_behind() {
        val z = zip("a" to ByteArray(5000), "b" to ByteArray(5000))
        val base = dir(); val t = CancelToken()
        try { ZipExtractor.extract(z, File(base, "G"), cancel = t, spaceMargin = noMargin) { t.cancel() }; fail() } catch (_: OperationCancelled) { }
        assertTrue(base.listFiles()!!.isEmpty())
    }

    @Test fun garbage_is_a_clean_error() {
        val f = File(dir(), "x.zip").apply { writeText("not a zip") }
        try { ZipExtractor.extract(f, File(dir(), "G"), spaceMargin = noMargin); fail() } catch (e: ExtractException) { assertTrue(e.message!!.contains("not a readable ZIP")) }
    }

    @Test fun existing_destination_gets_a_unique_name_and_is_never_overwritten() {
        val z = zip("a.txt" to "new".toByteArray()); val base = dir()
        val existing = File(base, "Game").apply { mkdirs() }; File(existing, "keep.txt").writeText("keep")
        val r = ZipExtractor.extract(z, File(base, "Game"), spaceMargin = noMargin)
        assertEquals("Game (1)", r.directory.name)
        assertEquals("keep", File(existing, "keep.txt").readText())
    }
}
