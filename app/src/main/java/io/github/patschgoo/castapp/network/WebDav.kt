package io.github.patschgoo.castapp.network

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** A file or folder on a network share or cloud drive. */
data class RemoteEntry(
    val name: String,
    /** Full address: http(s)://… for WebDAV, a path inside the share for SMB. */
    val path: String,
    val isFolder: Boolean,
    val mimeType: String? = null,
    val size: Long? = null,
)

/**
 * WebDAV: Nextcloud, ownCloud, most NAS boxes (Synology, QNAP…), and cloud
 * storage that offers it (pCloud, Koofr, Box…). Lists folders with PROPFIND.
 */
object WebDav {
    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:displayname/><d:getcontenttype/><d:getcontentlength/><d:resourcetype/>" +
            "</d:prop></d:propfind>"

    fun authHeader(user: String, password: String): Map<String, String> =
        if (user.isEmpty()) emptyMap()
        else mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray()))

    fun list(folderUrl: String, user: String, password: String, trustAll: Boolean): List<RemoteEntry> {
        val url = if (folderUrl.endsWith("/")) folderUrl else "$folderUrl/"
        val headers = authHeader(user, password) + mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8")
        val r = RawHttp.request("PROPFIND", url, headers, PROPFIND_BODY.toByteArray(), trustAll)
        when (r.status) {
            207, 200 -> return parse(r.text, url)
            401, 403 -> throw IOException("Wrong user name or password")
            404 -> throw IOException("Folder not found")
            else -> throw IOException("The server answered ${r.status}")
        }
    }

    /** Parses a PROPFIND multistatus answer; the folder itself is left out. */
    fun parse(xml: String, folderUrl: String): List<RemoteEntry> {
        val base = URI(folderUrl)
        val out = ArrayList<RemoteEntry>()
        val response = Regex("<(?:[A-Za-z0-9]+:)?response\\b[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?response>", RegexOption.DOT_MATCHES_ALL)
        for (m in response.findAll(xml)) {
            val block = m.groupValues[1]
            val href = tag(block, "href") ?: continue
            val absolute = try { base.resolve(URI(href.replace(" ", "%20"))).toString() } catch (_: Exception) { continue }
            if (absolute.trimEnd('/') == folderUrl.trimEnd('/')) continue
            val isFolder = Regex("<(?:[A-Za-z0-9]+:)?collection\\s*/?>").containsMatchIn(block)
            val name = tag(block, "displayname")?.takeIf { it.isNotBlank() }?.let(::unescape)
                ?: URLDecoder.decode(absolute.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
            out += RemoteEntry(
                name = name,
                path = absolute,
                isFolder = isFolder,
                mimeType = tag(block, "getcontenttype")?.substringBefore(';')?.trim()?.ifEmpty { null },
                size = tag(block, "getcontentlength")?.toLongOrNull(),
            )
        }
        return out.sortedWith(compareByDescending<RemoteEntry> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:[A-Za-z0-9]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9]+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
