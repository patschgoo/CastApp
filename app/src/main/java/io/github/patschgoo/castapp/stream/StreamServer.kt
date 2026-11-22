package io.github.patschgoo.castapp.stream

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small HTTP/1.1 server that hands the live stream to the Cast receiver.
 *
 * Every URL contains a random token, so other devices on the network cannot
 * watch the screen just by guessing the address.
 *
 *  - /<token>/stream.mp4       progressive fragmented MP4 (lowest latency)
 *  - /<token>/hls/live.m3u8    HLS playlist (compatibility mode)
 *  - /<token>/hls/init.mp4, /<token>/hls/segN.m4s
 */
class StreamServer(private val hub: StreamHub, private val preferredPort: Int) {

    interface Listener {
        fun onClientConnected(remote: String) {}
        fun onClientDisconnected(remote: String) {}
    }

    val token: String = ByteArray(12).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    @Volatile var port: Int = -1
        private set
    @Volatile var listener: Listener? = null
    @Volatile private var hls: HlsSegmenter? = null

    private var serverSocket: ServerSocket? = null
    private val threadCounter = AtomicInteger()
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "stream-http-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }
    private val openSockets = Collections.synchronizedSet(HashSet<Socket>())
    private val liveClients = Collections.synchronizedSet(HashSet<LiveClientQueue>())
    @Volatile private var running = false

    val mp4Path: String get() = "/$token/stream.mp4"
    val hlsPath: String get() = "/$token/hls/live.m3u8"

    val activeStreamClients: Int get() = liveClients.size

    /** Binds to [preferredPort] (or the next free one) on all interfaces. Returns the port. */
    fun start(): Int {
        var lastError: IOException? = null
        for (candidate in listOf(preferredPort, preferredPort + 1, preferredPort + 2, preferredPort + 3, 0)) {
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
        val ss = serverSocket ?: throw lastError ?: IOException("Could not open a server port")
        running = true
        executor.execute { acceptLoop(ss) }
        return port
    }

    fun urlFor(host: InetAddress, path: String): String {
        val h = host.hostAddress ?: "127.0.0.1"
        val hostPart = if (h.contains(':')) "[${h.substringBefore('%')}]" else h
        return "http://$hostPart:$port$path"
    }

    /** Starts the HLS segmenter (idempotent) and returns it. */
    fun enableHls(): HlsSegmenter {
        hls?.let { return it }
        val segmenter = HlsSegmenter(hub)
        hls = segmenter
        hub.subscribe(segmenter)
        return segmenter
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        hls?.let { hub.unsubscribe(it) }
        hls = null
        synchronized(liveClients) { liveClients.forEach { it.close() } }
        synchronized(openSockets) {
            openSockets.forEach { try { it.close() } catch (_: IOException) {} }
            openSockets.clear()
        }
        executor.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            openSockets.add(socket)
            try {
                executor.execute { handle(socket) }
            } catch (e: Exception) {
                closeQuietly(socket)
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val request = readRequest(input) ?: return
            route(request, socket, out)
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        } finally {
            closeQuietly(socket)
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A || head.endsWith("\n\n")) break
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
        val path = parts[1].substringBefore('?')
        return Request(parts[0].uppercase(), path, headers)
    }

    private fun route(req: Request, socket: Socket, out: OutputStream) {
        if (req.method == "OPTIONS") {
            writeHead(out, 204, "No Content", null, 0)
            return
        }
        if (req.method != "GET" && req.method != "HEAD") {
            writeHead(out, 405, "Method Not Allowed", "text/plain", 0)
            return
        }
        val prefix = "/$token/"
        if (!req.path.startsWith(prefix)) {
            writeHead(out, 404, "Not Found", "text/plain", 0)
            return
        }
        val sub = req.path.removePrefix(prefix)
        val head = req.method == "HEAD"
        when {
            sub == "stream.mp4" -> serveLiveMp4(socket, out, head)
            sub == "hls/live.m3u8" -> {
                val segmenter = hls
                if (segmenter == null) writeHead(out, 404, "Not Found", "text/plain", 0)
                else serveBytes(out, segmenter.playlist().toByteArray(), "application/vnd.apple.mpegurl", head)
            }
            sub == "hls/init.mp4" -> {
                val init = hub.currentInitSegment()
                if (init == null) writeHead(out, 404, "Not Found", "text/plain", 0)
                else serveBytes(out, init, "video/mp4", head)
            }
            sub.startsWith("hls/seg") && sub.endsWith(".m4s") -> {
                val index = sub.removePrefix("hls/seg").removeSuffix(".m4s").toLongOrNull()
                val data = index?.let { hls?.segment(it) }
                if (data == null) writeHead(out, 404, "Not Found", "text/plain", 0)
                else serveBytes(out, data, "video/mp4", head)
            }
            else -> writeHead(out, 404, "Not Found", "text/plain", 0)
        }
    }

    private fun serveBytes(out: OutputStream, data: ByteArray, type: String, head: Boolean) {
        writeHead(out, 200, "OK", type, data.size.toLong())
        if (!head) out.write(data)
        out.flush()
    }

    private fun serveLiveMp4(socket: Socket, out: OutputStream, head: Boolean) {
        val init = hub.awaitInitSegment(10_000)
        if (init == null) {
            writeHead(out, 503, "Service Unavailable", "text/plain", 0)
            return
        }
        writeHead(out, 200, "OK", "video/mp4", -1)
        if (head) {
            out.flush()
            return
        }
        out.write(init)
        out.flush()

        val remote = socket.remoteSocketAddress?.toString() ?: "?"
        val queue = LiveClientQueue(hub)
        val serializer = Mp4FragmentSerializer(hub.publishedAudioRate)
        liveClients.add(queue)
        hub.subscribe(queue)
        listener?.onClientConnected(remote)
        try {
            socket.soTimeout = 0
            while (running && !queue.isClosed) {
                val item = queue.take(1000) ?: continue
                val bytes = serializer.serialize(item.sample, item.discontinuity) ?: continue
                out.write(bytes)
                out.flush()
            }
        } catch (_: SocketException) {
        } finally {
            hub.unsubscribe(queue)
            queue.close()
            liveClients.remove(queue)
            listener?.onClientDisconnected(remote)
        }
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, type: String?, length: Long) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Cache-Control: no-cache, no-store\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Type\r\n")
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (length == 0L) out.flush()
    }

    private fun closeQuietly(socket: Socket) {
        openSockets.remove(socket)
        try { socket.close() } catch (_: IOException) {}
    }
}
