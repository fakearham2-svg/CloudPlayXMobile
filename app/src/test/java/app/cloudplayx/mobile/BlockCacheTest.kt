package app.cloudplayx.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

private class FakeRemote(val data: ByteArray) : Remote {
    var reads = 0
    override val rootId = "/"
    override fun list(dirId: String): List<RemoteEntry> = emptyList()
    override fun stat(id: String): RemoteEntry? = null
    override fun readRange(entry: RemoteEntry, offset: Long, length: Int): ByteArray {
        reads++
        return data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
    override fun openStream(entry: RemoteEntry, offset: Long): java.io.InputStream =
        java.io.ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
}

class BlockCacheTest {
    private val block = 16

    private fun newDir(): File = Files.createTempDirectory("cpx-cache").toFile()

    private fun fixture(size: Int): Pair<FakeRemote, RemoteEntry> {
        val data = ByteArray(size).also { Random(42).nextBytes(it) }
        return FakeRemote(data) to RemoteEntry("f1", "game.bin", false, size.toLong(), 1L)
    }

    @Test
    fun reads_across_block_boundaries_return_the_right_bytes() {
        val (remote, entry) = fixture(100)
        val cache = BlockCache(newDir(), 1_000_000, block, prefetchBlocks = 0)
        val out = ByteArray(50)
        val n = cache.read(entry, remote, 10, out, 0, 50)
        assertEquals(50, n)
        assertArrayEquals(remote.data.copyOfRange(10, 60), out)
    }

    @Test
    fun reading_past_the_end_is_clipped() {
        val (remote, entry) = fixture(100)
        val cache = BlockCache(newDir(), 1_000_000, block, prefetchBlocks = 0)
        val out = ByteArray(50)
        assertEquals(10, cache.read(entry, remote, 90, out, 0, 50))
        assertEquals(0, cache.read(entry, remote, 100, out, 0, 50))
    }

    @Test
    fun a_second_read_is_served_from_disk() {
        val (remote, entry) = fixture(100)
        val cache = BlockCache(newDir(), 1_000_000, block, prefetchBlocks = 0)
        val out = ByteArray(40)
        cache.read(entry, remote, 0, out, 0, 40)
        val after = remote.reads
        cache.read(entry, remote, 0, out, 0, 40)
        assertEquals(after, remote.reads)
    }

    @Test
    fun only_the_blocks_that_are_read_get_downloaded() {
        val (remote, entry) = fixture(160)
        val cache = BlockCache(newDir(), 1_000_000, block, prefetchBlocks = 0)
        cache.read(entry, remote, 64, ByteArray(10), 0, 10)
        assertEquals(1, remote.reads)
        assertEquals(block.toLong(), cache.usedBytes)
    }

    @Test
    fun budget_is_enforced_and_content_stays_correct() {
        val (remote, entry) = fixture(160)
        val cache = BlockCache(newDir(), 48, block, prefetchBlocks = 0)
        val out = ByteArray(160)
        assertEquals(160, cache.read(entry, remote, 0, out, 0, 160))
        assertArrayEquals(remote.data, out)
        assertTrue("used=${cache.usedBytes}", cache.usedBytes <= 48)
        // Evicted blocks come back from the cloud when they are needed again.
        val again = ByteArray(160)
        assertEquals(160, cache.read(entry, remote, 0, again, 0, 160))
        assertArrayEquals(remote.data, again)
    }

    @Test
    fun least_recently_used_blocks_are_evicted_first() {
        val dir = newDir()
        val (remote, entry) = fixture(64)
        val cache = BlockCache(dir, 1_000_000, block, prefetchBlocks = 0)
        cache.read(entry, remote, 0, ByteArray(64), 0, 64)
        val files = dir.listFiles()!!.sortedBy { it.name }
        files.forEachIndexed { i, f -> f.setLastModified(1_000_000L + i * 1000L) }
        val oldest = files.first()
        cache.budgetBytes = 48
        cache.trimToBudget()
        assertTrue(!oldest.exists())
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun blocks_not_used_for_too_long_are_deleted() {
        val dir = newDir()
        val (remote, entry) = fixture(32)
        val cache = BlockCache(dir, 1_000_000, block, prefetchBlocks = 0)
        cache.read(entry, remote, 0, ByteArray(32), 0, 32)
        for (f in dir.listFiles()!!) f.setLastModified(System.currentTimeMillis() - 10 * 86_400_000L)
        cache.trimByAge(5 * 86_400_000L)
        assertEquals(0, dir.listFiles()!!.size)
        assertEquals(0L, cache.usedBytes)
    }

    @Test
    fun clear_removes_everything() {
        val (remote, entry) = fixture(48)
        val dir = newDir()
        val cache = BlockCache(dir, 1_000_000, block, prefetchBlocks = 0)
        cache.read(entry, remote, 0, ByteArray(48), 0, 48)
        cache.clear()
        assertEquals(0, dir.listFiles()!!.size)
        assertEquals(0L, cache.usedBytes)
    }

    @Test
    fun a_changed_cloud_file_does_not_get_stale_blocks() {
        val dir = newDir()
        val (remote, entry) = fixture(32)
        val cache = BlockCache(dir, 1_000_000, block, prefetchBlocks = 0)
        cache.read(entry, remote, 0, ByteArray(32), 0, 32)
        val newer = entry.copy(modified = 2L)
        val reads = remote.reads
        cache.read(newer, remote, 0, ByteArray(32), 0, 32)
        assertTrue(remote.reads > reads)
    }
}
