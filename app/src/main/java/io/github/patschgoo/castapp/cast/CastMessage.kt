package io.github.patschgoo.castapp.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The CastMessage protobuf used by the Google Cast v2 protocol (cast_channel.proto),
 * hand-encoded so no protobuf runtime is needed:
 *
 * ```
 * message CastMessage {
 *   required ProtocolVersion protocol_version = 1;  // CASTV2_1_0 = 0
 *   required string source_id = 2;
 *   required string destination_id = 3;
 *   required string namespace = 4;
 *   required PayloadType payload_type = 5;          // STRING = 0, BINARY = 1
 *   optional string payload_utf8 = 6;
 *   optional bytes payload_binary = 7;
 * }
 * ```
 * On the wire every message is prefixed with its length as a 4-byte big-endian integer.
 */
class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String?,
    val payloadBinary: ByteArray? = null,
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream(64 + (payloadUtf8?.length ?: 0))
        writeTag(out, 1, WIRE_VARINT); writeVarint(out, 0)
        writeString(out, 2, sourceId)
        writeString(out, 3, destinationId)
        writeString(out, 4, namespace)
        writeTag(out, 5, WIRE_VARINT); writeVarint(out, if (payloadBinary != null) 1 else 0)
        if (payloadBinary != null) {
            writeBytes(out, 7, payloadBinary)
        } else {
            writeString(out, 6, payloadUtf8 ?: "")
        }
        return out.toByteArray()
    }

    override fun toString(): String = "CastMessage($sourceId -> $destinationId, $namespace, $payloadUtf8)"

    companion object {
        private const val WIRE_VARINT = 0
        private const val WIRE_64BIT = 1
        private const val WIRE_LEN = 2
        private const val WIRE_32BIT = 5
        const val MAX_MESSAGE_SIZE = 64 * 1024

        fun decode(bytes: ByteArray): CastMessage {
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= bytes.size) throw IOException("Truncated varint")
                    val b = bytes[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IOException("Varint too long")
                }
            }

            var source = ""
            var dest = ""
            var ns = ""
            var utf8: String? = null
            var binary: ByteArray? = null
            while (pos < bytes.size) {
                val key = readVarint().toInt()
                val field = key ushr 3
                when (key and 7) {
                    WIRE_VARINT -> readVarint()
                    WIRE_64BIT -> pos += 8
                    WIRE_32BIT -> pos += 4
                    WIRE_LEN -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > bytes.size) throw IOException("Bad length")
                        val value = bytes.copyOfRange(pos, pos + len)
                        pos += len
                        when (field) {
                            2 -> source = String(value, Charsets.UTF_8)
                            3 -> dest = String(value, Charsets.UTF_8)
                            4 -> ns = String(value, Charsets.UTF_8)
                            6 -> utf8 = String(value, Charsets.UTF_8)
                            7 -> binary = value
                        }
                    }
                    else -> throw IOException("Unsupported wire type")
                }
            }
            return CastMessage(source, dest, ns, utf8, binary)
        }

        fun writeFramed(out: OutputStream, message: CastMessage) {
            val body = message.encode()
            val n = body.size
            val frame = ByteArray(4 + n)
            frame[0] = (n ushr 24).toByte(); frame[1] = (n ushr 16).toByte()
            frame[2] = (n ushr 8).toByte(); frame[3] = n.toByte()
            System.arraycopy(body, 0, frame, 4, n)
            out.write(frame)
            out.flush()
        }

        fun readFramed(input: DataInputStream): CastMessage {
            val n = input.readInt()
            if (n < 0 || n > MAX_MESSAGE_SIZE) throw IOException("Invalid message length $n")
            val body = ByteArray(n)
            input.readFully(body)
            return decode(body)
        }

        private fun writeTag(out: ByteArrayOutputStream, field: Int, wire: Int) = writeVarint(out, ((field shl 3) or wire).toLong())

        private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        private fun writeString(out: ByteArrayOutputStream, field: Int, value: String) =
            writeBytes(out, field, value.toByteArray(Charsets.UTF_8))

        private fun writeBytes(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
            writeTag(out, field, WIRE_LEN)
            writeVarint(out, value.size.toLong())
            out.write(value)
        }
    }
}
