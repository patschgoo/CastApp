package io.github.patschgoo.castapp.remote.net

import io.github.patschgoo.castapp.remote.RemoteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * Messages of the Android TV Remote protocol v2, the one Google's "Google TV" phone app
 * uses: "polo" pairing messages on port 6467 and remote messages on port 6466.
 * Field numbers follow polo.proto and remotemessage.proto of the androidtvremote2 library.
 */
object AtvMessages {
    const val STATUS_OK = 200
    const val STATUS_BAD_SECRET = 402

    private const val ROLE_INPUT = 1
    private const val ENCODING_HEXADECIMAL = 3
    private const val CODE_LENGTH = 6

    // OuterMessage fields
    const val PAIRING_REQUEST_ACK = 11
    const val OPTIONS = 20
    const val CONFIGURATION_ACK = 31
    const val SECRET_ACK = 41

    // RemoteMessage fields
    const val REMOTE_CONFIGURE = 1
    const val REMOTE_SET_ACTIVE = 2
    const val REMOTE_ERROR = 3
    const val REMOTE_PING_REQUEST = 8
    const val REMOTE_KEY_INJECT = 10
    const val REMOTE_IME_KEY_INJECT = 20
    const val REMOTE_IME_BATCH_EDIT = 21
    const val REMOTE_IME_SHOW_REQUEST = 22
    const val REMOTE_START = 40

    /** RemoteDirection.SHORT: a full press. */
    const val DIRECTION_SHORT = 3

    // Features (RemoteConfigure.code1 / RemoteSetActive.active)
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 2
    const val FEATURE_IME = 4
    const val FEATURE_POWER = 32
    const val FEATURE_VOLUME = 64
    const val FEATURE_APP_LINK = 512
    const val FEATURES = FEATURE_PING or FEATURE_KEY or FEATURE_IME or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    private fun outer(body: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().varint(1, 2).varint(2, STATUS_OK).apply(body).toByteArray()

    private fun ProtoWriter.encoding() = message(1) { varint(1, ENCODING_HEXADECIMAL); varint(2, CODE_LENGTH) }

    fun pairingRequest(clientName: String) = outer { message(10) { string(1, "atvremote"); string(2, clientName) } }

    fun options() = outer { message(OPTIONS) { encoding(); varint(3, ROLE_INPUT) } }

    fun configuration() = outer { message(30) { encoding(); varint(2, ROLE_INPUT) } }

    fun secret(secret: ByteArray) = outer { message(40) { bytes(1, secret) } }

    // proto3: fields with their default value are left out.
    private fun ProtoWriter.nz(field: Int, v: Int) { if (v != 0) varint(field, v) }
    private fun ProtoWriter.nz(field: Int, v: String) { if (v.isNotEmpty()) string(field, v) }

    fun configure(features: Int, model: String, vendor: String): ByteArray = ProtoWriter().message(REMOTE_CONFIGURE) {
        nz(1, features)
        message(2) {
            nz(1, model)
            nz(2, vendor)
            varint(3, 1)
            string(4, "1")
            string(5, "atvremote")
            string(6, "1.0.0")
        }
    }.toByteArray()

    fun setActive(features: Int): ByteArray = ProtoWriter().message(REMOTE_SET_ACTIVE) { nz(1, features) }.toByteArray()

    fun pingResponse(value: Int): ByteArray = ProtoWriter().message(9) { nz(1, value) }.toByteArray()

    fun keyInject(keyCode: Int, direction: Int = DIRECTION_SHORT): ByteArray =
        ProtoWriter().message(REMOTE_KEY_INJECT) { nz(1, keyCode); nz(2, direction) }.toByteArray()

    /** Puts [text] into the focused text field, cursor after it (like androidtvremote2's send_text). */
    fun imeBatchEdit(imeCounter: Int, fieldCounter: Int, text: String): ByteArray {
        val length = text.codePointCount(0, text.length)
        return ProtoWriter().message(REMOTE_IME_BATCH_EDIT) {
            nz(1, imeCounter)
            nz(2, fieldCounter)
            message(3) {
                varint(1, 1)
                message(2) { nz(1, length); nz(2, length); nz(3, text) }
            }
        }.toByteArray()
    }

    /** Android key codes (android.view.KeyEvent) for the remote's buttons. */
    val keyCodes: Map<RemoteKey, Int> = mapOf(
        RemoteKey.POWER to 26,
        RemoteKey.UP to 19, RemoteKey.DOWN to 20, RemoteKey.LEFT to 21, RemoteKey.RIGHT to 22, RemoteKey.OK to 23,
        RemoteKey.BACK to 4, RemoteKey.HOME to 3, RemoteKey.MENU to 82,
        RemoteKey.VOLUME_UP to 24, RemoteKey.VOLUME_DOWN to 25, RemoteKey.MUTE to 164,
        RemoteKey.CHANNEL_UP to 166, RemoteKey.CHANNEL_DOWN to 167,
        RemoteKey.PLAY_PAUSE to 85, RemoteKey.STOP to 86, RemoteKey.REWIND to 89, RemoteKey.FAST_FORWARD to 90,
        RemoteKey.PREVIOUS to 88, RemoteKey.NEXT to 87,
        RemoteKey.INPUT to 178, RemoteKey.INFO to 165, RemoteKey.GUIDE to 172, RemoteKey.SETTINGS to 176,
        RemoteKey.NUM_0 to 7, RemoteKey.NUM_1 to 8, RemoteKey.NUM_2 to 9, RemoteKey.NUM_3 to 10, RemoteKey.NUM_4 to 11,
        RemoteKey.NUM_5 to 12, RemoteKey.NUM_6 to 13, RemoteKey.NUM_7 to 14, RemoteKey.NUM_8 to 15, RemoteKey.NUM_9 to 16,
    )

    private fun unsigned(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    /** Upper-case code without spaces, or null when it isn't six hex digits. */
    fun normalizeCode(code: String): String? =
        code.filterNot { it.isWhitespace() }.uppercase().takeIf { it.length == CODE_LENGTH && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }

    /**
     * The pairing secret: SHA-256 over the client's and the TV's RSA modulus and exponent
     * (unsigned big-endian) and the last four hex digits of the code. The first two digits
     * of the code are a check: they must equal the first byte of the hash. Null if they don't.
     */
    fun pairingSecret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? {
        val c = normalizeCode(code) ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        md.update(unsigned(client.modulus))
        md.update(unsigned(client.publicExponent))
        md.update(unsigned(server.modulus))
        md.update(unsigned(server.publicExponent))
        md.update(c.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val hash = md.digest()
        return if (hash[0] == c.substring(0, 2).toInt(16).toByte()) hash else null
    }
}

/** The TV doesn't know this phone (any more): pair first. */
class NeedsPairingException(cause: Throwable? = null) : IOException("Needs pairing", cause)

/** The TV ended pairing with an error status (402: wrong code). */
class PairingRejectedException(val status: Int) : IOException("Pairing rejected: $status")

/**
 * Talks to one Android TV, Google TV, NVIDIA Shield or Chromecast with Google TV.
 * Blocking network code; call from [Dispatchers.IO].
 */
class AndroidTvClient(
    private val host: String,
    private val identity: ClientIdentity,
    private val remotePort: Int = 6466,
    private val pairPort: Int = 6467,
    private val connectTimeoutMs: Int = 4000,
) {
    /**
     * Pairs with the TV: it shows a 6-character code that [askCode] gets from the user
     * (`wrong` is true after a code that didn't match). Returns false if the user cancelled.
     */
    suspend fun pair(clientName: String, askCode: suspend (wrong: Boolean) -> String?): Boolean = withContext(Dispatchers.IO) {
        val socket = connect(pairPort, 10_000)
        socket.use { s ->
            val input = s.inputStream.buffered()
            val output = s.outputStream
            fun send(m: ByteArray) = ProtoMessage.writeDelimited(output, m)
            fun expect(field: Int): ProtoMessage {
                val raw = ProtoMessage.readDelimited(input) ?: throw EOFException("The TV ended pairing")
                val msg = ProtoMessage.parse(raw)
                val status = msg.int(2, AtvMessages.STATUS_OK)
                if (status != AtvMessages.STATUS_OK) throw PairingRejectedException(status)
                if (!msg.has(field)) throw IOException("Unexpected pairing message ${msg.fieldNumbers}")
                return msg
            }
            send(AtvMessages.pairingRequest(clientName))
            expect(AtvMessages.PAIRING_REQUEST_ACK)
            send(AtvMessages.options())
            expect(AtvMessages.OPTIONS)
            send(AtvMessages.configuration())
            expect(AtvMessages.CONFIGURATION_ACK)
            // The TV shows the code now.
            val serverKey = (s.session.peerCertificates.firstOrNull() as? X509Certificate)?.publicKey as? RSAPublicKey
                ?: throw IOException("The TV has no RSA certificate")
            val clientKey = identity.certificate.publicKey as RSAPublicKey
            var wrong = false
            var secret: ByteArray? = null
            while (secret == null) {
                val code = askCode(wrong) ?: return@withContext false
                secret = AtvMessages.pairingSecret(clientKey, serverKey, code)
                wrong = true
            }
            send(AtvMessages.secret(secret))
            expect(AtvMessages.SECRET_ACK)
            true
        }
    }

    /**
     * Opens the remote channel and waits until the TV is ready for keys.
     * Throws [NeedsPairingException] when the TV refuses the phone's certificate.
     */
    suspend fun open(model: String, vendor: String): AndroidTvConnection = withContext(Dispatchers.IO) {
        val socket = connect(remotePort, 8000)
        try {
            val conn = AndroidTvConnection(socket, model, vendor)
            conn.handshake()
            conn
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun connect(port: Int, readTimeoutMs: Int): SSLSocket {
        val plain = Tls.connectPlain(host, port, connectTimeoutMs)
        return try {
            Tls.wrap(plain, host, port, Tls.context(identity), readTimeoutMs)
        } catch (e: SocketTimeoutException) {
            try { plain.close() } catch (_: Exception) {}
            throw e
        } catch (e: IOException) {
            try { plain.close() } catch (_: Exception) {}
            // The TV checks the phone's certificate during the handshake (TLS 1.2): an alert,
            // or just a dropped connection ("broken pipe", "connection reset").
            if (port == remotePort) throw NeedsPairingException(e) else throw e
        } catch (e: Exception) {
            try { plain.close() } catch (_: Exception) {}
            throw e
        }
    }
}

/** An open remote channel (port 6466). Answers the TV's pings on its own reader thread. */
class AndroidTvConnection internal constructor(
    private val socket: SSLSocket,
    private val model: String,
    private val vendor: String,
) : Closeable {
    private val input = socket.inputStream.buffered()
    private val output = socket.outputStream
    private val writeLock = Any()
    @Volatile private var closed = false
    @Volatile private var active = AtvMessages.FEATURES
    @Volatile var imeCounter = 0
        private set
    @Volatile var fieldCounter = 0
        private set
    private var hasImeCounter = false
    private var hasFieldCounter = false
    /** The TV's answer to "is the screen on", from RemoteStart. */
    @Volatile var isOn: Boolean? = null
        private set
    /** Package of the app in front, when the TV says. */
    @Volatile var currentApp: String = ""
        private set
    /** Model and maker the TV reported. */
    @Volatile var tvModel: String = ""
        private set

    val isOpen: Boolean get() = !closed && !socket.isClosed

    private fun send(m: ByteArray) {
        synchronized(writeLock) {
            if (closed) throw IOException("Closed")
            ProtoMessage.writeDelimited(output, m)
        }
    }

    /** Reads until the TV has said it's ready (RemoteStart), answering configure and set-active. */
    internal fun handshake() {
        var gotFirst = false
        var activeSet = false
        while (true) {
            val raw = try {
                ProtoMessage.readDelimited(input)
            } catch (e: SocketTimeoutException) {
                if (activeSet) break // Some TVs never send RemoteStart; they still take keys.
                throw e
            } catch (e: IOException) {
                // TLS 1.3: a refused certificate shows up as an alert or a dropped connection on the first read.
                if (!gotFirst) throw NeedsPairingException(e)
                throw e
            }
            if (raw == null) {
                if (!gotFirst) throw NeedsPairingException()
                throw EOFException("The TV closed the connection")
            }
            gotFirst = true
            val msg = ProtoMessage.parse(raw)
            handle(msg)
            if (msg.has(AtvMessages.REMOTE_SET_ACTIVE)) {
                activeSet = true
                socket.soTimeout = 3000
            }
            if (msg.has(AtvMessages.REMOTE_START)) break
        }
        // The TV pings every 5 seconds; nothing for 20 s means the connection is gone.
        socket.soTimeout = 20_000
        Thread({ readLoop() }, "atv-remote").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val raw = ProtoMessage.readDelimited(input) ?: break
                handle(ProtoMessage.parse(raw))
            }
        } catch (_: Exception) {
        }
        close()
    }

    private fun handle(msg: ProtoMessage) {
        msg.message(AtvMessages.REMOTE_CONFIGURE)?.let { cfg ->
            val supported = cfg.int(1)
            active = AtvMessages.FEATURES and supported
            cfg.message(2)?.let { info -> tvModel = listOf(info.string(2), info.string(1)).filter { it.isNotBlank() }.joinToString(" ") }
            send(AtvMessages.configure(active, model, vendor))
        }
        if (msg.has(AtvMessages.REMOTE_SET_ACTIVE)) send(AtvMessages.setActive(active))
        msg.message(AtvMessages.REMOTE_PING_REQUEST)?.let { send(AtvMessages.pingResponse(it.int(1))) }
        msg.message(AtvMessages.REMOTE_IME_KEY_INJECT)?.let { inject ->
            inject.message(1)?.let { app ->
                currentApp = app.string(12)
                imeCounter = app.int(1)
                hasImeCounter = imeCounter != 0
            }
            fieldCounter = inject.message(2)?.int(1) ?: 0
            hasFieldCounter = fieldCounter != 0
        }
        msg.message(AtvMessages.REMOTE_IME_SHOW_REQUEST)?.message(2)?.let {
            fieldCounter = it.int(1)
            hasFieldCounter = true
        }
        msg.message(AtvMessages.REMOTE_IME_BATCH_EDIT)?.let {
            if (!hasImeCounter) imeCounter = it.int(1)
            if (!hasFieldCounter) fieldCounter = it.int(2)
        }
        msg.message(AtvMessages.REMOTE_START)?.let { isOn = it.bool(1) }
    }

    fun sendKey(keyCode: Int, direction: Int = AtvMessages.DIRECTION_SHORT) = send(AtvMessages.keyInject(keyCode, direction))

    /** Types into the focused text field. Needs the TV's keyboard feature (most TVs have it). */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        send(AtvMessages.imeBatchEdit(imeCounter, fieldCounter, text))
    }

    val supportsText: Boolean get() = active and AtvMessages.FEATURE_IME != 0

    override fun close() {
        closed = true
        try { socket.close() } catch (_: Exception) {}
    }
}
