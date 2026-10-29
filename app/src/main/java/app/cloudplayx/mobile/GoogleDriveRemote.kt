package app.cloudplayx.mobile

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.time.Instant

/**
 * Google Drive through the official REST API v3 (read-only).
 * [token] returns a current OAuth access token; [invalidate] drops a token the server rejected (HTTP 401) so the
 * next call asks Google for a fresh one. Every call is retried once after such a refresh.
 */
class GoogleDriveRemote(
    private val token: () -> String,
    private val invalidate: () -> Unit = {},
) : Remote {
    override val rootId = "root"
    private val fields = "id,name,mimeType,size,modifiedTime,md5Checksum"

    private fun request(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${token()}")

    /** Runs [block]; if Google answers 401 the token is refreshed and the block is run once more. */
    private fun <T> authed(block: () -> T): T =
        try {
            block()
        } catch (e: RemoteException) {
            if (e.httpCode != 401) throw e
            invalidate()
            block()
        }

    /** Returns the signed-in Google account and Drive quota from the official Drive API. */
    fun accountSummary(): String {
        val url = "https://www.googleapis.com/drive/v3/about".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "user(displayName,emailAddress),storageQuota(limit,usage,usageInDrive)")
            .build()
        val json = authed { Http.execute(request(url.toString()).build()) { JSONObject(it.body!!.string()) } }
        val user = json.optJSONObject("user")
        val quota = json.optJSONObject("storageQuota")
        val name = user?.optString("displayName", "")?.takeIf { it.isNotBlank() } ?: "Google account"
        val email = user?.optString("emailAddress", "")?.takeIf { it.isNotBlank() }
        val used = quota?.optString("usage", "")?.toLongOrNull()
        val limit = quota?.optString("limit", "")?.toLongOrNull()
        val usageLine = if (used != null && limit != null && limit > 0) {
            "Drive storage: ${GameCompat.human(used)} used of ${GameCompat.human(limit)}"
        } else if (used != null) {
            "Drive storage used: ${GameCompat.human(used)} · quota limit not reported"
        } else {
            "Drive storage quota unavailable"
        }
        return listOfNotNull(name, email, usageLine).joinToString("\n")
    }

    override fun list(dirId: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var page: String? = null
        do {
            val builder = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'${escape(dirId)}' in parents and trashed=false")
                .addQueryParameter("fields", "nextPageToken,files($fields)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("orderBy", "folder,name")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
            val pageToken = page
            if (pageToken != null) builder.addQueryParameter("pageToken", pageToken)
            val url = builder.build().toString()
            val json = authed { Http.execute(request(url).build()) { JSONObject(it.body!!.string()) } }
            val files = json.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val e = parse(files.getJSONObject(i))
                    if (e != null) out.add(e)
                }
            }
            page = json.optString("nextPageToken", "").ifEmpty { null }
        } while (page != null)
        return out
    }

    override fun stat(id: String): RemoteEntry? {
        val url = "https://www.googleapis.com/drive/v3/files/${Uri.encode(id)}".toHttpUrl().newBuilder()
            .addQueryParameter("fields", fields)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        return try {
            authed { Http.execute(request(url.toString()).build()) { parse(JSONObject(it.body!!.string())) } }
        } catch (e: RemoteException) {
            if (e.httpCode == 404) null else throw e
        }
    }

    private fun mediaUrl(entry: RemoteEntry) =
        "https://www.googleapis.com/drive/v3/files/${Uri.encode(entry.id)}".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .addQueryParameter("supportsAllDrives", "true")
            .build().toString()

    override fun readRange(entry: RemoteEntry, offset: Long, length: Int): ByteArray {
        val url = mediaUrl(entry)
        return authed {
            val req = request(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
            Http.execute(req) { Http.readBody(it, offset, length) }
        }
    }

    override fun openStream(entry: RemoteEntry, offset: Long): InputStream {
        val url = mediaUrl(entry)
        return authed {
            val b = request(url)
            if (offset > 0) b.header("Range", "bytes=$offset-")
            Http.open(b.build(), offset)
        }
    }

    private fun parse(o: JSONObject): RemoteEntry? {
        val mime = o.optString("mimeType")
        val isDir = mime == "application/vnd.google-apps.folder"
        // Google Docs/Sheets/Slides (and shortcuts) have no raw bytes to stream or download here, so they are skipped.
        if (!isDir && mime.startsWith("application/vnd.google-apps.")) return null
        return RemoteEntry(
            id = o.getString("id"),
            name = o.optString("name", "unnamed"),
            isDir = isDir,
            size = o.optString("size", "0").toLongOrNull() ?: 0L,
            modified = runCatching { Instant.parse(o.getString("modifiedTime")).toEpochMilli() }.getOrDefault(0L),
            mime = if (isDir) null else mime.ifEmpty { null },
            md5 = o.optString("md5Checksum", "").lowercase().ifEmpty { null },
        )
    }

    /** Escapes a value for use inside a single-quoted Drive query string. */
    private fun escape(v: String) = v.replace("\\", "\\\\").replace("'", "\\'")
}
