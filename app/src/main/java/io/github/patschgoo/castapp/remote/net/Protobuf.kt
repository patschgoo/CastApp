package io.github.patschgoo.castapp.remote.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Just enough of the protobuf wire format for the Android TV remote protocol:
 * varints and length-delimited fields (strings, bytes, nested messages).
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream(64)

    private fun tag(field: Int, wireType: Int) = rawVarint((field.toLong() shl 3) or wireType.toLong())

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    /** int32, uint32, int64, enum and bool fields. Negative numbers take ten bytes, as in protobuf. */
    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        rawVarint(value)
        return this
    }

    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LENGTH)
        rawVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.toByteArray())

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = message(field, ProtoWriter().apply(build))

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5

        fun varintBytes(value: Long): ByteArray {
            val o = ByteArrayOutputStream(10)
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                o.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            o.write(v.toInt())
            return o.toByteArray()
        }
    }
}

/** A decoded message: every field with its raw value (a Long for varints, a ByteArray otherwise). */
class ProtoMessage private constructor(private val fields: List<Pair<Int, Any>>) {

    fun has(field: Int): Boolean = fields.any { it.first == field }

    fun long(field: Int, default: Long = 0): Long = fields.lastOrNull { it.first == field }?.second as? Long ?: default

    fun int(field: Int, default: Int = 0): Int = (fields.lastOrNull { it.first == field }?.second as? Long)?.toInt() ?: default

    fun bool(field: Int): Boolean = long(field) != 0L

    fun bytes(field: Int): ByteArray? = fields.lastOrNull { it.first == field }?.second as? ByteArray

    fun string(field: Int): String = bytes(field)?.toString(Charsets.UTF_8) ?: ""

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    fun messages(field: Int): List<ProtoMessage> = fields.filter { it.first == field }.mapNotNull { (it.second as? ByteArray)?.let(::parse) }

    /** Field numbers present, in order. */
    val fieldNumbers: List<Int> get() = fields.map { it.first }

    companion object {
        /** Parses [data]; throws [IOException] when it isn't valid protobuf. */
        fun parse(data: ByteArray): ProtoMessage {
            val fields = ArrayList<Pair<Int, Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 70) throw IOException("Varint too long")
                }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                if (field <= 0) throw IOException("Bad field number")
                when ((key and 7).toInt()) {
                    ProtoWriter.WIRE_VARINT -> fields += field to varint()
                    ProtoWriter.WIRE_LENGTH -> {
                        val len = varint()
                        if (len < 0 || len > data.size - pos) throw IOException("Truncated field")
                        fields += field to data.copyOfRange(pos, pos + len.toInt())
                        pos += len.toInt()
                    }
                    ProtoWriter.WIRE_FIXED64 -> { if (data.size - pos < 8) throw IOException("Truncated field"); pos += 8 }
                    ProtoWriter.WIRE_FIXED32 -> { if (data.size - pos < 4) throw IOException("Truncated field"); pos += 4 }
                    else -> throw IOException("Unsupported wire type")
                }
            }
            return ProtoMessage(fields)
        }

        /** Reads one message prefixed with its varint length; null at the end of the stream. */
        fun readDelimited(input: InputStream, maxSize: Int = 1 shl 20): ByteArray? {
            var length = 0L
            var shift = 0
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (shift == 0) return null
                    throw EOFException("Stream ended inside a length")
                }
                length = length or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) throw IOException("Bad message length")
            }
            if (length > maxSize) throw IOException("Message too large")
            val buf = ByteArray(length.toInt())
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) throw EOFException("Stream ended inside a message")
                off += n
            }
            return buf
        }

        fun writeDelimited(output: OutputStream, message: ByteArray) {
            output.write(ProtoWriter.varintBytes(message.size.toLong()) + message)
            output.flush()
        }
    }
}
