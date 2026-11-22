package io.github.patschgoo.castapp.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrEncodersTest {
    /** Reads a pulse-distance frame (NEC / Samsung) back as the usual 32-bit hex, first bit = MSB. */
    private fun pulseDistanceValue(p: IntArray): Long {
        assertEquals(67, p.size)
        var v = 0L
        for (bit in 0 until 32) {
            assertEquals(560, p[2 + bit * 2])
            val space = p[3 + bit * 2]
            assertTrue(space == 560 || space == 1690)
            v = (v shl 1) or if (space == 1690) 1 else 0
        }
        assertEquals(560, p[66])
        return v
    }

    /** Reads SIRC marks back, first bit = MSB. */
    private fun sircValue(p: IntArray): Int {
        assertEquals(2400, p[0])
        var v = 0
        var i = 2
        while (i < p.size) {
            assertEquals(600, p[i - 1])
            v = (v shl 1) or if (p[i] == 1200) 1 else 0
            i += 2
        }
        return v
    }

    @Test
    fun necFrameTimings() {
        val p = IrEncoders.nec(0x04, 0x08) // LG power
        assertEquals(9000, p[0])
        assertEquals(4500, p[1])
        assertEquals(0x20DF10EFL, pulseDistanceValue(p))
        // address 0x04 LSB first: 0,0,1,0,0,0,0,0
        assertArrayEquals(intArrayOf(560, 560, 560, 560, 560, 1690, 560, 560), p.copyOfRange(2, 10))
        assertEquals(0x02FD48B7L, pulseDistanceValue(IrEncoders.nec(0x40, 0x12))) // Toshiba power
        // Extended NEC: 16-bit address, low byte first
        assertEquals(0x7F8000FFL, pulseDistanceValue(IrEncoders.nec(0x01FE, 0x00)))
    }

    @Test
    fun necRepeatFrame() {
        assertArrayEquals(intArrayOf(9000, 2250, 560), IrEncoders.necRepeat())
    }

    @Test
    fun samsung32Frame() {
        val p = IrEncoders.samsung32(0x07, 0x02) // Samsung power
        assertEquals(4500, p[0])
        assertEquals(4500, p[1])
        assertEquals(0xE0E040BFL, pulseDistanceValue(p))
        assertEquals(0xE0E0E01FL, pulseDistanceValue(IrEncoders.samsung32(0x07, 0x07))) // volume up
    }

    @Test
    fun sirc12FrameExact() {
        // Sony power: command 0x15 (1,0,1,0,1,0,0 LSB first), device 1 (1,0,0,0,0)
        val expected = intArrayOf(
            2400,
            600, 1200, 600, 600, 600, 1200, 600, 600, 600, 1200, 600, 600, 600, 600,
            600, 1200, 600, 600, 600, 600, 600, 600, 600, 600,
        )
        assertArrayEquals(expected, IrEncoders.sirc(12, 1, 0x15))
    }

    @Test
    fun sirc15And20Bits() {
        val ret = IrEncoders.sirc(15, 0x97, 0x23) // Bravia Return, LIRC 0x62E9
        assertEquals(1 + 2 * 15, ret.size)
        assertEquals(0x62E9, sircValue(ret))
        assertEquals(1 + 2 * 20, IrEncoders.sirc(20, 0x1A, 0x7C, 0xE9).size)
    }

    @Test
    fun sonyIsSentThreeTimesFortyFiveMsApart() {
        val code = IrCode.Sirc(12, 1, 0x15)
        val frames = code.frames(toggle = false, held = false)
        assertEquals(3, frames.size)
        val p = IrEncoders.join(frames, code.periodUs)
        val one = IrEncoders.sirc(12, 1, 0x15)
        assertEquals(one.size * 3 + 2, p.size)
        assertEquals(45_000 - one.sum(), p[one.size])
        assertEquals(45_000 * 2 + one.sum(), p.sum())
        assertTrue(p.size % 2 == 1)
    }

    @Test
    fun rc5ManchesterCoding() {
        // System 0, command 12 (standby), toggle 0: bits 1 1 0 00000 001100
        val pairs = IntArray(12) { 889 }
        val expected = intArrayOf(889, 889, 1778, 889) + pairs + intArrayOf(889, 1778, 889, 889, 1778, 889, 889)
        val p = IrEncoders.rc5(0, 12, toggle = false)
        assertArrayEquals(expected, p)
        assertEquals(14 * 1778 - 2 * 889, p.sum()) // leading space and trailing space dropped
    }

    @Test
    fun rc5ToggleAndFieldBit() {
        val t0 = IrEncoders.rc5(0, 12, toggle = false)
        val t1 = IrEncoders.rc5(0, 12, toggle = true)
        assertNotEquals(t0.toList(), t1.toList())
        // toggle 1: bits 1 1 1 0...: mark, space, mark, space, mark (double), ...
        assertArrayEquals(intArrayOf(889, 889, 889, 889, 1778), t1.copyOfRange(0, 5))
        // Command 64+: the field bit is 0, so the second bit starts with a mark: M(889+889)
        assertEquals(1778, IrEncoders.rc5(0, 0x40, toggle = false)[0])
    }

    @Test
    fun rc6Mode0Coding() {
        // Address 0, command 0x0C, toggle 0
        val pairs = IntArray(22) { 444 }
        val expected = intArrayOf(2664, 888, 444, 888, 444, 444, 444, 444, 444, 888, 888) +
            pairs + intArrayOf(444, 888, 444, 444, 888, 444, 444, 444)
        val p = IrEncoders.rc6(0, 0x0C, toggle = false)
        assertArrayEquals(expected, p)
        assertEquals(52 * 444, p.sum())
    }

    @Test
    fun rc6ToggleIsDoubleWidth() {
        val t1 = IrEncoders.rc6(0, 0x0C, toggle = true)
        // Mode 000 ends with a mark (444) and toggle 1 starts with a mark (888): they merge
        assertArrayEquals(intArrayOf(2664, 888, 444, 888, 444, 444, 444, 444, 1332), t1.copyOfRange(0, 9))
        assertEquals(52 * 444, t1.sum())
    }

    @Test
    fun panasonicKaseikyoFrame() {
        val p = IrEncoders.panasonic(0x0100BCBDL) // Panasonic TV power
        assertEquals(2 + 48 * 2 + 1, p.size)
        assertEquals(3456, p[0])
        assertEquals(1728, p[1])
        val bytes = IntArray(6)
        for (bit in 0 until 48) {
            assertEquals(432, p[2 + bit * 2])
            val space = p[3 + bit * 2]
            assertTrue(space == 432 || space == 1296)
            if (space == 1296) bytes[bit / 8] = bytes[bit / 8] or (1 shl (bit % 8))
        }
        assertEquals(432, p.last())
        assertArrayEquals(intArrayOf(0x02, 0x20, 0x80, 0x00, 0x3D, 0xBD), bytes)
        assertEquals(bytes[5], bytes[2] xor bytes[3] xor bytes[4])
    }

    @Test
    fun carrierIsKeptInsideTheEmitterRange() {
        assertEquals(38_000, pickCarrier(38_000, emptyList()))
        assertEquals(38_000, pickCarrier(38_000, listOf(30_000..60_000)))
        assertEquals(38_000, pickCarrier(38_000, listOf(36_000..36_000, 38_000..38_000, 40_000..40_000)))
        assertEquals(40_000, pickCarrier(40_000, listOf(36_000..36_000, 38_000..38_000, 40_000..40_000)))
        assertEquals(38_000, pickCarrier(37_500, listOf(30_000..33_000, 38_000..56_000)))
        assertEquals(33_000, pickCarrier(34_000, listOf(30_000..33_000, 38_000..56_000)))
    }

    @Test
    fun holdingAnNecButtonSendsRepeatFrames() {
        val codes = listOf<IrCode>(IrCode.Nec(0x04, 0x02))
        val r = IrPresser()
        val first = r.plan(RemoteKey.VOLUME_UP, codes, 1_000)
        assertFalse(first.held)
        assertEquals(38_000, first.carrierHz)
        assertArrayEquals(IrEncoders.nec(0x04, 0x02), first.pattern)
        r.started(1_000)
        val held = r.plan(RemoteKey.VOLUME_UP, codes, 1_090)
        assertTrue(held.held)
        assertArrayEquals(IrEncoders.necRepeat(), held.pattern)
        assertEquals(1_108, held.notBeforeMs) // 108 ms frame period
        r.started(1_108)
        // A slow repeat (or a new press) sends the whole code again
        val again = r.plan(RemoteKey.VOLUME_UP, codes, 1_600)
        assertFalse(again.held)
        assertArrayEquals(IrEncoders.nec(0x04, 0x02), again.pattern)
        r.started(1_600)
        // Another button is never a repeat
        assertFalse(r.plan(RemoteKey.VOLUME_DOWN, listOf(IrCode.Nec(0x04, 0x03)), 1_650).held)
    }

    @Test
    fun rc5ToggleFlipsPerPressAndStaysWhileHeld() {
        val codes = listOf<IrCode>(IrCode.Rc5(0, 16))
        val r = IrPresser()
        val a = r.plan(RemoteKey.VOLUME_UP, codes, 0).pattern
        r.started(0)
        val held = r.plan(RemoteKey.VOLUME_UP, codes, 120)
        assertTrue(held.held)
        assertArrayEquals(a, held.pattern)
        assertEquals(114, held.notBeforeMs)
        r.started(120)
        val b = r.plan(RemoteKey.VOLUME_UP, codes, 2_000).pattern
        assertNotEquals(a.toList(), b.toList())
        r.started(2_000)
        val c = r.plan(RemoteKey.VOLUME_UP, codes, 4_000).pattern
        assertArrayEquals(a, c)
    }

    @Test
    fun separatePlayAndPauseCodesAlternate() {
        val pause = IrCode.Samsung(0x07, 0x4A)
        val play = IrCode.Samsung(0x07, 0x47)
        val codes = listOf<IrCode>(pause, play)
        val r = IrPresser()
        fun at(t: Long) = r.plan(RemoteKey.PLAY_PAUSE, codes, t).pattern.also { r.started(t) }.toList()
        val pauseP = IrEncoders.samsung32(0x07, 0x4A).toList()
        val playP = IrEncoders.samsung32(0x07, 0x47).toList()
        assertEquals(pauseP, at(0))
        assertEquals(playP, at(1_000))
        assertEquals(playP, at(1_150)) // held: same button
        assertEquals(pauseP, at(3_000))
    }

    @Test
    fun joinKeepsFramePeriod() {
        val f = IrEncoders.samsung32(0x07, 0x07)
        val p = IrEncoders.join(listOf(f, f), IrEncoders.NEC_PERIOD_US)
        assertEquals(f.size * 2 + 1, p.size)
        assertEquals(108_000 - f.sum(), p[f.size])
    }
}
