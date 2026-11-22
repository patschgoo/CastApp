package io.github.patschgoo.castapp.capture

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Mixes the phone's microphone (mono, 16-bit) into the captured sound
 * (interleaved stereo, 16-bit little-endian, as AudioRecord delivers it).
 *
 * Loud passages would add up beyond what 16 bits can hold and crackle, so
 * the sum goes through a soft limiter: quiet sound passes unchanged, and above
 * [KNEE] the level is compressed smoothly towards full scale instead of being
 * cut off.
 */
object PcmMixer {
    /** Up to here samples pass unchanged (about -2.5 dBFS). */
    const val KNEE = 24_576
    private const val FULL = 32_767

    /** Soft clipping: maps any sum of samples into the 16-bit range without a hard edge. */
    fun softClip(sum: Int): Int {
        val a = abs(sum)
        if (a <= KNEE) return sum
        val range = FULL - KNEE
        val excess = (a - KNEE).toDouble()
        val limited = KNEE + range * excess / (excess + range)
        val out = limited.roundToInt().coerceAtMost(FULL)
        return if (sum < 0) -out else out
    }

    /**
     * Adds [frames] microphone samples from [mic] (starting at [micOffset]) at
     * [gain] to both channels of the stereo PCM in [pcm] (starting at byte [offset]).
     * When the microphone has fewer samples ([micCount] < [frames]) only those are mixed.
     */
    fun mixMicInto(pcm: ByteArray, offset: Int, frames: Int, mic: ShortArray, micOffset: Int, micCount: Int, gain: Float) {
        val n = minOf(frames, micCount)
        if (n <= 0 || gain <= 0f) return
        val g = (gain * 256).roundToInt() // fixed point, 8 fractional bits
        var p = offset
        for (i in 0 until n) {
            val m = (mic[micOffset + i] * g) shr 8
            for (ch in 0 until 2) {
                val s = (pcm[p].toInt() and 0xFF) or (pcm[p + 1].toInt() shl 8)
                val mixed = softClip(s + m)
                pcm[p] = mixed.toByte()
                pcm[p + 1] = (mixed shr 8).toByte()
                p += 2
            }
        }
    }
}

/**
 * Short queue of microphone samples between the microphone (which delivers in
 * bursts) and the mixer (which takes one AAC frame at a time). Kept short so the
 * voice isn't delayed: when it grows past [maxSamples] the oldest samples go.
 * After running dry it waits until [primeSamples] are there again, so a slow
 * microphone gives a short pause instead of constant crackling.
 */
class MonoFifo(private val maxSamples: Int, private val primeSamples: Int) {
    private val buf = ShortArray(maxSamples)
    private var start = 0
    var size = 0
        private set
    private var primed = false

    fun write(data: ShortArray, offset: Int, count: Int) {
        for (i in 0 until count) {
            if (size == maxSamples) {
                // Too far behind: drop the oldest sample.
                start = (start + 1) % maxSamples
                size--
            }
            buf[(start + size) % maxSamples] = data[offset + i]
            size++
        }
        if (size >= primeSamples) primed = true
    }

    /** Copies up to [count] samples into [out]; returns how many. */
    fun read(out: ShortArray, count: Int): Int {
        if (!primed) return 0
        val n = minOf(count, size)
        for (i in 0 until n) out[i] = buf[(start + i) % maxSamples]
        start = (start + n) % maxSamples
        size -= n
        if (n < count) primed = false // ran dry
        return n
    }

    /** Drops all but the newest [keep] samples (latency got too long). */
    fun trimTo(keep: Int) {
        if (size <= keep) return
        val drop = size - keep
        start = (start + drop) % maxSamples
        size = keep
    }
}

/** Linear resampler for microphones that only record at 44.1 kHz (mono). */
class LinearResampler(private val fromRate: Int, private val toRate: Int) {
    /** Next output position, in input samples relative to this call's first; -1..0 = between the previous call's last sample and it. */
    private var pos = 0.0
    private var last: Short = 0

    /** Resamples [count] samples of [input]; returns the output. */
    fun process(input: ShortArray, count: Int): ShortArray {
        if (fromRate == toRate) return input.copyOf(count)
        if (count == 0) return ShortArray(0)
        val step = fromRate.toDouble() / toRate
        val out = ArrayList<Short>(count * toRate / fromRate + 2)
        while (pos < count - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = pos - i
            val a = if (i < 0) last.toInt() else input[i].toInt()
            val b = input[i + 1].toInt()
            out.add((a + (b - a) * frac).roundToInt().toShort())
            pos += step
        }
        pos -= count
        last = input[count - 1]
        return out.toShortArray()
    }
}
