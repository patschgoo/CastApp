package io.github.patschgoo.castapp.screenshots

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Robolectric has no Android Keystore. Saved passwords, server tokens and the watch history are
 * encrypted with a Keystore key (network/Secrets), so the screenshot tests put an in-memory
 * "AndroidKeyStore" in its place: plain AES keys that live as long as the test JVM.
 */
object FakeKeyStore {
    internal val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(ProviderImpl())
    }

    private class ProviderImpl : Provider("AndroidKeyStore", 1.0, "In-memory AndroidKeyStore for JVM tests") {
        init {
            put("KeyStore.AndroidKeyStore", Store::class.java.name)
            put("KeyGenerator.AES", Generator::class.java.name)
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date(0)
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }

    class Generator : KeyGeneratorSpi() {
        private var alias = "default"
        private var random = SecureRandom()

        override fun engineInit(random: SecureRandom?) {
            if (random != null) this.random = random
        }

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            (params as? KeyGenParameterSpec)?.let { alias = it.keystoreAlias }
            if (random != null) this.random = random
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) = engineInit(random)

        override fun engineGenerateKey(): SecretKey {
            val bytes = ByteArray(32).also(random::nextBytes)
            return SecretKeySpec(bytes, "AES").also { keys[alias] = it }
        }
    }
}
