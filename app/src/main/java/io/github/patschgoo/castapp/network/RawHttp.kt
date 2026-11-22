package io.github.patschgoo.castapp.network

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A tiny HTTP/1.1 client for requests Android's HttpURLConnection refuses
 * (WebDAV's PROPFIND). Handles Content-Length, chunked and close-delimited bodies.
 */
object RawHttp {
    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        trustAllCertificates: Boolean = false,
        timeoutMs: Int = 15_000,
    ): Response {
        val uri = URI(url)
        val https = uri.scheme.equals("https", true)
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val raw = Socket()
        raw.connect(InetSocketAddress(uri.host, port), timeoutMs)
        val socket = if (https) {
            val factory = if (trustAllCertificates) trustAllFactory() else SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(raw, uri.host, port, true) as SSLSocket).also {
                it.soTimeout = timeoutMs
                it.startHandshake()
                if (!trustAllCertificates && !HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.host, it.session)) {
                    it.close()
                    throw IOException("Certificate does not match ${uri.host}")
                }
            }
        } else raw
        socket.use { s ->
            s.soTimeout = timeoutMs
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(uri.host).append(if (uri.port > 0) ":${uri.port}" else "").append("\r\n")
            head.append("Connection: close\r\n")
            head.append("User-Agent: CastApp\r\n")
            for ((k, v) in headers) head.append(k).append(": ").append(v).append("\r\n")
            if (body != null) head.append("Content-Length: ").append(body.size).append("\r\n")
            head.append("\r\n")
            val out = s.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            return readResponse(BufferedInputStream(s.getInputStream()))
        }
    }

    fun readResponse(input: InputStream): Response {
        val statusLine = readLine(input) ?: throw IOException("No response")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad status: $statusLine")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val body = ByteArrayOutputStream()
        when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> {
                while (true) {
                    val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                    if (size == 0) break
                    copy(input, body, size.toLong())
                    readLine(input)
                }
            }
            headers["content-length"] != null -> copy(input, body, headers["content-length"]!!.toLong())
            else -> input.copyTo(body)
        }
        return Response(status, headers, body.toByteArray())
    }

    private fun copy(input: InputStream, out: ByteArrayOutputStream, count: Long) {
        val buf = ByteArray(16 * 1024)
        var left = count
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    /** For NAS boxes with self-signed certificates (only when the user allows it). */
    fun trustAllFactory(): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
        return ctx.socketFactory
    }

    @android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
