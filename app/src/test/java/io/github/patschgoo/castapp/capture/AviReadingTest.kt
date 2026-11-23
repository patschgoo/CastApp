package io.github.patschgoo.castapp.capture

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.MpegAudioUtil
import io.github.patschgoo.castapp.stream.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * AVI files on phones without an AVI reader: Media3's AVI reader under the converter, which codecs
 * are converted and which are refused with a reason. Samples made with FFmpeg (src/test/resources/media).
 */
@OptIn(UnstableApi::class)
class AviReadingTest {
    /** Media3 asks android.os.Build whether it runs under a test, which is empty on the JVM: tell it. */
    @Before
    fun strictParsing() {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        // Its warnings ask android.text.TextUtils about the message, which is empty here.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
    }

    /** A file in memory, opened from any position like a network share; counts the opens. */
    private class Bytes(private val data: ByteArray) : MediaSource {
        var opens = 0
        override val length: Long get() = data.size.toLong()
        override fun open(offset: Long): InputStream {
            opens++
            return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        }
    }

    private fun media(name: String) = Bytes(javaClass.getResourceAsStream("/media/$name")!!.readBytes())

    private fun Media3Samples.all(): List<Media3Samples.Sample> {
        val out = ArrayList<Media3Samples.Sample>()
        var s = current()
        while (s != null) {
            out += s
            advance()
            s = current()
        }
        return out
    }

    @Test
    fun theStreamListOfAnAvi() {
        val xvid = AviHeader.read(media("xvid_bframes_mp3.avi"))
        assertEquals(listOf("vids", "auds"), xvid.map { it.type })
        assertEquals("XVID", AviHeader.videoCodec(xvid))
        assertEquals("0x0055", xvid[1].codec) // MP3
        assertEquals("DIV3", AviHeader.videoCodec(AviHeader.read(media("divx3_mp3.avi"))))
        assertEquals("MP43", AviHeader.videoCodec(AviHeader.read(media("mp43_ac3.avi"))))
        // Not an AVI at all.
        assertTrue(AviHeader.read(media("h264_aac.ts")).isEmpty())
        assertNull(AviHeader.videoCodec(emptyList()))
    }

    @Test
    fun codecNamesByFourCc() {
        for (f in listOf("XVID", "xvid", "DIVX", "divx", "DX50", "FMP4", "MP4V")) {
            assertEquals(f, AviCodecs.Codec("video/mp4v-es", "MPEG-4 (XviD/DivX)"), AviCodecs.video(f))
        }
        for (f in listOf("DIV3", "div3", "DIV4", "MP43", "mp43")) {
            val c = AviCodecs.video(f)
            assertEquals(f, "DivX 3", c.name)
            assertTrue(f, c.noDecoder)
        }
        assertEquals("MS-MPEG4 v2", AviCodecs.video("MP42").name)
        assertEquals("video/avc", AviCodecs.video("H264").mime)
        assertEquals(AviCodecs.Codec(null, "RV40"), AviCodecs.video("RV40"))
        // Media3 hands MS-MPEG4 out as video/mp42 / video/mp43: no Android decoder, never asked for one.
        assertEquals("DivX 3", AviCodecs.msMpeg4Name(MimeTypes.VIDEO_MP43))
        assertEquals("MS-MPEG4 v2", AviCodecs.msMpeg4Name(MimeTypes.VIDEO_MP42))
        assertNull(AviCodecs.msMpeg4Name(MimeTypes.VIDEO_MP4V))
        assertNull(AviCodecs.msMpeg4Name(null))
    }

    @Test
    fun media3OnlyWhereMediaExtractorCantOpenAnAvi() {
        val avi = media("xvid_bframes_mp3.avi").open(0).readNBytes(12)
        val ts = media("h264_aac.ts").open(0).readNBytes(12)
        assertTrue(AviHeader.isAvi(avi))
        assertFalse(AviHeader.isAvi(ts))
        // A phone whose MediaExtractor reads AVI (Samsung...) keeps it, vendor decoders and all.
        assertEquals(ReaderKind.ANDROID, ReaderChoice.choose(extractorOpened = true, head = avi))
        assertEquals(ReaderKind.ANDROID, ReaderChoice.choose(extractorOpened = true, head = null))
        // A Pixel: Media3 for the AVI ...
        assertEquals(ReaderKind.MEDIA3_AVI, ReaderChoice.choose(extractorOpened = false, head = avi))
        // ... and still nothing for what neither reads (the reason comes from ConvertChecks.openFailed).
        assertNull(ReaderChoice.choose(extractorOpened = false, head = ts))
        assertNull(ReaderChoice.choose(extractorOpened = false, head = null))
        assertNull(ReaderChoice.choose(extractorOpened = false, head = byteArrayOf(0x52, 0x49)))
        assertEquals(12, ReaderChoice.head(media("divx3_mp3.avi"))!!.size)
    }

    @Test
    fun anXvidAviReadsLikeMediaExtractorWould() {
        val samples = Media3Samples.avi(media("xvid_bframes_mp3.avi"))
        try {
            assertEquals(2, samples.tracks.size)
            val video = samples.tracks[0]
            val audio = samples.tracks[1]
            assertEquals(C.TRACK_TYPE_VIDEO, video.type)
            assertEquals(MimeTypes.VIDEO_MP4V, video.mime)
            assertEquals(96, video.format!!.width)
            assertEquals(MimeTypes.AUDIO_MPEG, audio.mime)
            assertEquals(44100, audio.format!!.sampleRate)
            assertTrue(samples.durationUs.toString(), samples.durationUs in 2_900_000L..3_200_000L)
            // The decoder's set-up (VOL header) from the first frame, for conversions that start later.
            val config = Mpeg4Video.config(samples.firstVideoSample!!)
            assertNotNull(config)
            assertTrue(config!!.size in 10..200)

            samples.select(0)
            samples.seekTo(0)
            val frames = samples.all()
            assertEquals(30, frames.size)
            assertTrue(frames.first().isKey)
            assertEquals(0L, frames.first().timeUs)
            // AVI: one frame per 100 ms in decoding order (B-frames come later in time than shown).
            frames.zipWithNext().forEach { (a, b) -> assertEquals(100_000L, b.timeUs - a.timeUs) }
            assertTrue(frames.any { Mpeg4Video.vopType(it.data) == 2 }) // B-frames
            // A key frame about every second (FFmpeg's open GOPs: at 0, 1.0, 1.9 and 2.8 s).
            assertEquals(listOf(0L, 1_000_000L, 1_900_000L, 2_800_000L), frames.filter { it.isKey }.map { it.timeUs })
        } finally {
            samples.close()
        }
    }

    @Test
    fun theSetUpIsTheHeadersBeforeTheFirstPicture() {
        val vos = byteArrayOf(0, 0, 1, 0xB0.toByte(), 0xF5.toByte())
        val vol = byteArrayOf(0, 0, 1, 0x20, 0x08, 0xC8.toByte())
        val vop = byteArrayOf(0, 0, 1, 0xB6.toByte(), 0x10, 0x60)
        assertEquals((vos + vol).toList(), Mpeg4Video.config(vos + vol + vop)!!.toList())
        // A key frame without VOL (later in the file): nothing to take.
        assertNull(Mpeg4Video.config(vop))
        assertNull(Mpeg4Video.config(vos + vop))
        assertEquals(0, Mpeg4Video.vopType(vop))
        assertEquals(2, Mpeg4Video.vopType(byteArrayOf(0, 0, 1, 0xB6.toByte(), 0x90.toByte())))
        assertEquals(-1, Mpeg4Video.vopType(vos))
    }

    @Test
    fun openGopBFramesAfterTheStartAreLeftOut() {
        val samples = Media3Samples.avi(media("xvid_bframes_mp3.avi"))
        try {
            samples.select(0)
            samples.seekTo(1_550_000)
            val leading = Mpeg4Video.LeadingPictures()
            val kept = ArrayList<Long>()
            var s = samples.current()
            while (s != null && s.timeUs < 1_900_000) {
                if (!leading.skip(s.data.copyOf(minOf(64, s.data.size)), s.isKey)) kept += s.timeUs
                samples.advance()
                s = samples.current()
            }
            // The key frame at 1.0 s, then its two B-frames (shown before it, made from the frame before) go.
            assertEquals(listOf(1_000_000L, 1_300_000, 1_400_000, 1_500_000, 1_600_000, 1_700_000, 1_800_000), kept)
        } finally {
            samples.close()
        }
    }

    @Test
    fun seekingGoesToTheKeyFrameBefore() {
        val source = media("xvid_bframes_mp3.avi")
        val samples = Media3Samples.avi(source)
        try {
            samples.select(0)
            samples.seekTo(1_550_000)
            val first = samples.current()!!
            assertTrue(first.isKey)
            assertEquals(1_000_000L, first.timeUs)
            val opensBefore = source.opens
            samples.seekTo(2_200_000)
            assertEquals(1_900_000L, samples.current()!!.timeUs)
            assertTrue(samples.current()!!.isKey)
            assertTrue(source.opens > opensBefore) // read again from the key frame's place
            // Back to the start.
            samples.seekTo(0)
            assertEquals(0L, samples.current()!!.timeUs)
        } finally {
            samples.close()
        }
    }

    @Test
    fun mp3SoundComesInWholeFramesTimedBySampleCount() {
        val samples = Media3Samples.avi(media("xvid_bframes_mp3.avi"))
        try {
            samples.select(1)
            samples.seekTo(0)
            val frames = samples.all()
            assertTrue(frames.size > 100)
            val header = MpegAudioUtil.Header()
            for (f in frames) {
                val h = ((f.data[0].toInt() and 0xFF) shl 24) or ((f.data[1].toInt() and 0xFF) shl 16) or
                    ((f.data[2].toInt() and 0xFF) shl 8) or (f.data[3].toInt() and 0xFF)
                assertTrue(header.setForHeaderData(h))
                assertEquals(header.frameSize, f.data.size)
            }
            // 1152 samples at 44.1 kHz each.
            frames.zipWithNext().forEach { (a, b) -> assertTrue(b.timeUs - a.timeUs in 26_100L..26_150L) }
        } finally {
            samples.close()
        }
    }

    @Test
    fun ac3SoundToo() {
        val samples = Media3Samples.avi(media("mp43_ac3.avi"))
        try {
            assertEquals(MimeTypes.VIDEO_MP43, samples.tracks[0].mime)
            assertEquals(MimeTypes.AUDIO_AC3, samples.tracks[1].mime)
            samples.select(1)
            samples.seekTo(0)
            val frames = samples.all()
            assertTrue(frames.isNotEmpty())
            assertTrue(frames.all { it.data[0] == 0x0B.toByte() && it.data[1] == 0x77.toByte() })
            frames.zipWithNext().forEach { (a, b) -> assertEquals(32_000L, b.timeUs - a.timeUs) } // 1536 at 48 kHz
        } finally {
            samples.close()
        }
    }

    @Test
    fun framesCutAcrossChunksAreJoined() {
        // Two MP3 frames from the test file, handed in as three chunks cut in the middle.
        val samples = Media3Samples.avi(media("xvid_bframes_mp3.avi"))
        val frames = try {
            samples.select(1)
            samples.seekTo(0)
            samples.all().take(2).map { it.data }
        } finally {
            samples.close()
        }
        val joined = frames[0] + frames[1]
        val framer = AudioFramer.forFormat(androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).setSampleRate(44100).build())!!
        val out = ArrayList<Pair<Int, Long>>()
        val cut1 = frames[0].size / 2
        val cut2 = frames[0].size + 3
        framer.add(joined.copyOfRange(0, cut1), 500_000) { d, t -> out += d.size to t }
        framer.add(joined.copyOfRange(cut1, cut2), 510_000) { d, t -> out += d.size to t }
        framer.add(joined.copyOfRange(cut2, joined.size), 520_000) { d, t -> out += d.size to t }
        assertEquals(listOf(frames[0].size to 500_000L, frames[1].size to 500_000L + 1152 * 1_000_000L / 44100), out)
        // Not framed: PCM and AAC are taken as they come.
        assertNull(AudioFramer.forFormat(androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build()))
    }

    @Test
    fun divx3IsDroppedByMedia3AndNamedFromTheHeader() {
        val source = media("divx3_mp3.avi")
        val samples = Media3Samples.avi(source)
        try {
            // Media3 keeps only the sound: the picture's FourCC means nothing to it.
            assertEquals(listOf(C.TRACK_TYPE_AUDIO), samples.tracks.map { it.type })
        } finally {
            samples.close()
        }
        val declared = AviHeader.videoCodec(AviHeader.read(source))?.let { AviCodecs.video(it) }
        val check = ConvertChecks.decide(
            listOf(ConvertChecks.TrackFacts(MimeTypes.AUDIO_MPEG, 2_000_000)), 0,
            videoDecoder = { true }, audioDecoder = { true }, droppedVideo = { declared },
        )
        assertEquals(ConvertProblem.NoVideoDecoder(AviCodecs.MIME_MS_MPEG4_V3, "DivX 3"), check.problem)
        assertEquals("no decoder for DivX 3 video", check.problem!!.describe())
        // A file that really has no picture still says so.
        val none = ConvertChecks.decide(listOf(ConvertChecks.TrackFacts(MimeTypes.AUDIO_MPEG)), 0, videoDecoder = { true }, audioDecoder = { true })
        assertEquals(ConvertProblem.NoVideoTrack, none.problem)
    }

    @Test
    fun msMpeg4TracksAreRefusedWithoutAskingForADecoder() {
        var asked = false
        val tracks = listOf(ConvertChecks.TrackFacts(MimeTypes.VIDEO_MP43), ConvertChecks.TrackFacts(MimeTypes.AUDIO_AC3))
        val check = ConvertChecks.decide(
            tracks, 0,
            // What Transcoder.check does: MS-MPEG4 never reaches the codec list.
            videoDecoder = { i -> AviCodecs.msMpeg4Name(tracks[i].mime) == null && run { asked = true; true } },
            audioDecoder = { true },
            videoCodec = { i -> AviCodecs.msMpeg4Name(tracks[i].mime) ?: ConvertChecks.codecName(tracks[i].mime) },
        )
        assertFalse(asked)
        assertEquals("no decoder for DivX 3 video", check.problem!!.describe())
    }

    @Test
    fun notAnAviIsRefusedByTheReader() {
        try {
            Media3Samples.avi(media("h264_aac.ts")).close()
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }
}
