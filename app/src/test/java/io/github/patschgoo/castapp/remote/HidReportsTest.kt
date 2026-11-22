package io.github.patschgoo.castapp.remote

import io.github.patschgoo.castapp.remote.HidReports.Key
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsTest {
    /** Input bits per report id, worked out from the descriptor's global/main items. */
    private fun inputBitsPerReport(d: ByteArray): Map<Int, Int> {
        val bits = LinkedHashMap<Int, Int>()
        var reportId = 0
        var size = 0
        var count = 0
        var depth = 0
        var i = 0
        while (i < d.size) {
            val prefix = d[i].toInt() and 0xFF
            val len = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            var value = 0
            for (j in 0 until len) value = value or ((d[i + 1 + j].toInt() and 0xFF) shl (8 * j))
            when (prefix and 0xFC) {
                0x84 -> reportId = value
                0x74 -> size = value
                0x94 -> count = value
                0x80 -> bits[reportId] = (bits[reportId] ?: 0) + size * count // Input
                0xA0 -> depth++ // Collection
                0xC0 -> depth-- // End Collection
            }
            i += 1 + len
        }
        assertEquals("collections are balanced", 0, depth)
        return bits
    }

    @Test
    fun descriptorReportSizesMatchTheReportsWeSend() {
        val bits = inputBitsPerReport(HidReports.descriptor)
        assertEquals(setOf(HidReports.ID_KEYBOARD, HidReports.ID_CONSUMER), bits.keys)
        assertEquals(HidReports.KEYBOARD_SIZE * 8, bits[HidReports.ID_KEYBOARD])
        assertEquals(HidReports.CONSUMER_SIZE * 8, bits[HidReports.ID_CONSUMER])
        assertEquals(HidReports.KEYBOARD_SIZE, HidReports.keyboard().size)
        assertEquals(HidReports.CONSUMER_SIZE, HidReports.consumer(0).size)
    }

    @Test
    fun descriptorStartsAsKeyboardAndHasConsumerPage() {
        val d = HidReports.descriptor.map { it.toInt() and 0xFF }
        assertEquals(listOf(0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01), d.take(8))
        val consumer = d.indices.first { d[it] == 0x05 && d[it + 1] == 0x0C }
        assertEquals(listOf(0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02), d.subList(consumer, consumer + 8))
        // 16-bit usage maximum 0x3FF covers AC Home / AC Back
        assertTrue(d.windowed(3).any { it == listOf(0x2A, 0xFF, 0x03) })
        assertEquals(0xC0, d.last())
    }

    @Test
    fun keyboardAndConsumerReportBytes() {
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), HidReports.keyboard(0x02, 0x04))
        assertArrayEquals(byteArrayOf(0x24, 0x02), HidReports.consumer(0x224))
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0x00), HidReports.consumer(0xE9))

        val (down, up) = HidReports.pressReports(Key.Consumer(HidReports.CC_AC_BACK))
        assertEquals(HidReports.ID_CONSUMER, down.first)
        assertArrayEquals(byteArrayOf(0x24, 0x02), down.second)
        assertEquals(HidReports.ID_CONSUMER, up.first)
        assertArrayEquals(byteArrayOf(0, 0), up.second)

        val (kDown, kUp) = HidReports.pressReports(Key.Keyboard(HidReports.KB_ENTER))
        assertEquals(HidReports.ID_KEYBOARD, kDown.first)
        assertArrayEquals(byteArrayOf(0, 0, 0x28, 0, 0, 0, 0, 0), kDown.second)
        assertArrayEquals(ByteArray(8), kUp.second)
    }

    @Test
    fun remoteKeysMapToTheUsagesTvBoxesUnderstand() {
        val m = HidReports.keyMap
        assertEquals(Key.Keyboard(0x52), m[RemoteKey.UP])
        assertEquals(Key.Keyboard(0x51), m[RemoteKey.DOWN])
        assertEquals(Key.Keyboard(0x50), m[RemoteKey.LEFT])
        assertEquals(Key.Keyboard(0x4F), m[RemoteKey.RIGHT])
        assertEquals(Key.Keyboard(0x28), m[RemoteKey.OK])
        assertEquals(Key.Consumer(0x224), m[RemoteKey.BACK])
        assertEquals(Key.Consumer(0x223), m[RemoteKey.HOME])
        assertEquals(Key.Consumer(0xE9), m[RemoteKey.VOLUME_UP])
        assertEquals(Key.Consumer(0xEA), m[RemoteKey.VOLUME_DOWN])
        assertEquals(Key.Consumer(0xE2), m[RemoteKey.MUTE])
        assertEquals(Key.Consumer(0xCD), m[RemoteKey.PLAY_PAUSE])
        assertEquals(Key.Consumer(0xB5), m[RemoteKey.NEXT])
        assertEquals(Key.Consumer(0xB6), m[RemoteKey.PREVIOUS])
        assertEquals(Key.Consumer(0xB3), m[RemoteKey.FAST_FORWARD])
        assertEquals(Key.Consumer(0xB4), m[RemoteKey.REWIND])
        assertEquals(Key.Consumer(0xB7), m[RemoteKey.STOP])
        assertEquals(Key.Keyboard(0x27), m[RemoteKey.NUM_0])
        assertEquals(Key.Keyboard(0x1E), m[RemoteKey.NUM_1])
        assertEquals(Key.Keyboard(0x26), m[RemoteKey.NUM_9])
        assertFalse("power can't wake a TV box over Bluetooth", RemoteKey.POWER in m)
        // Every usage fits the descriptor's logical ranges
        for (k in m.values) when (k) {
            is Key.Keyboard -> assertTrue(k.usage in 0..0x65)
            is Key.Consumer -> assertTrue(k.usage in 1..0x3FF)
        }
    }

    @Test
    fun textUsesUsLayoutWithShift() {
        val shift = HidReports.MOD_LEFT_SHIFT
        assertEquals(Key.Keyboard(0x04), HidReports.keyFor('a'))
        assertEquals(Key.Keyboard(0x1D), HidReports.keyFor('z'))
        assertEquals(Key.Keyboard(0x04, shift), HidReports.keyFor('A'))
        assertEquals(Key.Keyboard(0x1E), HidReports.keyFor('1'))
        assertEquals(Key.Keyboard(0x27), HidReports.keyFor('0'))
        assertEquals(Key.Keyboard(0x1E, shift), HidReports.keyFor('!'))
        assertEquals(Key.Keyboard(0x1F, shift), HidReports.keyFor('@'))
        assertEquals(Key.Keyboard(0x26, shift), HidReports.keyFor('('))
        assertEquals(Key.Keyboard(0x27, shift), HidReports.keyFor(')'))
        assertEquals(Key.Keyboard(0x2C), HidReports.keyFor(' '))
        assertEquals(Key.Keyboard(0x2D), HidReports.keyFor('-'))
        assertEquals(Key.Keyboard(0x2D, shift), HidReports.keyFor('_'))
        assertEquals(Key.Keyboard(0x34), HidReports.keyFor('\''))
        assertEquals(Key.Keyboard(0x34, shift), HidReports.keyFor('"'))
        assertEquals(Key.Keyboard(0x37), HidReports.keyFor('.'))
        assertEquals(Key.Keyboard(0x38, shift), HidReports.keyFor('?'))
        assertEquals(Key.Keyboard(0x31), HidReports.keyFor('\\'))
        assertEquals(Key.Keyboard(0x35, shift), HidReports.keyFor('~'))
        assertNull(HidReports.keyFor('é'))

        assertEquals(
            listOf(Key.Keyboard(0x0B, shift), Key.Keyboard(0x0C), Key.Keyboard(0x2C), Key.Keyboard(0x1E, shift)),
            HidReports.keysFor("Hi é!"),
        )
        // Every printable ASCII character can be typed
        for (c in ' '..'~') assertTrue("'$c'", HidReports.keyFor(c) != null)
    }
}
