package io.github.patschgoo.castapp.service

import java.util.Locale

/** A sound track inside a video file. [ordinal]: its place among the file's sound tracks (0 = first). */
data class AudioTrackInfo(
    val ordinal: Int,
    val language: String?,
    val mimeType: String,
    val channels: Int,
    val label: String? = null,
) {
    /** "English · AC-3 · 5.1" */
    fun describe(): String = listOfNotNull(
        label?.takeIf { it.isNotBlank() } ?: TrackNames.language(language),
        TrackNames.codec(mimeType),
        TrackNames.channels(channels),
    ).ifEmpty { listOf("Track ${ordinal + 1}") }.joinToString(" · ")
}

/** A subtitle track inside a video file. [ordinal]: its place among the file's subtitle tracks. */
data class TextTrackInfo(
    val ordinal: Int,
    val language: String?,
    /** The original format, e.g. "application/x-subrip", "text/x-ssa", "application/pgs". */
    val mimeType: String,
    val label: String? = null,
    val forced: Boolean = false,
) {
    /** Text subtitles can be read out of the file; picture subtitles (PGS, VobSub, DVB) can't. */
    val supported: Boolean get() = TrackNames.isTextSubtitle(mimeType)

    fun describe(): String = listOfNotNull(
        label?.takeIf { it.isNotBlank() } ?: TrackNames.language(language) ?: "Track ${ordinal + 1}",
        TrackNames.codec(mimeType),
        if (forced) "forced" else null,
    ).joinToString(" · ")
}

data class TrackList(val audio: List<AudioTrackInfo> = emptyList(), val text: List<TextTrackInfo> = emptyList()) {
    val textSupported: List<TextTrackInfo> get() = text.filter { it.supported }
    val pictureSubtitles: Int get() = text.count { !it.supported }
}

/** Readable names for languages, codecs and channel layouts. */
object TrackNames {
    fun language(code: String?): String? {
        val c = code?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", true) && !it.equals("unknown", true) } ?: return null
        // Three-letter codes like "ger"/"fre" (ISO 639-2) aren't always known to Locale.
        THREE_LETTER[c.lowercase()]?.let { return it }
        val name = try { Locale.forLanguageTag(c.replace('_', '-')).getDisplayLanguage(Locale.ENGLISH) } catch (_: Exception) { "" }
        return name.takeIf { it.isNotBlank() && !it.equals(c, true) } ?: c
    }

    private val THREE_LETTER = mapOf(
        "ger" to "German", "fre" to "French", "dut" to "Dutch", "chi" to "Chinese", "cze" to "Czech", "gre" to "Greek",
        "per" to "Persian", "rum" to "Romanian", "slo" to "Slovak", "alb" to "Albanian", "arm" to "Armenian",
        "baq" to "Basque", "bur" to "Burmese", "geo" to "Georgian", "ice" to "Icelandic", "mac" to "Macedonian",
        "may" to "Malay", "wel" to "Welsh", "tib" to "Tibetan",
        "eng" to "English", "deu" to "German", "fra" to "French", "spa" to "Spanish", "ita" to "Italian",
        "por" to "Portuguese", "nld" to "Dutch", "rus" to "Russian", "pol" to "Polish", "tur" to "Turkish",
        "jpn" to "Japanese", "kor" to "Korean", "zho" to "Chinese", "swe" to "Swedish", "dan" to "Danish",
        "nor" to "Norwegian", "fin" to "Finnish", "hun" to "Hungarian", "ces" to "Czech", "ell" to "Greek",
        "ara" to "Arabic", "heb" to "Hebrew", "hin" to "Hindi", "ukr" to "Ukrainian", "ron" to "Romanian",
    )

    fun codec(mime: String): String? = when (mime.lowercase()) {
        "audio/mp4a-latm", "audio/aac", "audio/mp4" -> "AAC"
        "audio/ac3", "audio/ac-3" -> "AC-3"
        "audio/eac3", "audio/e-ac-3", "audio/eac3-joc" -> "E-AC-3"
        "audio/ac4" -> "AC-4"
        "audio/vnd.dts", "audio/dts" -> "DTS"
        "audio/vnd.dts.hd", "audio/dts-hd" -> "DTS-HD"
        "audio/true-hd", "audio/truehd" -> "TrueHD"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l2" -> "MP2"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        "application/x-subrip" -> "SRT"
        "text/x-ssa", "text/x-ass" -> "SSA/ASS"
        "text/vtt" -> "WebVTT"
        "application/x-mp4-vtt" -> "WebVTT"
        "application/x-quicktime-tx3g", "text/3gpp-tt", "application/x-tx3g" -> "tx3g"
        "application/ttml+xml" -> "TTML"
        "application/pgs" -> "PGS"
        "application/vobsub" -> "VobSub"
        "application/dvbsubs" -> "DVB"
        "application/x-mp4-cea-608", "application/cea-608" -> "CEA-608"
        else -> mime.substringAfter('/').substringAfter("x-").uppercase().takeIf { it.isNotBlank() }
    }

    fun channels(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "mono"
        n == 2 -> "stereo"
        n == 6 -> "5.1"
        n == 8 -> "7.1"
        else -> "$n channels"
    }

    fun isTextSubtitle(mime: String): Boolean = mime.lowercase() in TEXT_SUBTITLES

    private val TEXT_SUBTITLES = setOf(
        "application/x-subrip", "text/x-ssa", "text/x-ass", "text/vtt", "application/x-mp4-vtt",
        "application/x-quicktime-tx3g", "text/3gpp-tt", "application/x-tx3g", "application/ttml+xml",
    )
}
