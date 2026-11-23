package io.github.patschgoo.castapp.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmLevelTest {
    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s ->
            b[2 * i] = (s and 0xFF).toByte()
            b[2 * i + 1] = (s shr 8).toByte()
        }
    }

    @Test fun silenceAndDitherAreQuiet() {
        assertFalse(PcmLevel.hasSound(pcm(0, 0, 0, 0), 8))
        assertFalse(PcmLevel.hasSound(pcm(3, -5, 16, -16), 8))
    }

    @Test fun realSoundIsHeard() {
        assertTrue(PcmLevel.hasSound(pcm(0, 0, 1200, 0), 8))
        assertTrue(PcmLevel.hasSound(pcm(0, -300), 4))
        assertTrue(PcmLevel.hasSound(pcm(-32768), 2))
    }

    @Test fun onlyTheReadPartCounts() {
        assertFalse(PcmLevel.hasSound(pcm(0, 0, 5000), 4))
        assertFalse(PcmLevel.hasSound(pcm(5000), 1))
    }
}
