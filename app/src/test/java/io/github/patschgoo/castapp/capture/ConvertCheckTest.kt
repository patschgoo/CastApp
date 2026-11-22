package io.github.patschgoo.castapp.capture

import io.github.patschgoo.castapp.capture.ConvertChecks.TrackFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Whether the phone can convert a file, decided from what MediaExtractor and the codec list say. */
class ConvertCheckTest {
    private val yes: (Int) -> Boolean = { true }
    private val no: (Int) -> Boolean = { false }

    @Test
    fun aFileThePhoneCanConvert() {
        val tracks = listOf(TrackFacts("video/hevc", 1_440_000_000), TrackFacts("audio/ac3", 1_440_500_000))
        val check = ConvertChecks.decide(tracks, 0, videoDecoder = yes, audioDecoder = yes)
        assertNull(check.problem)
        assertNull(check.droppedAudioMime)
        assertEquals(1_440_500L, check.durationMs)
    }

    @Test
    fun aviWithoutAnAviReaderOnThePhone() {
        // MediaExtractor.setDataSource failed, but the bytes are there: the file type is the problem.
        val check = ConvertChecks.decide(null, 0, readable = true, fileType = "AVI", videoDecoder = yes, audioDecoder = yes)
        assertEquals(ConvertProblem.UnreadableFileType("AVI"), check.problem)
        assertEquals("it can't read AVI files", check.problem!!.describe())
        assertEquals("it can't read this file type", ConvertProblem.UnreadableFileType(null).describe())
        // Not even the bytes: a network problem, not the file type.
        assertEquals(ConvertProblem.ReadFailed, ConvertChecks.decide(null, 0, readable = false, fileType = "AVI", videoDecoder = yes, audioDecoder = yes).problem)
    }

    @Test
    fun mpeg2RecordingWithoutAnMpeg2Decoder() {
        val tracks = listOf(TrackFacts("video/mpeg2"), TrackFacts("audio/mpeg-L2"), TrackFacts("audio/ac3"))
        val check = ConvertChecks.decide(tracks, 0, videoDecoder = { i -> i != 0 }, audioDecoder = yes)
        assertEquals(ConvertProblem.NoVideoDecoder("video/mpeg2", "MPEG-2"), check.problem)
        assertEquals("no decoder for MPEG-2 video", check.problem!!.describe())
    }

    @Test
    fun tenBitH264NamesTheProfile() {
        val tracks = listOf(TrackFacts("video/avc"), TrackFacts("audio/aac"))
        val check = ConvertChecks.decide(
            tracks, 0, videoDecoder = no, audioDecoder = yes,
            videoCodec = { i -> ConvertChecks.videoCodecName(tracks[i].mime, 0x10) },
        )
        assertEquals("no decoder for H.264 10-bit video", check.problem!!.describe())
        assertEquals("H.264", ConvertChecks.videoCodecName("video/avc", 0x8)) // High: nothing to add
        assertEquals("HEVC 10-bit", ConvertChecks.videoCodecName("video/hevc", 0x2))
    }

    @Test
    fun onlyDeepProfilesAreChecked() {
        assertEquals("10-bit", ConvertChecks.deepProfile("video/avc", 0x10))
        assertEquals("4:2:2", ConvertChecks.deepProfile("video/avc", 0x20))
        assertNull(ConvertChecks.deepProfile("video/avc", 0x8)) // High
        assertNull(ConvertChecks.deepProfile("video/avc", 0x10000)) // Constrained Baseline
        assertNull(ConvertChecks.deepProfile("video/avc", 0x80000)) // Constrained High
        assertEquals("10-bit", ConvertChecks.deepProfile("video/hevc", 0x1000)) // Main 10 HDR10
        assertNull(ConvertChecks.deepProfile("video/hevc", 0x1)) // Main
        assertEquals("10-bit", ConvertChecks.deepProfile("video/x-vnd.on2.vp9", 0x4)) // Profile 2
        assertNull(ConvertChecks.deepProfile("video/x-vnd.on2.vp9", 0x1))
        assertNull(ConvertChecks.deepProfile("video/mpeg2", 0x10))
    }

    @Test
    fun hdrIsAskedAsPlainTenBit() {
        assertEquals(0x10, ConvertChecks.profileToCheck("video/avc", 0x10))
        assertNull(ConvertChecks.profileToCheck("video/avc", 0x8)) // High: the codec alone is asked about
        assertEquals(0x2, ConvertChecks.profileToCheck("video/hevc", 0x1000)) // Main 10 HDR10 → Main 10
        assertEquals(0x2, ConvertChecks.profileToCheck("video/hevc", 0x2))
        assertNull(ConvertChecks.profileToCheck("video/hevc", 0x1))
        assertEquals(0x4, ConvertChecks.profileToCheck("video/x-vnd.on2.vp9", 0x1000)) // Profile 2 HDR → Profile 2
        assertEquals(0x8, ConvertChecks.profileToCheck("video/x-vnd.on2.vp9", 0x2000)) // Profile 3 HDR → Profile 3
        assertEquals(0x2, ConvertChecks.profileToCheck("video/av01", 0x2000))
    }

    @Test
    fun soundWithoutDecoderIsConvertedWithoutSound() {
        val tracks = listOf(TrackFacts("video/avc"), TrackFacts("audio/vnd.dts"), TrackFacts("audio/aac"))
        val first = ConvertChecks.decide(tracks, 0, videoDecoder = yes, audioDecoder = { i -> i != 1 })
        assertNull(first.problem)
        assertEquals("audio/vnd.dts", first.droppedAudioMime)
        // The second sound track (picked by hand) has a decoder.
        assertNull(ConvertChecks.decide(tracks, 1, videoDecoder = yes, audioDecoder = { i -> i != 1 }).droppedAudioMime)
        // No sound track at all: nothing dropped.
        assertNull(ConvertChecks.decide(listOf(TrackFacts("video/avc")), 0, videoDecoder = yes, audioDecoder = no).droppedAudioMime)
    }

    @Test
    fun noPictureTrack() {
        val check = ConvertChecks.decide(listOf(TrackFacts("audio/mpeg")), 0, videoDecoder = yes, audioDecoder = yes)
        assertEquals(ConvertProblem.NoVideoTrack, check.problem)
        assertEquals(ConvertProblem.NoVideoTrack, ConvertChecks.decide(emptyList(), 0, videoDecoder = yes, audioDecoder = yes).problem)
    }

    @Test
    fun picksTheTracksTheConverterUses() {
        val tracks = listOf(TrackFacts("audio/ac3"), TrackFacts("video/avc"), TrackFacts(null), TrackFacts("audio/aac"), TrackFacts("video/mjpeg"))
        assertEquals(1 to 0, ConvertChecks.pickTracks(tracks, 0))
        assertEquals(1 to 3, ConvertChecks.pickTracks(tracks, 1))
        // Fewer sound tracks than asked for: the first one.
        assertEquals(1 to 0, ConvertChecks.pickTracks(tracks, 5))
        assertEquals(-1 to -1, ConvertChecks.pickTracks(emptyList(), 0))
    }

    @Test
    fun fileTypeFromTheName() {
        assertEquals("AVI", ConvertChecks.fileType("smb://share/Anime/Drawn Together 01.avi"))
        assertEquals("TS", ConvertChecks.fileType("smb://share/TV/SpongeBob.ts"))
        assertEquals("DIVX", ConvertChecks.fileType("http://nas/x/Movie.DivX?token=1"))
        assertEquals("MKV", ConvertChecks.fileType("content://com.android.externalstorage.documents/document/primary%3AMovies%2Ffilm.mkv"))
        assertNull(ConvertChecks.fileType("content://media/external/video/media/42"))
        assertNull(ConvertChecks.fileType("Inuyasha 01"))
    }

    @Test
    fun readableCodecNames() {
        assertEquals("MPEG-4 (XviD/DivX)", ConvertChecks.codecName("video/mp4v-es"))
        assertEquals("DivX 3", ConvertChecks.codecName("video/divx3"))
        assertEquals("DTS", ConvertChecks.codecName("audio/vnd.dts"))
        assertEquals("MP2", ConvertChecks.codecName("audio/mpeg-L2"))
    }
}
