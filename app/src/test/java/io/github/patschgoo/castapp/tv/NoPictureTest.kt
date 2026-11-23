package io.github.patschgoo.castapp.tv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import io.github.patschgoo.castapp.tv.TrackSupport.Group
import io.github.patschgoo.castapp.tv.TrackSupport.Kind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CastApp TV and a video that has sound but no picture group at all: Media3's AVI reader drops a DivX 3
 * stream, so only the sound would play. It fails like a picture without decoder (the phone moves on to
 * the server's HLS or its converter), but only once the tracks have settled.
 */
class NoPictureTest {
    private val mp3 = Group(Kind.AUDIO, "audio/mpeg", supported = true)
    private val xvid = Group(Kind.VIDEO, "video/mp4v-es", supported = true)
    private val mpeg2 = Group(Kind.VIDEO, "video/mpeg2", supported = false)
    private val srt = Group(Kind.OTHER, "application/x-subrip", supported = true)

    private fun soundOnly(
        vararg groups: Group,
        kind: TvMediaKind = TvMediaKind.VIDEO,
        lowLatency: Boolean = false,
        joined: Boolean = false,
        live: Boolean = false,
    ) = TrackSupport.soundWithoutPicture(groups.toList(), kind, lowLatency, joined, live)

    @Test
    fun aDivx3AviIsSoundWithoutPicture() {
        assertTrue(soundOnly(mp3))
        assertTrue(soundOnly(mp3, srt))
        // The verdict for a group without decoder stays the other check's (CantPlay with its codec).
        assertFalse(soundOnly(mpeg2, mp3))
        assertFalse(soundOnly(xvid, mp3))
    }

    @Test
    fun noTracksYetIsNotNoPicture() {
        // Tracks are empty between two items and before the player knows them.
        assertFalse(soundOnly())
        assertFalse(soundOnly(srt))
    }

    @Test
    fun onlyVideosFromAFileOrServerCanMissTheirPicture() {
        assertFalse(soundOnly(mp3, kind = TvMediaKind.AUDIO))
        assertFalse(soundOnly(mp3, kind = TvMediaKind.PHOTO))
        assertFalse(soundOnly(mp3, lowLatency = true))
        assertFalse(soundOnly(mp3, joined = true))
        // A radio channel among the TV channels is sound only; converted streams always have their picture.
        assertFalse(soundOnly(mp3, live = true))
    }

    @Test
    fun itCountsOnlyOnceItHasStoodAWhileWithThePlayerReady() {
        val watch = TrackSupport.PictureWatch(settleMs = 2000)
        assertFalse(watch.check(0, soundOnly = true, ready = false)) // still preparing
        assertFalse(watch.check(500, soundOnly = true, ready = true)) // ready: the wait starts
        assertFalse(watch.check(2000, soundOnly = true, ready = true))
        assertTrue(watch.check(2500, soundOnly = true, ready = true))
        // Said once.
        assertFalse(watch.check(3000, soundOnly = true, ready = true))
        // The next item starts afresh.
        watch.reset()
        assertFalse(watch.check(3500, soundOnly = true, ready = true))
        assertTrue(watch.check(5500, soundOnly = true, ready = true))
    }

    @Test
    fun aPictureArrivingLaterCancelsIt() {
        // HLS: the sound rendition can be known before the picture's.
        val watch = TrackSupport.PictureWatch(settleMs = 2000)
        assertFalse(watch.check(0, soundOnly = true, ready = true))
        assertFalse(watch.check(1500, soundOnly = false, ready = true)) // the picture is there
        assertFalse(watch.check(2500, soundOnly = false, ready = true))
        assertFalse(watch.check(10_000, soundOnly = false, ready = true))
    }

    @Test
    fun transientStatesStartTheWaitAgain() {
        val watch = TrackSupport.PictureWatch(settleMs = 2000)
        assertFalse(watch.check(0, soundOnly = true, ready = true))
        assertFalse(watch.check(1000, soundOnly = false, ready = true)) // empty tracks for a moment
        assertFalse(watch.check(1500, soundOnly = true, ready = true)) // waits from here
        assertFalse(watch.check(3000, soundOnly = true, ready = false)) // buffering
        assertFalse(watch.check(3200, soundOnly = true, ready = true))
        assertFalse(watch.check(5000, soundOnly = true, ready = true))
        assertTrue(watch.check(5200, soundOnly = true, ready = true))
    }

    @Test
    fun readsMedia3TracksWithoutAPicture() {
        fun group(mime: String) = Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType(mime).build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
        )
        val divx3 = Tracks(listOf(group(MimeTypes.AUDIO_MPEG)))
        assertTrue(TrackSupport.soundWithoutPicture(TrackSupport.groupsOf(divx3), TvMediaKind.VIDEO, false, false, false))
        // And nothing else is said about it: the sound plays.
        assertTrue(TrackSupport.verdict(TrackSupport.groupsOf(divx3), TvMediaKind.VIDEO, false, false) == TrackSupport.Verdict.Fine)
        val xvidAvi = Tracks(listOf(group(MimeTypes.VIDEO_MP4V), group(MimeTypes.AUDIO_MPEG)))
        assertFalse(TrackSupport.soundWithoutPicture(TrackSupport.groupsOf(xvidAvi), TvMediaKind.VIDEO, false, false, false))
        assertFalse(TrackSupport.soundWithoutPicture(TrackSupport.groupsOf(Tracks.EMPTY), TvMediaKind.VIDEO, false, false, false))
    }
}
