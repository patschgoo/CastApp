package io.github.patschgoo.castapp.dlna

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * UPnP / DLNA helpers for controlling smart TVs that don't have Chromecast
 * built in (most Samsung, LG, Sony, Philips, Panasonic TVs): SSDP discovery
 * messages, device descriptions and AVTransport / RenderingControl SOAP calls.
 */
object Dlna {
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    /** Marker of the subtitle look in the metadata CastApp sends to CastApp TV receivers. */
    const val SUBTITLE_STYLE_PREFIX = "castapp:substyle:"

    /** The subtitle look in DIDL-Lite metadata from CastApp, or null. */
    fun subtitleStyleIn(metadata: String): String? =
        Regex(Regex.escape(SUBTITLE_STYLE_PREFIX) + "([^<]*)").find(metadata)?.groupValues?.get(1)?.let(::unescape)?.trim()

    fun searchMessage(target: String = MEDIA_RENDERER): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $target\r\n" +
            "USER-AGENT: Android UPnP/1.1 CastApp/1.0\r\n\r\n"

    /** Header map (lower-case keys) of an SSDP response or announcement. */
    fun parseSsdp(message: String): Map<String, String> {
        val headers = HashMap<String, String>()
        for (line in message.lines().drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return headers
    }

    class Description(
        val friendlyName: String,
        val manufacturer: String?,
        val modelName: String?,
        val udn: String?,
        val avTransportUrl: String?,
        val renderingControlUrl: String?,
        val isRenderer: Boolean,
        val contentDirectoryUrl: String? = null,
        val iconUrl: String? = null,
    ) {
        val isServer: Boolean get() = contentDirectoryUrl != null
    }

    /** Parses a UPnP device description (the XML behind an SSDP LOCATION). */
    fun parseDescription(xml: String, location: String): Description {
        val base = tag(xml, "URLBase")?.takeIf { it.startsWith("http") } ?: location
        var avt: String? = null
        var rc: String? = null
        var cd: String? = null
        for (service in blocks(xml, "service")) {
            val type = tag(service, "serviceType") ?: continue
            val control = tag(service, "controlURL") ?: continue
            when {
                type.startsWith("urn:schemas-upnp-org:service:AVTransport:") && avt == null -> avt = resolve(base, control)
                type.startsWith("urn:schemas-upnp-org:service:RenderingControl:") && rc == null -> rc = resolve(base, control)
                type.startsWith("urn:schemas-upnp-org:service:ContentDirectory:") && cd == null -> cd = resolve(base, control)
            }
        }
        return Description(
            friendlyName = tag(xml, "friendlyName")?.let(::unescape) ?: "TV",
            manufacturer = tag(xml, "manufacturer")?.let(::unescape),
            modelName = tag(xml, "modelName")?.let(::unescape),
            udn = tag(xml, "UDN"),
            avTransportUrl = avt,
            renderingControlUrl = rc,
            isRenderer = xml.contains("MediaRenderer") || avt != null,
            contentDirectoryUrl = cd,
            iconUrl = blocks(xml, "icon").firstNotNullOfOrNull { tag(it, "url") }?.let { resolve(base, it) },
        )
    }

    fun soapEnvelope(service: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(service).append("\">")
        for ((k, v) in args) append('<').append(k).append('>').append(escape(v)).append("</").append(k).append('>')
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    /**
     * DIDL-Lite metadata describing what to play. Samsung TVs pick up SRT
     * subtitles from the sec:CaptionInfoEx element.
     */
    fun didl(
        title: String,
        url: String,
        mimeType: String,
        live: Boolean,
        srtUrl: String? = null,
        lowLatency: Boolean = false,
        /** CastApp TV receivers: how subtitles should look ([io.github.patschgoo.castapp.subtitles.SubtitleStyle.encode]). */
        subtitleStyle: String? = null,
    ): String {
        val upnpClass = when {
            mimeType.startsWith("audio/") -> "object.item.audioItem.musicTrack"
            mimeType.startsWith("image/") -> "object.item.imageItem.photo"
            else -> "object.item.videoItem"
        }
        val features = (if (live) "DLNA.ORG_OP=00;" else "DLNA.ORG_OP=01;") +
            "DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
            append("xmlns:sec=\"http://www.sec.co.kr/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(escape(title)).append("</dc:title>")
            append("<upnp:class>").append(upnpClass).append("</upnp:class>")
            append("<res protocolInfo=\"http-get:*:").append(mimeType).append(':').append(features).append("\">")
            append(escape(url)).append("</res>")
            if (srtUrl != null) {
                val type = if (srtUrl.substringBefore('?').endsWith(".vtt", ignoreCase = true)) "vtt" else "srt"
                append("<res protocolInfo=\"http-get:*:text/").append(type).append(":*\">").append(escape(srtUrl)).append("</res>")
                append("<sec:CaptionInfoEx sec:type=\"").append(type).append("\">").append(escape(srtUrl)).append("</sec:CaptionInfoEx>")
            }
            // CastApp TV receivers play the screen stream with minimal buffering when they see this.
            if (lowLatency) append("<desc id=\"castapp\" nameSpace=\"urn:castapp\">castapp:lowlatency</desc>")
            if (subtitleStyle != null && srtUrl != null) {
                append("<desc id=\"castapp-subtitles\" nameSpace=\"urn:castapp\">").append(SUBTITLE_STYLE_PREFIX).append(escape(subtitleStyle)).append("</desc>")
            }
            append("</item></DIDL-Lite>")
        }
    }

    /** "01:02:03" (or "1:02:03.500") to milliseconds; null for "NOT_IMPLEMENTED" and the like. */
    fun parseTime(text: String?): Long? {
        val parts = text?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return (h * 3600 + m * 60) * 1000 + (s * 1000).toLong()
    }

    fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT, total / 3600, (total % 3600) / 60, total % 60)
    }

    /** Value of the first `<name>` element (namespace prefixes are ignored). */
    fun tag(xml: String, name: String): String? =
        Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()

    private fun blocks(xml: String, name: String): List<String> =
        Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()

    fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref.trim()).toString()
    } catch (_: Exception) {
        ref
    }

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    class SoapException(message: String) : IOException(message)

    /** Calls [action] on a service and returns the response body. */
    fun call(controlUrl: String, service: String, action: String, args: List<Pair<String, String>>, timeoutMs: Int = 5000): String {
        val body = soapEnvelope(service, action, args).toByteArray(Charsets.UTF_8)
        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            conn.setRequestProperty("Connection", "close")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code >= 400) {
                val detail = tag(text, "errorDescription") ?: tag(text, "faultstring") ?: "HTTP $code"
                throw SoapException("$action failed: $detail")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads a small document (device description). */
    fun fetch(url: String, timeoutMs: Int = 3000): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        try {
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** A folder or file on a DLNA media server (Plex, Jellyfin, a NAS, Windows media sharing…). */
    data class Entry(
        val id: String,
        val title: String,
        val isFolder: Boolean,
        val url: String? = null,
        val mimeType: String? = null,
        val size: Long? = null,
        val durationMs: Long? = null,
        val resolution: String? = null,
        val artUrl: String? = null,
    )

    /** Parses a ContentDirectory Browse result (DIDL-Lite) into folders and files. */
    fun parseDidl(didl: String): List<Entry> {
        val out = ArrayList<Entry>()
        val element = Regex("<(container|item)\\b([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        for (m in element.findAll(didl)) {
            val kind = m.groupValues[1]
            val attrs = m.groupValues[2]
            val body = m.groupValues[3]
            val id = Regex("\\bid=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.let(::unescape) ?: continue
            val title = tag(body, "title")?.let(::unescape) ?: "?"
            if (kind == "container") {
                out += Entry(id, title, isFolder = true)
                continue
            }
            // Prefer the first plain HTTP resource (the original file).
            val res = Regex("<res\\b([^>]*)>(.*?)</res>", RegexOption.DOT_MATCHES_ALL).findAll(body)
                .map { it.groupValues[1] to unescape(it.groupValues[2].trim()) }
                .firstOrNull { (a, url) -> url.startsWith("http") && Regex("protocolInfo=\"http-get:").containsMatchIn(a) }
                ?: continue
            val (resAttrs, url) = res
            fun attr(name: String) = Regex("\\b$name=\"([^\"]*)\"").find(resAttrs)?.groupValues?.get(1)
            val mime = attr("protocolInfo")?.split(':')?.getOrNull(2)?.takeIf { it.contains('/') }
            out += Entry(
                id = id,
                title = title,
                isFolder = false,
                url = url,
                mimeType = mime,
                size = attr("size")?.toLongOrNull(),
                durationMs = parseTime(attr("duration")),
                resolution = attr("resolution"),
                artUrl = tag(body, "albumArtURI")?.let(::unescape),
            )
        }
        return out
    }

    /** Lists a folder of a media server ("0" is the top). Follows paging up to [max] entries. */
    fun browse(contentDirectoryUrl: String, objectId: String, max: Int = 2000): List<Entry> {
        val all = ArrayList<Entry>()
        var start = 0
        while (all.size < max) {
            val response = call(
                contentDirectoryUrl, CONTENT_DIRECTORY, "Browse",
                listOf(
                    "ObjectID" to objectId, "BrowseFlag" to "BrowseDirectChildren", "Filter" to "*",
                    "StartingIndex" to start.toString(), "RequestedCount" to "200", "SortCriteria" to "",
                ),
                timeoutMs = 15_000,
            )
            val result = tag(response, "Result")?.let(::unescape) ?: break
            val page = parseDidl(result)
            all += page
            val returned = tag(response, "NumberReturned")?.toIntOrNull() ?: page.size
            val total = tag(response, "TotalMatches")?.toIntOrNull() ?: 0
            start += returned
            if (returned == 0 || start >= total) break
        }
        return all
    }
}
