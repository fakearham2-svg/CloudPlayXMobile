package app.cloudplayx.mobile

import android.net.Uri
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** Any WebDAV server (Nextcloud, Koofr, `rclone serve webdav`, ...). Read-only. Entry ids are paths below [baseUrl]. */
class WebDavRemote(baseUrl: String, private val user: String, private val pass: String) : Remote {
    private val base = baseUrl.trim().trimEnd('/')
    private val basePath: String =
        Uri.decode(runCatching { java.net.URI(base).rawPath ?: "" }.getOrDefault("")).trimEnd('/')

    override val rootId = "/"

    private val propfindBody = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

    override fun list(dirId: String): List<RemoteEntry> {
        val self = normalize(dirId)
        val url = urlFor(dirId).let { if (it.endsWith("/")) it else "$it/" }
        return propfind(url, 1).filter { it.id != self }
    }

    override fun stat(id: String): RemoteEntry? =
        try {
            propfind(urlFor(id), 0).firstOrNull()
        } catch (e: RemoteException) {
            if (e.httpCode == 404) null else throw e
        }

    override fun readRange(entry: RemoteEntry, offset: Long, length: Int): ByteArray {
        val req = Request.Builder().url(urlFor(entry.id))
            .header("Authorization", Credentials.basic(user, pass))
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        return Http.execute(req) { Http.readBody(it, offset, length) }
    }

    override fun openStream(entry: RemoteEntry, offset: Long): InputStream {
        val b = Request.Builder().url(urlFor(entry.id)).header("Authorization", Credentials.basic(user, pass))
        if (offset > 0) b.header("Range", "bytes=$offset-")
        return Http.open(b.build(), offset)
    }

    private fun propfind(url: String, depth: Int): List<RemoteEntry> {
        val req = Request.Builder().url(url)
            .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
            .header("Depth", depth.toString())
            .header("Authorization", Credentials.basic(user, pass))
            .build()
        return Http.execute(req) { parse(it.body!!.byteStream()) }
    }

    private fun parse(stream: InputStream): List<RemoteEntry> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // A malicious server must not be able to make us resolve external entities (XXE).
        factory.isExpandEntityReferences = false
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) { }
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) { }
        val doc = factory.newDocumentBuilder().parse(stream)
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = ArrayList<RemoteEntry>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
            val id = normalize(relativePath(href))
            val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = r.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)
                ?.textContent?.trim()?.toLongOrNull() ?: 0L
            val modified = r.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)
                ?.textContent?.trim()?.let { parseDate(it) } ?: 0L
            val name = if (id == "/") "CloudPlayX" else id.substringAfterLast('/')
            out.add(RemoteEntry(id = id, name = name, isDir = isDir, size = size, modified = modified))
        }
        return out
    }

    private fun parseDate(text: String): Long =
        runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0L)

    private fun relativePath(href: String): String {
        val raw = if (href.startsWith("http", ignoreCase = true)) {
            runCatching { java.net.URI(href).rawPath }.getOrNull() ?: "/"
        } else {
            href.substringBefore('?')
        }
        var p = Uri.decode(raw)
        if (basePath.isNotEmpty() && p.startsWith(basePath)) p = p.substring(basePath.length)
        if (!p.startsWith("/")) p = "/$p"
        return p
    }

    /** "/a/b/" -> "/a/b", "/" stays "/". */
    private fun normalize(id: String): String {
        val p = if (id.startsWith("/")) id else "/$id"
        return if (p.length > 1) p.trimEnd('/').ifEmpty { "/" } else p
    }

    private fun urlFor(id: String): String =
        base + normalize(id).split('/').joinToString("/") { Uri.encode(it) }
}
