package app.cloudplayx.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * The SHA-1 fingerprint of the certificate that signed THIS installed APK. Google's Android OAuth client only works
 * when that exact SHA-1 (and the package name) is registered in Google Cloud Console, so showing it in the app
 * turns "login fails with developer error" into a copy-and-paste fix.
 */
object SigningInfo {
    fun sha1(ctx: Context): String? = try {
        val pm = ctx.packageManager
        val cert: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo
            val sigs = if (si == null) null else if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            sigs?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
        cert?.let { MessageDigest.getInstance("SHA-1").digest(it).joinToString(":") { b -> "%02X".format(b) } }
    } catch (e: Exception) {
        null
    }
}
