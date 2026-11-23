package io.github.patschgoo.castapp.capture

/** Tells real sound from digital silence in 16-bit little-endian PCM. */
object PcmLevel {
    /** Samples at or below this level count as silence (dither and rounding noise). */
    const val SILENCE_LEVEL = 16

    /** True if any sample in the first [length] bytes of [pcm] is louder than [SILENCE_LEVEL]. */
    fun hasSound(pcm: ByteArray, length: Int): Boolean {
        var i = 0
        val end = minOf(length, pcm.size) - 1
        while (i < end) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            if (sample > SILENCE_LEVEL || sample < -SILENCE_LEVEL) return true
            i += 2
        }
        return false
    }
}
