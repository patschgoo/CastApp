package io.github.patschgoo.castapp.iptv

import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** An Xtream Codes log-in: server, user name and password. */
data class XtreamAccount(val server: String, val user: String, val password: String) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun apiUrl(action: String? = null, vararg params: Pair<String, String>): String = buildString {
        append(server).append("/player_api.php?username=").append(enc(user)).append("&password=").append(enc(password))
        if (action != null) append("&action=").append(action)
        for ((k, v) in params) append('&').append(k).append('=').append(enc(v))
    }

    val xmltvUrl: String get() = "$server/xmltv.php?username=${enc(user)}&password=${enc(password)}"

    /** `<server>/movie/<user>/<pass>/<id>.<ext>` */
    fun movieUrl(streamId: String, extension: String): String = "$server/movie/${seg(user)}/${seg(password)}/${seg(streamId)}.${ext(extension)}"

    /** `<server>/series/<user>/<pass>/<episode id>.<ext>` */
    fun episodeUrl(episodeId: String, extension: String): String = "$server/series/${seg(user)}/${seg(password)}/${seg(episodeId)}.${ext(extension)}"

    /**
     * Catch-up: `<server>/timeshift/<user>/<pass>/<minutes>/<YYYY-MM-DD:HH-MM>/<stream id>.ts`.
     * Providers expect the start time in their own time zone, which is usually the one
     * the guide uses; [timeZone] is that zone.
     */
    fun timeshiftUrl(streamId: String, startMs: Long, durationMinutes: Int, timeZone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(timeZone, Locale.US).apply { timeInMillis = startMs }
        val stamp = String.format(
            Locale.US, "%04d-%02d-%02d:%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE),
        )
        return "$server/timeshift/${seg(user)}/${seg(password)}/$durationMinutes/$stamp/${seg(streamId)}.ts"
    }

    /** The id of a live channel in its stream address (`…/live/u/p/123.ts` or `…/u/p/123`). */
    fun liveStreamId(channelUrl: String): String? {
        if (!channelUrl.startsWith(server)) return null
        val last = channelUrl.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return last.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun seg(s: String) = enc(s).replace("+", "%20")
    private fun ext(s: String) = s.trim().trimStart('.').ifEmpty { "mp4" }

    companion object {
        /**
         * Recognises an Xtream playlist address, `<server>/get.php?username=…&password=…`.
         * Null for anything else.
         */
        fun fromPlaylistUrl(url: String): XtreamAccount? {
            val trimmed = url.trim()
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val path = uri.rawPath.orEmpty()
            if (!path.endsWith("/get.php")) return null
            val query = uri.rawQuery ?: return null
            val params = query.split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val user = params["username"]?.takeIf { it.isNotEmpty() } ?: return null
            val pass = params["password"] ?: return null
            val host = uri.rawAuthority ?: return null
            return XtreamAccount("$scheme://$host${path.removeSuffix("/get.php")}", user, pass)
        }
    }
}

data class XtreamCategory(val id: String, val name: String)

data class Movie(
    val streamId: String,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val extension: String,
    val rating: String? = null,
    val year: String? = null,
)

data class SeriesShow(
    val seriesId: String,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val plot: String? = null,
    val year: String? = null,
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val extension: String,
    val cover: String? = null,
    val plot: String? = null,
    val durationSeconds: Int = 0,
) {
    val code: String get() = "S%02dE%02d".format(season, number)
}

data class SeriesInfo(val name: String, val cover: String?, val plot: String?, val episodes: List<Episode>)

/** A live channel's catch-up settings from `get_live_streams`. */
data class LiveStream(val streamId: String, val name: String, val archiveDays: Int)

/** Parses the `player_api.php` answers. Lists are read one entry at a time, so tens of thousands of films are fine. */
object XtreamJson {
    fun categories(reader: Reader): List<XtreamCategory> = buildList {
        MiniJson.forEachElement(reader) { o ->
            val id = o.str("category_id")
            val name = o.str("category_name")
            if (id != null && name != null) add(XtreamCategory(id, name))
        }
    }

    fun movies(reader: Reader): List<Movie> = buildList {
        MiniJson.forEachElement(reader) { o ->
            val id = o.str("stream_id") ?: return@forEachElement
            val name = o.str("name") ?: return@forEachElement
            add(
                Movie(
                    streamId = id,
                    name = name,
                    cover = o.str("stream_icon")?.takeIf { it.startsWith("http") },
                    categoryId = o.str("category_id"),
                    extension = o.str("container_extension") ?: "mp4",
                    rating = o.str("rating")?.takeIf { it != "0" && it != "0.0" },
                    year = o.str("year") ?: o.str("releaseDate")?.take(4),
                ),
            )
        }
    }

    fun series(reader: Reader): List<SeriesShow> = buildList {
        MiniJson.forEachElement(reader) { o ->
            val id = o.str("series_id") ?: return@forEachElement
            val name = o.str("name") ?: return@forEachElement
            add(
                SeriesShow(
                    seriesId = id,
                    name = name,
                    cover = o.str("cover")?.takeIf { it.startsWith("http") },
                    categoryId = o.str("category_id"),
                    plot = o.str("plot"),
                    year = o.str("year") ?: o.str("releaseDate")?.take(4),
                ),
            )
        }
    }

    fun liveStreams(reader: Reader): List<LiveStream> = buildList {
        MiniJson.forEachElement(reader) { o ->
            val id = o.str("stream_id") ?: return@forEachElement
            val archive = o.str("tv_archive")?.toIntOrNull() ?: 0
            if (archive > 0) add(LiveStream(id, o.str("name").orEmpty(), o.str("tv_archive_duration")?.toIntOrNull()?.takeIf { it > 0 } ?: 3))
        }
    }

    /** `get_series_info`: episodes either as `{"1": [..], "2": [..]}` (by season) or as a list of lists. */
    fun seriesInfo(text: String): SeriesInfo {
        val root = MiniJson.parse(StringReader(text)) as? Map<*, *> ?: throw IOException("Unexpected answer for the series")
        val info = root["info"] as? Map<*, *>
        val episodes = ArrayList<Episode>()
        fun addSeason(seasonKey: Int?, list: Any?) {
            for (e in list as? List<*> ?: return) {
                val o = e as? Map<*, *> ?: continue
                val id = o.str("id") ?: continue
                val details = o["info"] as? Map<*, *>
                val season = o.str("season")?.toIntOrNull() ?: seasonKey ?: 1
                val number = o.str("episode_num")?.toIntOrNull() ?: (episodes.count { it.season == season } + 1)
                episodes += Episode(
                    id = id,
                    title = o.str("title")?.takeIf { it.isNotBlank() } ?: "Episode $number",
                    season = season,
                    number = number,
                    extension = o.str("container_extension") ?: "mp4",
                    cover = details?.str("movie_image")?.takeIf { it.startsWith("http") },
                    plot = details?.str("plot"),
                    durationSeconds = details?.str("duration_secs")?.toIntOrNull() ?: 0,
                )
            }
        }
        when (val raw = root["episodes"]) {
            is Map<*, *> -> raw.forEach { (k, v) -> addSeason(k?.toString()?.toIntOrNull(), v) }
            is List<*> -> raw.forEachIndexed { i, v -> addSeason(i + 1, v) }
        }
        return SeriesInfo(
            name = info?.str("name").orEmpty(),
            cover = info?.str("cover")?.takeIf { it.startsWith("http") },
            plot = info?.str("plot"),
            episodes = episodes.sortedWith(compareBy({ it.season }, { it.number })),
        )
    }
}

/** The mime type for a container extension; MKV/AVI and friends are sent as is. */
fun containerMime(extension: String): String = when (extension.trim().trimStart('.').lowercase()) {
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts" -> "video/mp2t"
    "m3u8" -> "application/x-mpegurl"
    else -> "video/mp4"
}

/** Looks up a value of a parsed JSON object as text (numbers and strings alike; blank and null give null). */
internal fun Map<*, *>.str(key: String): String? = when (val v = this[key]) {
    null -> null
    is String -> v.trim().ifEmpty { null }
    is Double -> if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    is Boolean -> v.toString()
    else -> null
}

/**
 * Minimal JSON reader that works on a [Reader] and can hand over the elements of a
 * top-level array one at a time. Values become Map / List / String / Double / Boolean / null.
 */
internal object MiniJson {
    fun parse(reader: Reader): Any? = Parser(reader).run {
        skipWs()
        value()
    }

    /** Calls [onElement] for each object of a top-level array; anything else (an error object, `false`, `[]`) yields nothing. */
    fun forEachElement(reader: Reader, onElement: (Map<*, *>) -> Unit) {
        val p = Parser(reader)
        p.skipWs()
        if (p.peek() != '['.code) return
        p.read()
        p.skipWs()
        if (p.peek() == ']'.code) return
        while (true) {
            p.skipWs()
            val v = p.value()
            if (v is Map<*, *>) onElement(v)
            p.skipWs()
            val c = p.read()
            if (c != ','.code) return
        }
    }

    private class Parser(private val r: Reader) {
        private val buf = CharArray(16 * 1024)
        private var len = 0
        private var pos = 0

        fun read(): Int {
            if (pos >= len && !fill()) return -1
            return buf[pos++].code
        }

        fun peek(): Int {
            if (pos >= len && !fill()) return -1
            return buf[pos].code
        }

        private fun fill(): Boolean {
            val n = r.read(buf, 0, buf.size)
            len = if (n > 0) n else 0
            pos = 0
            return n > 0
        }

        fun skipWs() {
            while (true) {
                val c = peek()
                if (c < 0 || c > ' '.code) return
                read()
            }
        }

        fun value(): Any? {
            skipWs()
            return when (val c = peek()) {
                '{'.code -> obj()
                '['.code -> array()
                '"'.code -> string()
                -1 -> throw IOException("Unexpected end of JSON")
                else -> {
                    val sb = StringBuilder()
                    while (true) {
                        val d = peek()
                        if (d < 0 || d == ','.code || d == '}'.code || d == ']'.code || d <= ' '.code) break
                        sb.append(read().toChar())
                    }
                    when (val t = sb.toString()) {
                        "null" -> null
                        "true" -> true
                        "false" -> false
                        else -> t.toDoubleOrNull() ?: throw IOException("Bad JSON value near '${c.toChar()}'")
                    }
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            read() // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}'.code) {
                read()
                return map
            }
            while (true) {
                skipWs()
                if (peek() != '"'.code) throw IOException("Bad JSON object")
                val key = string()
                skipWs()
                if (read() != ':'.code) throw IOException("Bad JSON object")
                map[key] = value()
                skipWs()
                when (read()) {
                    ','.code -> continue
                    '}'.code -> return map
                    else -> throw IOException("Bad JSON object")
                }
            }
        }

        private fun array(): List<Any?> {
            read() // [
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']'.code) {
                read()
                return list
            }
            while (true) {
                list += value()
                skipWs()
                when (read()) {
                    ','.code -> continue
                    ']'.code -> return list
                    else -> throw IOException("Bad JSON array")
                }
            }
        }

        private fun string(): String {
            read() // "
            val sb = StringBuilder()
            while (true) {
                val c = read()
                when (c) {
                    -1 -> throw IOException("Unterminated JSON string")
                    '"'.code -> return sb.toString()
                    '\\'.code -> when (val e = read()) {
                        'n'.code -> sb.append('\n')
                        't'.code -> sb.append('\t')
                        'r'.code -> sb.append('\r')
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        'u'.code -> {
                            val hex = StringBuilder()
                            repeat(4) { read().takeIf { it >= 0 }?.let { h -> hex.append(h.toChar()) } }
                            sb.append((hex.toString().toIntOrNull(16) ?: '?'.code).toChar())
                        }
                        -1 -> throw IOException("Unterminated JSON string")
                        else -> sb.append(e.toChar()) // \" \\ \/
                    }
                    else -> sb.append(c.toChar())
                }
            }
        }
    }
}
