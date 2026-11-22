package io.github.patschgoo.castapp.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recording helpers used for voice search. */
class PcmTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s -> b[i * 2] = s.toByte(); b[i * 2 + 1] = (s shr 8).toByte() }
    }

    @Test
    fun downsampleAverages() {
        val src = pcm(100, 300, -1000, -2000, 32767, 32767, 7)
        // Pairs are averaged; the odd sample at the end is dropped.
        assertEquals(pcm(200, -1500, 32767).toList(), Pcm.downsample(src, src.size, 2).toList())
        assertEquals(pcm(100, 300).toList(), Pcm.downsample(src, 4, 1).toList())
        val six = pcm(6, 6, 6, 12, 12, 12)
        assertEquals(pcm(9).toList(), Pcm.downsample(six, six.size, 6).toList())
    }

    @Test
    fun levelAndRates() {
        assertEquals(0f, Pcm.level(pcm(0, 0, 0, 0), 8))
        assertEquals(1f, Pcm.level(pcm(32767, -32768), 4))
        val quiet = Pcm.level(pcm(1000, -1000, 1000, -1000), 8)
        assertTrue(quiet > 0.05f && quiet < 0.2f)
        assertEquals(listOf(8000, 16000, 24000, 48000), Pcm.captureRates(8000))
    }
}
