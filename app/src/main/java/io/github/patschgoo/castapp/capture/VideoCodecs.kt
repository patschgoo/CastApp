package io.github.patschgoo.castapp.capture

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import io.github.patschgoo.castapp.settings.CodecPreference
import io.github.patschgoo.castapp.stream.VideoCodec

/** What this device's hardware video codecs can do. */
object VideoCodecs {
    private const val TAG = "VideoCodecs"

    @Volatile private var hevcDecoderCache: Boolean? = null

    /**
     * Name of a hardware encoder for [mime] that can encode [width] x [height], or
     * null. Software encoders are skipped: they can't keep up with mirroring.
     */
    fun hardwareEncoder(mime: String, width: Int, height: Int): String? = find(mime, encoder = true) { caps ->
        caps.videoCapabilities?.isSizeSupported(width, height) != false
    }

    /** True if the phone has a hardware HEVC encoder (for 720p at least). */
    fun canEncodeHevc(width: Int = 1280, height: Int = 720): Boolean =
        hardwareEncoder(VideoCodec.HEVC.mime, width, height) != null

    /** True if this device (a TV) can decode HEVC in hardware; used by the TV receiver. */
    fun hasHevcDecoder(): Boolean {
        hevcDecoderCache?.let { return it }
        val found = find(VideoCodec.HEVC.mime, encoder = false) { caps ->
            caps.videoCapabilities?.isSizeSupported(1920, 1080) != false
        } != null
        hevcDecoderCache = found
        return found
    }

    private fun find(mime: String, encoder: Boolean, accept: (MediaCodecInfo.CodecCapabilities) -> Boolean): String? = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder == encoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                isHardware(info) &&
                try { accept(info.getCapabilitiesForType(mime)) } catch (_: Exception) { false }
        }?.name
    } catch (e: Exception) {
        Log.w(TAG, "Codec list unavailable", e)
        null
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isAlias
        return isHardwareName(info.name)
    }

    /** Before Android 10 only the name tells software codecs apart. */
    fun isHardwareName(name: String): Boolean {
        val n = name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") ||
            n.contains(".sw.") || n.endsWith(".sw"))
    }
}

/**
 * Picks the mirroring codec. HEVC needs roughly a third less bitrate for the same
 * picture, but only some TVs decode it, so "Automatic" uses it only where it is
 * known to work.
 */
object CodecChooser {
    /** What we know about the TV when the session starts. */
    enum class Target {
        /** Google Cast device (Chromecast, Google TV, Android TV with Cast). */
        CAST,
        /** CastApp's own receiver on a Fire TV / Android TV. */
        CASTAPP_TV,
        /** DLNA smart TVs get an MPEG-TS stream, which we only make in H.264. */
        DLNA,
        /** AirPlay and Roku: HLS. */
        HLS_PLAYER,
    }

    fun choose(
        preference: CodecPreference,
        target: Target,
        phoneEncodesHevc: Boolean,
        /** CastApp TV: what its capabilities endpoint said (null = unknown, e.g. an older version). */
        tvDecodesHevc: Boolean?,
        /** Google Cast model name from discovery. */
        castModel: String?,
        /** HEVC was tried on this TV before and it didn't play. */
        failedBefore: Boolean,
        /** "Compatible (HLS)" stream mode is chosen for Google Cast. */
        compatibleMode: Boolean,
    ): VideoCodec {
        if (!phoneEncodesHevc || target == Target.DLNA) return VideoCodec.H264
        return when (preference) {
            CodecPreference.H264 -> VideoCodec.H264
            // Forced, except where the TV itself says it can't.
            CodecPreference.HEVC -> if (tvDecodesHevc == false) VideoCodec.H264 else VideoCodec.HEVC
            CodecPreference.AUTO -> when {
                failedBefore -> VideoCodec.H264
                target == Target.CASTAPP_TV && tvDecodesHevc == true -> VideoCodec.HEVC
                target == Target.CAST && !compatibleMode && castModelDecodesHevc(castModel) -> VideoCodec.HEVC
                else -> VideoCodec.H264
            }
        }
    }

    /**
     * Google Cast devices whose model name says they decode HEVC (Google's
     * "supported media" list). Plain "Chromecast" is left out on purpose: the
     * 1st–3rd generation (no HEVC) and the Chromecast with Google TV 4K can
     * announce the same name. Those can still use HEVC with the codec set to HEVC.
     */
    fun castModelDecodesHevc(model: String?): Boolean {
        val m = model?.trim()?.lowercase() ?: return false
        return HEVC_CAST_MODELS.any { m.contains(it) }
    }

    private val HEVC_CAST_MODELS = listOf(
        "chromecast ultra",
        "chromecast hd", // Chromecast with Google TV (HD)
        "google tv streamer",
        "shield", // NVIDIA SHIELD Android TV
    )
}

/** Remembers TVs where HEVC didn't play, so "Automatic" goes straight to H.264 next time. */
object HevcMemory {
    private const val PREFS = "hevc_memory"
    private const val KEY_FAILED = "failed"

    fun failedBefore(context: Context, deviceId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FAILED, emptySet())?.contains(deviceId) == true

    fun markFailed(context: Context, deviceId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FAILED, emptySet()).orEmpty().toMutableSet()
        if (set.add(deviceId)) prefs.edit { putStringSet(KEY_FAILED, set.toList().takeLast(50).toSet()) }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_FAILED) }
    }
}
