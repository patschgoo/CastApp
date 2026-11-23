package io.github.patschgoo.castapp.capture

import io.github.patschgoo.castapp.stream.AudioConfig
import io.github.patschgoo.castapp.stream.EncodedSample
import io.github.patschgoo.castapp.stream.FragmentedMp4
import io.github.patschgoo.castapp.stream.Mp4FragmentSerializer
import io.github.patschgoo.castapp.stream.StreamHub
import io.github.patschgoo.castapp.stream.VideoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * A TV recording (MPEG-TS, H.264 with B-frames, made with FFmpeg) copied into the converter's
 * fragmented MP4 without decoding: the samples as MediaExtractor hands them out ([TestTs]) through
 * [PictureCopier] and [StreamHub] into [Mp4FragmentSerializer], as [Transcoder]'s copy does. The output
 * is read back box by box, and decoded by FFmpeg where it's installed.
 */
class RemuxStreamTest {
    private fun ts(name: String) = TestTs(javaClass.getResourceAsStream("/media/$name")!!.readBytes())

    private class Remuxed(val mp4: ByteArray, val inputPts: List<Long>, val video: VideoConfig?)

    /** What [Transcoder]'s copy does with a file's picture (and AAC sound when [withAudio]). */
    private fun remux(name: String, withAudio: Boolean): Remuxed {
        val file = ts(name)
        val hub = StreamHub()
        val got = ArrayList<EncodedSample>()
        hub.subscribe { got += it }
        hub.expectAudio(withAudio)
        if (withAudio) {
            assertTrue(Remux.canCopyAudio("audio/mp4a-latm", file.asc, file.channels, file.sampleRate))
            hub.setAudioConfig(AudioConfig(file.sampleRate, file.channels, file.asc!!, 128_000))
            file.audio.forEach { hub.pushAudio(it.data, it.ptsUs) }
        }
        // No csd: the parameter sets come from the stream, as with recordings MediaExtractor has none for.
        val copier = PictureCopier(
            null, null,
            onStart = { f, sps, pps -> hub.setVideoConfig(VideoConfig(f.width, f.height, sps, pps)) },
            onSample = { data, dts, key, offset -> hub.pushVideo(data, dts, key, offset) },
        )
        for (s in file.video) assertTrue(copier.add(s.data, s.ptsUs, s.key))
        copier.finish()

        val init = hub.awaitInitSegment(1000)!!
        val serializer = Mp4FragmentSerializer(hub.publishedAudioRate)
        val out = ByteArrayOutputStream()
        out.write(init)
        // In timestamp order across the tracks, as the InterleavingQueue hands them to the writer.
        for (sample in got.sortedBy { it.ptsUs }) serializer.serialize(sample)?.let { out.write(it) }
        return Remuxed(out.toByteArray(), file.video.map { it.ptsUs }, hub.videoConfig)
    }

    /** One sample of the written fragments: its track, decoding and presentation time in the track's ticks. */
    private data class Written(val track: Int, val dts: Long, val pts: Long, val sync: Boolean)

    private fun readFragments(mp4: ByteArray): List<Written> {
        val b = ByteBuffer.wrap(mp4)
        val out = ArrayList<Written>()
        fun boxes(start: Int, end: Int, visit: (String, Int, Int) -> Unit) {
            var p = start
            while (p + 8 <= end) {
                val size = b.getInt(p)
                visit(String(mp4, p + 4, 4, Charsets.US_ASCII), p + 8, p + size)
                p += size
            }
        }
        boxes(0, mp4.size) { type, s, e ->
            if (type != "moof") return@boxes
            boxes(s, e) { t, ts, te ->
                if (t != "traf") return@boxes
                var track = 0
                var base = 0L
                boxes(ts, te) { box, bs, _ ->
                    when (box) {
                        "tfhd" -> track = b.getInt(bs + 4)
                        "tfdt" -> base = b.getLong(bs + 4)
                        "trun" -> {
                            val flags = b.getInt(bs) and 0xFFFFFF
                            val count = b.getInt(bs + 4)
                            var p = bs + 12 // after version/flags, count, data offset
                            var dts = base
                            repeat(count) {
                                val duration = b.getInt(p)
                                val sampleFlags = b.getInt(p + 8)
                                p += 12
                                val cto = if (flags and 0x800 != 0) b.getInt(p).toLong().also { p += 4 } else 0L
                                out += Written(track, dts, dts + cto, sampleFlags and 0x00010000 == 0)
                                dts += duration
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    @Test
    fun theTestReaderSeesWhatMediaExtractorWould() {
        val file = ts("h264_bframes_mp2.ts")
        assertEquals(0x1B, file.videoStreamType)
        assertEquals(0x03, file.audioStreamType) // MP2: converted to AAC on the phone
        assertEquals(50, file.video.size)
        assertTrue(file.video.first().key)
        // B-frames: presentation times out of order.
        assertTrue(file.video.zipWithNext().any { (a, b) -> b.ptsUs < a.ptsUs })
    }

    @Test
    fun aTvRecordingWithBFramesIsCopiedFrameForFrame() {
        val r = remux("h264_bframes_mp2.ts", withAudio = false)
        assertEquals(128, r.video!!.width)
        assertEquals(96, r.video.height)
        assertEquals("avc1.64000A", r.video.codecString) // High profile, as in the file
        val written = readFragments(r.mp4).filter { it.track == FragmentedMp4.VIDEO_TRACK_ID }
        // Every frame but the last (the hub holds one back to know its duration).
        assertEquals(r.inputPts.size - 1, written.size)
        assertTrue(written.first().sync)
        assertEquals(2, written.count { it.sync }) // a key frame each second, 2 s
        // Decoding order kept and strictly increasing; frames are shown at their own times.
        written.zipWithNext().forEach { (a, b) -> assertTrue(a.dts < b.dts) }
        assertTrue(written.all { it.pts >= it.dts })
        assertTrue("B-frames are shown later than decoded", written.any { it.pts > it.dts })
        val base = written.first().pts - r.inputPts.first() * 9 / 100
        val shown = written.map { it.pts - base }
        val expected = r.inputPts.dropLast(1).map { it * 9 / 100 }
        shown.zip(expected).forEach { (a, e) -> assertTrue("$a vs $e", kotlin.math.abs(a - e) <= 2) }
        decodesWithFfmpeg(r.mp4, frames = written.size, source = "h264_bframes_mp2.ts")
    }

    @Test
    fun aacSoundIsCopiedNextToThePicture() {
        val r = remux("h264_aac.ts", withAudio = true)
        val written = readFragments(r.mp4)
        val audio = written.filter { it.track == FragmentedMp4.AUDIO_TRACK_ID }
        val video = written.filter { it.track == FragmentedMp4.VIDEO_TRACK_ID }
        // 2 s of 48 kHz AAC: about 94 frames of 1024 samples, back to back.
        assertTrue(audio.size.toString(), audio.size in 85..96)
        audio.zipWithNext().forEach { (a, b) -> assertEquals(1024L, b.dts - a.dts) }
        assertTrue(video.size > 40)
        decodesWithFfmpeg(r.mp4, frames = video.size, source = "h264_aac.ts")
    }

    @Test
    fun mpeg2RecordingsAreNotCopied() {
        val file = ts("mpeg2.ts")
        assertEquals(0x02, file.videoStreamType)
        assertTrue(Remux.videoRoute(Remux.VideoFacts("video/mpeg2", null), 1080) is Remux.VideoRoute.Convert)
    }

    /**
     * FFmpeg decodes the copied stream without a single error, to the very pictures of the [source] file
     * in the same order (one missing: the hub holds the last one back). Skipped where FFmpeg isn't installed.
     */
    private fun decodesWithFfmpeg(mp4: ByteArray, frames: Int, source: String) {
        val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() }
        assumeTrue("FFmpeg not installed", ffmpeg != null)
        fun pictures(file: File): List<String> {
            val p = ProcessBuilder(ffmpeg, "-v", "error", "-i", file.path, "-map", "0:v", "-f", "framemd5", "-").start()
            val stdout = p.inputStream.bufferedReader().readText()
            val stderr = p.errorStream.bufferedReader().readText()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            assertEquals("FFmpeg: $stderr", "", stderr.trim())
            return stdout.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringAfterLast(',').trim() }
        }
        val copied = File.createTempFile("remux", ".mp4")
        val original = File.createTempFile("source", ".ts")
        try {
            copied.writeBytes(mp4)
            original.writeBytes(javaClass.getResourceAsStream("/media/$source")!!.readBytes())
            val got = pictures(copied)
            val want = pictures(original)
            assertEquals(frames, got.size)
            assertEquals(want.size - 1, got.size)
            // Lossless: every picture is one of the original's, in display order.
            val rest = want.iterator()
            assertTrue("copied pictures differ from the file's", got.all { g -> rest.asSequence().any { w -> w == g } })
        } finally {
            copied.delete()
            original.delete()
        }
    }
}
