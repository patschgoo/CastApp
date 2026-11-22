package io.github.patschgoo.castapp.stream

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP/1.1 server for serving media to a Cast receiver on the local network.
 *
 * Every URL starts with a random token, so other devices on the network cannot
 * fetch anything just by guessing the address. One thread per connection; the
 * receiver only ever opens a handful.
 */
abstract class TokenHttpServer(private val preferredPort: Int, threadName: String) {

    class Request(val method: String, val path: String, val headers: Map<String, String>) {
        val isHead: Boolean get() = method == "HEAD"
    }

    val token: String = ByteArray(12).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    @Volatile var port: Int = -1
        private set
    @Volatile protected var running = false
        private set

    private var serverSocket: ServerSocket? = null
    private val threadCounter = AtomicInteger()
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "$threadName-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }
    private val openSockets = Collections.synchronizedSet(HashSet<Socket>())

    /** Binds to the preferred port (or the next free one) on all interfaces. Returns the port. */
    fun start(): Int {
        var lastError: IOException? = null
        val candidates = if (preferredPort == 0) listOf(0) else listOf(preferredPort, preferredPort + 1, preferredPort + 2, preferredPort + 3, 0)
        for (candidate in candidates) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(candidate))
                serverSocket = ss
                port = ss.localPort
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        val ss = serverSocket ?: throw lastError ?: IOException(Txt.s(R.string.err_no_server_port))
        running = true
        executor.execute { acceptLoop(ss) }
        return port
    }

    open fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        synchronized(openSockets) {
            openSockets.forEach { try { it.close() } catch (_: IOException) {} }
            openSockets.clear()
        }
        executor.shutdownNow()
    }

    fun urlFor(host: InetAddress, path: String): String {
        val h = host.hostAddress ?: "127.0.0.1"
        val hostPart = if (h.contains(':')) "[${h.substringBefore('%')}]" else h
        return "http://$hostPart:$port$path"
    }

    /** Handles a request for [subPath] (the path after "/<token>/"). */
    protected abstract fun handle(request: Request, subPath: String, socket: Socket, out: OutputStream)

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try { ss.accept() } catch (e: IOException) { break }
            openSockets.add(socket)
            try {
                executor.execute { serve(socket) }
            } catch (e: Exception) {
                closeQuietly(socket)
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val request = readRequest(input) ?: return
            when {
                request.method == "OPTIONS" -> writeHead(out, 204, "No Content", null, 0)
                request.method != "GET" && request.method != "HEAD" -> writeHead(out, 405, "Method Not Allowed", "text/plain", 0)
                !request.path.startsWith("/$token/") -> writeHead(out, 404, "Not Found", "text/plain", 0)
                else -> handle(request, request.path.removePrefix("/$token/"), socket, out)
            }
            out.flush()
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        } finally {
            closeQuietly(socket)
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
            if (head.length > 16 * 1024) return null
        }
        val lines = head.lines()
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), headers)
    }

    protected fun writeHead(
        out: OutputStream,
        code: Int,
        reason: String,
        type: String?,
        length: Long,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        for ((k, v) in extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Cache-Control: no-cache, no-store\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Type, Content-Range, Accept-Ranges\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (length == 0L) out.flush()
    }

    protected fun serveBytes(out: OutputStream, data: ByteArray, type: String, head: Boolean) {
        writeHead(out, 200, "OK", type, data.size.toLong())
        if (!head) out.write(data)
        out.flush()
    }

    protected fun notFound(out: OutputStream) = writeHead(out, 404, "Not Found", "text/plain", 0)

    private fun closeQuietly(socket: Socket) {
        openSockets.remove(socket)
        try { socket.close() } catch (_: IOException) {}
    }
}
