package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.tv.UpnpRenderer
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** A request to the peer server, already parsed. */
class PeerRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
    val remote: InetAddress?,
    /** The body was larger than [PeerServer.MAX_BODY]: read and dropped, [body] is empty. */
    val tooLarge: Boolean = false,
) {
    val json: JSONObject by lazy { try { JSONObject(body) } catch (_: Exception) { JSONObject() } }
    val remoteAddress: String get() = remote?.hostAddress ?: ""
}

class PeerResponse(val code: Int, val body: String, val type: String = "application/json; charset=utf-8") {
    companion object {
        fun json(code: Int, o: JSONObject) = PeerResponse(code, o.toString())
        fun error(code: Int, error: String, message: String = "") =
            json(code, JSONObject().put("error", error).apply { if (message.isNotEmpty()) put("message", message) })
        fun ok() = json(200, JSONObject().put("ok", true))
    }
}

/**
 * The small HTTP server every CastApp phone, tablet and TV runs for the others on the same
 * network: what is being cast here (GET /castapp/session), the host clock for synced playback
 * (GET /castapp/time), joining, hand-over and remote control. Only answers devices on the
 * local network; everything else goes to [handler].
 */
class PeerServer(
    private val handler: (PeerRequest) -> PeerResponse,
    /** The clock synced playback runs on (the same one the shared timeline uses). */
    private val clock: () -> Long,
) {
    @Volatile var port = 0
        private set
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "peer-http").apply { isDaemon = true } }

    fun start(preferredPort: Int = 0) {
        if (running) return
        val ss = try {
            ServerSocket(preferredPort)
        } catch (_: IOException) {
            ServerSocket(0)
        }
        server = ss
        port = ss.localPort
        running = true
        Thread({ acceptLoop(ss) }, "peer-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    val isRunning: Boolean get() = running

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try { ss.accept() } catch (_: IOException) { break }
            try {
                workers.execute { serve(socket) }
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.tcpNoDelay = true
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream())
                val firstLine = readLine(input) ?: return
                // The clock is read as soon as the request line is in: the time it took to arrive is the trip there.
                val received = clock()
                val request = readRequest(firstLine, input, s.inetAddress) ?: return
                val response = if (request.path == TIME_PATH && (request.method == "GET" || request.method == "POST")) {
                    if (!UpnpRenderer.isLocal(request.remote)) {
                        PeerResponse.error(403, "forbidden")
                    } else {
                        PeerResponse.json(200, JSONObject().put("t1", received).put("t2", clock()))
                    }
                } else if (!UpnpRenderer.isLocal(request.remote)) {
                    PeerResponse.error(403, "forbidden", "Only devices on the local network may ask that")
                } else if (request.tooLarge) {
                    // Said so, rather than handing on a cut-off body that fails as "bad request".
                    PeerResponse.error(413, "too_large", "The request is larger than ${MAX_BODY / 1024} KB")
                } else {
                    try {
                        handler(request)
                    } catch (e: Exception) {
                        PeerResponse.error(500, "internal", e.message ?: "")
                    }
                }
                val body = response.body.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 ${response.code} ${reason(response.code)}\r\n" +
                    "Content-Type: ${response.type}\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(head.toByteArray(Charsets.UTF_8))
                if (request.method != "HEAD") out.write(body)
                out.flush()
            } catch (_: IOException) {
            }
        }
    }

    private fun readRequest(line: String, input: InputStream, remote: InetAddress?): PeerRequest? {
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val declared = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=')
            decode(k) to decode(v)
        }
        if (declared > MAX_BODY) {
            // Read it (up to a point), so the sender gets the answer instead of a broken connection.
            var left = minOf(declared, MAX_DRAIN)
            val buffer = ByteArray(16 * 1024)
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (n < 0) break
                left -= n
            }
            return PeerRequest(parts[0].uppercase(), path, query, headers, "", remote, tooLarge = true)
        }
        val length = declared.toInt()
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return PeerRequest(parts[0].uppercase(), path, query, headers, String(body, 0, read, Charsets.UTF_8), remote)
    }

    private fun decode(s: String): String = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            if (out.size() > 8192) throw IOException("Header line too long")
            out.write(b)
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Content Too Large"
        429 -> "Too Many Requests"
        else -> if (code >= 500) "Internal Server Error" else "OK"
    }

    companion object {
        const val TIME_PATH = "/castapp/time"
        /** The largest request body taken (a hand-over offer with its play list is the biggest). */
        const val MAX_BODY = 256 * 1024
        /** How much of a larger body is read before answering 413. */
        private const val MAX_DRAIN = 16L * 1024 * 1024
    }
}
