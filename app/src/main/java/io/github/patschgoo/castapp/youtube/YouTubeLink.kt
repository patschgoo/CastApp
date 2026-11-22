package io.github.patschgoo.castapp.youtube

import java.net.URI
import java.net.URLDecoder

/**
 * A YouTube video link: the video id, an optional playlist and start time.
 * Understands watch, youtu.be, shorts, live, embed and music links.
 */
data class YouTubeLink(
    val videoId: String,
    /** Playlist (list=...), if the video was opened from one. */
    val listId: String? = null,
    /** Where to start, in seconds (t=, start= or #t=). */
    val startSeconds: Int = 0,
) {
    /** A clean link to the video, the only kind a TV is asked to open. */
    val canonicalUrl: String
        get() = buildString {
            append("https://www.youtube.com/watch?v=").append(videoId)
            if (listId != null) append("&list=").append(listId)
            if (startSeconds > 0) append("&t=").append(startSeconds).append('s')
        }

    companion object {
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val LIST_ID = Regex("^[A-Za-z0-9_-]{2,64}$")
        private val YOUTUBE_HOSTS = setOf(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "gaming.youtube.com",
            "youtube-nocookie.com", "www.youtube-nocookie.com", "youtu.be", "www.youtu.be",
        )

        /** True if [url] points to youtube.com / youtu.be at all (also pages that aren't a video). */
        fun isYouTubeUrl(url: String): Boolean = hostOf(url)?.let { it in YOUTUBE_HOSTS } == true

        /** Parses [url]; null if it isn't a link to a YouTube video. */
        fun parse(url: String?): YouTubeLink? {
            val text = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val uri = try {
                URI(if (text.contains("://")) text else "https://$text")
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase() ?: return null
            if (host !in YOUTUBE_HOSTS) return null
            val query = parseQuery(uri.rawQuery)
            val fragment = parseQuery(uri.rawFragment)
            val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }

            val id = when {
                host.endsWith("youtu.be") -> segments.firstOrNull()
                segments.firstOrNull() == "watch" -> query["v"]
                segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed", "v", "e") -> segments[1]
                else -> query["v"]
            }?.takeIf { VIDEO_ID.matches(it) } ?: return null
            val list = query["list"]?.takeIf { LIST_ID.matches(it) }
            val start = (query["t"] ?: query["start"] ?: query["time_continue"] ?: fragment["t"])?.let(::parseTime) ?: 0
            return YouTubeLink(id, list, start)
        }

        /** "90", "90s", "1m30s", "1h2m3s", "01:30" or "1:02:03" -> seconds; null if unreadable. */
        fun parseTime(value: String): Int? {
            val v = value.trim().lowercase()
            if (v.isEmpty()) return null
            v.toIntOrNull()?.let { return it.takeIf { s -> s >= 0 } }
            if (v.contains(':')) {
                val parts = v.split(':').map { it.toIntOrNull() ?: return null }
                if (parts.size > 3 || parts.any { it < 0 }) return null
                return parts.fold(0) { acc, p -> acc * 60 + p }
            }
            val m = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(v) ?: return null
            if (m.value.isEmpty()) return null
            val (h, min, s) = m.destructured
            return (h.toIntOrNull() ?: 0) * 3600 + (min.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
        }

        /** "m:ss" / "h:mm:ss" for showing a start time. */
        fun formatTime(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds / 60 % 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s) else "%d:%02d".format(java.util.Locale.ROOT, m, s)
        }

        private fun hostOf(url: String): String? = try {
            val t = url.trim()
            URI(if (t.contains("://")) t else "https://$t").host?.lowercase()
        } catch (_: Exception) {
            null
        }

        private fun parseQuery(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val key = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))
                if (key !in out) out[key] = value
            }
            return out
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }
}
