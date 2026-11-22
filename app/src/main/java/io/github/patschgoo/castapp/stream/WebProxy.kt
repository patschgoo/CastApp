package io.github.patschgoo.castapp.stream

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64

/**
 * Relays web videos through the phone. Many sites only hand out their videos with
 * the right cookies/referrer, and HLS streams need CORS headers that a Cast
 * device's player insists on. The proxy adds both, and rewrites HLS playlists so
 * every segment also goes through it.
 */
class WebProxy(private val server: MediaServer, @Volatile var headers: Map<String, String> = emptyMap()) {

    init {
        server.addPrefixRoute(PREFIX) { request, socket, out -> handle(request, socket, out) }
    }

    /** Path on our server that serves [url] through the proxy. */
    fun pathFor(url: String): String {
        val name = url.substringBefore('?').substringAfterLast('/').let(MediaServer::safeName).take(60)
        return "/${server.token}/$PREFIX" + ENCODER.encodeToString(url.toByteArray()) + "/" + name
    }

    private fun handle(request: TokenHttpServer.Request, socket: java.net.Socket, out: OutputStream) {
        val encoded = request.path.substringAfter("/$PREFIX").substringBefore('/')
        val target = try { String(DECODER.decode(encoded)) } catch (_: Exception) { null }
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            server.writeResponseHead(out, 404, "Not Found", "text/plain", 0, emptyMap())
            return
        }
        socket.soTimeout = 0
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = if (request.isHead) "HEAD" else "GET"
                for ((k, v) in headers) setRequestProperty(k, v)
                request.headers["range"]?.let { setRequestProperty("Range", it) }
            }
            val code = conn.responseCode
            val type = conn.contentType
            if (code in 200..299 && HlsRewriter.isPlaylist(target, type)) {
                val text = conn.inputStream.use { readLimited(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)
                val base = URI(conn.url.toString())
                val rewritten = HlsRewriter.rewrite(text, base) { pathFor(it) }.toByteArray()
                server.writeResponseHead(out, 200, "OK", "application/vnd.apple.mpegurl", rewritten.size.toLong(), emptyMap())
                if (!request.isHead) out.write(rewritten)
                out.flush()
                return
            }
            val extra = LinkedHashMap<String, String>()
            conn.getHeaderField("Content-Range")?.let { extra["Content-Range"] = it }
            conn.getHeaderField("Accept-Ranges")?.let { extra["Accept-Ranges"] = it }
            val length = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val reason = when (code) { 200 -> "OK"; 206 -> "Partial Content"; else -> "Upstream $code" }
            server.writeResponseHead(out, code, reason, type, length, extra)
            if (!request.isHead && code < 400) {
                val input = conn.inputStream
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            out.flush()
        } catch (_: IOException) {
            try { server.writeResponseHead(out, 502, "Bad Gateway", "text/plain", 0, emptyMap()) } catch (_: IOException) {}
        } finally {
            conn?.disconnect()
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val PREFIX = "p/"
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
    }
}

/** Rewrites every URL in an HLS playlist (segments, keys, init sections, variants). */
object HlsRewriter {
    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")

    fun isPlaylist(url: String, contentType: String?): Boolean =
        contentType?.contains("mpegurl", ignoreCase = true) == true ||
            url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    fun rewrite(playlist: String, base: URI, mapper: (String) -> String): String {
        val out = StringBuilder(playlist.length + 1024)
        for (raw in playlist.lineSequence()) {
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> out.append(line)
                trimmed.startsWith("#") -> out.append(
                    URI_ATTR.replace(line) { m -> "URI=\"" + mapper(resolve(base, m.groupValues[1])) + "\"" },
                )
                else -> out.append(mapper(resolve(base, trimmed)))
            }
            out.append('\n')
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    fun resolve(base: URI, ref: String): String = try {
        base.resolve(URI(ref.replace(" ", "%20"))).toString()
    } catch (_: Exception) {
        if (ref.startsWith("http")) ref else base.toString().substringBeforeLast('/') + "/" + ref
    }
}
