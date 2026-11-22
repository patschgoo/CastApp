package io.github.patschgoo.castapp.airplay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Apple's binary property lists ("bplist00"), the body format of AirPlay
 * messages. Values map to Kotlin as: Map<String, Any?>, List<Any?>, String,
 * Long, Double, Boolean, ByteArray and null.
 */
object BinaryPlist {

    // ---- writing ----------------------------------------------------------------------------

    fun encode(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        val refs = ArrayList<IntArray?>() // child references of arrays/dicts, by object index
        fun add(value: Any?): Int {
            val index = objects.size
            objects.add(value)
            refs.add(null)
            when (value) {
                is Map<*, *> -> {
                    val keys = value.keys.map { add(it.toString()) }
                    val values = value.values.map { add(it) }
                    refs[index] = (keys + values).toIntArray()
                }
                is List<*> -> refs[index] = value.map { add(it) }.toIntArray()
            }
            return index
        }
        add(root)
        val refSize = if (objects.size < 256) 1 else 2

        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = LongArray(objects.size)
        for ((i, value) in objects.withIndex()) {
            offsets[i] = out.size().toLong()
            when (value) {
                null -> out.write(0x00)
                is Boolean -> out.write(if (value) 0x09 else 0x08)
                is Int, is Long, is Short, is Byte -> writeInt(out, (value as Number).toLong())
                is Float, is Double -> {
                    out.write(0x23)
                    out.write(ByteBuffer.allocate(8).putDouble((value as Number).toDouble()).array())
                }
                is ByteArray -> {
                    writeMarker(out, 0x40, value.size)
                    out.write(value)
                }
                is String -> {
                    if (value.all { it.code < 128 }) {
                        writeMarker(out, 0x50, value.length)
                        out.write(value.toByteArray(Charsets.US_ASCII))
                    } else {
                        writeMarker(out, 0x60, value.length)
                        out.write(value.toByteArray(Charsets.UTF_16BE))
                    }
                }
                is List<*> -> {
                    writeMarker(out, 0xA0, value.size)
                    refs[i]!!.forEach { writeSized(out, it.toLong(), refSize) }
                }
                is Map<*, *> -> {
                    writeMarker(out, 0xD0, value.size)
                    refs[i]!!.forEach { writeSized(out, it.toLong(), refSize) }
                }
                else -> throw IllegalArgumentException("Can't put ${value.javaClass.simpleName} into a plist")
            }
        }
        val tableOffset = out.size().toLong()
        val offsetSize = bytesFor(tableOffset)
        offsets.forEach { writeSized(out, it, offsetSize) }
        out.write(ByteArray(6))
        out.write(offsetSize)
        out.write(refSize)
        writeSized(out, objects.size.toLong(), 8)
        writeSized(out, 0, 8)
        writeSized(out, tableOffset, 8)
        return out.toByteArray()
    }

    private fun bytesFor(v: Long) = when {
        v < 0x100 -> 1
        v < 0x10000 -> 2
        v < 0x100000000L -> 4
        else -> 8
    }

    private fun writeSized(out: ByteArrayOutputStream, v: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        val (marker, size) = when {
            v < 0 -> 0x13 to 8
            v < 0x100 -> 0x10 to 1
            v < 0x10000 -> 0x11 to 2
            v < 0x100000000L -> 0x12 to 4
            else -> 0x13 to 8
        }
        out.write(marker)
        writeSized(out, v, size)
    }

    private fun writeMarker(out: ByteArrayOutputStream, type: Int, count: Int) {
        if (count < 15) {
            out.write(type or count)
        } else {
            out.write(type or 0x0F)
            writeInt(out, count.toLong())
        }
    }

    // ---- reading ----------------------------------------------------------------------------

    fun decode(data: ByteArray): Any? {
        if (data.size < 40 || String(data, 0, 6, Charsets.US_ASCII) != "bplist") throw IOException("Not a binary plist")
        val trailer = data.size - 32
        val offsetSize = data[trailer + 6].toInt() and 0xFF
        val refSize = data[trailer + 7].toInt() and 0xFF
        val count = readSized(data, trailer + 8, 8).toInt()
        val top = readSized(data, trailer + 16, 8).toInt()
        val tableOffset = readSized(data, trailer + 24, 8).toInt()
        if (count <= 0 || tableOffset + count * offsetSize > trailer) throw IOException("Broken plist")
        val offsets = IntArray(count) { readSized(data, tableOffset + it * offsetSize, offsetSize).toInt() }

        fun read(index: Int, depth: Int): Any? {
            if (depth > 32 || index !in offsets.indices) throw IOException("Broken plist")
            var pos = offsets[index]
            val marker = data[pos].toInt() and 0xFF
            val type = marker and 0xF0
            val info = marker and 0x0F
            pos++
            fun length(): Int {
                if (info != 0x0F) return info
                val intMarker = data[pos].toInt() and 0xFF
                val size = 1 shl (intMarker and 0x0F)
                val v = readSized(data, pos + 1, size).toInt()
                pos += 1 + size
                return v
            }
            return when (type) {
                0x00 -> when (marker) {
                    0x08 -> false
                    0x09 -> true
                    else -> null
                }
                0x10 -> {
                    val size = 1 shl info
                    val v = readSized(data, pos, size)
                    if (size == 16) readSized(data, pos + 8, 8) else v
                }
                0x20 -> {
                    val size = 1 shl info
                    if (size == 4) ByteBuffer.wrap(data, pos, 4).float.toDouble() else ByteBuffer.wrap(data, pos, 8).double
                }
                0x30 -> ByteBuffer.wrap(data, pos, 8).double // date: seconds since 2001
                0x40 -> { val n = length(); data.copyOfRange(pos, pos + n) }
                0x50 -> { val n = length(); String(data, pos, n, Charsets.US_ASCII) }
                0x60 -> { val n = length(); String(data, pos, n * 2, Charsets.UTF_16BE) }
                0x80 -> readSized(data, pos, info + 1)
                0xA0, 0xC0 -> {
                    val n = length()
                    (0 until n).map { read(readSized(data, pos + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD0 -> {
                    val n = length()
                    val map = LinkedHashMap<String, Any?>()
                    for (i in 0 until n) {
                        val key = read(readSized(data, pos + i * refSize, refSize).toInt(), depth + 1).toString()
                        map[key] = read(readSized(data, pos + (n + i) * refSize, refSize).toInt(), depth + 1)
                    }
                    map
                }
                else -> throw IOException("Unknown plist object 0x${marker.toString(16)}")
            }
        }
        return read(top, 0)
    }

    private fun readSized(data: ByteArray, pos: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        return v
    }
}
