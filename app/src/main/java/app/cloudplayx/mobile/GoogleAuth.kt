package app.cloudplayx.mobile

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Google authorization for the official Drive API (read-only), using Google Identity Services' AuthorizationClient.
 *
 * - No client secret or key is in the app: Google identifies the app by package name + signing-certificate SHA-1.
 * - The client never holds a refresh token. Each call to [token] asks Google Play services, which returns a valid
 *   access token silently once the user has approved the permission, and asks for a user action only when needed.
 * - The access token is kept in memory only (never written to disk or logs).
 */
object GoogleAuth {
    const val DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_READONLY))).build()

    @Volatile private var token: String? = null
    @Volatile private var tokenUntil = 0L

    /** Keep an access token returned by the user-visible authorization flow. */
    fun remember(accessToken: String) {
        if (accessToken.isBlank()) return
        token = accessToken
        // Google access tokens usually last an hour; renew early. A 401 also drops it (see [forget]).
        tokenUntil = System.currentTimeMillis() + 45 * 60 * 1000L
    }

    /** Must not be called on the main thread. */
    fun token(ctx: Context): String {
        val cached = token
        if (cached != null && System.currentTimeMillis() < tokenUntil) return cached
        try {
            val client = Identity.getAuthorizationClient(ctx.applicationContext)
            val result = Tasks.await(client.authorize(request()), 30, TimeUnit.SECONDS)
            if (result.hasResolution()) {
                throw RemoteException("Open CloudPlayX and tap Connect Google Drive to finish authorization.", needsSignIn = true)
            }
            val accessToken = result.accessToken
                ?: throw RemoteException("Google returned no access token. Reconnect Google Drive.", needsSignIn = true)
            remember(accessToken)
            return accessToken
        } catch (e: RemoteException) {
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            throw RemoteException(describe(ctx, e.cause ?: e), needsSignIn = true)
        } catch (e: Exception) {
            throw RemoteException(describe(ctx, e), needsSignIn = true)
        }
    }

    /** Drops the in-memory token (after HTTP 401, on disconnect). */
    fun forget() {
        token = null
        tokenUntil = 0L
    }

    /** A message that tells the user what to fix; for developer errors it includes this build's SHA-1. */
    fun describe(ctx: Context, e: Throwable): String {
        val status = (e as? ApiException)?.statusCode
        return GoogleAuthErrors.explain(status, e.message, SigningInfo.sha1(ctx))
    }
}
