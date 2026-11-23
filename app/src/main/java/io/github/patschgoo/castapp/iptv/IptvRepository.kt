package io.github.patschgoo.castapp.iptv

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import android.content.Context
import androidx.core.net.toUri
import androidx.core.content.edit
import io.github.patschgoo.castapp.network.SealedText
import io.github.patschgoo.castapp.service.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * A saved IPTV channel list: a web address or a file on the phone. [guide] is a TV guide
 * (XMLTV) address the user set; empty means: use the one in the playlist or the Xtream server.
 */
data class Playlist(val id: String, val name: String, val source: String, val guide: String = "")

/**
 * IPTV playlists, their channels (cached on the phone) and favourite channels. The playlist addresses
 * can hold the provider's user name and password (Xtream), so the list is saved encrypted ([SealedText]);
 * everything IPTV keeps on the phone is left out of Android's backups (res/xml/backup_rules.xml).
 */
class IptvRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val cacheDir = File(appContext.filesDir, "iptv").apply { mkdirs() }

    private val _playlists = MutableStateFlow(loadPlaylists())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _favorites = MutableStateFlow(prefs.getStringSet("favorites", emptySet())!!.toSet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    fun add(name: String, source: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString().take(8), name.ifBlank { guessName(source) }, source.trim())
        _playlists.value = _playlists.value + p
        persist()
        return p
    }

    fun remove(id: String) {
        _playlists.value = _playlists.value.filter { it.id != id }
        cacheDir.listFiles { f -> f.name.startsWith("$id.") || f.name.startsWith("epg_$id.") || f.name.startsWith("xt_${id}_") }
            ?.forEach { it.delete() }
        persist()
    }

    /** Sets (or, when blank, clears) the TV guide address of a playlist and drops its old guide. */
    fun setGuide(id: String, url: String) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(guide = url.trim()) else it }
        File(cacheDir, "epg_$id.tsv").delete()
        persist()
    }

    fun toggleFavorite(channelUrl: String) {
        val f = _favorites.value.toMutableSet()
        if (!f.add(channelUrl)) f.remove(channelUrl)
        _favorites.value = f
        prefs.edit { putStringSet("favorites", f) }
    }

    /** Channels of [playlist]: from the phone's copy, or downloaded again when [refresh] is set. */
    suspend fun channels(playlist: Playlist, refresh: Boolean = false): List<Channel> = withContext(Dispatchers.IO) {
        val cache = File(cacheDir, "${playlist.id}.json")
        if (!refresh && cache.exists()) {
            try {
                val arr = JSONArray(cache.readText())
                return@withContext (0 until arr.length()).map { Channel.fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {
            }
        }
        val text = read(playlist.source)
        val channels = M3uParser.parse(text)
        if (channels.isEmpty()) throw IOException(Txt.s(R.string.err_playlist_empty))
        File(cacheDir, "${playlist.id}.guide").writeText(M3uParser.guideUrls(text).joinToString("\n"))
        cache.writeText(JSONArray().also { a -> channels.forEach { a.put(it.toJson()) } }.toString())
        channels
    }

    /** The Xtream log-in of [playlist], when it is one (an address with `get.php?username=…&password=…`). */
    fun xtream(playlist: Playlist): XtreamAccount? = XtreamAccount.fromPlaylistUrl(playlist.source)

    /** Movies and series of an Xtream playlist; null for other playlists. */
    fun xtreamClient(playlist: Playlist): XtreamClient? =
        xtream(playlist)?.let { XtreamClient(it, cacheDir, "xt_${playlist.id}_") }

    /** Where the guide comes from: the address the user set, else the Xtream server and the playlist's header. */
    fun guideUrls(playlist: Playlist): List<String> {
        if (playlist.guide.isNotBlank()) return listOf(playlist.guide.trim())
        val header = try {
            File(cacheDir, "${playlist.id}.guide").takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
        return (listOfNotNull(xtream(playlist)?.xmltvUrl) + header).distinct()
    }

    /** The guide saved on the phone, however old; null when there is none for the current guide address. */
    suspend fun cachedGuide(playlist: Playlist): EpgData? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "epg_${playlist.id}.tsv")
        if (!file.exists()) return@withContext null
        try {
            file.bufferedReader().use { EpgData.read(it) }.takeIf { it.signature == GuideLoader.signatureOf(guideUrls(playlist)) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The guide of [playlist] for its [channels]: the copy on the phone while it is younger than
     * [GuideLoader.MAX_AGE_MS], otherwise downloaded again. Null when there is no guide address;
     * throws when the download fails (the caller then keeps the old copy).
     */
    suspend fun updateGuide(playlist: Playlist, channels: List<Channel>, force: Boolean = false): EpgData? = guideLock.withLock {
        val urls = guideUrls(playlist)
        if (urls.isEmpty()) return@withLock null
        val old = cachedGuide(playlist)
        val now = System.currentTimeMillis()
        if (!force && old != null && now - old.fetchedAt < GuideLoader.MAX_AGE_MS) return@withLock old
        withContext(Dispatchers.IO) {
            val data = GuideLoader.load(urls, channels, now)
            val file = File(cacheDir, "epg_${playlist.id}.tsv")
            val tmp = File(cacheDir, "epg_${playlist.id}.tmp")
            tmp.bufferedWriter().use { data.write(it) }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            data
        }
    }

    private val guideLock = Mutex()

    private fun read(source: String): String {
        if (source.startsWith("content:") || source.startsWith("file:")) {
            return appContext.contentResolver.openInputStream(source.toUri())?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException(Txt.s(R.string.err_playlist_file))
        }
        val conn = URL(source).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CastApp")
        try {
            if (conn.responseCode !in 200..299) throw IOException(Txt.sOr(R.string.err_playlist_answered, "The playlist address answered %d", conn.responseCode))
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun loadPlaylists(): List<Playlist> {
        val stored = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        // Unreadable (restored onto another phone, or the Keystore key is gone): start empty, keep what's stored.
        val list = SealedText.open(stored)?.let(::decodePlaylists) ?: return emptyList()
        if (!SealedText.isSealed(stored) && list.isNotEmpty()) save(list) // saved in plain text by an older version
        return list
    }

    private fun persist() = save(_playlists.value)

    private fun save(list: List<Playlist>) {
        prefs.edit { putString(KEY_PLAYLISTS, SealedText.seal(encodePlaylists(list))) }
    }

    companion object {
        private const val KEY_PLAYLISTS = "playlists"

        fun encodePlaylists(list: List<Playlist>): String {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("source", it.source).put("guide", it.guide)) }
            return arr.toString()
        }

        fun decodePlaylists(json: String): List<Playlist> = try {
            val arr = JSONArray(json.ifBlank { "[]" })
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Playlist(it.getString("id"), it.optString("name"), it.optString("source"), it.optString("guide")) } }
        } catch (_: Exception) {
            emptyList()
        }

        fun guessName(source: String): String =
            source.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.').ifBlank { "TV channels" }

        /** Channels as play-list items, so Next/Previous on the TV switches channels. */
        fun toItems(channels: List<Channel>): List<MediaItem> = channels.map { c ->
            val mime = MediaItem.mimeForUrl(c.url).let { if (it == "video/mp4" && !c.url.substringBefore('?').endsWith(".mp4")) "video/mp2t" else it }
            MediaItem(title = c.name, uri = c.url, mimeType = mime, headers = c.headers, live = true, artUri = MediaItem.pictureAddress(c.logo))
        }
    }
}
