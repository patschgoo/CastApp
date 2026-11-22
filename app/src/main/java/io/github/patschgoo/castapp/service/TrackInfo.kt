package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
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
    ).ifEmpty { listOf(Txt.sOr(R.string.track_n, "Track %d", ordinal + 1)) }.joinToString(" · ")
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
        label?.takeIf { it.isNotBlank() } ?: TrackNames.language(language) ?: Txt.sOr(R.string.track_n, "Track %d", ordinal + 1),
        TrackNames.codec(mimeType),
        if (forced) Txt.sOr(R.string.track_forced, "forced") else null,
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
        val tag = THREE_LETTER[c.lowercase()] ?: c.replace('_', '-')
        val name = try { Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()) } catch (_: Exception) { "" }
        return name.takeIf { it.isNotBlank() && !it.equals(c, true) } ?: c
    }

    private val THREE_LETTER = mapOf(
        "ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el",
        "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy",
        "baq" to "eu", "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk",
        "may" to "ms", "wel" to "cy", "tib" to "bo",
        "eng" to "en", "deu" to "de", "fra" to "fr", "spa" to "es", "ita" to "it",
        "por" to "pt", "nld" to "nl", "rus" to "ru", "pol" to "pl", "tur" to "tr",
        "jpn" to "ja", "kor" to "ko", "zho" to "zh", "swe" to "sv", "dan" to "da",
        "nor" to "no", "fin" to "fi", "hun" to "hu", "ces" to "cs", "ell" to "el",
        "ara" to "ar", "heb" to "he", "hin" to "hi", "ukr" to "uk", "ron" to "ro",
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
        n == 1 -> Txt.sOr(R.string.track_mono, "mono")
        n == 2 -> Txt.sOr(R.string.track_stereo, "stereo")
        n == 6 -> "5.1"
        n == 8 -> "7.1"
        else -> Txt.sOr(R.string.track_channels, "%d channels", n)
    }

    fun isTextSubtitle(mime: String): Boolean = mime.lowercase() in TEXT_SUBTITLES

    private val TEXT_SUBTITLES = setOf(
        "application/x-subrip", "text/x-ssa", "text/x-ass", "text/vtt", "application/x-mp4-vtt",
        "application/x-quicktime-tx3g", "text/3gpp-tt", "application/x-tx3g", "application/ttml+xml",
    )
}
