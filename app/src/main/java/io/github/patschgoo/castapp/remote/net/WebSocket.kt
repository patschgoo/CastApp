package io.github.patschgoo.castapp.remote.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/** The server answered the WebSocket upgrade with something else than 101. */
class WebSocketHandshakeException(val status: Int, message: String) : IOException(message)

/**
 * A small RFC 6455 WebSocket client for TV remotes (Samsung, LG): text and binary
 * messages, client masking, fragmented messages, ping/pong and the closing handshake.
 * `wss://` accepts the TV's self-signed certificate. Blocking; use from a background thread.
 */
class WebSocket private constructor(private val socket: Socket, private val input: InputStream, private val output: OutputStream) : Closeable {
    private val writeLock = Any()
    private val random = SecureRandom()
    @Volatile private var closeSent = false
    @Volatile var isClosed = false
        private set

    /** Close code the server sent, if any. */
    @Volatile var closeCode: Int? = null
        private set

    var readTimeoutMs: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }

    fun sendText(text: String) = send(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun sendBinary(data: ByteArray) = send(OP_BINARY, data)

    fun ping(data: ByteArray = ByteArray(0)) = send(OP_PING, data)

    private fun send(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val frame = encodeFrame(opcode, payload, mask)
        synchronized(writeLock) {
            if (isClosed || closeSent) throw IOException("WebSocket is closed")
            output.write(frame)
            output.flush()
        }
    }

    /** A received message: [text] for text messages, [data] for binary ones. */
    class Message(val text: String?, val data: ByteArray?)

    /**
     * The next message. Answers pings and the server's close on its own.
     * Null once the connection is closed.
     */
    fun receive(): Message? {
        var opcode = -1
        val buffer = ByteArrayOutputStream()
        while (true) {
            val frame = try {
                readFrame(input)
            } catch (e: EOFException) {
                markClosed()
                return null
            } ?: run { markClosed(); return null }
            when (frame.opcode) {
                OP_PING -> { try { send(OP_PONG, frame.payload) } catch (_: IOException) {} }
                OP_PONG -> {}
                OP_CLOSE -> {
                    closeCode = if (frame.payload.size >= 2) ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF) else 1005
                    if (!closeSent) {
                        try { sendClose(closeCode.takeIf { it != 1005 } ?: 1000) } catch (_: IOException) {}
                    }
                    markClosed()
                    return null
                }
                OP_CONTINUATION -> {
                    if (opcode < 0) throw IOException("Continuation without a start")
                    buffer.write(frame.payload)
                }
                OP_TEXT, OP_BINARY -> {
                    if (opcode >= 0) throw IOException("New message inside a fragmented one")
                    opcode = frame.opcode
                    buffer.write(frame.payload)
                }
                else -> throw IOException("Unknown opcode ${frame.opcode}")
            }
            if (buffer.size() > MAX_MESSAGE) throw IOException("Message too large")
            if (frame.fin && opcode >= 0 && frame.opcode !in CONTROL) {
                val bytes = buffer.toByteArray()
                return if (opcode == OP_TEXT) Message(bytes.toString(Charsets.UTF_8), null) else Message(null, bytes)
            }
        }
    }

    /** The next text message (binary ones are skipped); null when closed. */
    fun receiveText(): String? {
        while (true) {
            val m = receive() ?: return null
            if (m.text != null) return m.text
        }
    }

    private fun sendClose(code: Int) {
        val payload = byteArrayOf((code ushr 8).toByte(), code.toByte())
        val mask = ByteArray(4).also { random.nextBytes(it) }
        synchronized(writeLock) {
            if (closeSent || isClosed) return
            closeSent = true
            output.write(encodeFrame(OP_CLOSE, payload, mask))
            output.flush()
        }
    }

    private fun markClosed() {
        isClosed = true
        try { socket.close() } catch (_: Exception) {}
    }

    /** Sends a close frame (1000, normal) and closes the connection. */
    override fun close() {
        if (isClosed) return
        try { sendClose(1000) } catch (_: Exception) {}
        markClosed()
    }

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    companion object {
        const val OP_CONTINUATION = 0
        const val OP_TEXT = 1
        const val OP_BINARY = 2
        const val OP_CLOSE = 8
        const val OP_PING = 9
        const val OP_PONG = 10
        private val CONTROL = setOf(OP_CLOSE, OP_PING, OP_PONG)
        private const val MAX_MESSAGE = 16 shl 20
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Sec-WebSocket-Accept for a Sec-WebSocket-Key (RFC 6455 section 4.2.2). */
        fun acceptKey(key: String): String =
            Base64.Default.encode(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))

        /** One frame; [mask] is required for frames a client sends, null for server frames. */
        fun encodeFrame(opcode: Int, payload: ByteArray, mask: ByteArray?, fin: Boolean = true): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 14)
            out.write((if (fin) 0x80 else 0) or (opcode and 0x0F))
            val maskBit = if (mask != null) 0x80 else 0
            when {
                payload.size < 126 -> out.write(maskBit or payload.size)
                payload.size <= 0xFFFF -> {
                    out.write(maskBit or 126)
                    out.write(payload.size ushr 8)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(maskBit or 127)
                    val len = payload.size.toLong()
                    for (i in 7 downTo 0) out.write(((len ushr (8 * i)) and 0xFF).toInt())
                }
            }
            if (mask != null) {
                out.write(mask)
                val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
                out.write(masked)
            } else {
                out.write(payload)
            }
            return out.toByteArray()
        }

        /** Reads one frame (unmasking it if masked); null at a clean end of stream. */
        fun readFrame(input: InputStream): Frame? {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            if (b1 < 0) throw EOFException()
            if (b0 and 0x70 != 0) throw IOException("Reserved bits set")
            val opcode = b0 and 0x0F
            val fin = b0 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = readN(input, 2).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) }
            else if (len == 127L) len = readN(input, 8).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) }
            if (len < 0 || len > MAX_MESSAGE) throw IOException("Frame too large")
            if (opcode in CONTROL && (len > 125 || !fin)) throw IOException("Bad control frame")
            val mask = if (b1 and 0x80 != 0) readN(input, 4) else null
            val payload = readN(input, len.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            return Frame(fin, opcode, payload)
        }

        private fun readN(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            return buf
        }

        /**
         * Connects to a `ws://` or `wss://` URL and does the opening handshake.
         * Throws [WebSocketHandshakeException] when the server doesn't switch protocols.
         */
        fun connect(
            url: String,
            connectTimeoutMs: Int = 5000,
            readTimeoutMs: Int = 10_000,
            headers: Map<String, String> = emptyMap(),
        ): WebSocket {
            val uri = URI(url)
            val secure = when (uri.scheme?.lowercase()) {
                "ws" -> false
                "wss" -> true
                else -> throw IOException("Not a WebSocket URL: $url")
            }
            val host = uri.host ?: throw IOException("No host in $url")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val plain = Tls.connectPlain(host, port, connectTimeoutMs)
            var socket: Socket = plain
            try {
                plain.soTimeout = readTimeoutMs
                if (secure) socket = Tls.wrap(plain, host, port, Tls.context(), readTimeoutMs)
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                val key = Base64.Default.encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val hostHeader = if (host.contains(':')) "[$host]" else host
                val defaultPort = if (secure) 443 else 80
                val request = buildString {
                    append("GET ").append(path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(hostHeader).append(if (port != defaultPort) ":$port" else "").append("\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
                    append("\r\n")
                }
                output.write(request.toByteArray(Charsets.UTF_8))
                output.flush()
                val head = readHead(input)
                val lines = head.split("\r\n")
                val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (status != 101) throw WebSocketHandshakeException(status, "WebSocket refused: ${lines.first()}")
                val responseHeaders = lines.drop(1).filter { ':' in it }
                    .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                if (responseHeaders["sec-websocket-accept"] != acceptKey(key)) throw WebSocketHandshakeException(status, "Bad Sec-WebSocket-Accept")
                return WebSocket(socket, input, output)
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                try { plain.close() } catch (_: Exception) {}
                throw e
            }
        }

        private fun readHead(input: InputStream): String {
            val out = ByteArrayOutputStream(512)
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("Connection closed during the WebSocket handshake")
                out.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (out.size() > 16 * 1024) throw IOException("Handshake response too long")
            }
            return out.toString("ISO-8859-1").trimEnd()
        }
    }
}
