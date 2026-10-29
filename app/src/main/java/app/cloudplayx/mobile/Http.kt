package app.cloudplayx.mobile

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun backoff(attempt: Int) = 500L * (1L shl attempt.coerceAtMost(6))

    /**
     * Runs [request] and hands the successful response to [handle].
     * Retries network errors, HTTP 429, 5xx and Google rate-limit 403s with back-off (honouring Retry-After).
     * Failures become [RemoteException] with a message a person can act on.
     */
    fun <T> execute(request: Request, retries: Int = 3, handle: (Response) -> T): T {
        var attempt = 0
        while (true) {
            var wait = 0L
            try {
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) return handle(resp)
                    val body = readErrorBody(resp)
                    val ex = ApiErrors.toException(resp.code, resp.message, body)
                    if (!ex.retryable || attempt >= retries) throw ex
                    wait = retryAfterMs(resp) ?: backoff(attempt)
                }
            } catch (e: RemoteException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= retries) throw RemoteException("Network error: ${e.message ?: e.javaClass.simpleName}", retryable = true)
                wait = backoff(attempt)
            }
            attempt++
            Thread.sleep(wait)
        }
    }

    /**
     * Opens [request] and returns the body as a stream that starts at [offset]. The caller must close it (that also
     * releases the connection). No retries here: the download engine owns retry/resume so it can continue from disk.
     */
    fun open(request: Request, offset: Long): InputStream {
        val resp = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw RemoteException("Network error: ${e.message ?: e.javaClass.simpleName}", retryable = true)
        }
        if (!resp.isSuccessful) {
            resp.use { throw ApiErrors.toException(it.code, it.message, readErrorBody(it)) }
        }
        if (offset > 0 && resp.code != 206) { // the server ignored the Range header
            resp.close()
            throw RangeNotSupportedException()
        }
        val body = resp.body ?: run { resp.close(); throw RemoteException("Empty response", retryable = true) }
        return body.byteStream()
    }

    /** Reads [length] bytes of a (possibly range) response; if the server ignored the Range header it skips to [offset]. */
    fun readBody(resp: Response, offset: Long, length: Int): ByteArray {
        val body = resp.body ?: throw RemoteException("Empty response")
        val input = body.byteStream()
        if (resp.code == 200 && offset > 0) skipFully(input, offset)
        val buf = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(buf, n, length - n)
            if (r < 0) break
            n += r
        }
        return if (n == length) buf else buf.copyOf(n)
    }

    private fun readErrorBody(resp: Response): String =
        runCatching { resp.peekBody(4096).string() }.getOrDefault("")

    private fun retryAfterMs(resp: Response): Long? =
        resp.header("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(0, 60)?.times(1000)

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) return
                left--
            } else {
                left -= s
            }
        }
    }
}
