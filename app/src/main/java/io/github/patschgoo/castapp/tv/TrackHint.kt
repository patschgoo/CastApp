package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.mediaservers.PlaybackPlanner
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Which track of one kind inside the file a CastApp TV plays, told by the sender in the DIDL-Lite
 * metadata: the subtitle track (`castapp:subtrack:`, see [TextTrackHint]) or the sound track
 * (`castapp:audiotrack:`). Both use the same fields. Without a hint the TV's player picks by itself
 * (the file's default track).
 *
 * [off]: none (subtitles only). Otherwise the track at [track] among the file's tracks of that kind
 * if its [language] agrees, else one in [language] named [title], else the player's choice in
 * [languages] (best first).
 */
data class TrackHint(
    val off: Boolean = false,
    val track: Int? = null,
    val language: String? = null,
    val title: String? = null,
    val languages: List<String> = emptyList(),
) {
    /** "off", or "t=2&lang=jpn&title=Full&prefer=de,en" (values URL-encoded); "" when it says nothing. */
    fun encode(): String = if (off) OFF else listOfNotNull(
        track?.let { "t=$it" },
        language?.let { "lang=" + enc(it) },
        title?.let { "title=" + enc(it) },
        languages.takeIf { it.isNotEmpty() }?.let { "prefer=" + enc(it.joinToString(",")) },
    ).joinToString("&")

    /** The languages to prefer, the track's own first. */
    val preferred: List<String> get() = (listOfNotNull(language) + languages).distinct()

    /** One track of that kind the TV's player found in the file: its language and name, as Media3 tells them. */
    data class Found(val language: String?, val label: String?)

    /** The place in [found] (the file's tracks of that kind in its order) of the track to play, or null to leave it to the player. */
    fun pick(found: List<Found>): Int? {
        if (off) return null
        fun agrees(f: Found) = language == null || f.language == null || f.language == "und" || sameLanguage(language, f.language)
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

        /** "ger", "deu", "de" and "de-AT" are the same language, whichever side says which. */
        fun sameLanguage(a: String, b: String): Boolean = PlaybackPlanner.languageMatches(a, b) || PlaybackPlanner.languageMatches(b, a)

        fun decode(text: String?): TrackHint? {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (t == OFF) return TrackHint(off = true)
            val values = t.split('&').filter { '=' in it }.associate { it.substringBefore('=') to dec(it.substringAfter('=')) }
            val hint = TrackHint(
                track = values["t"]?.toIntOrNull()?.takeIf { it >= 0 },
                language = values["lang"]?.takeIf { it.isNotBlank() },
                title = values["title"]?.takeIf { it.isNotBlank() },
                languages = values["prefer"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            )
            return hint.takeIf { it.track != null || it.language != null || it.languages.isNotEmpty() }
        }
    }
}
