package io.github.patschgoo.castapp.subtitles

/**
 * Turns decoded audio (any sample rate, any channel count) into the 16 kHz mono
 * 16-bit samples speech recognisers want. Keeps state between chunks, so a long
 * file can be fed piece by piece without clicks at the joins.
 */
class PcmConverter(private val inRate: Int, private val channels: Int, private val outRate: Int = 16_000) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var prev = 0f

    /** [interleaved] samples in -1..1, [frames] frames of [channels] samples each. */
    fun convert(interleaved: FloatArray, frames: Int): ShortArray {
        if (frames <= 0) return ShortArray(0)
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            mono[f] = sum / channels
        }
        val out = ShortArray(((frames - pos) / step).toInt() + 2)
        var n = 0
        var p = pos
        while (p < frames - 1) {
            val i = kotlin.math.floor(p).toInt()
            val frac = (p - i).toFloat()
            val a = if (i < 0) prev else mono[i]
            val b = mono[i + 1]
            out[n++] = ((a + (b - a) * frac).coerceIn(-1f, 1f) * 32767).toInt().toShort()
            p += step
        }
        pos = p - frames
        prev = mono[frames - 1]
        return out.copyOf(n)
    }
}
