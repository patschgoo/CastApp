package io.github.patschgoo.castapp.mediaservers

import java.util.Locale

/** What the TV can play as it is; everything else is converted by the server. */
data class PlaybackCaps(
    val containers: Set<String>,
    val videoCodecs: Set<String>,
    val audioCodecs: Set<String>,
    val audioOnlyCodecs: Set<String>,
    val maxWidth: Int,
    val maxHeight: Int,
    /** Highest bit rate (bits per second) worth sending as it is. */
    val maxBitrate: Long,
    /** What the server converts to: bit rate (bits per second) and sound channels. */
    val transcodeBitrate: Long,
    val transcodeAudioChannels: Int,
) {
    companion object {
        /** A Chromecast-like TV: the least that every Cast device can do. */
        val TV = PlaybackCaps(
            containers = setOf("mp4", "m4v", "mov", "webm"),
            videoCodecs = setOf("h264", "avc", "vp8", "vp9"),
            audioCodecs = setOf("aac", "mp3", "ac3", "eac3", "opus", "vorbis", "flac"),
            audioOnlyCodecs = setOf("mp3", "aac", "flac", "opus", "vorbis", "wav", "pcm_s16le"),
            maxWidth = 1920,
            maxHeight = 1088,
            maxBitrate = 20_000_000,
            transcodeBitrate = 15_000_000,
            transcodeAudioChannels = 2,
        )
    }
}

object PlaybackPlanner {
    private fun parts(container: String?): List<String> = container?.lowercase()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** Servers call both Matroska and WebM files "matroska,webm"; the codecs tell them apart. */
    private fun isMatroska(parts: List<String>) = parts.any { it == "matroska" || it == "mkv" }

    private fun isWebmContent(info: MediaInfo) =
        (info.videoCodec?.lowercase() in setOf("vp8", "vp9", "av1")) && (info.audioCodec == null || info.audioCodec.lowercase() in setOf("opus", "vorbis"))

    /** Direct stream when the TV can play the file as it is, otherwise the server converts it. */
    fun decideVideo(info: MediaInfo?, caps: PlaybackCaps = PlaybackCaps.TV): PlayMethod {
        if (info == null) return PlayMethod.DIRECT
        val containers = parts(info.container)
        if (containers.isNotEmpty()) {
            val fits = if (isMatroska(containers)) "webm" in caps.containers && isWebmContent(info) else containers.any { it in caps.containers }
            if (!fits) return PlayMethod.TRANSCODE
        }
        info.videoCodec?.lowercase()?.let { if (it !in caps.videoCodecs) return PlayMethod.TRANSCODE }
        info.audioCodec?.lowercase()?.let { if (it !in caps.audioCodecs) return PlayMethod.TRANSCODE }
        if ((info.bitDepth ?: 8) > 8) return PlayMethod.TRANSCODE
        if ((info.width ?: 0) > caps.maxWidth || (info.height ?: 0) > caps.maxHeight) return PlayMethod.TRANSCODE
        if ((info.bitrate ?: 0) > caps.maxBitrate) return PlayMethod.TRANSCODE
        return PlayMethod.DIRECT
    }

    fun decideAudio(info: MediaInfo?, caps: PlaybackCaps = PlaybackCaps.TV): PlayMethod {
        val codec = (info?.audioCodec ?: return PlayMethod.DIRECT).lowercase()
        return if (codec in caps.audioOnlyCodecs) PlayMethod.DIRECT else PlayMethod.TRANSCODE
    }

    /** [chooseSubtitle] for the phone's language alone. */
    fun chooseSubtitle(tracks: List<SubtitleTrack>, audioLanguage: String?, deviceLanguage: String = Locale.getDefault().language): SubtitleTrack? =
        chooseSubtitle(tracks, audioLanguage, listOf(deviceLanguage))

    /**
     * The subtitle to send along; only text subtitles can be sent. [preferred]: the languages
     * wanted, best first (the subtitle setting's, see [io.github.patschgoo.castapp.subtitles.Sidecars.preferredLanguages]).
     *
     * - Sound in the first of them (or unknown): only what isn't spoken, a forced or "Signs &
     *   Songs" track in that language; else the one picked in Plex; else the file's default.
     * - Other sound: a full track in the first language that has one (one without SDH first),
     *   else a partial one in the first language that has one; among equals the one picked in Plex.
     * - Nothing in those languages: the one picked in Plex, the file's default, or a forced one
     *   in the sound's language.
     */
    fun chooseSubtitle(tracks: List<SubtitleTrack>, audioLanguage: String?, preferred: List<String>): SubtitleTrack? {
        val text = tracks.filter { it.isText }
        if (text.isEmpty()) return null
        fun List<SubtitleTrack>.best(): SubtitleTrack? = firstOrNull { it.selected } ?: firstOrNull()
        fun inLanguage(language: String) = text.filter { languageMatches(it.language, language) }
        val main = preferred.firstOrNull()
        val soundIsOurs = audioLanguage == null || main == null || languageMatches(audioLanguage, main)
        if (soundIsOurs) {
            return main?.let { inLanguage(it).filter { t -> t.isPartial }.best() } ?: text.firstOrNull { it.selected } ?: text.firstOrNull { it.isDefault }
        }
        for (language in preferred) {
            val full = inLanguage(language).filter { !it.isPartial }
            (full.filter { !it.isSdh }.best() ?: full.best())?.let { return it }
        }
        for (language in preferred) inLanguage(language).best()?.let { return it }
        return text.firstOrNull { it.selected } ?: text.firstOrNull { it.isDefault }
            ?: text.firstOrNull { it.isForced && languageMatches(it.language, audioLanguage) }
    }

    /**
     * The order the subtitle picker lists [tracks] in after the one shown: the [preferred]
     * languages in their order, full ones before partial ones, else as the file has them.
     */
    fun rankSubtitles(tracks: List<SubtitleTrack>, preferred: List<String>): List<SubtitleTrack> =
        tracks.sortedWith(
            compareBy<SubtitleTrack> { t -> preferred.indexOfFirst { languageMatches(t.language, it) }.let { if (it < 0) preferred.size else it } }
                .thenBy { it.isPartial },
        )

    private val BIBLIOGRAPHIC = mapOf(
        "de" to "ger", "fr" to "fre", "nl" to "dut", "cs" to "cze", "el" to "gre", "zh" to "chi", "es" to "spa",
        "it" to "ita", "pt" to "por", "ru" to "rus", "ja" to "jpn", "ko" to "kor", "sv" to "swe", "pl" to "pol",
        "tr" to "tur", "ar" to "ara", "da" to "dan", "fi" to "fin", "no" to "nor", "hu" to "hun", "en" to "eng",
    )

    /** "ger", "deu", "de" and "de-AT" all match the language "de". */
    fun languageMatches(code: String?, language: String): Boolean {
        if (code.isNullOrBlank()) return false
        val c = code.lowercase().substringBefore('-').substringBefore('_')
        val l = language.lowercase()
        if (c == l) return true
        val iso3 = try { Locale.forLanguageTag(l).isO3Language.lowercase() } catch (_: Exception) { "" }
        return c == iso3 || c == BIBLIOGRAPHIC[l]
    }

    /** MIME type of a video file for the TV. */
    fun mimeForVideo(info: MediaInfo?): String {
        val p = parts(info?.container)
        return when {
            isMatroska(p) -> if (info != null && isWebmContent(info)) "video/webm" else "video/x-matroska"
            "webm" in p -> "video/webm"
            "avi" in p -> "video/x-msvideo"
            "mpegts" in p || "ts" in p -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    /** MIME type of a music file by its container. */
    fun mimeForAudio(container: String?): String = when (parts(container).firstOrNull()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/mp4"
    }

    /** File extension for a stream address ("mov,mp4,m4a,..." gives "mp4"). */
    fun videoExtension(info: MediaInfo?): String = when (mimeForVideo(info)) {
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/x-msvideo" -> "avi"
        "video/mp2t" -> "ts"
        else -> "mp4"
    }

    fun audioExtension(container: String?): String {
        val p = parts(container)
        return when {
            "mp3" in p -> "mp3"
            "flac" in p -> "flac"
            "ogg" in p -> "ogg"
            "wav" in p -> "wav"
            else -> "m4a"
        }
    }
}
