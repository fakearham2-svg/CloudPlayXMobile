package app.cloudplayx.mobile

import java.net.URI

/** Rules for user-entered server URLs. Credentials must not travel in clear text across the public internet. */
object UrlPolicy {
    /** Returns an error message, or null when [url] may be used as a WebDAV server. */
    fun checkWebDav(url: String): String? {
        val u = try { URI(url.trim()) } catch (e: Exception) { return "That is not a valid URL." }
        val scheme = u.scheme?.lowercase() ?: return "The URL must start with https:// (or http:// for a server on your own network)."
        val host = u.host?.lowercase() ?: return "The URL has no server name."
        return when (scheme) {
            "https" -> null
            "http" ->
                if (isPrivateHost(host)) null
                else "Plain http:// would send your password unencrypted over the internet. Use https://, or an http:// address on your own network (for example 192.168.x.x)."
            else -> "The URL must start with https:// (or http:// for a server on your own network)."
        }
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        if (!h.contains('.') && !h.contains(':')) return true // single-label LAN name
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(h)
        if (v4 != null) {
            val o = v4.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 || o[0] == 127 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 169 && o[1] == 254) ||
                (o[0] == 100 && o[1] in 64..127)
        }
        if (h.contains(':')) return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        return false
    }
}
