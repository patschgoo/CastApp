package io.github.patschgoo.castapp.iptv

import org.json.JSONObject

/** One TV channel (or radio station / video) from an M3U playlist. */
data class Channel(
    val name: String,
    val url: String,
    val group: String? = null,
    val logo: String? = null,
    val tvgId: String? = null,
    /** Some providers only answer with a particular User-Agent / Referer. */
    val userAgent: String? = null,
    val referrer: String? = null,
) {
    val headers: Map<String, String>
        get() = buildMap {
            userAgent?.let { put("User-Agent", it) }
            referrer?.let { put("Referer", it) }
        }

    fun toJson(): JSONObject = JSONObject()
        .put("n", name).put("u", url).put("g", group ?: "").put("l", logo ?: "")
        .put("i", tvgId ?: "").put("ua", userAgent ?: "").put("r", referrer ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Channel(
            name = o.optString("n"),
            url = o.optString("u"),
            group = o.optString("g").ifEmpty { null },
            logo = o.optString("l").ifEmpty { null },
            tvgId = o.optString("i").ifEmpty { null },
            userAgent = o.optString("ua").ifEmpty { null },
            referrer = o.optString("r").ifEmpty { null },
        )
    }
}

/**
 * Reads M3U / M3U8 channel lists as IPTV providers and public lists publish them:
 *
 * ```
 * #EXTM3U
 * #EXTINF:-1 tvg-id="das.erste" tvg-logo="https://…/logo.png" group-title="News",Das Erste HD
 * #EXTVLCOPT:http-user-agent=Mozilla/5.0
 * https://…/index.m3u8
 * ```
 * Kodi-style headers after the URL ("url|User-Agent=…&Referer=…") are understood too.
 */
object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val channels = ArrayList<Channel>()
        var info: String? = null
        var attrs: Map<String, String> = emptyMap()
        var groupLine: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        for (raw in text.trimStart(Char(0xFEFF)).lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    info = titleOf(body)
                    attrs = ATTR.findAll(body).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> groupLine = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> userAgent = value
                        "http-referrer", "http-referer" -> referrer = value
                    }
                }
                line.startsWith("#") -> Unit // #EXTM3U, #KODIPROP and other tags we don't need
                else -> {
                    var url = line
                    var ua = userAgent
                    var ref = referrer
                    if ('|' in url) {
                        // Kodi style: url|User-Agent=…&Referer=…
                        val opts = url.substringAfter('|')
                        url = url.substringBefore('|')
                        for (part in opts.split('&')) {
                            val k = part.substringBefore('=').trim().lowercase()
                            val v = java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                            if (k == "user-agent") ua = v
                            if (k == "referer" || k == "referrer") ref = v
                        }
                    }
                    if (url.contains("://")) {
                        val name = info?.takeIf { it.isNotBlank() }
                            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: url.substringBefore('?').substringAfterLast('/')
                        channels += Channel(
                            name = name,
                            url = url,
                            group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: groupLine,
                            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                            userAgent = ua ?: attrs["http-user-agent"] ?: attrs["user-agent"],
                            referrer = ref ?: attrs["http-referrer"] ?: attrs["referrer"],
                        )
                    }
                    info = null
                    attrs = emptyMap()
                    groupLine = null
                    userAgent = null
                    referrer = null
                }
            }
        }
        return channels
    }

    /** The channel name: everything after the first comma that is not inside quotes. */
    private fun titleOf(body: String): String {
        var quoted = false
        for (i in body.indices) {
            when (body[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return body.substring(i + 1).trim()
            }
        }
        return ""
    }
}
