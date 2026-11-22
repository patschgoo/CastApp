package io.github.patschgoo.castapp.iptv

import android.content.Context
import androidx.core.net.toUri
import androidx.core.content.edit
import io.github.patschgoo.castapp.service.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A saved IPTV channel list: a web address or a file on the phone. */
data class Playlist(val id: String, val name: String, val source: String)

/** IPTV playlists, their channels (cached on the phone) and favourite channels. */
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
        File(cacheDir, "$id.json").delete()
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
        if (channels.isEmpty()) throw IOException("No channels found in this playlist")
        cache.writeText(JSONArray().also { a -> channels.forEach { a.put(it.toJson()) } }.toString())
        channels
    }

    private fun read(source: String): String {
        if (source.startsWith("content:") || source.startsWith("file:")) {
            return appContext.contentResolver.openInputStream(source.toUri())?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException("Can't open the playlist file")
        }
        val conn = URL(source).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CastApp")
        try {
            if (conn.responseCode !in 200..299) throw IOException("The playlist address answered ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun loadPlaylists(): List<Playlist> = try {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Playlist(it.getString("id"), it.optString("name"), it.optString("source")) } }
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist() {
        val arr = JSONArray()
        _playlists.value.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("source", it.source)) }
        prefs.edit { putString("playlists", arr.toString()) }
    }

    companion object {
        fun guessName(source: String): String =
            source.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.').ifBlank { "TV channels" }

        /** Channels as play-list items, so Next/Previous on the TV switches channels. */
        fun toItems(channels: List<Channel>): List<MediaItem> = channels.map { c ->
            val mime = MediaItem.mimeForUrl(c.url).let { if (it == "video/mp4" && !c.url.substringBefore('?').endsWith(".mp4")) "video/mp2t" else it }
            MediaItem(title = c.name, uri = c.url, mimeType = mime, headers = c.headers, live = true)
        }
    }
}
