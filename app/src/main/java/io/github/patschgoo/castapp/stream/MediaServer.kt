package io.github.patschgoo.castapp.stream

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** A media file the TV can read from any position. */
interface MediaSource {
    /** Total size in bytes, or -1 if unknown (seeking is then impossible). */
    val length: Long

    /** Opens the file positioned at [offset]. */
    fun open(offset: Long): InputStream
}

/**
 * Serves everything a media cast needs from the phone: files with byte-range
 * support (so the TV can seek), downscaled photos, subtitles, web videos through
 * a proxy, and converted video streams. Each thing is a route under the token.
 */
class MediaServer(preferredPort: Int) : TokenHttpServer(preferredPort, "media-http") {

    fun interface Route {
        fun handle(request: Request, socket: Socket, out: OutputStream)
    }

    private val routes = ConcurrentHashMap<String, Route>()
    private val prefixRoutes = ConcurrentHashMap<String, Route>()

    /** Registers [route] at "/<token>/[subPath]" and returns that path. */
    fun addRoute(subPath: String, route: Route): String {
        routes[subPath] = route
        return "/$token/$subPath"
    }

    /** Registers [route] for every path starting with "/<token>/[prefix]". */
    fun addPrefixRoute(prefix: String, route: Route) {
        prefixRoutes[prefix] = route
    }

    fun removeRoute(subPath: String) {
        routes.remove(subPath)
    }

    override fun handle(request: Request, subPath: String, socket: Socket, out: OutputStream) {
        val route = routes[subPath] ?: prefixRoutes.entries.firstOrNull { subPath.startsWith(it.key) }?.value
        if (route == null) return notFound(out)
        route.handle(request, socket, out)
    }

    /** A file with byte-range support. */
    fun ranged(source: MediaSource, mimeType: String) = Route { request, socket, out ->
        serveRange(request, socket, out, source, mimeType)
    }

    /** Small content produced on demand (photos, subtitles, playlists). */
    fun bytes(mimeType: String, producer: () -> ByteArray?) = Route { request, _, out ->
        val data = try { producer() } catch (_: Exception) { null }
        if (data == null) notFound(out) else serveBytes(out, data, mimeType, request.isHead, dlnaHeaders(mimeType))
    }

    /** An endless/unknown-length stream (converted video), written by [writer]. */
    fun stream(mimeType: String, writer: (OutputStream) -> Unit) = Route { request, socket, out ->
        writeHead(out, 200, "OK", mimeType, -1, dlnaHeaders(mimeType, seekable = false))
        out.flush()
        if (request.isHead) return@Route
        socket.soTimeout = 0
        try {
            writer(out)
        } catch (_: IOException) {
        }
    }

    /** Lets other routes (the proxy) write a response head with our standard headers. */
    fun writeResponseHead(out: OutputStream, code: Int, reason: String, type: String?, length: Long, extra: Map<String, String>) =
        writeHead(out, code, reason, type, length, extra)

    private fun serveRange(request: Request, socket: Socket, out: OutputStream, source: MediaSource, mimeType: String) {
        socket.soTimeout = 0
        val length = source.length
        val range = request.headers["range"]?.let { parseRange(it, length) }

        if (length < 0) {
            // Unknown size: stream everything, no seeking.
            writeHead(out, 200, "OK", mimeType, -1, dlnaHeaders(mimeType, seekable = false))
            if (!request.isHead) source.open(0).use { copy(it, out, Long.MAX_VALUE) }
            return
        }
        if (range == INVALID_RANGE) {
            writeHead(out, 416, "Range Not Satisfiable", mimeType, 0, mapOf("Content-Range" to "bytes */$length"))
            return
        }
        val headers = dlnaHeaders(mimeType) + ("Accept-Ranges" to "bytes")
        if (range == null) {
            writeHead(out, 200, "OK", mimeType, length, headers)
            if (!request.isHead) source.open(0).use { copy(it, out, length) }
            return
        }
        val (start, end) = range
        val count = end - start + 1
        writeHead(out, 206, "Partial Content", mimeType, count, headers + ("Content-Range" to "bytes $start-$end/$length"))
        if (!request.isHead) source.open(start).use { copy(it, out, count) }
    }

    private fun serveBytes(out: OutputStream, data: ByteArray, type: String, head: Boolean, extra: Map<String, String>) {
        writeHead(out, 200, "OK", type, data.size.toLong(), extra)
        if (!head) out.write(data)
        out.flush()
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

        /** A file name that is safe in a URL path; keeps the extension (some TVs look at it). */
        fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "media" }

        /**
         * Extra headers that DLNA TVs (Samsung, LG, ...) expect; Cast devices ignore them.
         */
        fun dlnaHeaders(mimeType: String, seekable: Boolean = true): Map<String, String> {
            val image = mimeType.startsWith("image/")
            val flags = if (seekable) "DLNA.ORG_OP=01;" else "DLNA.ORG_OP=00;"
            return mapOf(
                "transferMode.dlna.org" to if (image) "Interactive" else "Streaming",
                "contentFeatures.dlna.org" to "${flags}DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            )
        }

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
