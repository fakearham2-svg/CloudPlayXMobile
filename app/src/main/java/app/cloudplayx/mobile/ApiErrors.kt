package app.cloudplayx.mobile

import java.io.FileNotFoundException

/** Turns cloud HTTP failures into messages a person can act on. Pure functions, no Android classes. */
object ApiErrors {
    private val REASON = Regex("\"reason\"\\s*:\\s*\"([A-Za-z0-9_]+)\"")
    private val RATE_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "backendError")

    fun reasonOf(body: String): String? = REASON.find(body)?.groupValues?.get(1)

    /** Builds the exception for a failed HTTP response. [body] is the (possibly truncated) response text. */
    fun toException(code: Int, statusMessage: String, body: String): RemoteException {
        val reason = reasonOf(body)
        val rate = code == 403 && reason in RATE_REASONS
        val retryable = code == 429 || code >= 500 || rate
        val text = explain(code, reason, statusMessage, body)
        return RemoteException(text, needsSignIn = code == 401, retryable = retryable, httpCode = code)
    }

    fun explain(code: Int, reason: String?, statusMessage: String, body: String): String = when {
        code == 401 -> "Not signed in, or the sign-in expired (HTTP 401). Open CloudPlayX and reconnect."
        code == 403 && reason == "accessNotConfigured" ->
            "Google Drive API is not enabled for the Google Cloud project that owns this app's OAuth client (HTTP 403 accessNotConfigured). Enable \"Google Drive API\" in that project."
        code == 403 && (reason == "insufficientPermissions" || reason == "ACCESS_TOKEN_SCOPE_INSUFFICIENT") ->
            "Google did not grant the Drive read permission (HTTP 403). Disconnect, reconnect and approve the Drive permission."
        code == 403 && (reason == "downloadQuotaExceeded") ->
            "Google has temporarily blocked downloads of this file because it was downloaded too often (HTTP 403 downloadQuotaExceeded). Try again later, or make your own copy of the file in Drive."
        code == 403 && reason == "cannotDownloadAbusiveFile" ->
            "Google Drive flagged this file as malware or abusive and will not serve it."
        code == 403 && reason in RATE_REASONS -> "Google is rate-limiting requests (HTTP 403). Waiting and retrying."
        code == 403 -> "Access denied (HTTP 403)${detail(body)}"
        code == 404 -> "The file or folder no longer exists, or your account has lost access to it (HTTP 404)."
        code == 416 -> "The server could not serve the requested part of the file (HTTP 416)."
        code == 429 -> "Too many requests (HTTP 429). Waiting and retrying."
        code >= 500 -> "The cloud service had a problem (HTTP $code). Waiting and retrying."
        else -> "HTTP $code $statusMessage${detail(body)}"
    }

    private fun detail(body: String): String {
        val d = body.replace(Regex("\\s+"), " ").trim().take(300)
        return if (d.isEmpty()) "" else ": $d"
    }
}

/** Plain-language text for any failure that reaches the UI. */
object ErrorText {
    fun of(e: Throwable): String = when (e) {
        is OperationCancelled -> "Cancelled."
        is RemoteException -> e.message ?: "Cloud error"
        is DownloadException -> e.message ?: "Download failed"
        is FileNotFoundException -> e.message ?: "File not found"
        else -> {
            val m = e.message.orEmpty()
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) "The device storage is full."
            else m.ifBlank { e.javaClass.simpleName }
        }
    }
}

/** Maps Google Play services statuses (ApiException.statusCode) to guidance. */
object GoogleAuthErrors {
    fun explain(statusCode: Int?, rawMessage: String?, sha1: String?): String {
        val fp = if (sha1.isNullOrBlank()) "" else " This build's signing SHA-1 is $sha1."
        return when (statusCode) {
            10 -> "Google rejected this app (developer error, code 10). The OAuth client in Google Cloud Console must be an Android client with package name app.cloudplayx.mobile and the SHA-1 of the key that signed THIS APK.$fp A debug APK built by a fresh CI runner gets a new SHA-1 every time unless a fixed keystore is used."
            12500, 12501 -> "Google sign-in was cancelled or could not start (code $statusCode). Check that the Google account is listed as a test user on the OAuth consent screen, then try again."
            16 -> "Google sign-in was cancelled."
            7 -> "No network connection to Google (code 7). Check your internet and try again."
            4 -> "Google needs you to sign in again (code 4)."
            8 -> "Google Play services reported an internal error (code 8). Update Google Play services and try again."
            17 -> "Google Play services is unavailable or out of date on this device (code 17)."
            else -> "Google authorization failed${if (statusCode != null) " (code $statusCode)" else ""}: ${rawMessage ?: "unknown error"}.$fp"
        }
    }
}
