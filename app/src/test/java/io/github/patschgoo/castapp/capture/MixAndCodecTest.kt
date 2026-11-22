package io.github.patschgoo.castapp.capture

import io.github.patschgoo.castapp.capture.CodecChooser.Target
import io.github.patschgoo.castapp.settings.CodecPreference
import io.github.patschgoo.castapp.stream.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class MixAndCodecTest {
    private fun stereo(vararg frames: Pair<Int, Int>): ByteArray {
        val out = ByteArray(frames.size * 4)
        frames.forEachIndexed { i, (l, r) ->
            out[i * 4] = l.toByte(); out[i * 4 + 1] = (l shr 8).toByte()
            out[i * 4 + 2] = r.toByte(); out[i * 4 + 3] = (r shr 8).toByte()
        }
        return out
    }

    private fun sample(pcm: ByteArray, index: Int): Int = (pcm[index * 2].toInt() and 0xFF) or (pcm[index * 2 + 1].toInt() shl 8)

    @Test
    fun softClipPassesQuietSoundAndNeverOverflows() {
        for (v in listOf(0, 1, -1, 1000, -20_000, PcmMixer.KNEE, -PcmMixer.KNEE)) assertEquals(v, PcmMixer.softClip(v))
        var previous = PcmMixer.KNEE
        for (v in PcmMixer.KNEE + 1..200_000 step 997) {
            val out = PcmMixer.softClip(v)
            assertTrue("monotonic at $v", out >= previous)
            assertTrue(out <= 32_767)
            assertEquals(-out, PcmMixer.softClip(-v))
            previous = out
        }
        assertEquals(32_767, PcmMixer.softClip(Int.MAX_VALUE / 2))
    }

    @Test
    fun micIsAddedToBothChannelsWithGain() {
        val pcm = stereo(100 to -100, 0 to 0, 5 to 5)
        PcmMixer.mixMicInto(pcm, 0, 3, shortArrayOf(1000, -2000, 0), 0, 3, 0.5f)
        assertEquals(600, sample(pcm, 0)); assertEquals(400, sample(pcm, 1))
        assertEquals(-1000, sample(pcm, 2)); assertEquals(-1000, sample(pcm, 3))
        assertEquals(5, sample(pcm, 4)); assertEquals(5, sample(pcm, 5))
    }

    @Test
    fun loudMixIsLimitedNotWrapped() {
        val pcm = stereo(30_000 to -30_000)
        PcmMixer.mixMicInto(pcm, 0, 1, shortArrayOf(30_000), 0, 1, 2f)
        val l = sample(pcm, 0).toShort().toInt()
        val r = sample(pcm, 1).toShort().toInt()
        assertTrue("no wrap-around: $l", l in PcmMixer.KNEE..32_767)
        assertTrue("$r", r in PcmMixer.KNEE until l) // -30000 + 60000 = 30000: limited, and below the louder channel
    }

    @Test
    fun shortMicReadMixesOnlyWhatIsThere() {
        val pcm = stereo(1 to 1, 2 to 2)
        val before = pcm.copyOf()
        PcmMixer.mixMicInto(pcm, 0, 2, shortArrayOf(10, 10), 0, 1, 1f)
        assertEquals(11, sample(pcm, 0))
        assertEquals(before.copyOfRange(4, 8).toList(), pcm.copyOfRange(4, 8).toList())
        PcmMixer.mixMicInto(pcm, 0, 2, shortArrayOf(10, 10), 0, 2, 0f) // level 0: nothing changes
        assertEquals(11, sample(pcm, 0))
    }

    @Test
    fun fifoPrimesDropsOldestAndTrims() {
        val fifo = MonoFifo(maxSamples = 8, primeSamples = 4)
        val out = ShortArray(8)
        fifo.write(shortArrayOf(1, 2, 3), 0, 3)
        assertEquals(0, fifo.read(out, 2)) // not primed yet
        fifo.write(shortArrayOf(4), 0, 1)
        assertEquals(2, fifo.read(out, 2))
        assertArrayEquals(shortArrayOf(1, 2), out.copyOf(2))
        assertEquals(2, fifo.read(out, 4)) // ran dry
        assertEquals(0, fifo.read(out, 1))
        fifo.write(ShortArray(10) { (it + 10).toShort() }, 0, 10) // overflow keeps the newest 8
        assertEquals(8, fifo.size)
        fifo.trimTo(3)
        assertEquals(3, fifo.read(out, 3))
        assertArrayEquals(shortArrayOf(17, 18, 19), out.copyOf(3))
    }

    @Test
    fun resamplerKeepsRateAndShape() {
        val r = LinearResampler(44_100, 48_000)
        var produced = 0
        val chunk = 441
        val all = ArrayList<Short>()
        repeat(100) { c ->
            val input = ShortArray(chunk) { i -> (10_000 * sin(2 * Math.PI * 1000 * (c * chunk + i) / 44_100.0)).toInt().toShort() }
            val out = r.process(input, chunk)
            produced += out.size
            all += out.toList()
        }
        // One second in, one second out (give or take a sample).
        assertTrue("produced $produced", produced in 47_998..48_001)
        // Still a 1 kHz sine: compare against the ideal curve at 48 kHz.
        for (i in 100 until 47_000 step 37) {
            val ideal = 10_000 * sin(2 * Math.PI * 1000 * i / 48_000.0)
            assertTrue("sample $i", kotlin.math.abs(all[i] - ideal) < 400)
        }
    }

    @Test
    fun automaticCodecOnlyWhereKnownToWork() {
        fun auto(target: Target, tv: Boolean? = null, model: String? = null, failed: Boolean = false, compatible: Boolean = false, phone: Boolean = true) =
            CodecChooser.choose(CodecPreference.AUTO, target, phone, tv, model, failed, compatible)
        assertEquals(VideoCodec.HEVC, auto(Target.CASTAPP_TV, tv = true))
        assertEquals(VideoCodec.H264, auto(Target.CASTAPP_TV, tv = false))
        assertEquals(VideoCodec.H264, auto(Target.CASTAPP_TV, tv = null)) // older TV version
        assertEquals(VideoCodec.HEVC, auto(Target.CAST, model = "Chromecast Ultra"))
        assertEquals(VideoCodec.HEVC, auto(Target.CAST, model = "Google TV Streamer"))
        assertEquals(VideoCodec.HEVC, auto(Target.CAST, model = "SHIELD Android TV"))
        assertEquals(VideoCodec.H264, auto(Target.CAST, model = "Chromecast")) // could be a 3rd generation one
        assertEquals(VideoCodec.H264, auto(Target.CAST, model = "Google Nest Hub"))
        assertEquals(VideoCodec.H264, auto(Target.CAST, model = "Chromecast Ultra", failed = true))
        assertEquals(VideoCodec.H264, auto(Target.CAST, model = "Chromecast Ultra", compatible = true))
        assertEquals(VideoCodec.H264, auto(Target.CAST, model = "Chromecast Ultra", phone = false))
        assertEquals(VideoCodec.H264, auto(Target.DLNA))
        assertEquals(VideoCodec.H264, auto(Target.HLS_PLAYER))
    }

    @Test
    fun forcedCodecs() {
        fun pick(pref: CodecPreference, target: Target, tv: Boolean? = null, phone: Boolean = true) =
            CodecChooser.choose(pref, target, phone, tv, "Chromecast", false, false)
        assertEquals(VideoCodec.HEVC, pick(CodecPreference.HEVC, Target.CAST))
        assertEquals(VideoCodec.HEVC, pick(CodecPreference.HEVC, Target.HLS_PLAYER))
        assertEquals(VideoCodec.H264, pick(CodecPreference.HEVC, Target.DLNA)) // TS stream is H.264 only
        assertEquals(VideoCodec.H264, pick(CodecPreference.HEVC, Target.CASTAPP_TV, tv = false))
        assertEquals(VideoCodec.H264, pick(CodecPreference.HEVC, Target.CAST, phone = false))
        assertEquals(VideoCodec.H264, pick(CodecPreference.H264, Target.CASTAPP_TV, tv = true))
    }

    @Test
    fun softwareCodecNames() {
        assertFalse(VideoCodecs.isHardwareName("c2.android.hevc.encoder"))
        assertFalse(VideoCodecs.isHardwareName("OMX.google.h264.encoder"))
        assertTrue(VideoCodecs.isHardwareName("c2.qti.hevc.encoder"))
        assertTrue(VideoCodecs.isHardwareName("OMX.Exynos.HEVC.Encoder"))
    }
}
