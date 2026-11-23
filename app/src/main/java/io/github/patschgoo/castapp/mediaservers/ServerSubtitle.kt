package io.github.patschgoo.castapp.mediaservers

import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.service.TextTrackInfo
import io.github.patschgoo.castapp.service.TrackNames
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A subtitle track of a Plex item as one of the subtitle files of a
 * [io.github.patschgoo.castapp.service.MediaItem]: the address it is read from, with what the
 * picker and CastApp TV need to know about it behind "#castapp-sub" (a fragment never goes to the
 * server, so older versions fetch the address as before).
 *
 * With [track], a text track inside the video file at the address, its place among the file's
 * subtitle tracks. Plex hands such tracks out only for the track picked in Plex and only through
 * its transcoder (choosing another one there means changing the user's pick on the server), so
 * CastApp reads the track out of the file itself; a CastApp TV playing that file shows it as it is.
 */
data class ServerSubtitle(
    val language: String?,
    val title: String?,
    val codec: String?,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    val track: Int? = null,
) {
    /** A track inside the video file at [address], read out on the phone. */
    val inFile: Boolean get() = track != null

    /** [address] (without any fragment) with this description behind it. */
    fun attachTo(address: String): String {
        val parts = listOfNotNull(
            track?.let { "t=$it" },
            language?.let { "lang=" + enc(it) },
            title?.let { "title=" + enc(it) },
            codec?.let { "codec=" + enc(it) },
            "forced=1".takeIf { forced },
            "sdh=1".takeIf { hearingImpaired },
        )
        return address.substringBefore('#') + "#" + (listOf(MARK) + parts).joinToString("&")
    }

    /** The picker's name for it: its language, with the track's own name where that says more ("English (Signs & Songs)"). */
    fun label(): String {
        val languageName = TrackNames.language(language)
        val own = title?.trim()?.takeIf { it.isNotEmpty() && !it.equals(languageName, true) && !it.equals(language, true) }
        return when {
            languageName == null -> own ?: Txt.sOr(R.string.track_n, "Track %d", (track ?: 0) + 1)
            own == null -> languageName
            else -> "$languageName ($own)"
        }
    }

    /** The picker's second line: "Inside the video file · SSA/ASS · forced". */
    fun detail(): String = listOfNotNull(
        if (inFile) Txt.sOr(R.string.subs_in_file, "Inside the video file") else null,
        codecName(codec),
        if (forced) Txt.sOr(R.string.track_forced, "forced") else null,
        if (hearingImpaired) Txt.sOr(R.string.track_sdh, "SDH") else null,
    ).joinToString(" · ")

    companion object {
        const val MARK = "castapp-sub"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun dec(s: String) = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }

        /** What [uri] carries behind "#castapp-sub", or null for any other subtitle file. */
        fun parse(uri: String): ServerSubtitle? {
            val fragment = uri.substringAfter('#', "").takeIf { it == MARK || it.startsWith("$MARK&") } ?: return null
            val values = fragment.split('&').drop(1).filter { '=' in it }.associate { it.substringBefore('=') to dec(it.substringAfter('=')) }
            return ServerSubtitle(
                language = values["lang"],
                title = values["title"],
                codec = values["codec"],
                forced = values["forced"] == "1",
                hearingImpaired = values["sdh"] == "1",
                track = values["t"]?.toIntOrNull()?.takeIf { it >= 0 },
            )
        }

        /** Where the subtitle (or the video file holding it) is read from: [uri] without its fragment. */
        fun address(uri: String): String = uri.substringBefore('#')

        /** A text track inside the video file, to be read out on the phone. */
        fun isInFile(uri: String): Boolean = parse(uri)?.inFile == true

        /** "SRT", "SSA/ASS"… for a server's codec name. */
        fun codecName(codec: String?): String? = when (codec?.lowercase()) {
            null, "" -> null
            "srt", "subrip" -> "SRT"
            "ass", "ssa" -> "SSA/ASS"
            "vtt", "webvtt" -> "WebVTT"
            "mov_text", "tx3g" -> "tx3g"
            "ttml" -> "TTML"
            "smi" -> "SAMI"
            else -> codec.uppercase()
        }

        /**
         * The place of [sub] among the subtitle tracks Media3 finds in the file ([listed],
         * [io.github.patschgoo.castapp.subtitles.EmbeddedSubtitles.list]): the one at the server's
         * place when its language agrees, else the first of that language with the same name, else
         * the first of that language; null when the file has none like it.
         */
        fun ordinalIn(listed: List<TextTrackInfo>, sub: ServerSubtitle): Int? {
            fun sameLanguage(t: TextTrackInfo) = sub.language == null || t.language == null || t.language == "und" ||
                PlaybackPlanner.languageMatches(sub.language, t.language) || PlaybackPlanner.languageMatches(t.language, sub.language)
            listed.firstOrNull { it.ordinal == sub.track }?.takeIf { it.supported && sameLanguage(it) }?.let { return it.ordinal }
            val candidates = listed.filter { it.supported && sub.language != null && it.language != null && sameLanguage(it) }
            return (candidates.firstOrNull { !sub.title.isNullOrBlank() && it.label.equals(sub.title, ignoreCase = true) } ?: candidates.firstOrNull())?.ordinal
        }
    }
}
