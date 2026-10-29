package app.cloudplayx.mobile

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The heart of the app, same idea as the Windows version: a file is cut into blocks and only the blocks that are
 * really read get downloaded. Blocks live on disk until the budget is exceeded; then the least recently used blocks
 * are deleted (they stay in the cloud and come back the next time they are read).
 *
 * Pure JVM code (no Android classes) so it can be unit tested on a PC.
 */
class BlockCache(
    private val dir: File,
    var budgetBytes: Long,
    private val blockSize: Int = 4 * 1024 * 1024,
    private val prefetchBlocks: Int = 1,
) {
    private val lock = Any()
    private val stripes = Array(64) { Any() }
    private val prefetching = ConcurrentHashMap.newKeySet<String>()
    private val pool by lazy {
        Executors.newFixedThreadPool(2) { r -> Thread(r, "cpx-prefetch").apply { isDaemon = true } }
    }

    @Volatile
    private var used = 0L

    val usedBytes: Long get() = used

    init {
        dir.mkdirs()
        used = scan()
    }

    /** Copies up to [size] bytes of [entry] starting at [offset] into [dst], downloading missing blocks. */
    fun read(entry: RemoteEntry, remote: Remote, offset: Long, dst: ByteArray, dstOff: Int, size: Int): Int {
        if (size <= 0 || offset < 0 || offset >= entry.size) return 0
        val end = minOf(entry.size, offset + size)
        var pos = offset
        var done = 0
        while (pos < end) {
            val index = (pos / blockSize).toInt()
            val inBlock = (pos - index.toLong() * blockSize).toInt()
            val take = minOf((end - pos).toInt(), blockLength(entry, index) - inBlock)
            val downloaded = readFromBlock(entry, remote, index, inBlock, dst, dstOff + done, take)
            if (downloaded && prefetchBlocks > 0) prefetch(entry, remote, index + 1)
            pos += take
            done += take
        }
        return done
    }

    /** Deletes every cached block. Cloud files are never touched. */
    fun clear() {
        synchronized(lock) {
            for (f in dir.listFiles() ?: emptyArray()) f.delete()
            used = 0
        }
    }

    /** Deletes blocks that were not read for [maxAgeMs]. */
    fun trimByAge(maxAgeMs: Long) {
        val limit = System.currentTimeMillis() - maxAgeMs
        synchronized(lock) {
            for (f in dir.listFiles() ?: emptyArray()) {
                if (f.name.endsWith(".tmp")) continue
                if (f.lastModified() < limit) {
                    val len = f.length()
                    if (f.delete()) used -= len
                }
            }
        }
    }

    /** Evicts least recently used blocks until the cache fits the budget again. */
    fun trimToBudget(protect: String? = null) {
        if (used <= budgetBytes) return
        synchronized(lock) {
            if (used <= budgetBytes) return
            val files = (dir.listFiles() ?: return)
                .filter { !it.name.endsWith(".tmp") && it.name != protect }
                .sortedBy { it.lastModified() }
            for (f in files) {
                if (used <= budgetBytes) break
                val len = f.length()
                if (f.delete()) used -= len
            }
        }
    }

    private fun readFromBlock(
        entry: RemoteEntry, remote: Remote, index: Int, inBlock: Int, dst: ByteArray, dstOff: Int, take: Int,
    ): Boolean {
        val file = blockFile(entry, index)
        val expected = blockLength(entry, index)
        var downloaded = false
        for (attempt in 0..1) {
            if (!(file.exists() && file.length() == expected.toLong())) {
                download(entry, remote, index, file, expected)
                downloaded = true
            }
            try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(inBlock.toLong())
                    raf.readFully(dst, dstOff, take)
                }
                file.setLastModified(System.currentTimeMillis())
                return downloaded
            } catch (e: IOException) {
                // The block was evicted or damaged while we read it: drop it and fetch it again once.
                forget(file)
                if (attempt == 1) throw e
            }
        }
        throw IOException("Could not read block $index of ${entry.name}")
    }

    private fun download(entry: RemoteEntry, remote: Remote, index: Int, file: File, expected: Int) {
        val stripe = stripes[(file.name.hashCode() and Int.MAX_VALUE) % stripes.size]
        synchronized(stripe) {
            if (file.exists() && file.length() == expected.toLong()) return
            if (file.exists()) forget(file)
            val data = remote.readRange(entry, index.toLong() * blockSize, expected)
            if (data.size != expected) throw IOException("Short read: got ${data.size} of $expected bytes")
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Cannot store block $index")
            }
            synchronized(lock) { used += expected }
        }
        trimToBudget(protect = file.name)
    }

    private fun prefetch(entry: RemoteEntry, remote: Remote, first: Int) {
        for (i in first until first + prefetchBlocks) {
            if (i.toLong() * blockSize >= entry.size) return
            val file = blockFile(entry, i)
            val len = blockLength(entry, i)
            if (file.exists() && file.length() == len.toLong()) continue
            if (used + len > budgetBytes) return
            if (!prefetching.add(file.name)) continue
            pool.execute {
                try {
                    download(entry, remote, i, file, len)
                } catch (e: Exception) {
                    // best effort only
                } finally {
                    prefetching.remove(file.name)
                }
            }
        }
    }

    private fun blockLength(entry: RemoteEntry, index: Int): Int =
        minOf(blockSize.toLong(), entry.size - index.toLong() * blockSize).toInt()

    /** The name contains size and modified time, so a changed cloud file never serves stale blocks. */
    private fun blockFile(entry: RemoteEntry, index: Int): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${entry.id}|${entry.size}|${entry.modified}".toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "${hex}_$index")
    }

    private fun forget(f: File) {
        val len = f.length()
        if (f.delete()) synchronized(lock) { used -= len }
    }

    private fun scan(): Long {
        var total = 0L
        for (f in dir.listFiles() ?: emptyArray()) {
            if (f.name.endsWith(".tmp")) f.delete() else total += f.length()
        }
        return total
    }
}
