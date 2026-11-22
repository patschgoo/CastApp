package io.github.patschgoo.castapp.remote.net

import io.github.patschgoo.castapp.remote.RemoteKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * Protobuf encoding and the pairing secret of the Android TV Remote protocol v2.
 * The expected bytes and secrets were computed with the androidtvremote2 Python library
 * (0.3.3, its own protobuf classes and its PairingProtocol.async_finish_pairing).
 */
class AndroidTvProtocolTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun pairingMessagesMatchReference() {
        assertEquals("080210c80152140a0961747672656d6f7465120743617374417070", hex(AtvMessages.pairingRequest("CastApp")))
        assertEquals("080210c801a201080a04080310061801", hex(AtvMessages.options()))
        assertEquals("080210c801f201080a04080310061001", hex(AtvMessages.configuration()))
        val secret = unhex("4b49b7d08b2a52a4ef5f4af4acc2c97902120584f642ad25e3b8797923e903d1")
        assertEquals("080210c801c202220a20" + hex(secret), hex(AtvMessages.secret(secret)))
    }

    @Test
    fun remoteMessagesMatchReference() {
        assertEquals(
            "0a2d08e70412280a07506978656c20391206476f6f676c6518012201312a0961747672656d6f74653205312e302e30",
            hex(AtvMessages.configure(615, "Pixel 9", "Google")),
        )
        assertEquals("120308e304", hex(AtvMessages.setActive(611)))
        assertEquals("4a020807", hex(AtvMessages.pingResponse(7)))
        assertEquals("4a00", hex(AtvMessages.pingResponse(0)))
        assertEquals("520408171003", hex(AtvMessages.keyInject(AtvMessages.keyCodes.getValue(RemoteKey.OK))))
        assertEquals("520508b2011003", hex(AtvMessages.keyInject(AtvMessages.keyCodes.getValue(RemoteKey.INPUT))))
        assertEquals("aa011d080310051a1708011213080b100b1a0d68c3a96c6c6f2077c3b6726c64", hex(AtvMessages.imeBatchEdit(3, 5, "héllo wörld")))
        assertEquals("aa010b08feffffffffffffffff01", hex(ProtoWriter().message(21) { varint(1, -2) }.toByteArray()))
    }

    @Test
    fun decodesServerMessages() {
        val pairAck = ProtoMessage.parse(unhex("080210c8015a0d0a0b4c6976696e6720526f6f6d"))
        assertEquals(2, pairAck.int(1))
        assertEquals(200, pairAck.int(2))
        assertEquals("Living Room", pairAck.message(AtvMessages.PAIRING_REQUEST_ACK)!!.string(1))
        assertTrue(ProtoMessage.parse(unhex("080210c801fa0100")).has(AtvMessages.CONFIGURATION_ACK))
        assertTrue(ProtoMessage.parse(unhex("080210c801ca02040a020102")).has(AtvMessages.SECRET_ACK))
        assertEquals(402, ProtoMessage.parse(unhex("0802109203")).int(2))

        val cfg = ProtoMessage.parse(unhex("0a2908ee0412240a11534849454c4420416e64726f696420545612064e56494449413207352e322e343733"))
            .message(AtvMessages.REMOTE_CONFIGURE)!!
        assertEquals(622, cfg.int(1))
        assertEquals("SHIELD Android TV", cfg.message(2)!!.string(1))
        assertEquals("NVIDIA", cfg.message(2)!!.string(2))
        assertEquals("5.2.473", cfg.message(2)!!.string(6))

        val ping = ProtoMessage.parse(unhex("420408071009")).message(AtvMessages.REMOTE_PING_REQUEST)!!
        assertEquals(7, ping.int(1))
        assertEquals(9, ping.int(2))
        assertTrue(ProtoMessage.parse(unhex("c202020801")).message(AtvMessages.REMOTE_START)!!.bool(1))

        val ime = ProtoMessage.parse(unhex("a201270a210803621d636f6d2e676f6f676c652e616e64726f69642e796f75747562652e747612020805"))
            .message(AtvMessages.REMOTE_IME_KEY_INJECT)!!
        assertEquals(3, ime.message(1)!!.int(1))
        assertEquals("com.google.android.youtube.tv", ime.message(1)!!.string(12))
        assertEquals(5, ime.message(2)!!.int(1))

        assertEquals(-2, ProtoMessage.parse(unhex("aa010b08feffffffffffffffff01")).message(21)!!.int(1))
    }

    @Test
    fun rejectsBrokenProtobuf() {
        for (bad in listOf("0a05", "08", "0aff", "0b00")) {
            try {
                ProtoMessage.parse(unhex(bad))
                throw AssertionError("parsed $bad")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun lengthDelimitedFraming() {
        val out = ByteArrayOutputStream()
        val big = ByteArray(300) { it.toByte() }
        ProtoMessage.writeDelimited(out, AtvMessages.options())
        ProtoMessage.writeDelimited(out, big)
        val bytes = out.toByteArray()
        assertEquals(AtvMessages.options().size, bytes[0].toInt())
        // 300 = 0xAC 0x02 as varint
        assertEquals(0xAC.toByte(), bytes[1 + AtvMessages.options().size])
        val input = ByteArrayInputStream(bytes)
        assertArrayEquals(AtvMessages.options(), ProtoMessage.readDelimited(input))
        assertArrayEquals(big, ProtoMessage.readDelimited(input))
        assertNull(ProtoMessage.readDelimited(input))
    }

    private fun referenceCerts(): List<X509Certificate> {
        val pem = javaClass.getResourceAsStream("/remote/atv_reference_certs.pem")!!.readBytes()
        return CertificateFactory.getInstance("X.509").generateCertificates(pem.inputStream()).map { it as X509Certificate }
    }

    @Test
    fun pairingSecretMatchesAndroidTvRemote2() {
        val (client, server) = referenceCerts()
        val c = client.publicKey as RSAPublicKey
        val s = server.publicKey as RSAPublicKey
        val expected = mapOf(
            "4B1A2B" to "4b49b7d08b2a52a4ef5f4af4acc2c97902120584f642ad25e3b8797923e903d1",
            "1D00FF" to "1da70dcd56bfff0cf8996b0ec3d639d2db3cfc69d1eb90d33840c9764a372397",
            "ADC0DE" to "ad167f171dc65c27548ec6add3512d8c6d61024704619de3ac0d2c0578adc06c",
        )
        for ((code, secret) in expected) {
            assertEquals(secret, hex(AtvMessages.pairingSecret(c, s, code)!!))
            // Typed in lower case or with a space, the code still works.
            assertEquals(secret, hex(AtvMessages.pairingSecret(c, s, code.lowercase().chunked(3).joinToString(" "))!!))
        }
        // A wrong check byte (first two digits) is caught before anything is sent.
        assertNull(AtvMessages.pairingSecret(c, s, "4C1A2B"))
        assertNull(AtvMessages.pairingSecret(c, s, "12345"))
        assertNull(AtvMessages.pairingSecret(c, s, "GGGGGG"))
        // Client and server order matters.
        assertNull(AtvMessages.pairingSecret(s, c, "4B1A2B"))
    }

    @Test
    fun generatedIdentityRoundTrips() {
        val id = ClientIdentity.generate("CastApp")
        val key = id.certificate.publicKey as RSAPublicKey
        assertEquals(2048, key.modulus.bitLength())
        id.certificate.verify(key)
        assertTrue(id.certificate.basicConstraints >= 0)
        assertTrue(id.certificate.notAfter.time > System.currentTimeMillis() + 9L * 365 * 24 * 3600_000)
        val back = ClientIdentity.decode(id.encode())
        assertNotNull(back)
        assertArrayEquals(id.certificate.encoded, back!!.certificate.encoded)
        assertArrayEquals(id.privateKey.encoded, back.privateKey.encoded)
        assertNull(ClientIdentity.decode(""))
        assertFalse(ClientIdentity.decode("abc:def") != null)
    }

    @Test
    fun everyRemoteKeyHasAnAndroidKeyCode() {
        // Spot checks against android.view.KeyEvent.
        assertEquals(26, AtvMessages.keyCodes[RemoteKey.POWER])
        assertEquals(164, AtvMessages.keyCodes[RemoteKey.MUTE])
        assertEquals(85, AtvMessages.keyCodes[RemoteKey.PLAY_PAUSE])
        assertEquals(7, AtvMessages.keyCodes[RemoteKey.NUM_0])
        assertEquals(16, AtvMessages.keyCodes[RemoteKey.NUM_9])
        assertEquals(RemoteKey.entries.toSet(), AtvMessages.keyCodes.keys)
    }
}
