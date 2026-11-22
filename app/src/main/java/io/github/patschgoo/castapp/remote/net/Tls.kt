package io.github.patschgoo.castapp.remote.net

import android.annotation.SuppressLint
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import kotlin.io.encoding.Base64

/**
 * TVs use self-signed certificates that can't be checked against anything, so
 * their certificate is accepted as it is (like every TV remote app does).
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
internal object TrustAllCerts : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The phone's own certificate and key, shown to Android TVs during pairing and on every connection. */
class ClientIdentity(val certificate: X509Certificate, val privateKey: PrivateKey) {

    /** "certificate:key", both Base64 DER; store it encrypted. */
    fun encode(): String = Base64.Default.encode(certificate.encoded) + ":" + Base64.Default.encode(privateKey.encoded)

    val keyManager: KeyManager
        get() = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            // Also usable as a server identity (the tests' fake TVs use it).
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = privateKey
        }

    companion object {
        private const val ALIAS = "castapp"

        fun decode(stored: String): ClientIdentity? = try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.Default.decode(stored.substringBefore(':')).inputStream()) as X509Certificate
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.Default.decode(stored.substringAfter(':'))))
            ClientIdentity(cert, key)
        } catch (_: Exception) {
            null
        }

        /**
         * A new self-signed RSA-2048 certificate, built like the one androidtvremote2 makes
         * (CA basic constraint, the name as DNS alternative name, valid for ten years).
         */
        fun generate(commonName: String): ClientIdentity {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048, SecureRandom())
            val pair = gen.generateKeyPair()
            val name = X500Name("CN=" + commonName.replace(Regex("[,+=<>#;\"\\\\]"), " "))
            val now = System.currentTimeMillis()
            val signatureAlgorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger(63, SecureRandom()).add(BigInteger.ONE)))
                setIssuer(name)
                setSubject(name)
                setStartDate(Time(Date(now - 24L * 3600_000)))
                setEndDate(Time(Date(now + 10L * 365 * 24 * 3600_000)))
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
                setSignature(signatureAlgorithm)
                setExtensions(
                    Extensions(
                        arrayOf(
                            Extension(Extension.basicConstraints, false, DEROctetString(BasicConstraints(0))),
                            Extension(Extension.subjectAlternativeName, false, DEROctetString(GeneralNames(GeneralName(GeneralName.dNSName, "castapp")))),
                        ),
                    ),
                )
            }.generateTBSCertificate()
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update(tbs.encoded)
                sign()
            }
            val der = DERSequence(ASN1EncodableVector().apply {
                add(tbs)
                add(signatureAlgorithm)
                add(DERBitString(signature))
            }).encoded
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
            return ClientIdentity(cert, pair.private)
        }
    }
}

internal object Tls {
    fun context(identity: ClientIdentity? = null): SSLContext = SSLContext.getInstance("TLS").apply {
        init(identity?.let { arrayOf(it.keyManager) }, arrayOf<TrustManager>(TrustAllCerts), SecureRandom())
    }

    /** A plain TCP connection with a connect timeout (throws IOException when the TV doesn't answer). */
    fun connectPlain(host: String, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
        return s
    }

    /** TLS over [plain]; the handshake runs right away. */
    fun wrap(plain: Socket, host: String, port: Int, context: SSLContext, timeoutMs: Int): SSLSocket {
        val ssl = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        return ssl
    }
}
