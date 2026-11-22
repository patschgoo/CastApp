package io.github.patschgoo.castapp.remote.net

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

/**
 * The whole Android TV flow against an in-process fake TV speaking TLS with client
 * certificates: refused unknown phone, pairing with the code check, remote channel
 * (configure, set active, pings), key presses and typing.
 */
class AndroidTvClientTest {
    private val tvIdentity = ClientIdentity.generate("fake-android-tv")
    private val known = ConcurrentHashMap.newKeySet<BigInteger>()
    private val received = LinkedBlockingQueue<ProtoMessage>()
    private val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    @Volatile private var shownCode: String? = null
    @Volatile private var pingAnswer: Int? = null

    private fun server(): SSLServerSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(tvIdentity.keyManager), arrayOf<TrustManager>(TrustAllCerts), SecureRandom())
        return (ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
            needClientAuth = true
        }
    }

    private val pairServer = server()
    private val remoteServer = server()

    init {
        Thread { while (true) { val s = try { pairServer.accept() as SSLSocket } catch (_: Exception) { break }; Thread { try { pairing(s) } catch (e: Throwable) { errors += e } }.start() } }.start()
        Thread { while (true) { val s = try { remoteServer.accept() as SSLSocket } catch (_: Exception) { break }; Thread { try { remote(s) } catch (e: Throwable) { errors += e } }.start() } }.start()
    }

    @After
    fun tearDown() {
        pairServer.close()
        remoteServer.close()
    }

    private fun clientKey(s: SSLSocket) = (s.session.peerCertificates[0] as X509Certificate).publicKey as RSAPublicKey

    private fun read(input: InputStream) = ProtoMessage.parse(ProtoMessage.readDelimited(input)!!)

    private fun outer(body: ProtoWriter.() -> Unit) = ProtoWriter().varint(1, 2).varint(2, 200).apply(body).toByteArray()

    /** The TV side of polo pairing, the way Android TV Remote Service does it. */
    private fun pairing(s: SSLSocket) = s.use {
        val input = s.inputStream
        val output = s.outputStream
        val req = read(input)
        assertEquals("atvremote", req.message(10)!!.string(1))
        assertEquals("CastApp", req.message(10)!!.string(2))
        ProtoMessage.writeDelimited(output, outer { message(11) { string(1, "Fake TV") } })
        val options = read(input).message(20)!!
        assertEquals(1, options.int(3))
        assertEquals(3, options.messages(1).single().int(1))
        assertEquals(6, options.messages(1).single().int(2))
        ProtoMessage.writeDelimited(output, outer { message(20) { message(1) { varint(1, 3); varint(2, 6) }; varint(3, 1) } })
        val config = read(input).message(30)!!
        assertEquals(1, config.int(2))
        // Show a code: two random bytes, then the check byte in front.
        val client = clientKey(s)
        val server = tvIdentity.certificate.publicKey as RSAPublicKey
        val nonce = ByteArray(2).also { SecureRandom().nextBytes(it) }
        fun u(v: BigInteger) = v.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val hash = MessageDigest.getInstance("SHA-256").run {
            update(u(client.modulus)); update(u(client.publicExponent)); update(u(server.modulus)); update(u(server.publicExponent)); update(nonce)
            digest()
        }
        shownCode = (byteArrayOf(hash[0]) + nonce).joinToString("") { "%02X".format(it) }
        ProtoMessage.writeDelimited(output, outer { message(31) {} })
        val secret = read(input).message(40)!!.bytes(1)!!
        if (secret.contentEquals(hash)) {
            known.add(client.modulus)
            ProtoMessage.writeDelimited(output, outer { message(41) { bytes(1, secret) } })
        } else {
            ProtoMessage.writeDelimited(output, ProtoWriter().varint(1, 2).varint(2, 402).toByteArray())
        }
    }

    /** The TV side of the remote channel; unknown phones are dropped right away. */
    private fun remote(s: SSLSocket) = s.use {
        if (clientKey(s).modulus !in known) return@use
        val input = s.inputStream
        val output: OutputStream = s.outputStream
        ProtoMessage.writeDelimited(output, ProtoWriter().message(1) { varint(1, 622); message(2) { string(1, "Fake"); string(2, "Test") } }.toByteArray())
        val cfg = read(input).message(1)!!
        assertEquals(AtvMessages.FEATURES and 622, cfg.int(1))
        assertEquals("atvremote", cfg.message(2)!!.string(5))
        ProtoMessage.writeDelimited(output, ProtoWriter().message(2) { varint(1, 622) }.toByteArray())
        assertEquals(AtvMessages.FEATURES and 622, read(input).message(2)!!.int(1))
        ProtoMessage.writeDelimited(output, ProtoWriter().message(40) { varint(1, 1) }.toByteArray())
        ProtoMessage.writeDelimited(output, ProtoWriter().message(8) { varint(1, 42); varint(2, 1) }.toByteArray())
        // A text field got focus.
        ProtoMessage.writeDelimited(output, ProtoWriter().message(20) { message(1) { varint(1, 3); string(12, "com.example") }; message(2) { varint(1, 5) } }.toByteArray())
        try {
            while (true) {
                val m = read(input)
                if (m.has(9)) pingAnswer = m.message(9)!!.int(1) else received.put(m)
            }
        } catch (_: Exception) {
        }
    }

    @Test
    fun pairsThenSendsKeysAndText() = runBlocking {
        val phone = ClientIdentity.generate("CastApp")
        val client = AndroidTvClient("127.0.0.1", phone, remotePort = remoteServer.localPort, pairPort = pairServer.localPort)

        // Unknown phone: the TV drops the connection, which means "pair first".
        try {
            client.open("Pixel", "Google")
            throw AssertionError("connected without pairing")
        } catch (_: NeedsPairingException) {
        }

        var asks = 0
        val paired = client.pair("CastApp") { wrong ->
            asks++
            if (asks == 1) {
                assertFalse(wrong)
                // A typo: the check byte doesn't match, so the phone asks again without telling the TV.
                val good = shownCode!!
                (if (good[0] == '0') "1" else "0") + good.substring(1)
            } else {
                assertTrue(wrong)
                shownCode!!.lowercase()
            }
        }
        assertTrue(paired)
        assertEquals(2, asks)

        val conn = client.open("Pixel", "Google")
        assertEquals(true, conn.isOn)
        assertEquals("Test Fake", conn.tvModel)
        conn.sendKey(23)
        val keyMsg = received.poll(5, TimeUnit.SECONDS)
        assertTrue("got ${keyMsg?.fieldNumbers} $errors", keyMsg?.has(10) == true)
        val key = keyMsg!!.message(10)!!
        assertEquals(23, key.int(1))
        assertEquals(3, key.int(2))

        // Wait for the reader thread to take the text field's counters.
        val until = System.currentTimeMillis() + 5000
        while ((conn.fieldCounter != 5 || pingAnswer == null) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(42, pingAnswer)
        assertTrue(conn.supportsText)
        conn.sendText("hi")
        val edit = received.poll(5, TimeUnit.SECONDS)!!.message(21)!!
        assertEquals(3, edit.int(1))
        assertEquals(5, edit.int(2))
        assertEquals("hi", edit.message(3)!!.message(2)!!.string(3))
        assertArrayEquals(AtvMessages.imeBatchEdit(3, 5, "hi"), ProtoWriter().message(21) {
            varint(1, 3); varint(2, 5); message(3) { varint(1, 1); message(2) { varint(1, 2); varint(2, 2); string(3, "hi") } }
        }.toByteArray())
        conn.close()
        assertFalse(conn.isOpen)
    }

    /** TLS 1.2 TVs refuse an unknown certificate inside the handshake itself. */
    @Test
    fun tls12HandshakeRefusalMeansPairing() = runBlocking {
        val refuseAll = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw java.security.cert.CertificateException("unknown phone")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(tvIdentity.keyManager), arrayOf<TrustManager>(refuseAll), SecureRandom())
        val strict = (ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
            needClientAuth = true
            enabledProtocols = arrayOf("TLSv1.2")
        }
        Thread { try { (strict.accept() as SSLSocket).use { it.startHandshake() } } catch (_: Exception) {} }.start()
        strict.use {
            val client = AndroidTvClient("127.0.0.1", ClientIdentity.generate("CastApp"), remotePort = strict.localPort, pairPort = pairServer.localPort)
            try {
                client.open("Pixel", "Google")
                throw AssertionError("connected")
            } catch (_: NeedsPairingException) {
            }
        }
    }

    @Test
    fun cancelledPairingReturnsFalse() = runBlocking {
        val client = AndroidTvClient("127.0.0.1", ClientIdentity.generate("CastApp"), remotePort = remoteServer.localPort, pairPort = pairServer.localPort)
        assertFalse(client.pair("CastApp") { null })
    }
}
