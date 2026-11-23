package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.mediaservers.PlaybackPlanner
import io.github.patschgoo.castapp.mediaservers.ServerSubtitle
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Which subtitle track inside the file a CastApp TV shows when it plays a server's file itself
 * (Plex: the MKV with its ASS/SRT tracks), told by CastApp on the phone. Without one the TV's
 * player picks by itself (the file's default or forced track).
 *
 * [off]: none (picked by hand on the phone). Otherwise the track at [track] among the file's
 * subtitle tracks if its [language] agrees, else one in [language] named [title], else the
 * player's choice in [languages] (the phone's subtitle languages, best first).
 */
data class TextTrackHint(
    val off: Boolean = false,
    val track: Int? = null,
    val language: String? = null,
    val title: String? = null,
    val languages: List<String> = emptyList(),
) {
    /** "off", or "t=2&lang=jpn&title=Full&prefer=de,en". */
    fun encode(): String = if (off) OFF else listOfNotNull(
        track?.let { "t=$it" },
        language?.let { "lang=" + enc(it) },
        title?.let { "title=" + enc(it) },
        languages.takeIf { it.isNotEmpty() }?.let { "prefer=" + enc(it.joinToString(",")) },
    ).joinToString("&")

    /** The languages to prefer, the track's own first. */
    val preferred: List<String> get() = (listOfNotNull(language) + languages).distinct()

    /** One subtitle track the TV's player found in the file: its language and name, as Media3 tells them. */
    data class Found(val language: String?, val label: String?)

    /** The place in [found] (the file's subtitle tracks in its order) of the track to show, or null to leave it to the player. */
    fun pick(found: List<Found>): Int? {
        if (off) return null
        fun agrees(f: Found) = language == null || f.language == null || f.language == "und" ||
            PlaybackPlanner.languageMatches(language, f.language) || PlaybackPlanner.languageMatches(f.language, language)
        track?.let { t -> found.getOrNull(t)?.takeIf(::agrees)?.let { return t } }
        if (language == null) return null
        val same = found.withIndex().filter { (_, f) -> f.language != null && agrees(f) }
        return (same.firstOrNull { (_, f) -> !title.isNullOrBlank() && f.label.equals(title, ignoreCase = true) } ?: same.firstOrNull())?.index
    }

    companion object {
        private const val OFF = "off"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun dec(s: String) = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }

        fun decode(text: String?): TextTrackHint? {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (t == OFF) return TextTrackHint(off = true)
            val values = t.split('&').filter { '=' in it }.associate { it.substringBefore('=') to dec(it.substringAfter('=')) }
            val hint = TextTrackHint(
                track = values["t"]?.toIntOrNull()?.takeIf { it >= 0 },
                language = values["lang"]?.takeIf { it.isNotBlank() },
                title = values["title"]?.takeIf { it.isNotBlank() },
                languages = values["prefer"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            )
            return hint.takeIf { it.track != null || it.language != null || it.languages.isNotEmpty() }
        }

        /**
         * What CastApp tells a CastApp TV that plays the server's file itself: the track shown on
         * the phone's side when it is one inside that file ([shownFile], a [ServerSubtitle]), "off"
         * when subtitles were switched off by hand, else nothing (the TV's player picks).
         * Another subtitle file goes to the TV next to the video as before (the TV then shows that one).
         */
        fun forTv(shownFile: String?, pickedOff: Boolean, languages: List<String>): TextTrackHint? {
            if (pickedOff) return TextTrackHint(off = true)
            val sub = shownFile?.let { ServerSubtitle.parse(it) }?.takeIf { it.inFile } ?: return null
            return TextTrackHint(track = sub.track, language = sub.language, title = sub.title, languages = languages)
        }
    }
}
