package io.github.patschgoo.castapp.opensubtitles

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * A small client for the OpenSubtitles.com REST API v1 (https://opensubtitles.stoplight.io).
 * Every request carries the user's own API key; nothing here runs unless the user asks.
 */
class OpenSubtitlesClient(
    private val apiKey: String,
    private val userAgent: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutMs: Int = 15_000,
) {
    /** Changes to the server the login names (VIP users get their own). */
    var baseUrl: String = baseUrl.trimEnd('/')
        private set

    enum class Problem { NO_KEY, BAD_KEY, LOGIN, LIMIT, NOTHING_FOUND, OFFLINE, BUSY, SERVER }

    /** A failure with a message that can be shown to the user as it is. */
    class Failure(val problem: Problem, message: String) : IOException(message)

    data class Login(val token: String, val baseUrl: String, val allowedDownloads: Int?)

    data class Result(
        val fileId: Long,
        val language: String,
        val release: String,
        val fileName: String,
        val title: String,
        val downloads: Int,
        val hashMatch: Boolean,
        val hearingImpaired: Boolean,
        val machineTranslated: Boolean,
    ) {
        /** What to show as the name of the subtitle. */
        val label: String get() = release.ifBlank { fileName.ifBlank { title } }
    }

    data class DownloadLink(val url: String, val fileName: String, val remaining: Int?, val message: String)

    fun login(username: String, password: String): Login {
        val body = JSONObject().put("username", username).put("password", password)
        val json = request("POST", "$baseUrl/login", body = body, isLogin = true)
        val token = json.optString("token").ifEmpty { throw Failure(Problem.LOGIN, "OpenSubtitles didn't log you in") }
        val host = json.optString("base_url")
        val newBase = when {
            host.isEmpty() -> baseUrl
            host.contains("://") -> host.trimEnd('/').let { if (it.endsWith("/api/v1")) it else "$it/api/v1" }
            else -> baseUrl.substringBefore("://") + "://" + host.trimEnd('/') + "/api/v1"
        }
        baseUrl = newBase
        val allowed = json.optJSONObject("user")?.let { if (it.has("allowed_downloads")) it.optInt("allowed_downloads") else null }
        return Login(token, newBase, allowed)
    }

    /**
     * Looks for subtitles by [hash] (exact file) and by [query] (title), in [languages]
     * (codes like "en"). Files matching the hash come first, then the most downloaded.
     */
    fun search(hash: String?, query: SearchQuery?, languages: List<String>, token: String? = null): List<Result> {
        val langs = normalizeLanguages(languages)
        val found = LinkedHashMap<Long, Result>()
        if (hash != null) {
            val params = mutableMapOf("moviehash" to hash)
            if (langs.isNotEmpty()) params["languages"] = langs.joinToString(",")
            val byHash = try {
                parseResults(request("GET", searchUrl(params), token = token))
            } catch (e: Failure) {
                if (e.problem != Problem.NOTHING_FOUND) throw e
                emptyList()
            }
            byHash.forEach { found.putIfAbsent(it.fileId, it) }
        }
        if (query != null && query.text.isNotBlank()) {
            val params = mutableMapOf("query" to query.text)
            if (langs.isNotEmpty()) params["languages"] = langs.joinToString(",")
            query.year?.let { params["year"] = it.toString() }
            query.season?.let { params["season_number"] = it.toString() }
            query.episode?.let { params["episode_number"] = it.toString() }
            val byName = try {
                parseResults(request("GET", searchUrl(params), token = token))
            } catch (e: Failure) {
                if (e.problem != Problem.NOTHING_FOUND) throw e
                emptyList()
            }
            byName.forEach { found.putIfAbsent(it.fileId, it) }
        }
        if (found.isEmpty()) throw Failure(Problem.NOTHING_FOUND, "No subtitles found for this video")
        return found.values.sortedWith(compareByDescending<Result> { it.hashMatch }.thenByDescending { it.downloads })
    }

    /** Asks for the download address of [fileId]; this counts against the daily download limit. */
    fun requestDownload(fileId: Long, token: String?): DownloadLink {
        val body = JSONObject().put("file_id", fileId).put("sub_format", "srt")
        val json = request("POST", "$baseUrl/download", body = body, token = token, isDownload = true)
        val link = json.optString("link").ifEmpty { throw Failure(Problem.SERVER, json.optString("message").ifEmpty { "OpenSubtitles gave no download" }) }
        return DownloadLink(
            url = link,
            fileName = json.optString("file_name"),
            remaining = if (json.has("remaining")) json.optInt("remaining") else null,
            message = json.optString("message"),
        )
    }

    /** Fetches the subtitle file itself from the address [requestDownload] gave. */
    fun fetch(url: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray = guard {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode !in 200..299) throw Failure(Problem.SERVER, "The subtitle download failed (${conn.responseCode})")
            conn.inputStream.use { readLimited(it, maxBytes) }
        } finally {
            conn.disconnect()
        }
    }

    /** /subtitles with its parameters sorted and lower-case, as the API asks (otherwise it redirects). */
    fun searchUrl(params: Map<String, String>): String =
        "$baseUrl/subtitles?" + params.toSortedMap().entries.joinToString("&") { (k, v) ->
            k + "=" + URLEncoder.encode(v.lowercase(Locale.ROOT), "UTF-8")
        }

    private fun request(
        method: String,
        url: String,
        body: JSONObject? = null,
        token: String? = null,
        isLogin: Boolean = false,
        isDownload: Boolean = false,
    ): JSONObject = guard {
        if (apiKey.isBlank()) throw Failure(Problem.NO_KEY, NO_KEY_MESSAGE)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.requestMethod = method
            conn.setRequestProperty("Api-Key", apiKey.trim())
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { String(readLimited(it, 2 * 1024 * 1024), Charsets.UTF_8) }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code in 200..299) return@guard json
            throw failureFor(code, json, isLogin, isDownload)
        } finally {
            conn.disconnect()
        }
    }

    private fun failureFor(code: Int, json: JSONObject, isLogin: Boolean, isDownload: Boolean): Failure {
        val serverMessage = json.optString("message").ifEmpty {
            json.optJSONArray("errors")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } }.orEmpty()
        }.trim()
        return when {
            isDownload && (code == 406 || (json.has("remaining") && json.optInt("remaining", 0) < 0)) ->
                Failure(Problem.LIMIT, "Daily download limit reached. " + serverMessage.ifEmpty { "Try again tomorrow." })
            code == 401 && isLogin -> Failure(Problem.LOGIN, "Wrong OpenSubtitles username or password")
            code == 401 -> Failure(Problem.LOGIN, "OpenSubtitles wants you to log in again. Check the username and password in Settings → Subtitles.")
            code == 403 -> Failure(Problem.BAD_KEY, "OpenSubtitles didn't accept the API key. Check it in Settings → Subtitles.")
            code == 429 -> Failure(Problem.BUSY, "OpenSubtitles is busy. Try again in a moment.")
            code == 404 -> Failure(Problem.NOTHING_FOUND, "No subtitles found for this video")
            else -> Failure(Problem.SERVER, "OpenSubtitles answered with an error ($code)" + if (serverMessage.isNotEmpty()) ": $serverMessage" else "")
        }
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: Failure) {
        throw e
    } catch (e: UnknownHostException) {
        throw Failure(Problem.OFFLINE, OFFLINE_MESSAGE)
    } catch (e: ConnectException) {
        throw Failure(Problem.OFFLINE, OFFLINE_MESSAGE)
    } catch (e: SocketTimeoutException) {
        throw Failure(Problem.OFFLINE, OFFLINE_MESSAGE)
    } catch (e: SSLException) {
        throw Failure(Problem.OFFLINE, OFFLINE_MESSAGE)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.opensubtitles.com/api/v1"
        const val NO_KEY_MESSAGE = "Enter your OpenSubtitles API key in Settings → Subtitles first"
        const val OFFLINE_MESSAGE = "Couldn't reach OpenSubtitles.com. Check the internet connection."

        /** "en, DE ,pt" → ["de", "en", "pt-br", "pt-pt"]: lower case, sorted, in the codes the API knows. */
        fun normalizeLanguages(languages: List<String>): List<String> = languages
            .flatMap { it.split(',', ' ', ';') }
            .map { it.trim().lowercase(Locale.ROOT).replace('_', '-') }
            .filter { it.isNotEmpty() }
            .flatMap {
                when (it) {
                    "pt" -> listOf("pt-br", "pt-pt")
                    "zh" -> listOf("zh-cn", "zh-tw")
                    else -> listOf(it)
                }
            }
            .distinct()
            .sorted()

        fun parseResults(json: JSONObject): List<Result> {
            val data = json.optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).mapNotNull { i ->
                val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
                val file = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                val fileId = file.optLong("file_id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val feature = a.optJSONObject("feature_details")
                Result(
                    fileId = fileId,
                    language = a.optString("language"),
                    release = a.optString("release"),
                    fileName = file.optString("file_name"),
                    title = feature?.optString("title")?.ifEmpty { feature.optString("movie_name") }.orEmpty(),
                    downloads = a.optInt("download_count"),
                    hashMatch = a.optBoolean("moviehash_match"),
                    hearingImpaired = a.optBoolean("hearing_impaired"),
                    machineTranslated = a.optBoolean("machine_translated") || a.optBoolean("ai_translated"),
                )
            }
        }

        private fun readLimited(input: InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw Failure(Problem.SERVER, "The answer from OpenSubtitles was too big")
            }
            return out.toByteArray()
        }
    }
}
