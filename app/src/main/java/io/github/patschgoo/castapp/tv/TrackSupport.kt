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
