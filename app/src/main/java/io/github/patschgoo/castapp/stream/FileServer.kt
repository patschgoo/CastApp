package io.github.patschgoo.castapp.stream

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** A media file the TV can read from any position. */
interface MediaSource {
    /** Total size in bytes, or -1 if unknown (seeking is then impossible). */
    val length: Long

    /** Opens the file positioned at [offset]. */
    fun open(offset: Long): InputStream
}

/**
 * Serves one media file (video, audio) to the Cast receiver with HTTP range
 * support, so the TV can seek and read the index at the end of MP4 files.
 * Because the TV pulls the file itself, the phone's screen can be off.
 */
class FileServer(
    private val source: MediaSource,
    private val mimeType: String,
    fileName: String,
    preferredPort: Int,
) : TokenHttpServer(preferredPort, "file-http") {

    private val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "media" }

    val mediaPath: String get() = "/$token/media/$safeName"

    override fun handle(request: Request, subPath: String, socket: Socket, out: OutputStream) {
        if (subPath != "media/$safeName") return notFound(out)
        socket.soTimeout = 0
        val length = source.length
        val range = request.headers["range"]?.let { parseRange(it, length) }

        if (length < 0) {
            // Unknown size: stream everything, no seeking.
            writeHead(out, 200, "OK", mimeType, -1)
            if (!request.isHead) source.open(0).use { copy(it, out, Long.MAX_VALUE) }
            return
        }
        if (range == INVALID_RANGE) {
            writeHead(out, 416, "Range Not Satisfiable", mimeType, 0, mapOf("Content-Range" to "bytes */$length"))
            return
        }
        val acceptRanges = mapOf("Accept-Ranges" to "bytes")
        if (range == null) {
            writeHead(out, 200, "OK", mimeType, length, acceptRanges)
            if (!request.isHead) source.open(0).use { copy(it, out, length) }
            return
        }
        val (start, end) = range
        val count = end - start + 1
        writeHead(
            out, 206, "Partial Content", mimeType, count,
            acceptRanges + ("Content-Range" to "bytes $start-$end/$length"),
        )
        if (!request.isHead) source.open(start).use { copy(it, out, count) }
    }

    private fun copy(input: InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = count
        try {
            while (remaining > 0 && running) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
            out.flush()
        } catch (_: IOException) {
            // The TV closed the connection (seeking, stopping); that's normal.
        }
    }

    companion object {
        val INVALID_RANGE = -1L to -1L

        /**
         * Parses a single "bytes=" range against a file of [length] bytes.
         * Returns null to serve the whole file, [INVALID_RANGE] if unsatisfiable.
         */
        fun parseRange(header: String, length: Long): Pair<Long, Long>? {
            val spec = header.trim()
            if (!spec.startsWith("bytes=", ignoreCase = true) || length < 0) return null
            val first = spec.substring(6).split(',').first().trim()
            val dash = first.indexOf('-')
            if (dash < 0) return null
            val startText = first.substring(0, dash).trim()
            val endText = first.substring(dash + 1).trim()
            return if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull() ?: return null
                if (suffix <= 0) return INVALID_RANGE
                maxOf(0, length - suffix) to length - 1
            } else {
                val start = startText.toLongOrNull() ?: return null
                if (start >= length) return INVALID_RANGE
                val end = endText.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
                if (end < start) return INVALID_RANGE
                start to end
            }
        }
    }
}
