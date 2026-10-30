package app.cloudplayx.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SafeNamesTest {
    @Test fun strips_separators_controls_and_hidden_prefix() {
        assertEquals("a_b_c.txt", SafeNames.sanitize("a/b\\c.txt"))
        assertEquals("evil", SafeNames.sanitize("..evil.."))
        assertEquals("file", SafeNames.sanitize("..."))
        assertEquals("file", SafeNames.sanitize(""))
        assertEquals("a_b", SafeNames.sanitize("a\u0000b"))
        assertEquals("_.._x", SafeNames.sanitize("/.._x"))
    }
    @Test fun long_names_keep_extension() {
        val n = SafeNames.sanitize("x".repeat(300) + ".apk")
        assertTrue(n.endsWith(".apk")); assertTrue(n.toByteArray().size <= 120)
    }
    @Test fun containment_check() {
        val root = Files.createTempDirectory("cpx-r").toFile()
        assertTrue(SafeNames.isInside(root, File(root, "a/b")))
        assertFalse(SafeNames.isInside(root, File(root, "../x")))
        assertFalse(SafeNames.isInside(root, File(root.parentFile, root.name + "-evil")))
    }
}

class UrlPolicyTest {
    @Test fun https_is_always_fine() { assertNull(UrlPolicy.checkWebDav("https://dav.example.com/remote.php")) }
    @Test fun http_only_on_private_networks() {
        assertNull(UrlPolicy.checkWebDav("http://192.168.1.5:8080"))
        assertNull(UrlPolicy.checkWebDav("http://10.0.0.2"))
        assertNull(UrlPolicy.checkWebDav("http://172.20.1.1"))
        assertNull(UrlPolicy.checkWebDav("http://nas.local/dav"))
        assertNull(UrlPolicy.checkWebDav("http://localhost:8080"))
        assertNotNull(UrlPolicy.checkWebDav("http://example.com"))
        assertNotNull(UrlPolicy.checkWebDav("http://8.8.8.8"))
        assertNotNull(UrlPolicy.checkWebDav("http://172.32.0.1"))
    }
    @Test fun other_schemes_and_junk_are_rejected() {
        assertNotNull(UrlPolicy.checkWebDav("ftp://x.com"))
        assertNotNull(UrlPolicy.checkWebDav("not a url"))
        assertNotNull(UrlPolicy.checkWebDav("example.com"))
    }
}

class ApiErrorsTest {
    @Test fun drive_reasons_are_classified() {
        val api = ApiErrors.toException(403, "Forbidden", """{"error":{"errors":[{"reason":"accessNotConfigured"}]}}""")
        assertTrue(api.message!!.contains("Drive API")); assertFalse(api.retryable)
        val rate = ApiErrors.toException(403, "Forbidden", """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""")
        assertTrue(rate.retryable)
        val quota = ApiErrors.toException(403, "Forbidden", """{"error":{"errors":[{"reason":"downloadQuotaExceeded"}]}}""")
        assertTrue(quota.message!!.contains("downloaded too often")); assertFalse(quota.retryable)
        assertTrue(ApiErrors.toException(401, "x", "").needsSignIn)
        assertTrue(ApiErrors.toException(503, "x", "").retryable)
        assertTrue(ApiErrors.toException(429, "x", "").retryable)
        assertEquals(404, ApiErrors.toException(404, "x", "").httpCode)
    }
    @Test fun google_status_codes_explain_the_sha1_problem() {
        val m = GoogleAuthErrors.explain(10, null, "AA:BB")
        assertTrue(m.contains("SHA-1")); assertTrue(m.contains("AA:BB"))
    }
    @Test fun storage_full_is_friendly() {
        assertEquals("The device storage is full.", ErrorText.of(java.io.IOException("write failed: ENOSPC (No space left on device)")))
    }
}

class GameCompatTest {
    private fun a(n: String) = GameCompat.assess(n)
    @Test fun windows_programs_are_unsupported() { assertEquals(Support.UNSUPPORTED, a("Setup.exe").support); assertEquals(Support.UNSUPPORTED, a("game.MSI").support) }
    @Test fun apk_is_installable_zip_is_extractable() {
        assertTrue(a("game.apk").canInstall); assertEquals(Support.SUPPORTED, a("game.apk").support)
        assertTrue(a("data.zip").canExtract)
    }
    @Test fun bundles_and_obb_are_partial() {
        assertEquals(Support.PARTIAL, a("g.xapk").support); assertTrue(a("g.xapk").canExtract)
        assertEquals(Support.PARTIAL, a("main.1.com.x.obb").support)
        assertTrue(a("main.1.com.x.obb").detail.contains("Android 11"))
    }
    @Test fun roms_discs_and_other_archives_are_partial_and_never_claimed_runnable() {
        for (n in listOf("x.gba", "x.iso", "x.7z", "x.pak", "x.nsp", "unknown.zzz")) assertEquals(n, Support.PARTIAL, a(n).support)
        assertFalse(a("x.7z").canExtract)
    }
    @Test fun archives_that_support_selective_download_are_distinguished_from_the_rest() {
        assertTrue(a("data.zip").canBrowseInside); assertTrue(a("game.xapk").canBrowseInside); assertTrue(a("game.apks").canBrowseInside)
        assertFalse(a("game.apk").canBrowseInside); assertFalse(a("x.7z").canBrowseInside); assertFalse(a("main.obb").canBrowseInside)
        assertTrue(a("game.apks").detail.contains("split-APK installer")); assertFalse(a("game.apks").canInstall)
    }
    @Test fun storage_verdicts() {
        val gb = 1L shl 30
        assertEquals(StorageLevel.OK, GameCompat.storage(gb, 10 * gb).level)
        assertEquals(StorageLevel.LOW, GameCompat.storage(gb, gb + 100 * 1024 * 1024).level)
        assertEquals(StorageLevel.INSUFFICIENT, GameCompat.storage(gb, gb - 1).level)
    }
}

class LibraryStoreTest {
    private fun store() = LibraryStore(Files.createTempDirectory("cpx-lib").toFile())
    private fun entry(id: String = "id1", name: String = "game.zip", size: Long = 10, modified: Long = 1, md5: String? = "ab") = RemoteEntry(id, name, false, size, modified, md5 = md5)

    @Test fun already_downloaded_unchanged_file_is_detected() {
        val s = store(); val e = entry()
        val p = s.plan("gdrive", e); assertFalse(p.upToDate)
        p.target.writeBytes(ByteArray(10)); s.record(p.target, "gdrive", e)
        assertTrue(s.plan("gdrive", e).upToDate)
        assertFalse("changed in the cloud -> download again", s.plan("gdrive", e.copy(modified = 2)).upToDate)
        assertEquals("replaces the same file rather than duplicating", p.target, s.plan("gdrive", e.copy(modified = 2)).target)
        assertFalse("other backend is a different file", s.plan("webdav", e).upToDate)
    }
    @Test fun truncated_local_file_is_not_considered_up_to_date() {
        val s = store(); val e = entry(); val p = s.plan("gdrive", e)
        p.target.writeBytes(ByteArray(10)); s.record(p.target, "gdrive", e)
        p.target.writeBytes(ByteArray(3))
        assertFalse(s.plan("gdrive", e).upToDate)
    }
    @Test fun same_name_from_another_cloud_file_gets_a_unique_name() {
        val s = store(); val a = s.plan("gdrive", entry("A")); a.target.writeText("x"); s.record(a.target, "gdrive", entry("A"))
        val b = s.plan("gdrive", entry("B"))
        assertEquals("game (1).zip", b.target.name)
    }
    @Test fun delete_is_confined_to_the_library_and_cleans_metadata() {
        val s = store(); val outside = Files.createTempFile("cpx-out", ".txt").toFile()
        assertFalse(s.delete(outside)); assertTrue(outside.exists())
        assertFalse(s.delete(s.root))
        val p = s.plan("gdrive", entry()); p.target.writeText("x"); s.record(p.target, "gdrive", entry())
        assertTrue(s.delete(p.target)); assertNull(s.metaOf(p.target))
    }
    @Test fun resolve_blocks_traversal_and_hidden_internals() {
        val s = store()
        assertNull(s.resolve("../x")); assertNull(s.resolve(".meta/abc")); assertNull(s.resolve("a/../../x"))
        assertNotNull(s.resolve("a/b.txt"))
    }
    @Test fun clear_partials_only_touches_unfinished_downloads() {
        val s = store(); val keep = File(s.root, "done.zip").apply { writeText("x") }
        val part = s.partFor("gdrive", "id"); part.parentFile.mkdirs(); part.writeBytes(ByteArray(7))
        assertEquals(7L, s.partialBytes()); assertEquals(7L, s.clearPartials())
        assertTrue(keep.exists()); assertFalse(part.exists())
    }
    @Test fun cleanTemp_removes_only_stale_temporary_data_never_user_files() {
        val s = store(); val now = 100L * 86_400_000L
        val keep = File(s.root, "game.apk").apply { writeText("keep"); setLastModified(1) }
        val keepDir = File(s.root, "Extracted Game").apply { mkdirs() }; File(keepDir, "a.bin").apply { writeText("x"); setLastModified(1) }; keepDir.setLastModified(1)
        val oldPart = s.partFor("g", "old"); oldPart.parentFile.mkdirs(); oldPart.writeBytes(ByteArray(10)); oldPart.setLastModified(now - 10 * 86_400_000L)
        val newPart = s.partFor("g", "new"); newPart.writeBytes(ByteArray(5)); newPart.setLastModified(now - 86_400_000L)
        val staleTmp = File(s.root, ".extract-x-1").apply { mkdirs() }; File(staleTmp, "f").writeBytes(ByteArray(7)); staleTmp.setLastModified(now - 7_200_000L)
        val freshTmp = File(s.root, ".extract-y-2").apply { mkdirs() }; freshTmp.setLastModified(now - 60_000L)
        val e = entry(); val p = s.plan("gdrive", e); p.target.writeBytes(ByteArray(10)); s.record(p.target, "gdrive", e)
        p.target.delete() // orphaned index entry
        val freed = s.cleanTemp(7 * 86_400_000L, now)
        assertEquals(17L, freed)
        assertTrue(keep.exists()); assertTrue(File(keepDir, "a.bin").exists())
        assertFalse(oldPart.exists()); assertTrue(newPart.exists())
        assertFalse(staleTmp.exists()); assertTrue(freshTmp.exists())
        assertNull(s.metaOf(p.target))
    }
    @Test fun list_hides_internal_folders() {
        val s = store(); File(s.root, "a.zip").writeText("x"); s.partFor("g", "i").parentFile.mkdirs()
        assertEquals(listOf("a.zip"), s.list().map { it.file.name })
    }
}

class JobCenterTest {
    private fun waitFor(c: () -> Boolean) { val end = System.currentTimeMillis() + 5000; while (!c() && System.currentTimeMillis() < end) Thread.sleep(5); assertTrue(c()) }

    @Test fun runs_jobs_and_reports_result_and_failure_text() {
        val c = JobCenter()
        val ok = c.submit(JobKind.DOWNLOAD, "ok") { "done!" }
        val bad = c.submit(JobKind.DOWNLOAD, "bad") { throw java.io.IOException("write failed: ENOSPC") }
        waitFor { !ok.isActive && !bad.isActive }
        assertEquals(JobState.DONE, ok.state); assertEquals("done!", ok.message)
        assertEquals(JobState.FAILED, bad.state); assertEquals("The device storage is full.", bad.message)
    }
    @Test fun duplicate_keys_do_not_start_a_second_download() {
        val c = JobCenter(); val gate = CountDownLatch(1); val runs = AtomicInteger()
        val a = c.submit(JobKind.DOWNLOAD, "a", key = "k") { runs.incrementAndGet(); gate.await(); "x" }
        val b = c.submit(JobKind.DOWNLOAD, "a", key = "k") { runs.incrementAndGet(); "y" }
        assertTrue(a === b); gate.countDown(); waitFor { !a.isActive }; assertEquals(1, runs.get())
    }
    @Test fun concurrency_is_bounded() {
        val c = JobCenter(threads = 2); val running = AtomicInteger(); val peak = AtomicInteger(); val gate = CountDownLatch(1)
        val js = (1..6).map { c.submit(JobKind.DOWNLOAD, "j$it") { val n = running.incrementAndGet(); peak.accumulateAndGet(n) { x, y -> maxOf(x, y) }; gate.await(2, TimeUnit.SECONDS); running.decrementAndGet(); "ok" } }
        Thread.sleep(100); gate.countDown(); waitFor { js.none { it.isActive } }
        assertTrue("peak=${peak.get()}", peak.get() <= 2)
    }
    @Test fun cancel_then_retry() {
        val c = JobCenter(); var attempts = 0
        val j = c.submit(JobKind.DOWNLOAD, "x", key = "k") { job -> attempts++; if (attempts == 1) { while (true) { job.token.check(); Thread.sleep(2) } }; "second" }
        waitFor { j.state == JobState.RUNNING }; c.cancel(j.id); waitFor { j.state == JobState.CANCELLED }
        assertTrue(c.retry(j.id)); waitFor { j.state == JobState.DONE }; assertEquals("second", j.message)
    }
    @Test fun cancelling_a_queued_job_never_runs_it() {
        val c = JobCenter(threads = 1); val gate = CountDownLatch(1); var ran = false
        c.submit(JobKind.DOWNLOAD, "block") { gate.await(2, TimeUnit.SECONDS); "x" }
        val q = c.submit(JobKind.DOWNLOAD, "queued") { ran = true; "y" }
        c.cancel(q.id); gate.countDown(); waitFor { c.activeCount == 0 }
        assertEquals(JobState.CANCELLED, q.state); assertFalse(ran)
    }
}
