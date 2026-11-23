package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.capture.ConvertProblem
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.settings.ConvertMode
import io.github.patschgoo.castapp.stream.MediaServer

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
     * The sound track a CastApp TV is told to play for [item] sent as [d] ([MediaItem.audioTrack]): only
     * when it plays the very file the hint describes, not the phone's converted video and not a server's
     * converted stream (its [MediaItem.original] is that file). Other TVs are never told.
     */
    fun audioTrackFor(item: MediaItem, d: Delivery, target: CastDevice): String? =
        item.audioTrack?.takeIf { target.isCastAppTv && d != Delivery.CONVERT && (item.original == null || d == Delivery.ORIGINAL) }

    /**
     * The subtitle track an app asked for ([MediaItem.subtitleTrack]) that a CastApp TV is told to show
     * inside the file: only when the TV plays the file itself, gets no subtitle file ([sendsFile]) and
     * nothing was picked on the phone ([picked]). Other TVs are never told.
     */
    fun subtitleTrackFor(item: MediaItem, d: Delivery, target: CastDevice, sendsFile: Boolean, picked: Boolean): String? =
        item.subtitleTrack?.takeIf { target.isCastAppTv && d != Delivery.CONVERT && !sendsFile && !picked && (item.original == null || d == Delivery.ORIGINAL) }

    /**
     * The type a TV is told for a file of type [mime]. Chromecasts take MKV only as "video/webm";
     * DLNA TVs (Samsung, LG), Roku and the rest know "video/x-matroska" and may refuse "webm".
     */
    fun mimeFor(mime: String, kind: DeviceKind): String =
        if (kind != DeviceKind.CAST && (mime == "video/x-matroska" || mime == "video/mkv")) MATROSKA else CastService.normalizeMime(mime)

    private const val MATROSKA = "video/x-matroska"

    /** The path part that marks a file address given out as [MATROSKA]: file/<id>/m/<name>. */
    private const val MATROSKA_PART = "m"

    /**
     * Where the phone serves the file of queue entry [id] ([title]: its name) to a TV that was told it's
     * of type [told] ([mimeFor]). TVs told "video/x-matroska" get "m/" in the address, so the file/ route
     * answers them with that type while a Chromecast fetches the same file as "video/webm".
     */
    fun filePath(token: String, id: Long, title: String, told: String): String =
        "/$token/file/$id/" + (if (told == MATROSKA) "$MATROSKA_PART/" else "") + MediaServer.safeName(title)

    /**
     * The type the file/ route answers for [path] ([filePath]) of a file of type [mime]: what the TV was
     * told. Addresses without the mark (also those handed out before it) get [CastService.normalizeMime].
     */
    fun servedMime(path: String, mime: String): String {
        val parts = path.substringAfter("/file/").split('/')
        return if (parts.size >= 3 && parts[1] == MATROSKA_PART) MATROSKA else CastService.normalizeMime(mime)
    }

    /**
     * Why the file plays as it is ([played]) though the phone was to convert it: the conversion it
     * refused ([refused]), worth a word only when converting was asked for by hand ([picked]: a quality
     * or sound track). Null when there's nothing to say.
     */
    fun playedAsIsNote(refused: ConvertProblem?, played: Delivery, picked: Boolean): ConvertProblem? =
        refused?.takeIf { picked && played != Delivery.CONVERT }

    /**
     * Where a conversion from [startMs] begins. A picture copied as it is ([copies]) can only begin at a
     * key frame: the one at or before [startMs] ([keyFrameMs], null if it couldn't be told), so the TV's
     * clock and the subtitles begin there too. One implausibly far back (a file without a usable index)
     * is left alone.
     */
    fun convertStartMs(startMs: Long, copies: Boolean, keyFrameMs: Long?): Long =
        if (copies && keyFrameMs != null && keyFrameMs in (startMs - MAX_KEY_FRAME_BACK_MS)..startMs) keyFrameMs else startMs

    private const val MAX_KEY_FRAME_BACK_MS = 60_000L

    /**
     * A conversion that copied the picture as it is ([pictureCopied]) didn't play or broke off on the TV:
     * convert again, the picture too. Once per file ([refusedBefore]): then it's like any conversion.
     */
    fun retryWithoutCopy(d: Delivery, pictureCopied: Boolean, refusedBefore: Boolean): Boolean =
        d == Delivery.CONVERT && pictureCopied && !refusedBefore

    /** Sound Chromecasts (Default Media Receiver) can't play: DTS in all its forms and Dolby TrueHD. */
    fun castCantPlaySound(mime: String): Boolean = mime.lowercase().let {
        it.startsWith("audio/vnd.dts") || it == "audio/dts" || it == "audio/dts-hd" || it == "audio/true-hd" || it == "audio/truehd"
    }

    /**
     * A Chromecast playing the file as it is ([Delivery.SERVE], [Delivery.DIRECT]) takes its first sound
     * track: when that's DTS or TrueHD the video plays without sound. Null when there's nothing to say.
     * Picking another sound track converts the file on the phone, so it's only offered for a track
     * [canPlayInstead] says the phone can convert with its sound (asked only when it comes to that).
     */
    fun castSoundWarning(
        kind: DeviceKind,
        delivery: Delivery,
        audio: List<AudioTrackInfo>,
        canPlayInstead: (AudioTrackInfo) -> Boolean,
    ): CastSoundWarning? {
        if (kind != DeviceKind.CAST || (delivery != Delivery.SERVE && delivery != Delivery.DIRECT)) return null
        val playing = audio.firstOrNull() ?: return null
        if (!castCantPlaySound(playing.mimeType)) return null
        return CastSoundWarning(
            codec = TrackNames.codec(playing.mimeType) ?: playing.mimeType,
            otherTrack = audio.any { it.ordinal != playing.ordinal && !castCantPlaySound(it.mimeType) && canPlayInstead(it) },
        )
    }
}
