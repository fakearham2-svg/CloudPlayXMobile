package app.cloudplayx.mobile

import java.io.IOException
import java.io.InputStream

/** One file or folder in the cloud. [id] is only meaningful to the [Remote] that produced it. */
data class RemoteEntry(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String? = null,
    /** Lower-case hex MD5 of the content when the cloud reports one (Google Drive does), else null. */
    val md5: String? = null,
)

/**
 * A cloud error.
 * [needsSignIn]: the user has to open the app and sign in (again).
 * [retryable]: the failure is transient (network drop, HTTP 429/5xx, rate limit) and trying again can succeed.
 * [httpCode]: the HTTP status when the error came from an HTTP response, else 0.
 */
class RemoteException(
    message: String,
    val needsSignIn: Boolean = false,
    val retryable: Boolean = false,
    val httpCode: Int = 0,
) : IOException(message)

/** The server did not honour a Range request, so a download cannot continue from the middle. */
class RangeNotSupportedException(message: String = "The server does not support resuming this download.") : IOException(message)

/** A cloud that can list folders and return file bytes. All calls block; never call them on the main thread. */
interface Remote {
    val rootId: String
    fun list(dirId: String): List<RemoteEntry>
    fun stat(id: String): RemoteEntry?

    /** Returns [length] bytes starting at [offset]; fewer only if the file ends earlier. */
    fun readRange(entry: RemoteEntry, offset: Long, length: Int): ByteArray

    /**
     * Streams [entry] from byte [offset] to the end. The stream starts exactly at [offset]; the caller must close it.
     * Throws [RangeNotSupportedException] if [offset] > 0 and the server cannot start there.
     * Nothing is buffered in memory: this is what whole-file downloads use.
     */
    fun openStream(entry: RemoteEntry, offset: Long): InputStream
}
