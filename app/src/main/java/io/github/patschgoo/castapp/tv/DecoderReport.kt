package io.github.patschgoo.castapp.tv

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import io.github.patschgoo.castapp.capture.VideoCodecs

/**
 * What CastApp TV can play, for `GET /castapp/capabilities` (phones and other sender apps ask before
 * they send a file). Pure rules in [report]; [scan] and [passthrough] read the device.
 *
 * Video, for each format `f`: `f` = a hardware decoder that does 1080p (plays smoothly), `f_sw` = a decoder,
 * but no such hardware one (software, or hardware below 1080p: fine for SD, may stutter or fail in HD). Audio: `f` = a decoder, or passed through to the
 * AV receiver over HDMI (`f_passthrough`). Subtitles: what the TV's player shows.
 */
object DecoderReport {
    /** One decoder for one format, as Android lists it. */
    data class Decoder(
        val mime: String,
        val hardware: Boolean,
        /** MediaCodecInfo.CodecProfileLevel profiles it lists. */
        val profiles: Set<Int> = emptySet(),
        /** It says it handles 1920 x 1080 (video only). */
        val fullHd: Boolean = true,
    )

    private class Video(val key: String, val mime: String, val profile: Int? = null)

    // The profile numbers are Android's MediaCodecInfo.CodecProfileLevel constants.
    private const val AVC_HIGH10 = 0x10
    private const val HEVC_MAIN10 = 0x2
    private const val HEVC_MAIN10_HDR10 = 0x1000
    private const val HEVC_MAIN10_HDR10_PLUS = 0x2000

    private val videos = listOf(
        Video("h264", "video/avc"),
        Video("h264_10bit", "video/avc", AVC_HIGH10),
        Video("hevc_10bit", "video/hevc", HEVC_MAIN10),
        Video("av1", "video/av01"),
        Video("vp9", "video/x-vnd.on2.vp9"),
        Video("vp8", "video/x-vnd.on2.vp8"),
        Video("mpeg2", "video/mpeg2"),
        Video("mpeg4", "video/mp4v-es"),
    )

    private val audios = mapOf(
        "aac" to "audio/mp4a-latm",
        "mp3" to "audio/mpeg",
        "ac3" to "audio/ac3",
        "eac3" to "audio/eac3",
        "dts" to "audio/vnd.dts",
        "truehd" to "audio/true-hd",
        "flac" to "audio/flac",
        "opus" to "audio/opus",
        "vorbis" to "audio/vorbis",
    )

    /** The keys that can be passed through to an AV receiver. */
    val PASSTHROUGH_KEYS = listOf("ac3", "eac3", "dts", "truehd")

    /**
     * The report. [passthrough] holds the [PASSTHROUGH_KEYS] the HDMI output takes as they are.
     * `hevc` stays out: CastApp TV has always answered it itself (a hardware decoder for 1080p).
     */
    fun report(decoders: List<Decoder>, passthrough: Set<String>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (v in videos) {
            val fits = decoders.filter { d ->
                d.mime.equals(v.mime, ignoreCase = true) && (v.profile == null || v.profile in withAliases(d.profiles))
            }
            val hardware = fits.any { it.hardware && it.fullHd }
            out[v.key] = hardware
            out[v.key + "_sw"] = !hardware && fits.isNotEmpty()
        }
        out["hi10p"] = out.getValue("h264_10bit")
        for ((key, mime) in audios) {
            val decoder = decoders.any { it.mime.equals(mime, ignoreCase = true) }
            out[key] = decoder || key in passthrough
            if (key in PASSTHROUGH_KEYS) out[key + "_passthrough"] = key in passthrough
        }
        // Media3 shows these; ASS keeps position, colours, bold and italic, but no effects, karaoke or fonts.
        out["sub_srt"] = true
        out["sub_vtt"] = true
        out["sub_ass"] = true
        out["sub_ass_full"] = false
        out["sub_pgs"] = true
        out["sub_vobsub"] = true
        return out
    }

    /** HDR10 and HDR10+ decoders are Main 10 decoders too. */
    private fun withAliases(profiles: Set<Int>): Set<Int> =
        if (HEVC_MAIN10_HDR10 in profiles || HEVC_MAIN10_HDR10_PLUS in profiles) profiles + HEVC_MAIN10 else profiles

    /** Every decoder on this device, one entry per format. */
    fun scan(): List<Decoder> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && !isAlias(it) }.flatMap { info ->
            info.supportedTypes.mapNotNull { mime ->
                try {
                    val caps = info.getCapabilitiesForType(mime)
                    Decoder(
                        mime = mime.lowercase(),
                        hardware = hardware(info),
                        profiles = caps.profileLevels.orEmpty().map { it.profile }.toSet(),
                        fullHd = caps.videoCapabilities?.isSizeSupported(1920, 1080) != false,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** What the HDMI output passes through now (it follows the AV receiver or TV that is plugged in). */
    @OptIn(UnstableApi::class)
    fun passthrough(context: Context): Set<String> = try {
        val caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
        buildSet {
            if (caps.supportsEncoding(C.ENCODING_AC3)) add("ac3")
            if (caps.supportsEncoding(C.ENCODING_E_AC3) || caps.supportsEncoding(C.ENCODING_E_AC3_JOC)) add("eac3")
            if (caps.supportsEncoding(C.ENCODING_DTS) || caps.supportsEncoding(C.ENCODING_DTS_HD)) add("dts")
            if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) add("truehd")
        }
    } catch (_: Exception) {
        emptySet()
    }

    private fun isAlias(info: MediaCodecInfo) = Build.VERSION.SDK_INT >= 29 && info.isAlias

    private fun hardware(info: MediaCodecInfo) =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else VideoCodecs.isHardwareName(info.name)
}
