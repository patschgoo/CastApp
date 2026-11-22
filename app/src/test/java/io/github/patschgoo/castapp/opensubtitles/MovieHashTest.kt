package io.github.patschgoo.castapp.opensubtitles

import io.github.patschgoo.castapp.stream.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MovieHashTest {
    /** A file in memory that, like a network share, can only be opened at an offset. */
    private class BytesSource(private val bytes: ByteArray) : MediaSource {
        val opened = ArrayList<Long>()
        override val length: Long get() = bytes.size.toLong()
        override fun open(offset: Long): InputStream {
            opened += offset
            return ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
    }

    private fun lcgBytes(size: Int): ByteArray {
        var x = 42L
        return ByteArray(size) {
            x = x * 6364136223846793005L + 1442695040888963407L
            (x ushr 56).toByte()
        }
    }

    @Test
    fun zerosHashToTheSize() {
        assertEquals("0000000000020000", MovieHash.of(BytesSource(ByteArray(131_072))))
        assertEquals("0000000000030d40", MovieHash.of(BytesSource(ByteArray(200_000))))
    }

    @Test
    fun sumsWrapAround() {
        // Every 64-bit word is 0xFFFF_FFFF_FFFF_FFFF = -1: 131072 - 8192 - 8192 = 114688 = 0x1c000.
        val ones = ByteArray(131_072) { 0xFF.toByte() }
        assertEquals("000000000001c000", MovieHash.of(BytesSource(ones)))
    }

    @Test
    fun matchesAnIndependentImplementation() {
        // Expected value computed with the reference Python algorithm (struct.unpack('<8192Q')) on the same bytes.
        val bytes = lcgBytes(300_007)
        val source = BytesSource(bytes)
        assertEquals("c39d9d5a54b68c41", MovieHash.of(source))
        // Only the first and the last 64 KiB are read.
        assertEquals(listOf(0L, 300_007L - 65_536), source.opened)
    }

    @Test
    fun lowByteFirst() {
        val bytes = ByteArray(131_072)
        bytes[0] = 1 // word 1 at the start
        bytes[131_072 - 8 + 1] = 1 // 0x100 in the last word
        assertEquals("0000000000020101", MovieHash.of(BytesSource(bytes)))
    }

    @Test
    fun smallOrUnknownFilesHaveNoHash() {
        assertNull(MovieHash.of(BytesSource(ByteArray(131_071))))
        val unknown = object : MediaSource {
            override val length = -1L
            override fun open(offset: Long): InputStream = throw AssertionError("must not read")
        }
        assertNull(MovieHash.of(unknown))
    }
}
