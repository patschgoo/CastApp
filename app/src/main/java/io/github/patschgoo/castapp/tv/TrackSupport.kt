package io.github.patschgoo.castapp.tv

import androidx.media3.common.C
import androidx.media3.common.Tracks
import io.github.patschgoo.castapp.service.TrackNames

/**
 * What the TV player does when the file has a track this box can't decode: no picture decoder
 * is a failure (the phone then tries its next way, e.g. converting), missing sound only a notice.
 */
object TrackSupport {
    enum class Kind { VIDEO, AUDIO, OTHER }

    /** One track group of the media: its kind, codec MIME and whether any of its tracks has a decoder here. */
    data class Group(val kind: Kind, val mime: String?, val supported: Boolean)

    sealed class Verdict {
        data object Fine : Verdict()

        /** A video with no playable picture: fail like a player error. [codec]: "MPEG-2". */
        data class CantPlay(val codec: String) : Verdict()

        /** Plays without sound: [codec] ("DTS") has no decoder here. */
        data class NoSound(val codec: String) : Verdict()
    }

    /**
     * [lowLatency] (mirroring) and [joined] streams come from CastApp and always play; they're left alone.
     * Only [TvMediaKind.VIDEO] can fail for its picture (music may carry a cover picture).
     */
    fun verdict(groups: List<Group>, kind: TvMediaKind, lowLatency: Boolean, joined: Boolean): Verdict {
        if (lowLatency || joined || kind == TvMediaKind.PHOTO) return Verdict.Fine
        val video = groups.filter { it.kind == Kind.VIDEO }
        if (kind == TvMediaKind.VIDEO && video.isNotEmpty() && video.none { it.supported }) {
            return Verdict.CantPlay(codecName(video.first().mime))
        }
        val audio = groups.filter { it.kind == Kind.AUDIO }
        if (audio.isNotEmpty() && audio.none { it.supported }) return Verdict.NoSound(codecName(audio.first().mime))
        return Verdict.Fine
    }

    /**
     * A video whose tracks have sound but no picture group at all: Media3 dropped a picture stream it
     * has no reader for (DivX 3 in an AVI), so only the sound would play. Fails like [Verdict.CantPlay]
     * once it has stood a moment ([PictureWatch]), and the phone tries its next way (a media server's
     * HLS, which decodes it; the phone's converter, which says why it can't).
     *
     * Only for [TvMediaKind.VIDEO] (music and photos have no picture to miss), not for mirroring
     * ([lowLatency]) and [joined] streams (CastApp's own), and not for [live] streams: a radio channel
     * is sound only, and a converted stream always has its picture. No tracks yet is not "no picture".
     */
    fun soundWithoutPicture(groups: List<Group>, kind: TvMediaKind, lowLatency: Boolean, joined: Boolean, live: Boolean): Boolean =
        kind == TvMediaKind.VIDEO && !lowLatency && !joined && !live &&
            groups.any { it.kind == Kind.AUDIO } && groups.none { it.kind == Kind.VIDEO }

    /** How long sound without a picture has to stand, while the player is ready, before it counts. */
    const val SETTLE_MS = 2000L

    /**
     * Judges [soundWithoutPicture] only once it has stood for [settleMs] with the player ready: tracks
     * can come in steps (an HLS stream's sound rendition before its picture), and between two items the
     * tracks are empty for a moment. Anything else in between starts the wait again.
     */
    class PictureWatch(private val settleMs: Long = SETTLE_MS) {
        private var since = -1L
        private var given = false

        /** True once, when the picture is to be given up on (at [nowMs], a monotonic clock). */
        fun check(nowMs: Long, soundOnly: Boolean, ready: Boolean): Boolean {
            if (given) return false
            if (!soundOnly || !ready) {
                since = -1
                return false
            }
            if (since < 0) since = nowMs
            if (nowMs - since < settleMs) return false
            given = true
            return true
        }

        /** A new item is loaded. */
        fun reset() {
            since = -1
            given = false
        }
    }

    /**
     * The groups of Media3's [tracks]. A track that exceeds what the decoder claims still counts as
     * supported: ExoPlayer tries it and usually plays it (boxes under-report levels), and if it really
     * fails, that's a player error anyway.
     */
    fun groupsOf(tracks: Tracks): List<Group> = tracks.groups.map { g ->
        Group(
            kind = when (g.type) {
                C.TRACK_TYPE_VIDEO -> Kind.VIDEO
                C.TRACK_TYPE_AUDIO -> Kind.AUDIO
                else -> Kind.OTHER
            },
            mime = if (g.length > 0) g.getTrackFormat(0).sampleMimeType ?: g.getTrackFormat(0).codecs else null,
            supported = g.isSupported(true),
        )
    }

    fun codecName(mime: String?): String = mime?.let { TrackNames.codec(it) ?: it } ?: "?"
}
