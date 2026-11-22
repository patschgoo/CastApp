package io.github.patschgoo.castapp.mediaservers

import io.github.patschgoo.castapp.network.RawHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Small JSON-over-HTTP helper for the Plex and Jellyfin APIs. */
object ServerHttp {
    class Response(val code: Int, val body: String)

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        trustAll: Boolean = false,
        timeoutMs: Int = 15_000,
    ): Response {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: java.net.MalformedURLException) {
            throw ServerHttpException(0, "Bad address: $url")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            if (trustAll && conn is HttpsURLConnection) {
                conn.sslSocketFactory = RawHttp.trustAllFactory()
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "CastApp")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                if (headers.keys.none { it.equals("Content-Type", true) }) conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw ServerHttpException(code, "HTTP $code")
            return Response(code, text)
        } catch (e: ServerHttpException) {
            throw e
        } catch (e: IOException) {
            throw ServerHttpException(0, e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    fun json(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, trustAll: Boolean = false, timeoutMs: Int = 15_000): JSONObject {
        val r = request(method, url, headers, body, trustAll, timeoutMs)
        return if (r.body.isBlank()) JSONObject() else try {
            JSONObject(r.body)
        } catch (e: org.json.JSONException) {
            throw ServerHttpException(r.code, "Unexpected answer")
        }
    }

    fun jsonArray(method: String, url: String, headers: Map<String, String> = emptyMap(), trustAll: Boolean = false): JSONArray {
        val r = request(method, url, headers, null, trustAll)
        return try {
            JSONArray(r.body)
        } catch (e: org.json.JSONException) {
            throw ServerHttpException(r.code, "Unexpected answer")
        }
    }

    /** Percent-encodes a query value or path part (spaces as %20). */
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** "?a=1&b=2" style query string from pairs; skips null values. */
    fun query(vararg pairs: Pair<String, Any?>): String =
        pairs.filter { it.second != null }.joinToString("&") { (k, v) -> "$k=${enc(v.toString())}" }
}

/** Null for a missing or JSON-null value, instead of the string "null". */
internal fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null

internal fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

internal fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

internal fun JSONObject.bool(key: String): Boolean = has(key) && !isNull(key) && optBoolean(key)

internal fun JSONObject.objects(key: String): List<JSONObject> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
