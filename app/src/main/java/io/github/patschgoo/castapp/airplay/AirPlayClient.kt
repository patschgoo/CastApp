package io.github.patschgoo.castapp.airplay

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** What remembers a paired Apple TV: our identity and the TV's. */
class AirPlayCredentials(
    val clientId: String,
    /** Our long-term Ed25519 secret key. */
    val secret: ByteArray,
    val deviceId: ByteArray,
    /** The TV's long-term Ed25519 public key. */
    val devicePublic: ByteArray,
) {
    fun toJson(): String = JSONObject()
        .put("c", clientId)
        .put("s", hex(secret))
        .put("d", hex(deviceId))
        .put("p", hex(devicePublic))
        .toString()

    companion object {
        fun fromJson(json: String): AirPlayCredentials? = try {
            val o = JSONObject(json)
            AirPlayCredentials(o.getString("c"), unhex(o.getString("s")), unhex(o.getString("d")), unhex(o.getString("p")))
        } catch (_: Exception) {
            null
        }

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** Where pairings are kept (encrypted on the phone). */
interface AirPlayPairingStore {
    fun load(deviceId: String): AirPlayCredentials?
    fun save(deviceId: String, credentials: AirPlayCredentials)
    fun clear(deviceId: String)
}

/** The device wants the code shown on screen (it doesn't allow pairing-free access). */
class PinRequiredException(message: String) : IOException(message)

/** A plain or encrypted byte stream over a socket. */
class HapStream(private val socket: Socket) : Closeable {
    private val rawIn: InputStream = BufferedInputStream(socket.getInputStream())
    private val rawOut: OutputStream = socket.getOutputStream()
    @Volatile var session: HapSession? = null
    private var buffer = ByteArray(0)
    private var bufferPos = 0

    fun write(data: ByteArray) {
        val s = session
        rawOut.write(if (s != null) s.encrypt(data) else data)
        rawOut.flush()
    }

    private fun fill(): Boolean {
        val s = session
        if (s == null) {
            val b = rawIn.read()
            if (b < 0) return false
            buffer = byteArrayOf(b.toByte())
        } else {
            buffer = s.readFrame(rawIn) ?: return false
        }
        bufferPos = 0
        return true
    }

    fun read(): Int {
        if (bufferPos >= buffer.size && !fill()) return -1
        return buffer[bufferPos++].toInt() and 0xFF
    }

    fun readLine(): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            if (out.size() > 16_384) throw IOException("Header line too long")
            out.write(b)
        }
    }

    fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        for (i in 0 until n) {
            val b = read()
            if (b < 0) throw IOException("Connection closed")
            out[i] = b.toByte()
        }
        return out
    }

    /** Reads a header block (first line + headers, lower-case keys) and its body. */
    fun readMessage(): Triple<String, Map<String, String>, ByteArray>? {
        var first = readLine() ?: return null
        while (first.isEmpty()) first = readLine() ?: return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 8 * 1024 * 1024) ?: 0
        return Triple(first, headers, if (length > 0) readExactly(length) else ByteArray(0))
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }
}

/** One TCP connection to an AirPlay device (port 7000), speaking HTTP and RTSP. */
class AirPlayConnection(val host: String, val port: Int, connectTimeoutMs: Int = 6000) : Closeable {
    private val socket = Socket().also { s ->
        // (Not "apply": inside it, "port" would be the unconnected socket's own port.)
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = 15_000
        s.tcpNoDelay = true
    }
    private val stream = HapStream(socket)
    val localAddress: InetAddress get() = socket.localAddress

    class Response(val code: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray) {
        @Suppress("UNCHECKED_CAST")
        fun plist(): Map<String, Any?> = try {
            if (body.isEmpty()) emptyMap() else BinaryPlist.decode(body) as? Map<String, Any?> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val ok: Boolean get() = code in 200..299
    }

    fun enableEncryption(outKey: ByteArray, inKey: ByteArray) {
        stream.session = HapSession(outKey, inKey)
    }

    val encrypted: Boolean get() = stream.session != null

    @Synchronized
    fun request(
        method: String,
        uri: String,
        protocol: String = "HTTP/1.1",
        headers: Map<String, Any> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        userAgent: String = "AirPlay/550.10",
    ): Response {
        val msg = StringBuilder("$method $uri $protocol")
        if (headers.keys.none { it.equals("User-Agent", true) }) msg.append("\r\nUser-Agent: ").append(userAgent)
        if (contentType != null) msg.append("\r\nContent-Type: ").append(contentType)
        if (body != null && body.isNotEmpty()) msg.append("\r\nContent-Length: ").append(body.size)
        for ((k, v) in headers) msg.append("\r\n").append(k).append(": ").append(v)
        msg.append("\r\n\r\n")
        val bytes = msg.toString().toByteArray(Charsets.UTF_8)
        stream.write(if (body != null && body.isNotEmpty()) bytes + body else bytes)
        val (status, respHeaders, respBody) = stream.readMessage() ?: throw IOException("Connection closed by $host")
        val parts = status.split(' ', limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad answer: $status")
        return Response(code, parts.getOrNull(2).orEmpty(), respHeaders, respBody)
    }

    override fun close() = stream.close()
}

/**
 * HomeKit-style pairing as AirPlay uses it (the same steps pyatv uses with real
 * Apple TVs): transient pairing for TVs that let anyone on the network cast,
 * pairing with the code shown on the TV, and verifying a stored pairing.
 */
object AirPlayPairing {
    private fun headers(hkp: Int) = mapOf(
        "User-Agent" to "AirPlay/320.20",
        "Connection" to "keep-alive",
        "X-Apple-HKP" to hkp,
        "Content-Type" to "application/octet-stream",
    )

    private fun post(c: AirPlayConnection, path: String, hkp: Int, body: ByteArray): Map<Int, ByteArray> {
        val resp = c.request("POST", path, headers = headers(hkp), body = body)
        if (resp.code == 470 || resp.code == 401 || resp.code == 403) throw PinRequiredException("The device asks for a code (${resp.code})")
        if (!resp.ok) throw IOException("Pairing failed (${resp.code} ${resp.reason})")
        return Tlv8.decode(resp.body)
    }

    /** Pairs without a code; returns the shared secret. Throws [PinRequiredException] if the TV wants a code. */
    fun transient(c: AirPlayConnection): ByteArray {
        try {
            c.request("POST", "/pair-pin-start", headers = headers(4))
        } catch (_: IOException) {
        }
        val m2 = post(
            c, "/pair-setup", 4,
            Tlv8.encode(Tlv8.METHOD to byteArrayOf(0), Tlv8.STATE to byteArrayOf(1), Tlv8.FLAGS to byteArrayOf(Tlv8.FLAG_TRANSIENT.toByte())),
        )
        if (m2.containsKey(Tlv8.ERROR)) throw PinRequiredException("The device doesn't allow casting without a code")
        val salt = m2[Tlv8.SALT] ?: throw IOException("No salt from the device")
        val serverPublic = m2[Tlv8.PUBLIC_KEY] ?: throw IOException("No key from the device")
        val srp = SrpClient("Pair-Setup", TRANSIENT_PIN, HapCrypto.randomBytes(32))
        srp.process(salt, serverPublic)
        val m4 = post(c, "/pair-setup", 4, Tlv8.encode(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof))
        if (m4.containsKey(Tlv8.ERROR)) throw PinRequiredException("The device doesn't allow casting without a code")
        return srp.sessionKey
    }

    /** Pairing with the code on the TV, in two halves: [start] shows the code, [finish] completes it. */
    class WithPin(private val c: AirPlayConnection) {
        private lateinit var salt: ByteArray
        private lateinit var serverPublic: ByteArray

        fun start() {
            c.request("POST", "/pair-pin-start", headers = headers(3))
            val m2 = post(c, "/pair-setup", 3, Tlv8.encode(Tlv8.METHOD to byteArrayOf(0), Tlv8.STATE to byteArrayOf(1)))
            if (m2.containsKey(Tlv8.ERROR)) throw IOException("The device refused pairing (try again in a minute)")
            salt = m2[Tlv8.SALT] ?: throw IOException("No salt from the device")
            serverPublic = m2[Tlv8.PUBLIC_KEY] ?: throw IOException("No key from the device")
        }

        fun finish(pin: String, clientId: String = java.util.UUID.randomUUID().toString().uppercase()): AirPlayCredentials {
            val signing = HapCrypto.SigningKey.generate()
            val srp = SrpClient("Pair-Setup", pin.trim(), HapCrypto.randomBytes(32))
            srp.process(salt, serverPublic)
            val m4 = post(c, "/pair-setup", 3, Tlv8.encode(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof))
            if (m4.containsKey(Tlv8.ERROR) || m4[Tlv8.PROOF]?.let { srp.verifyServer(it) } != true) throw WrongPinException()

            val k = srp.sessionKey
            val encKey = HapCrypto.hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", k)
            val x = HapCrypto.hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", k)
            val id = clientId.toByteArray(Charsets.UTF_8)
            val signature = signing.sign(x + id + signing.public)
            val sub = Tlv8.encode(Tlv8.IDENTIFIER to id, Tlv8.PUBLIC_KEY to signing.public, Tlv8.SIGNATURE to signature)
            val m6 = post(
                c, "/pair-setup", 3,
                Tlv8.encode(Tlv8.STATE to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to HapCrypto.seal(encKey, HapCrypto.nonce("PS-Msg05"), sub)),
            )
            if (m6.containsKey(Tlv8.ERROR)) throw IOException("The device refused the pairing")
            val reply = Tlv8.decode(HapCrypto.open(encKey, HapCrypto.nonce("PS-Msg06"), m6[Tlv8.ENCRYPTED_DATA] ?: throw IOException("No pairing data")))
            val deviceId = reply[Tlv8.IDENTIFIER] ?: throw IOException("No device id")
            val devicePublic = reply[Tlv8.PUBLIC_KEY] ?: throw IOException("No device key")
            return AirPlayCredentials(clientId, signing.secret, deviceId, devicePublic)
        }
    }

    class WrongPinException : IOException("Wrong code")

    /** Proves both sides know each other from an earlier pairing; returns the shared secret. */
    fun verify(c: AirPlayConnection, credentials: AirPlayCredentials): ByteArray {
        val kx = HapCrypto.KeyExchange()
        val m2 = post(c, "/pair-verify", 3, Tlv8.encode(Tlv8.STATE to byteArrayOf(1), Tlv8.PUBLIC_KEY to kx.public))
        if (m2.containsKey(Tlv8.ERROR)) throw PinRequiredException("The device forgot this phone")
        val devicePublic = m2[Tlv8.PUBLIC_KEY] ?: throw IOException("No key from the device")
        val shared = kx.shared(devicePublic)
        val key = HapCrypto.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        val sub = Tlv8.decode(HapCrypto.open(key, HapCrypto.nonce("PV-Msg02"), m2[Tlv8.ENCRYPTED_DATA] ?: throw IOException("No data")))
        val id = sub[Tlv8.IDENTIFIER] ?: throw IOException("No device id")
        if (!id.contentEquals(credentials.deviceId)) throw PinRequiredException("A different device answered")
        val signature = sub[Tlv8.SIGNATURE] ?: throw IOException("No signature")
        if (!HapCrypto.verify(credentials.devicePublic, devicePublic + id + kx.public, signature)) {
            throw PinRequiredException("The device's signature doesn't match the pairing")
        }
        val signing = HapCrypto.SigningKey(credentials.secret)
        val clientId = credentials.clientId.toByteArray(Charsets.UTF_8)
        val mine = Tlv8.encode(Tlv8.IDENTIFIER to clientId, Tlv8.SIGNATURE to signing.sign(kx.public + clientId + devicePublic))
        val m4 = post(c, "/pair-verify", 3, Tlv8.encode(Tlv8.STATE to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to HapCrypto.seal(key, HapCrypto.nonce("PV-Msg03"), mine)))
        if (m4.containsKey(Tlv8.ERROR)) throw PinRequiredException("The device didn't accept this phone")
        return shared
    }

    /** Switches [c] to the encrypted control channel. */
    fun encrypt(c: AirPlayConnection, shared: ByteArray) {
        c.enableEncryption(
            HapCrypto.hkdf("Control-Salt", "Control-Write-Encryption-Key", shared),
            HapCrypto.hkdf("Control-Salt", "Control-Read-Encryption-Key", shared),
        )
    }

    const val TRANSIENT_PIN = "3939"
}

/** Bits of the "features" value an AirPlay device announces. */
object AirPlayFeatures {
    fun parse(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        val parts = text.split(',')
        val low = parts[0].trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0
        val high = parts.getOrNull(1)?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16) ?: 0
        return (high shl 32) or (low and 0xFFFFFFFFL)
    }

    fun has(features: Long, bit: Int) = features and (1L shl bit) != 0L

    fun video(f: Long) = has(f, 0) || has(f, 4) || has(f, 49)
    fun needsPairing(f: Long) = has(f, 43) || has(f, 48)
    fun isV2(f: Long) = has(f, 38) || has(f, 48)
}
