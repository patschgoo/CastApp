package io.github.patschgoo.castapp.tv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import io.github.patschgoo.castapp.tv.TrackSupport.Group
import io.github.patschgoo.castapp.tv.TrackSupport.Kind
import io.github.patschgoo.castapp.tv.TrackSupport.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** What CastApp TV does with tracks this box has no decoder for. */
class TrackSupportTest {
    private fun verdict(vararg groups: Group, kind: TvMediaKind = TvMediaKind.VIDEO, lowLatency: Boolean = false, joined: Boolean = false) =
        TrackSupport.verdict(groups.toList(), kind, lowLatency, joined)

    private val h264 = Group(Kind.VIDEO, "video/avc", supported = true)
    private val mpeg2 = Group(Kind.VIDEO, "video/mpeg2", supported = false)
    private val aac = Group(Kind.AUDIO, "audio/mp4a-latm", supported = true)
    private val dts = Group(Kind.AUDIO, "audio/vnd.dts", supported = false)
    private val trueHd = Group(Kind.AUDIO, "audio/true-hd", supported = false)
    private val srt = Group(Kind.OTHER, "application/x-subrip", supported = true)

    @Test
    fun everythingPlays() {
        assertEquals(Verdict.Fine, verdict(h264, aac, srt))
        assertEquals(Verdict.Fine, verdict(h264))
        assertEquals(Verdict.Fine, verdict())
    }

    @Test
    fun noPictureDecoderFailsLikeAPlayerError() {
        assertEquals(Verdict.CantPlay("MPEG-2"), verdict(mpeg2, aac))
        // Fails for the picture even when the sound has no decoder either.
        assertEquals(Verdict.CantPlay("MPEG-2"), verdict(mpeg2, dts))
    }

    @Test
    fun onePlayablePictureIsEnough() {
        assertEquals(Verdict.Fine, verdict(mpeg2, h264, aac))
    }

    @Test
    fun soundWithoutDecoderPlaysOnWithANotice() {
        assertEquals(Verdict.NoSound("DTS"), verdict(h264, dts))
        assertEquals(Verdict.NoSound("DTS"), verdict(h264, dts, trueHd))
        // Another sound track plays: ExoPlayer picks it, nothing to say.
        assertEquals(Verdict.Fine, verdict(h264, dts, aac))
    }

    @Test
    fun musicIsNeverFailedForAPicture() {
        assertEquals(Verdict.Fine, verdict(mpeg2, aac, kind = TvMediaKind.AUDIO))
        assertEquals(Verdict.NoSound("TrueHD"), verdict(trueHd, kind = TvMediaKind.AUDIO))
    }

    @Test
    fun mirroringAndJoinedStreamsAreLeftAlone() {
        assertEquals(Verdict.Fine, verdict(mpeg2, dts, lowLatency = true))
        assertEquals(Verdict.Fine, verdict(mpeg2, dts, joined = true))
        assertEquals(Verdict.Fine, verdict(mpeg2, kind = TvMediaKind.PHOTO))
    }

    @Test
    fun unknownCodecsStillGetAName() {
        assertEquals(Verdict.CantPlay("WMV3"), verdict(Group(Kind.VIDEO, "video/x-wmv3", false)))
        assertEquals(Verdict.CantPlay("?"), verdict(Group(Kind.VIDEO, null, false)))
    }

    @Test
    fun readsMedia3Tracks() {
        fun group(mime: String, support: Int) = Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType(mime).build()), false, intArrayOf(support), booleanArrayOf(false),
        )
        val tracks = Tracks(
            listOf(
                group(MimeTypes.VIDEO_MPEG2, C.FORMAT_UNSUPPORTED_SUBTYPE),
                group(MimeTypes.AUDIO_AAC, C.FORMAT_HANDLED),
                // More than the decoder claims (a high level, say): ExoPlayer tries it, so it counts as playable.
                group(MimeTypes.VIDEO_H265, C.FORMAT_EXCEEDS_CAPABILITIES),
                group(MimeTypes.APPLICATION_SUBRIP, C.FORMAT_HANDLED),
            ),
        )
        assertEquals(
            listOf(
                Group(Kind.VIDEO, "video/mpeg2", false),
                Group(Kind.AUDIO, "audio/mp4a-latm", true),
                Group(Kind.VIDEO, "video/hevc", true),
                Group(Kind.OTHER, "application/x-subrip", true),
            ),
            TrackSupport.groupsOf(tracks),
        )
        assertEquals(Verdict.Fine, TrackSupport.verdict(TrackSupport.groupsOf(tracks), TvMediaKind.VIDEO, lowLatency = false, joined = false))
    }
}
