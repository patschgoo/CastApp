package io.github.patschgoo.castapp.remote

/**
 * Infrared protocol encoders. Every frame is an on/off pattern in microseconds that
 * starts and ends with "on" (odd length), which is what `ConsumerIrManager.transmit`
 * expects; gaps between frames are added by [IrEncoders.join].
 *
 * Plain Kotlin (no Android types) so it is tested on the JVM.
 */
object IrEncoders {
    // NEC (LG, Toshiba and many others): 38 kHz, LSB first
    const val NEC_CARRIER = 38_000
    const val NEC_HEADER_MARK = 9000
    const val NEC_HEADER_SPACE = 4500
    const val NEC_REPEAT_SPACE = 2250
    const val NEC_BIT_MARK = 560
    const val NEC_ZERO_SPACE = 560
    const val NEC_ONE_SPACE = 1690
    const val NEC_PERIOD_US = 108_000

    // Samsung32: like NEC but a 4.5 ms header mark, and held buttons resend the whole frame
    const val SAMSUNG_HEADER_MARK = 4500
    const val SAMSUNG_HEADER_SPACE = 4500

    // Sony SIRC: 40 kHz, pulse-width coded, 45 ms frame period, at least 3 frames per press
    const val SIRC_CARRIER = 40_000
    const val SIRC_HEADER_MARK = 2400
    const val SIRC_SPACE = 600
    const val SIRC_ONE_MARK = 1200
    const val SIRC_ZERO_MARK = 600
    const val SIRC_PERIOD_US = 45_000
    const val SIRC_MIN_FRAMES = 3

    // Philips RC5: 36 kHz, Manchester coded, 889 µs half bits, 113.8 ms period
    const val RC5_CARRIER = 36_000
    const val RC5_HALF = 889
    const val RC5_PERIOD_US = 113_778

    // Philips RC6 mode 0: 36 kHz, Manchester coded, 444 µs unit, 106.7 ms period
    const val RC6_CARRIER = 36_000
    const val RC6_UNIT = 444
    const val RC6_PERIOD_US = 106_667

    // Panasonic / Kaseikyo: ~37 kHz, 432 µs unit, 48 bits
    const val KASEIKYO_CARRIER = 37_000
    const val KASEIKYO_UNIT = 432
    const val KASEIKYO_PERIOD_US = 130_000

    /**
     * NEC frame. An 8-bit [address] is sent with its inverse; a 16-bit one ("extended NEC")
     * as low byte then high byte. The command is always followed by its inverse.
     */
    fun nec(address: Int, command: Int): IntArray =
        pulseDistance(NEC_HEADER_MARK, NEC_HEADER_SPACE, necBits(address, command))

    /** NEC repeat frame sent every 108 ms while a button is held. */
    fun necRepeat(): IntArray = intArrayOf(NEC_HEADER_MARK, NEC_REPEAT_SPACE, NEC_BIT_MARK)

    /** Samsung32: 8-bit address twice, then command and its inverse. */
    fun samsung32(address: Int, command: Int): IntArray {
        require(address in 0..0xFF && command in 0..0xFF)
        val bits = lsbFirst(address, 8) + lsbFirst(address, 8) + lsbFirst(command, 8) + lsbFirst(command.inv() and 0xFF, 8)
        return pulseDistance(SAMSUNG_HEADER_MARK, SAMSUNG_HEADER_SPACE, bits)
    }

    private fun necBits(address: Int, command: Int): List<Boolean> {
        require(address in 0..0xFFFF && command in 0..0xFF)
        val addr = if (address <= 0xFF) lsbFirst(address, 8) + lsbFirst(address.inv() and 0xFF, 8) else lsbFirst(address, 16)
        return addr + lsbFirst(command, 8) + lsbFirst(command.inv() and 0xFF, 8)
    }

    /** Header, then each bit as a 560 µs mark and a short (0) or long (1) space, then a stop mark. */
    private fun pulseDistance(headerMark: Int, headerSpace: Int, bits: List<Boolean>): IntArray {
        val p = PulseBuilder().mark(headerMark).space(headerSpace)
        for (b in bits) p.mark(NEC_BIT_MARK).space(if (b) NEC_ONE_SPACE else NEC_ZERO_SPACE)
        return p.mark(NEC_BIT_MARK).build()
    }

    /**
     * Sony SIRC frame: 7 command bits, then 5 device bits (12-bit), 8 device bits (15-bit),
     * or 5 device + 8 extended bits (20-bit), all LSB first.
     */
    fun sirc(bits: Int, device: Int, command: Int, extended: Int = 0): IntArray {
        require(command in 0..0x7F)
        val payload = when (bits) {
            12 -> { require(device in 0..0x1F); lsbFirst(command, 7) + lsbFirst(device, 5) }
            15 -> { require(device in 0..0xFF); lsbFirst(command, 7) + lsbFirst(device, 8) }
            20 -> { require(device in 0..0x1F && extended in 0..0xFF); lsbFirst(command, 7) + lsbFirst(device, 5) + lsbFirst(extended, 8) }
            else -> throw IllegalArgumentException("SIRC has 12, 15 or 20 bits")
        }
        val p = PulseBuilder().mark(SIRC_HEADER_MARK)
        for (b in payload) p.space(SIRC_SPACE).mark(if (b) SIRC_ONE_MARK else SIRC_ZERO_MARK)
        return p.build()
    }

    /**
     * RC5 frame: start bit 1, field bit (inverted command bit 6), toggle, 5 address bits,
     * 6 command bits, MSB first. A 1 is space-then-mark, a 0 mark-then-space.
     */
    fun rc5(address: Int, command: Int, toggle: Boolean): IntArray {
        require(address in 0..0x1F && command in 0..0x7F)
        val bits = listOf(true, command and 0x40 == 0, toggle) + msbFirst(address, 5) + msbFirst(command and 0x3F, 6)
        val p = PulseBuilder()
        for (b in bits) if (b) p.space(RC5_HALF).mark(RC5_HALF) else p.mark(RC5_HALF).space(RC5_HALF)
        return p.build()
    }

    /**
     * RC6 mode 0 frame: leader (6T mark, 2T space), start bit 1, mode 000, double-width
     * toggle bit, 8 address bits and 8 command bits MSB first. A 1 is mark-then-space.
     */
    fun rc6(address: Int, command: Int, toggle: Boolean): IntArray {
        require(address in 0..0xFF && command in 0..0xFF)
        val t = RC6_UNIT
        val p = PulseBuilder().mark(6 * t).space(2 * t)
        fun bit(b: Boolean, w: Int) { if (b) p.mark(w).space(w) else p.space(w).mark(w) }
        bit(true, t)
        repeat(3) { bit(false, t) }
        bit(toggle, 2 * t)
        for (b in msbFirst(address, 8) + msbFirst(command, 8)) bit(b, t)
        return p.build()
    }

    /** Kaseikyo frame of 6 bytes, each sent LSB first. */
    fun kaseikyo(bytes: IntArray): IntArray {
        require(bytes.size == 6 && bytes.all { it in 0..0xFF })
        val u = KASEIKYO_UNIT
        val p = PulseBuilder().mark(8 * u).space(4 * u)
        for (byte in bytes) for (b in lsbFirst(byte, 8)) p.mark(u).space(if (b) 3 * u else u)
        return p.mark(u).build()
    }

    /**
     * Panasonic in the form most code lists use (IRremote `sendPanasonic(0x4004, data)`):
     * the 16-bit vendor 0x4004 and 32 data bits, both sent MSB first.
     */
    fun panasonic(data: Long, vendor: Int = 0x4004): IntArray {
        val msb = msbFirst(vendor, 16) + msbFirst(data, 32)
        val bytes = IntArray(6) { i -> (0 until 8).sumOf { j -> if (msb[i * 8 + j]) 1 shl j else 0 } }
        return kaseikyo(bytes)
    }

    /** Frames one after another, each starting [periodUs] after the previous one (as a held remote sends them). */
    fun join(frames: List<IntArray>, periodUs: Int): IntArray {
        require(frames.isNotEmpty())
        val out = ArrayList<Int>()
        frames.forEachIndexed { i, f ->
            require(f.size % 2 == 1) { "frames start and end with a mark" }
            if (i > 0) out += maxOf(periodUs - frames[i - 1].sum(), MIN_GAP_US)
            f.forEach { out += it }
        }
        return out.toIntArray()
    }

    /** Shortest silence put between two frames. */
    const val MIN_GAP_US = 10_000

    private fun lsbFirst(v: Int, n: Int) = List(n) { (v shr it) and 1 == 1 }
    private fun msbFirst(v: Int, n: Int) = List(n) { (v shr (n - 1 - it)) and 1 == 1 }
    private fun msbFirst(v: Long, n: Int) = List(n) { (v shr (n - 1 - it)) and 1L == 1L }

    /** Collects marks and spaces, merging neighbours of the same kind and dropping leading/trailing spaces. */
    private class PulseBuilder {
        private val out = ArrayList<Int>()

        fun mark(us: Int) = add(us, mark = true)
        fun space(us: Int) = add(us, mark = false)

        private fun add(us: Int, mark: Boolean): PulseBuilder {
            val lastIsMark = out.size % 2 == 1
            when {
                out.isEmpty() && !mark -> {} // a frame starts with the first mark
                out.isNotEmpty() && lastIsMark == mark -> out[out.size - 1] += us
                else -> out += us
            }
            return this
        }

        fun build(): IntArray {
            if (out.size % 2 == 0 && out.isNotEmpty()) out.removeAt(out.size - 1)
            return out.toIntArray()
        }
    }
}

/** Picks a carrier the emitter can send: [wanted] if it's in a supported range, else the closest supported value. */
fun pickCarrier(wanted: Int, ranges: List<IntRange>): Int {
    val usable = ranges.filter { !it.isEmpty() }
    if (usable.isEmpty() || usable.any { wanted in it }) return wanted
    return usable.map { wanted.coerceIn(it.first, it.last) }.minBy { kotlin.math.abs(it - wanted) }
}

/** One button's infrared code. */
sealed class IrCode {
    abstract val carrierHz: Int
    /** Start-to-start time of frames while the button is held. */
    abstract val periodUs: Int
    /** A press this soon (start to start) after the last one of the same button counts as holding it. */
    open val holdWindowMs: Long get() = 300
    /** Frames for a new press ([held] = false) or for a held button. */
    abstract fun frames(toggle: Boolean, held: Boolean): List<IntArray>

    /** NEC: full frame, then short repeat frames while held. */
    data class Nec(val address: Int, val command: Int) : IrCode() {
        override val carrierHz get() = IrEncoders.NEC_CARRIER
        override val periodUs get() = IrEncoders.NEC_PERIOD_US
        // Repeat frames only make sense if they follow closely; later presses send the full code again.
        override val holdWindowMs: Long get() = 150
        override fun frames(toggle: Boolean, held: Boolean) =
            listOf(if (held) IrEncoders.necRepeat() else IrEncoders.nec(address, command))
    }

    data class Samsung(val address: Int, val command: Int) : IrCode() {
        override val carrierHz get() = IrEncoders.NEC_CARRIER
        override val periodUs get() = IrEncoders.NEC_PERIOD_US
        override fun frames(toggle: Boolean, held: Boolean) = listOf(IrEncoders.samsung32(address, command))
    }

    data class Sirc(val bits: Int, val device: Int, val command: Int, val extended: Int = 0) : IrCode() {
        override val carrierHz get() = IrEncoders.SIRC_CARRIER
        override val periodUs get() = IrEncoders.SIRC_PERIOD_US
        override fun frames(toggle: Boolean, held: Boolean): List<IntArray> {
            val f = IrEncoders.sirc(bits, device, command, extended)
            return List(IrEncoders.SIRC_MIN_FRAMES) { f }
        }
    }

    data class Rc5(val address: Int, val command: Int) : IrCode() {
        override val carrierHz get() = IrEncoders.RC5_CARRIER
        override val periodUs get() = IrEncoders.RC5_PERIOD_US
        override fun frames(toggle: Boolean, held: Boolean) = listOf(IrEncoders.rc5(address, command, toggle))
    }

    data class Rc6(val address: Int, val command: Int) : IrCode() {
        override val carrierHz get() = IrEncoders.RC6_CARRIER
        override val periodUs get() = IrEncoders.RC6_PERIOD_US
        override fun frames(toggle: Boolean, held: Boolean) = listOf(IrEncoders.rc6(address, command, toggle))
    }

    /** Panasonic data word as in IRremote / LIRC lists (vendor 0x4004). */
    data class Panasonic(val data: Long) : IrCode() {
        override val carrierHz get() = IrEncoders.KASEIKYO_CARRIER
        override val periodUs get() = IrEncoders.KASEIKYO_PERIOD_US
        override fun frames(toggle: Boolean, held: Boolean) = listOf(IrEncoders.panasonic(data))
    }
}

/** What to transmit for one [IrPresser.plan] call, and the earliest time to start it. */
class IrPlan(val carrierHz: Int, val pattern: IntArray, val notBeforeMs: Long, val held: Boolean)

/**
 * Turns button presses into transmissions the way a real remote would: a press that
 * repeats the same button quickly counts as holding it (NEC repeat frames, same RC5/RC6
 * toggle bit, frame spacing kept), a new press flips the RC5/RC6 toggle bit, and buttons
 * with two codes (separate Play and Pause) alternate between them.
 */
class IrPresser {
    private var lastKey: RemoteKey? = null
    private var lastStartMs = Long.MIN_VALUE / 2
    private var lastSlotMs = 0L
    private var toggle = false
    private val alternate = HashMap<RemoteKey, Int>()

    /** Plans a press at [nowMs]; call [started] with the actual start time once it is sent. */
    fun plan(key: RemoteKey, codes: List<IrCode>, nowMs: Long): IrPlan {
        require(codes.isNotEmpty())
        val sinceLast = nowMs - lastStartMs
        val firstGuess = codes[alternate[key] ?: 0]
        val held = key == lastKey && sinceLast <= firstGuess.holdWindowMs
        if (!held) {
            toggle = !toggle
            if (codes.size > 1) alternate[key] = alternate[key]?.let { (it + 1) % codes.size } ?: 0
        }
        val code = codes[alternate[key] ?: 0]
        val frames = code.frames(toggle, held)
        val pattern = IrEncoders.join(frames, code.periodUs)
        val notBefore = lastStartMs + lastSlotMs
        lastKey = key
        lastSlotMs = (frames.size.toLong() * code.periodUs + 999) / 1000
        return IrPlan(code.carrierHz, pattern, notBefore, held)
    }

    /** Records when the planned transmission really started (after waiting for [IrPlan.notBeforeMs]). */
    fun started(atMs: Long) {
        lastStartMs = atMs
    }
}
