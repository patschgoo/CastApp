package io.github.patschgoo.castapp.opensubtitles

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import android.content.Context
import android.util.Log
import io.github.patschgoo.castapp.BuildConfig
import io.github.patschgoo.castapp.network.HttpMediaSource
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.settings.SubtitleSourceSettings
import io.github.patschgoo.castapp.stream.MediaSource
import io.github.patschgoo.castapp.stream.Subtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Finds subtitles for the video being cast on OpenSubtitles.com: by the file's hash and by
 * a search made from its name, in the languages chosen in the settings. Only runs when the
 * user taps "Find subtitles", and only with their own API key.
 */
class SubtitleFinder(context: Context) {
    private val app = context.applicationContext
    private val account = OpenSubtitlesAccount.get(app)

    data class Found(val results: List<OpenSubtitlesClient.Result>, val byHash: Boolean, val query: SearchQuery, val languages: List<String>)

    data class Downloaded(val text: String, val remaining: Int?)

    fun languages(): List<String> {
        val chosen = SubtitleSourceSettings.get(app).current.searchLanguages
        return OpenSubtitlesClient.normalizeLanguages(listOf(chosen.ifBlank { Locale.getDefault().language.ifEmpty { "en" } }))
    }

    suspend fun search(item: MediaItem, source: MediaSource?): Found = withContext(Dispatchers.IO) {
        val client = client()
        val readable = source ?: if (item.isWeb) RangeOnlyHttpSource(item.uri, item.headers, item.trustAll) else null
        val hash = readable?.let {
            try {
                MovieHash.of(it)
            } catch (e: IOException) {
                Log.i(TAG, "No file hash: ${e.message}")
                null
            }
        }
        val query = SearchQuery.fromFileName(fileName(item))
        val langs = languages()
        val results = client.search(hash, query, langs)
        Found(results, results.any { it.hashMatch }, query, langs)
    }

    /** Downloads [result] (logs in first if a username is saved) and returns the subtitle text. */
    suspend fun download(result: OpenSubtitlesClient.Result): Downloaded = withContext(Dispatchers.IO) {
        val client = client()
        var token = token(client)
        val link = try {
            client.requestDownload(result.fileId, token)
        } catch (e: OpenSubtitlesClient.Failure) {
            if (e.problem != OpenSubtitlesClient.Problem.LOGIN || token == null) throw e
            // An old login: log in once more.
            account.login = null
            token = token(client)
            client.requestDownload(result.fileId, token)
        }
        val bytes = client.fetch(link.url)
        val text = Subtitles.decode(bytes)
        if (!text.contains("-->")) throw OpenSubtitlesClient.Failure(OpenSubtitlesClient.Problem.SERVER, Txt.sOr(R.string.os_err_no_subs_in_file, "The downloaded file has no subtitles in it"))
        Downloaded(text, link.remaining)
    }

    private fun client(): OpenSubtitlesClient {
        val c = account.credentials()
        if (c.apiKey.isBlank()) throw OpenSubtitlesClient.Failure(OpenSubtitlesClient.Problem.NO_KEY, OpenSubtitlesClient.NO_KEY_MESSAGE)
        return OpenSubtitlesClient(c.apiKey, USER_AGENT, account.login?.baseUrl ?: OpenSubtitlesClient.DEFAULT_BASE_URL)
    }

    private fun token(client: OpenSubtitlesClient): String? {
        val c = account.credentials()
        if (!c.hasLogin) return null
        account.login?.let { return it.token }
        return client.login(c.username, c.password).also { account.login = it }.token
    }

    companion object {
        private const val TAG = "SubtitleFinder"
        /** OpenSubtitles wants the app name and version. */
        val USER_AGENT = "CastApp v${BuildConfig.VERSION_NAME}"

        private val VIDEO_NAME = Regex("""(?i).+\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|wmv|mpg|mpeg|flv|3gp)$""")

        /** The best file name: web videos often have a page title, but the address may name the file. */
        fun fileName(item: MediaItem): String {
            if (item.isWeb || item.isSmb) {
                val raw = item.uri.substringBefore('?').substringAfterLast('/')
                val last = try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { raw }
                if (VIDEO_NAME.matches(last)) return last
            }
            return item.title
        }
    }
}

/** A web file read only with byte ranges: never downloads the whole video just to reach its end. */
class RangeOnlyHttpSource(
    private val url: String,
    private val headers: Map<String, String>,
    private val trustAll: Boolean,
) : MediaSource {
    override val length: Long by lazy { HttpMediaSource(url, headers, trustAll).length }

    override fun open(offset: Long): InputStream {
        val conn = HttpMediaSource.connect(url, headers, trustAll, range = "bytes=$offset-")
        val code = conn.responseCode
        if (code != 206 && !(code == 200 && offset == 0L)) {
            conn.disconnect()
            throw IOException(Txt.sOr(R.string.os_err_no_ranges, "The server can't send parts of the file (%d)", code))
        }
        return object : java.io.FilterInputStream(conn.inputStream) {
            override fun close() {
                try { super.close() } catch (_: IOException) {}
                conn.disconnect()
            }
        }
    }
}
