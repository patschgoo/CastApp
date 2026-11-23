package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.mediaservers.ServerSubtitle

/**
 * Which subtitle track inside the file a CastApp TV shows when it plays a server's file itself
 * (Plex: the MKV with its ASS/SRT tracks), told by CastApp on the phone. Without one the TV's
 * player picks by itself (the file's default or forced track).
 *
 * [off]: none (picked by hand on the phone). Otherwise the track at [track] among the file's
 * subtitle tracks if its [language] agrees, else one in [language] named [title], else the
 * player's choice in [languages] (the phone's subtitle languages, best first).
 *
 * The fields, their wire format and the picking are [TrackHint]'s, shared with the sound track.
 */
data class TextTrackHint(
    val off: Boolean = false,
    val track: Int? = null,
    val language: String? = null,
    val title: String? = null,
    val languages: List<String> = emptyList(),
) {
    /** The same as a [TrackHint]. */
    val asTrackHint: TrackHint get() = TrackHint(off, track, language, title, languages)

    /** "off", or "t=2&lang=jpn&title=Full&prefer=de,en". */
    fun encode(): String = asTrackHint.encode()

    /** The languages to prefer, the track's own first. */
    val preferred: List<String> get() = asTrackHint.preferred

    /** One subtitle track the TV's player found in the file: its language and name, as Media3 tells them. */
    data class Found(val language: String?, val label: String?)

    /** The place in [found] (the file's subtitle tracks in its order) of the track to show, or null to leave it to the player. */
    fun pick(found: List<Found>): Int? = asTrackHint.pick(found.map { TrackHint.Found(it.language, it.label) })

    companion object {
        fun of(hint: TrackHint) = TextTrackHint(hint.off, hint.track, hint.language, hint.title, hint.languages)

        fun decode(text: String?): TextTrackHint? = TrackHint.decode(text)?.let(::of)

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
