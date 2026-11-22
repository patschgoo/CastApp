package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.discovery.CastDevice
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The phone's side of CastApp's extras on a CastApp TV (see `UpnpRenderer`'s
 * `/castapp/` endpoints): what the TV can do, and opening YouTube links there.
 */
object CastAppTvClient {
    /** What the TV says it can do, e.g. {"hevc": true, "youtube": true}; null for older versions or when unreachable. */
    fun capabilities(tv: CastDevice, timeoutMs: Int = 2500): JSONObject? {
        val base = SwapDirection.baseUrl(tv) ?: return null
        return try {
            val conn = URL("$base/castapp/capabilities").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                if (conn.responseCode != 200) return null
                JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Asks the TV to open [url] (a YouTube link) in its YouTube app; status code (-1 = unreachable) and
     * answer text. A TV that only accepts paired phones is paired first (asks for its code once).
     */
    suspend fun openYouTube(tv: CastDevice, url: String): Pair<Int, String> {
        val base = SwapDirection.baseUrl(tv) ?: return -1 to ""
        val pairing = io.github.patschgoo.castapp.tv.AppPairing.client
        try {
            pairing?.ensure(tv, base)
        } catch (e: io.github.patschgoo.castapp.tv.PairingClient.PairingException) {
            return 401 to (e.message ?: "")
        }
        val headers = pairing?.headersFor(tv) ?: emptyMap()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            post("$base/castapp/youtube", JSONObject().put("url", url).toString(), headers)
        }
    }

    private fun post(url: String, json: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val bytes = json.toByteArray()
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        -1 to ""
    }
}
