package app.cloudplayx.mobile

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class JobKind { DOWNLOAD, EXTRACT, EXPORT }
enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** One unit of background work with live progress. Fields are written by the worker thread and read by the UI. */
class Job internal constructor(
    val id: Long,
    val kind: JobKind,
    val title: String,
    val key: String?,
    internal val work: (Job) -> String,
) {
    @Volatile var state = JobState.QUEUED
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var bytesPerSec = -1L
    @Volatile var etaSeconds = -1L
    @Volatile var message = ""
    @Volatile var token = CancelToken()
    internal var notify: () -> Unit = {}

    val isActive get() = state == JobState.QUEUED || state == JobState.RUNNING

    fun update(p: TransferProgress) {
        done = p.done; total = p.total; bytesPerSec = p.bytesPerSec; etaSeconds = p.etaSeconds
        notify()
    }
}

/**
 * Runs downloads/extractions with bounded concurrency (default 2 at a time; the rest wait in the queue),
 * prevents duplicate jobs for the same [Job.key], supports cancel and retry. No Android classes.
 */
class JobCenter(threads: Int = 2, private val describe: (Throwable) -> String = ErrorText::of) {
    private val ids = AtomicLong(1)
    private val jobs = CopyOnWriteArrayList<Job>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "cpx-job").apply { isDaemon = true } }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun snapshot(): List<Job> = jobs.toList()
    val activeCount: Int get() = jobs.count { it.isActive }

    /** Submits [work]; returns the already-active job instead if one with the same [key] is queued or running. */
    fun submit(kind: JobKind, title: String, key: String? = null, work: (Job) -> String): Job {
        synchronized(this) {
            if (key != null) jobs.firstOrNull { it.key == key && it.isActive }?.let { return it }
            val job = Job(ids.getAndIncrement(), kind, title, key, work).also { it.notify = ::fire }
            jobs.add(job)
            schedule(job)
            fire()
            return job
        }
    }

    fun cancel(id: Long) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        j.token.cancel()
        if (j.state == JobState.QUEUED) { j.state = JobState.CANCELLED; j.message = "Cancelled."; fire() }
    }

    /** Runs a failed or cancelled job again. A cancelled download resumes from the bytes already on disk. */
    fun retry(id: Long): Boolean {
        val j = jobs.firstOrNull { it.id == id } ?: return false
        synchronized(this) {
            if (j.isActive) return false
            if (j.key != null && jobs.any { it !== j && it.key == j.key && it.isActive }) return false
            j.token = CancelToken(); j.state = JobState.QUEUED; j.message = ""; j.bytesPerSec = -1; j.etaSeconds = -1
            schedule(j)
        }
        fire()
        return true
    }

    fun clearFinished() { jobs.removeAll(jobs.filter { !it.isActive }.toSet()); fire() }

    private fun schedule(job: Job) {
        pool.execute {
            if (job.state != JobState.QUEUED || job.token.isCancelled) {
                if (job.state == JobState.QUEUED) { job.state = JobState.CANCELLED; job.message = "Cancelled."; fire() }
                return@execute
            }
            job.state = JobState.RUNNING; fire()
            try {
                job.message = job.work(job)
                job.state = JobState.DONE
            } catch (e: OperationCancelled) {
                job.state = JobState.CANCELLED; job.message = "Cancelled."
            } catch (e: Throwable) {
                job.state = JobState.FAILED; job.message = describe(e)
            }
            fire()
        }
    }

    private fun fire() { for (l in listeners) try { l() } catch (_: Throwable) { } }
}
