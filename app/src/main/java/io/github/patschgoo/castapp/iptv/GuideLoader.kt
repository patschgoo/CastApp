package io.github.patschgoo.castapp.iptv

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Downloads TV guides (XMLTV, plain or gzip) and turns them into [EpgData]. */
object GuideLoader {
    /** A guide is refreshed when its copy on the phone is older than this. */
    const val MAX_AGE_MS = 12 * 3600_000L

    private const val USER_AGENT = "CastApp"

    /**
     * Reads the guides at [urls] (all that work are merged) and keeps what is relevant to
     * [channels]. Throws when none of them could be read.
     */
    fun load(
        urls: List<String>,
        channels: List<Channel>,
        now: Long,
        open: (String) -> InputStream = ::openUrl,
    ): EpgData {
        val signature = signatureOf(urls)
        var failure: Exception? = null
        val parts = ArrayList<EpgData>()
        for (url in urls) {
            try {
                open(url).use { raw -> parts += Xmltv.parse(unzip(raw), channels, now, signature = signature) }
            } catch (e: Exception) {
                failure = e
            }
        }
        if (parts.isEmpty()) throw (failure ?: IOException("No guide address"))
        val merged = EpgData.merge(parts)
        return EpgData(merged.programmes, merged.names, now, signature)
    }

    fun signatureOf(urls: List<String>): String = urls.joinToString(" ")

    /** Wraps [input] in a gzip decoder when it starts with the gzip magic bytes (a .gz file, or `Content-Encoding` we did not strip). */
    fun unzip(input: InputStream): InputStream {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
    }

    /** HTTP(S) GET with redirects (also http to https). Closing the stream ends the connection. */
    fun openUrl(url: String): InputStream {
        var target = url
        repeat(6) {
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            when (code) {
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) throw IOException("The guide address redirects nowhere")
                    target = URL(URL(target), location).toString()
                }
                in 200..299 -> return object : FilterInputStream(conn.inputStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            conn.disconnect()
                        }
                    }
                }
                else -> {
                    conn.disconnect()
                    throw IOException("The guide address answered $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }
}
