package io.github.patschgoo.castapp.iptv

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * Talks to an Xtream Codes server's `player_api.php`. The big lists (all films, all
 * series) are saved on the phone and read from there for [MAX_AGE_MS]; when the server
 * can't be reached, an older copy is used.
 */
class XtreamClient(
    val account: XtreamAccount,
    private val cacheDir: File,
    private val cachePrefix: String,
    private val open: (String) -> InputStream = GuideLoader::openUrl,
) {
    fun vodCategories(): List<XtreamCategory> = list("get_vod_categories") { XtreamJson.categories(it) }
    fun movies(): List<Movie> = list("get_vod_streams") { XtreamJson.movies(it) }
    fun seriesCategories(): List<XtreamCategory> = list("get_series_categories") { XtreamJson.categories(it) }
    fun series(): List<SeriesShow> = list("get_series") { XtreamJson.series(it) }

    /** Live channels that offer catch-up (`tv_archive`), by stream id. */
    fun archiveChannels(): Map<String, LiveStream> = list("get_live_streams") { XtreamJson.liveStreams(it) }.associateBy { it.streamId }

    fun seriesInfo(seriesId: String): SeriesInfo {
        val text = download(account.apiUrl("get_series_info", "series_id" to seriesId)).use { it.readBytes().toString(Charsets.UTF_8) }
        return XtreamJson.seriesInfo(text)
    }

    private fun <T> list(action: String, parse: (Reader) -> List<T>): List<T> {
        val file = File(cacheDir, "$cachePrefix$action.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        if (!fresh) {
            val tmp = File(cacheDir, "$cachePrefix$action.tmp")
            try {
                download(account.apiUrl(action)).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                tmp.delete()
                if (!file.exists()) throw if (e is IOException) e else IOException(e.message, e)
            }
        }
        val result = file.inputStream().buffered().use { parse(InputStreamReader(GuideLoader.unzip(it), Charsets.UTF_8)) }
        if (result.isEmpty()) file.delete() // an error answer or an empty list: ask again next time
        return result
    }

    private fun download(url: String): InputStream = GuideLoader.unzip(open(url))

    fun clearCache() {
        cacheDir.listFiles { f -> f.name.startsWith(cachePrefix) }?.forEach { it.delete() }
    }

    companion object {
        const val MAX_AGE_MS = 6 * 3600_000L
    }
}
