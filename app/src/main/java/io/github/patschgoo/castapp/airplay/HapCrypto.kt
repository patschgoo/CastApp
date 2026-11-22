package io.github.patschgoo.castapp.airplay

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** TLV8, the container format of HomeKit-style pairing messages. */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val FLAGS = 0x13
    const val FLAG_TRANSIENT = 0x10

    /** Values longer than 255 bytes are split into several items of the same type. */
    fun encode(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            if (value.isEmpty()) {
                out.write(type)
                out.write(0)
                continue
            }
            var pos = 0
            while (pos < value.size) {
                val n = minOf(255, value.size - pos)
                out.write(type)
                out.write(n)
                out.write(value, pos, n)
                pos += n
            }
        }
        return out.toByteArray()
    }

    /** Items of the same type are joined back together. */
    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 2 <= data.size) {
            val type = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            if (pos + 2 + len > data.size) throw IOException("Broken pairing message")
            val value = data.copyOfRange(pos + 2, pos + 2 + len)
            out[type] = out[type]?.plus(value) ?: value
            pos += 2 + len
        }
        return out
    }
}

/**
 * SRP-6a client as Apple's pairing uses it (3072-bit group, SHA-512), matching
 * the byte conventions of the srptools library that pyatv pairs with.
 */
class SrpClient(private val username: String, private val password: String, privateKey: ByteArray) {
    private val a = BigInteger(1, privateKey)
    val publicKey: ByteArray = minimal(G.modPow(a, N))
    lateinit var sessionKey: ByteArray
        private set
    lateinit var proof: ByteArray
        private set
    private lateinit var expectedServerProof: ByteArray

    /** Processes the accessory's salt and public key; afterwards [proof] and [sessionKey] are ready. */
    fun process(salt: ByteArray, serverPublic: ByteArray) {
        val b = BigInteger(1, serverPublic)
        if (b.mod(N).signum() == 0) throw IOException("Bad pairing data from the device")
        val k = hashInt(minimal(N), pad(G))
        val u = hashInt(pad(BigInteger(1, publicKey)), pad(b))
        val inner = sha512((username + ":" + password).toByteArray(Charsets.UTF_8))
        val x = hashInt(salt, inner)
        val base = b.subtract(k.multiply(G.modPow(x, N))).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)
        sessionKey = sha512(minimal(s))
        val hn = hashInt(minimal(N))
        val hg = hashInt(minimal(G))
        proof = sha512(
            minimal(hn.xor(hg)),
            minimal(hashInt(username.toByteArray(Charsets.UTF_8))),
            salt,
            publicKey,
            minimal(b),
            sessionKey,
        )
        expectedServerProof = sha512(publicKey, proof, sessionKey)
    }

    fun verifyServer(serverProof: ByteArray): Boolean = MessageDigest.isEqual(serverProof, expectedServerProof)

    private fun pad(v: BigInteger): ByteArray = minimal(v).let { b -> ByteArray(N_BYTES - b.size) + b }

    companion object {
        private val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        private val G = BigInteger.valueOf(5)
        private val N_BYTES = minimal(N).size

        /** Big-endian bytes without leading zeros (srptools' int_to_bytes). */
        fun minimal(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            val start = b.indexOfFirst { it != 0.toByte() }
            return if (start <= 0) (if (start < 0) byteArrayOf(0) else b) else b.copyOfRange(start, b.size)
        }

        fun sha512(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            parts.forEach { md.update(it) }
            return md.digest()
        }

        private fun hashInt(vararg parts: ByteArray) = BigInteger(1, sha512(*parts))
    }
}

/** The small crypto toolbox of HomeKit-style pairing. */
object HapCrypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun hkdf(salt: String, info: String, secret: ByteArray): ByteArray {
        val gen = HKDFBytesGenerator(SHA512Digest())
        gen.init(HKDFParameters(secret, salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8)))
        return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
    }

    /** ChaCha20-Poly1305 with a 12-byte nonce (an 8-byte one is padded with zeros in front). */
    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray =
        aead(true, key, nonce, plain, aad)

    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray =
        try {
            aead(false, key, nonce, sealed, aad)
        } catch (e: Exception) {
            throw IOException("Couldn't decrypt the device's answer", e)
        }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray {
        val n = if (nonce.size == 12) nonce else ByteArray(12 - nonce.size) + nonce
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, n, aad))
        val out = ByteArray(c.getOutputSize(input.size))
        val len = c.processBytes(input, 0, input.size, out, 0)
        val total = len + c.doFinal(out, len)
        return if (total == out.size) out else out.copyOf(total)
    }

    /** Nonce of a pairing message: "PS-Msg05" and the like. */
    fun nonce(label: String): ByteArray = label.toByteArray(Charsets.US_ASCII)

    /** 64-bit little-endian counter nonce of the encrypted session. */
    fun counterNonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte()
        return n
    }

    class SigningKey(val secret: ByteArray) {
        private val key = Ed25519PrivateKeyParameters(secret, 0)
        val public: ByteArray = key.generatePublicKey().encoded

        fun sign(data: ByteArray): ByteArray = Ed25519Signer().run {
            init(true, key)
            update(data, 0, data.size)
            generateSignature()
        }

        companion object {
            fun generate() = SigningKey(randomBytes(32))
        }
    }

    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(data, 0, data.size)
            verifySignature(signature)
        }
    } catch (_: Exception) {
        false
    }

    class KeyExchange {
        private val key = X25519PrivateKeyParameters(random)
        val public: ByteArray = key.generatePublicKey().encoded

        fun shared(otherPublic: ByteArray): ByteArray {
            val agreement = X25519Agreement()
            agreement.init(key)
            return ByteArray(agreement.agreementSize).also { agreement.calculateAgreement(X25519PublicKeyParameters(otherPublic, 0), it, 0) }
        }
    }
}

/**
 * The encrypted channel after pairing: data goes in frames of up to 1024 bytes,
 * each sealed with its own counter nonce and its length as additional data.
 */
class HapSession(private val outKey: ByteArray, private val inKey: ByteArray) {
    private var outCounter = 0L
    private var inCounter = 0L

    @Synchronized
    fun encrypt(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 64)
        var pos = 0
        while (pos < data.size) {
            val n = minOf(FRAME, data.size - pos)
            val len = byteArrayOf((n and 0xFF).toByte(), (n ushr 8).toByte())
            out.write(len)
            out.write(HapCrypto.seal(outKey, HapCrypto.counterNonce(outCounter++), data.copyOfRange(pos, pos + n), len))
            pos += n
        }
        return out.toByteArray()
    }

    /** Reads and decrypts one frame from [input]; null at end of stream. */
    fun readFrame(input: java.io.InputStream): ByteArray? {
        val lenBytes = readFully(input, 2) ?: return null
        val n = (lenBytes[0].toInt() and 0xFF) or ((lenBytes[1].toInt() and 0xFF) shl 8)
        val sealed = readFully(input, n + 16) ?: throw IOException("Connection closed mid-frame")
        synchronized(this) {
            return HapCrypto.open(inKey, HapCrypto.counterNonce(inCounter++), sealed, lenBytes)
        }
    }

    private fun readFully(input: java.io.InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) return if (read == 0) null else throw IOException("Connection closed")
            read += r
        }
        return buf
    }

    companion object {
        const val FRAME = 1024
    }
}
