package io.github.patschgoo.castapp.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrCodesTest {
    @Test
    fun everyListedKeyHasAWorkingCode() {
        for (brand in IrCodes.all) {
            assertTrue(brand.id, brand.buttons.isNotEmpty())
            for ((key, codes) in brand.buttons) {
                assertTrue("${brand.id} $key", codes.isNotEmpty())
                for (code in codes) for (held in listOf(false, true)) for (toggle in listOf(false, true)) {
                    val frames = code.frames(toggle, held)
                    assertTrue(frames.isNotEmpty())
                    val p = IrEncoders.join(frames, code.periodUs)
                    assertTrue("${brand.id} $key odd length", p.size % 2 == 1)
                    assertTrue("${brand.id} $key positive", p.all { it > 0 })
                    assertTrue("${brand.id} $key under 2 s", p.sum() < 2_000_000)
                    assertTrue(code.carrierHz in 30_000..60_000)
                    for (f in frames) assertTrue("${brand.id} $key frame fits period", f.sum() < code.periodUs)
                }
            }
        }
    }

    @Test
    fun brandsHaveTheBasicsAndUniqueIds() {
        assertEquals(IrCodes.all.size, IrCodes.all.map { it.id }.toSet().size)
        val basics = setOf(RemoteKey.POWER, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE)
        for (brand in IrCodes.all) assertTrue(brand.id, brand.buttons.keys.containsAll(basics))
        val numbers = RemoteKey.entries.filter { it.name.startsWith("NUM_") }
        for (brand in IrCodes.all) assertTrue(brand.id, brand.buttons.keys.containsAll(numbers))
        val dpad = setOf(RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT, RemoteKey.OK)
        for (brand in listOf(IrCodes.samsung, IrCodes.lg, IrCodes.sony, IrCodes.philips, IrCodes.panasonic)) {
            assertTrue(brand.id, brand.buttons.keys.containsAll(dpad))
        }
        assertEquals(
            setOf("samsung", "lg", "sony", "philips", "philips-rc5", "panasonic", "toshiba"),
            IrCodes.all.map { it.id }.toSet(),
        )
    }

    @Test
    fun codesWithinProtocolRanges() {
        for (brand in IrCodes.all) for (codes in brand.buttons.values) for (code in codes) when (code) {
            is IrCode.Nec -> assertTrue(code.address in 0..0xFFFF && code.command in 0..0xFF)
            is IrCode.Samsung -> assertTrue(code.address in 0..0xFF && code.command in 0..0xFF)
            is IrCode.Sirc -> assertTrue(code.command in 0..0x7F)
            is IrCode.Rc5 -> assertTrue(code.address in 0..0x1F && code.command in 0..0x7F)
            is IrCode.Rc6 -> assertTrue(code.address in 0..0xFF && code.command in 0..0xFF)
            is IrCode.Panasonic -> {
                val b = (0..3).map { ((code.data shr (24 - 8 * it)) and 0xFF).toInt() }
                assertEquals("Panasonic checksum ${code.data.toString(16)}", b[3], b[0] xor b[1] xor b[2])
            }
        }
    }

    @Test
    fun wellKnownCodes() {
        assertEquals(IrCode.Samsung(0x07, 0x02), IrCodes.samsung.buttons[RemoteKey.POWER]!!.single())
        assertEquals(IrCode.Nec(0x04, 0x08), IrCodes.lg.buttons[RemoteKey.POWER]!!.single())
        assertEquals(IrCode.Sirc(12, 0x01, 0x15), IrCodes.sony.buttons[RemoteKey.POWER]!!.single())
        assertEquals(IrCode.Sirc(12, 0x01, 0x09), IrCodes.sony.buttons[RemoteKey.NUM_0]!!.single())
        assertEquals(IrCode.Sirc(12, 0x01, 0x00), IrCodes.sony.buttons[RemoteKey.NUM_1]!!.single())
        assertEquals(IrCode.Rc6(0x00, 0x0C), IrCodes.philips.buttons[RemoteKey.POWER]!!.single())
        assertEquals(IrCode.Rc5(0x00, 12), IrCodes.philipsRc5.buttons[RemoteKey.POWER]!!.single())
        assertEquals(IrCode.Panasonic(0x0100BCBDL), IrCodes.panasonic.buttons[RemoteKey.POWER]!!.single())
        assertEquals(IrCode.Panasonic(0x01000809L), IrCodes.panasonic.buttons[RemoteKey.NUM_1]!!.single())
        assertEquals(IrCode.Panasonic(0x01009899L), IrCodes.panasonic.buttons[RemoteKey.NUM_0]!!.single())
        assertEquals(IrCode.Nec(0x40, 0x12), IrCodes.toshiba.buttons[RemoteKey.POWER]!!.single())
        assertEquals(IrCode.Nec(0x04, 0x11), IrCodes.lg.buttons[RemoteKey.NUM_1]!!.single())
        assertEquals(IrCode.Samsung(0x07, 0x11), IrCodes.samsung.buttons[RemoteKey.NUM_0]!!.single())
        assertEquals(2, IrCodes.samsung.buttons[RemoteKey.PLAY_PAUSE]!!.size)
    }
}
