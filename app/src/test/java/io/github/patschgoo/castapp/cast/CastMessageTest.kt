package io.github.patschgoo.castapp.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class CastMessageTest {
    @Test
    fun encodesExactProtobufBytes() {
        val msg = CastMessage("s", "d", "n", "{}")
        val expected = byteArrayOf(
            0x08, 0x00, // protocol_version = 0
            0x12, 0x01, 's'.code.toByte(),
            0x1a, 0x01, 'd'.code.toByte(),
            0x22, 0x01, 'n'.code.toByte(),
            0x28, 0x00, // payload_type = STRING
            0x32, 0x02, '{'.code.toByte(), '}'.code.toByte(),
        )
        assertArrayEquals(expected, msg.encode())
    }

    @Test
    fun roundTripsThroughFraming() {
        val payload = "{\"type\":\"LAUNCH\",\"appId\":\"CC1AD845\",\"requestId\":7,\"text\":\"" + "x".repeat(300) + "\"}"
        val out = ByteArrayOutputStream()
        CastMessage.writeFramed(out, CastMessage("sender-0", "receiver-0", CastClient.NS_RECEIVER, payload))
        val bytes = out.toByteArray()
        // 4-byte big-endian length prefix
        val len = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        assertEquals(bytes.size - 4, len)

        val decoded = CastMessage.readFramed(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals("sender-0", decoded.sourceId)
        assertEquals("receiver-0", decoded.destinationId)
        assertEquals(CastClient.NS_RECEIVER, decoded.namespace)
        assertEquals(payload, decoded.payloadUtf8)
    }

    @Test
    fun decodeSkipsUnknownFields() {
        val known = CastMessage("a", "b", "c", "x").encode()
        // field 15, varint 300 (unknown) appended
        val withUnknown = known + byteArrayOf(0x78, 0xAC.toByte(), 0x02)
        val decoded = CastMessage.decode(withUnknown)
        assertEquals("x", decoded.payloadUtf8)
        assertEquals("c", decoded.namespace)
    }
}
