package app.cloudplayx.mobile

/** Cooperative cancellation for long-running work (downloads, extraction, copying). */
class CancelToken {
    @Volatile var isCancelled = false
        private set

    fun cancel() { isCancelled = true }

    /** Throws [OperationCancelled] if cancellation was requested. */
    fun check() { if (isCancelled) throw OperationCancelled() }
}

class OperationCancelled : RuntimeException("Cancelled")

/** Progress of one transfer. [bytesPerSec] and [etaSeconds] are -1 when not known yet. */
data class TransferProgress(val done: Long, val total: Long, val bytesPerSec: Long = -1, val etaSeconds: Long = -1)

/** Smooths transfer speed so the UI does not jump around. Pure maths, injectable clock for tests. */
class SpeedMeter(private val now: () -> Long = System::nanoTime) {
    private var lastTime = now()
    private var lastBytes = 0L
    private var ema = -1.0

    fun update(bytesDone: Long): Long {
        val t = now()
        val dt = (t - lastTime) / 1e9
        if (dt >= 0.2) {
            val inst = (bytesDone - lastBytes) / dt
            ema = if (ema < 0) inst else ema * 0.7 + inst * 0.3
            lastTime = t
            lastBytes = bytesDone
        }
        return if (ema < 0) -1 else ema.toLong()
    }

    companion object {
        fun eta(remaining: Long, bytesPerSec: Long): Long =
            if (bytesPerSec <= 0 || remaining < 0) -1 else (remaining + bytesPerSec - 1) / bytesPerSec
    }
}
