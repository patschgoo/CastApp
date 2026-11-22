package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.settings.ConvertMode

/**
 * How [MediaCastSession] gets an item to a TV.
 * [ORIGINAL]: a media server's file as it is ([MediaItem.original]).
 */
internal enum class Delivery { ORIGINAL, DIRECT, PROXY, SERVE, CONVERT }

/** A sound track the TV can't play is the one it would play. */
internal data class CastSoundWarning(
    /** "DTS", "TrueHD" */
    val codec: String,
    /** Another sound track of the file would play (converted on the phone). */
    val otherTrack: Boolean,
)

/** The decisions of [MediaCastSession] that need no TV: which ways to try, what to call the file. */
internal object PlaybackChecks {
    /**
     * The ways to deliver [item] to [target], in the order to try them.
     * [picked]: a quality or sound track was picked by hand, which means converting.
     */
    fun plan(item: MediaItem, target: CastDevice, convert: ConvertMode, picked: Boolean): List<Delivery> {
        // The TV can't log in to a password-protected server or accept a self-signed certificate.
        val needsPhone = item.trustAll || item.headers.keys.any { it.equals("Authorization", ignoreCase = true) }
        val direct = if (needsPhone) emptyList() else listOf(Delivery.DIRECT)
        val serverFile = item.original != null && !needsPhone && item.type == MediaType.VIDEO
        // A CastApp TV plays the server's file itself; the server's converted stream is the fallback.
        val first = if (serverFile && target.isCastAppTv) listOf(Delivery.ORIGINAL) else emptyList()
        // Other DLNA TVs (Samsung, LG) often take no HLS: the server's file is their last try.
        val last = if (serverFile && target.kind == DeviceKind.DLNA && !target.isCastAppTv) listOf(Delivery.ORIGINAL) else emptyList()
        val base = first + when {
            item.type == MediaType.PHOTO -> if (item.isWeb) direct + Delivery.PROXY else listOf(Delivery.SERVE)
            item.isHls -> listOf(Delivery.PROXY) + direct
            item.isWeb && item.type == MediaType.AUDIO -> direct + Delivery.PROXY
            item.isWeb -> when (convert) {
                ConvertMode.ALWAYS -> listOf(Delivery.CONVERT)
                ConvertMode.OFF -> direct + Delivery.PROXY
                ConvertMode.AUTO -> direct + Delivery.PROXY + Delivery.CONVERT
            }
            item.type == MediaType.AUDIO -> listOf(Delivery.SERVE)
            else -> when (convert) {
                ConvertMode.ALWAYS -> listOf(Delivery.CONVERT)
                ConvertMode.OFF -> listOf(Delivery.SERVE)
                ConvertMode.AUTO -> listOf(Delivery.SERVE, Delivery.CONVERT)
            }
        } + last
        // A quality or sound track picked by hand: convert to it (the original is the fallback).
        return if (picked && item.type == MediaType.VIDEO && !item.isHls) {
            listOf(Delivery.CONVERT) + base.filter { it != Delivery.CONVERT }
        } else {
            base
        }
    }

    /**
     * What an added screen tries: the way the first screen got the item ([used]), then the screen's own
     * ways ([targetPlan]) that don't need a second conversion. The server's original file goes first
     * only to CastApp receivers; other TVs try it where their own plan has it.
     */
    fun extraScreenOptions(used: Delivery, targetPlan: List<Delivery>, target: CastDevice): List<Delivery> {
        val usedFirst = if (used == Delivery.ORIGINAL && !target.isCastAppTv) emptyList() else listOf(used)
        return (usedFirst + targetPlan.filter { it != Delivery.CONVERT }).distinct()
    }

    /**
     * The type a TV is told for a file of type [mime]. Chromecasts take MKV only as "video/webm";
     * DLNA TVs (Samsung, LG), Roku and the rest know "video/x-matroska" and may refuse "webm".
     */
    fun mimeFor(mime: String, kind: DeviceKind): String =
        if (kind != DeviceKind.CAST && (mime == "video/x-matroska" || mime == "video/mkv")) "video/x-matroska" else CastService.normalizeMime(mime)

    /** Sound Chromecasts (Default Media Receiver) can't play: DTS in all its forms and Dolby TrueHD. */
    fun castCantPlaySound(mime: String): Boolean = mime.lowercase().let {
        it.startsWith("audio/vnd.dts") || it == "audio/dts" || it == "audio/dts-hd" || it == "audio/true-hd" || it == "audio/truehd"
    }

    /**
     * A Chromecast playing the file as it is ([Delivery.SERVE], [Delivery.DIRECT]) takes its first sound
     * track: when that's DTS or TrueHD the video plays without sound. Null when there's nothing to say.
     */
    fun castSoundWarning(kind: DeviceKind, delivery: Delivery, audio: List<AudioTrackInfo>): CastSoundWarning? {
        if (kind != DeviceKind.CAST || (delivery != Delivery.SERVE && delivery != Delivery.DIRECT)) return null
        val playing = audio.firstOrNull() ?: return null
        if (!castCantPlaySound(playing.mimeType)) return null
        return CastSoundWarning(
            codec = TrackNames.codec(playing.mimeType) ?: playing.mimeType,
            otherTrack = audio.any { it.ordinal != playing.ordinal && !castCantPlaySound(it.mimeType) },
        )
    }
}
