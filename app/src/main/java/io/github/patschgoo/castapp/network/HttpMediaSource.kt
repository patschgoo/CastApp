package io.github.patschgoo.castapp.network

import io.github.patschgoo.castapp.stream.MediaSource
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

/**
 * A file on a web server (WebDAV, Nextcloud, a media server) read from any
 * position with HTTP range requests, with log-in headers and, for the user's own
 * NAS, its self-signed certificate.
 */
class HttpMediaSource(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val trustAll: Boolean = false,
) : MediaSource {

    override val length: Long by lazy { probeLength() }

    override fun open(offset: Long): InputStream {
        val conn = connect(url, headers, trustAll, range = if (offset > 0) "bytes=$offset-" else null)
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("The server answered $code")
        }
        val input = object : FilterInputStream(conn.inputStream) {
            override fun close() {
                try { super.close() } catch (_: IOException) {}
                conn.disconnect()
            }
        }
        if (offset > 0 && code == 200) {
            // The server ignored the range: skip ahead ourselves.
            var left = offset
            while (left > 0) {
                val n = input.skip(left)
                if (n <= 0) {
                    if (input.read() < 0) break
                    left--
                } else {
                    left -= n
                }
            }
        }
        return input
    }

    private fun probeLength(): Long {
        try {
            val conn = connect(url, headers, trustAll, method = "HEAD")
            try {
                if (conn.responseCode in 200..299 && conn.contentLengthLong > 0) return conn.contentLengthLong
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
        }
        return try {
            val conn = connect(url, headers, trustAll, range = "bytes=0-0")
            try {
                conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                    ?: conn.contentLengthLong.takeIf { conn.responseCode == 200 && it > 0 }
                    ?: -1L
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            -1L
        }
    }

    companion object {
        fun connect(
            url: String,
            headers: Map<String, String>,
            trustAll: Boolean,
            method: String = "GET",
            range: String? = null,
            timeoutMs: Int = 15_000,
        ): HttpURLConnection {
            // Plain assignments on purpose: HttpURLConnection has its own protected "method" and "url" fields.
            val conn = URL(url).openConnection() as HttpURLConnection
            if (trustAll && conn is HttpsURLConnection) {
                conn.sslSocketFactory = RawHttp.trustAllFactory()
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            conn.connectTimeout = timeoutMs
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = method
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            range?.let { conn.setRequestProperty("Range", it) }
            return conn
        }

        /** Reads at most [max] bytes (null if the file is bigger). */
        fun readUpTo(input: InputStream, max: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
            return out.toByteArray()
        }
    }
}
