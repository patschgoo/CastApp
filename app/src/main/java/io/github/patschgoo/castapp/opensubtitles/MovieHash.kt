package io.github.patschgoo.castapp.opensubtitles

import io.github.patschgoo.castapp.stream.MediaSource
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The OpenSubtitles "movie hash": the file size plus the 64-bit little-endian sums of the
 * first and the last 64 KiB, wrapping around, as 16 hex digits. Only two small pieces of
 * the file are read, so it is quick for files on network shares too.
 */
object MovieHash {
    const val CHUNK = 64 * 1024

    /** The hash of [source], or null when its size is unknown or it is too small (under 128 KiB). */
    fun of(source: MediaSource): String? {
        val size = source.length
        if (size < CHUNK * 2L) return null
        val head = source.open(0).use { readFully(it, CHUNK) }
        val tail = source.open(size - CHUNK).use { readFully(it, CHUNK) }
        return of(size, head, tail)
    }

    fun of(size: Long, head: ByteArray, tail: ByteArray): String {
        require(head.size == CHUNK && tail.size == CHUNK)
        val hash = size + sum(head) + sum(tail)
        return "%016x".format(java.util.Locale.ROOT, hash)
    }

    private fun sum(bytes: ByteArray): Long {
        val longs = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
        var s = 0L
        while (longs.hasRemaining()) s += longs.get()
        return s
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = input.read(out, done, n - done)
            if (r < 0) throw IOException("The file ended early")
            done += r
        }
        return out
    }
}
