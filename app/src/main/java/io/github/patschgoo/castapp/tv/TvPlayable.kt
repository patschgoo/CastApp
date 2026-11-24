package io.github.patschgoo.castapp.tv

/**
 * "What this TV can play", for the TV's own screen: the same answers phones and sender apps read
 * from `/castapp/capabilities` ([TvReceiver.capabilities]), sorted into what plays, what may
 * stutter (only a software decoder) and what doesn't play. Formats the report says nothing about
 * are left out.
 */
object TvPlayable {
    data class Lists(val plays: List<String>, val slow: List<String>, val not: List<String>)

    /** A format of the report: its key, its full name and the short one for the home screen's row. */
    private class Format(val key: String, val name: String, val short: String = name)

    private val videos = listOf(
        Format("h264", "H.264"),
        Format("h264_10bit", "H.264 10-bit (Hi10P)", "Hi10P"),
        Format("hevc", "HEVC (H.265)", "HEVC"),
        Format("hevc_10bit", "HEVC 10-bit"),
        Format("av1", "AV1"),
        Format("vp9", "VP9"),
        Format("vp8", "VP8"),
        Format("mpeg2", "MPEG-2"),
        Format("mpeg4", "MPEG-4 (DivX, Xvid)", "MPEG-4"),
    )

    private val sounds = listOf(
        Format("aac", "AAC"),
        Format("mp3", "MP3"),
        Format("ac3", "Dolby Digital (AC-3)", "AC-3"),
        Format("eac3", "Dolby Digital Plus (E-AC-3)", "E-AC-3"),
        Format("dts", "DTS"),
        Format("truehd", "Dolby TrueHD", "TrueHD"),
        Format("flac", "FLAC"),
        Format("opus", "Opus"),
        Format("vorbis", "Vorbis"),
    )

    fun video(caps: Map<String, Any>): Lists = sort(videos, caps) { it.name }

    fun sound(caps: Map<String, Any>): Lists = sort(sounds, caps) { it.name }

    /** The short names of what doesn't play, picture formats first: "Hi10P", "AV1", "DTS". */
    fun missing(caps: Map<String, Any>): List<String> =
        sort(videos, caps) { it.short }.not + sort(sounds, caps) { it.short }.not

    private fun sort(formats: List<Format>, caps: Map<String, Any>, name: (Format) -> String): Lists {
        val plays = ArrayList<String>()
        val slow = ArrayList<String>()
        val not = ArrayList<String>()
        for (f in formats) {
            when {
                caps[f.key] == true -> plays += name(f)
                caps[f.key + "_sw"] == true -> slow += name(f)
                caps[f.key] == false -> not += name(f)
            }
        }
        return Lists(plays, slow, not)
    }
}
