package io.github.patschgoo.castapp.network

import android.content.Context
import androidx.core.content.edit
import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.dlna.SsdpBrowser
import io.github.patschgoo.castapp.service.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SourceKind(val label: String) { SMB("Windows / Samba share"), WEBDAV("WebDAV (Nextcloud, NAS, cloud)") }

/** A saved network share or cloud folder. */
data class NetworkSource(
    val id: String,
    val kind: SourceKind,
    val name: String,
    /** SMB: server name or IP (optionally :port). WebDAV: the folder's https address. */
    val address: String,
    /** SMB only: the share name. */
    val share: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    val trustAll: Boolean = false,
) {
    val smbTarget: SmbTarget
        get() = SmbTarget(
            host = address.substringBefore(':'),
            share = share,
            user = user,
            password = password,
            domain = domain,
            port = address.substringAfter(':', "").toIntOrNull() ?: 445,
        )

    /** The top folder to start browsing in. */
    val rootPath: String get() = if (kind == SourceKind.SMB) "" else address
}

/** A DLNA/UPnP media server on the network (Plex, Jellyfin, Emby, a NAS, Windows…). */
data class MediaServerInfo(val id: String, val name: String, val contentDirectoryUrl: String, val iconUrl: String?)

/** Saved shares (passwords encrypted), discovered media servers, and browsing for both. */
class NetworkSources(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("network_sources", Context.MODE_PRIVATE)
    private val _sources = MutableStateFlow(load())
    val sources: StateFlow<List<NetworkSource>> = _sources.asStateFlow()

    private val _servers = MutableStateFlow<List<MediaServerInfo>>(emptyList())
    val servers: StateFlow<List<MediaServerInfo>> = _servers.asStateFlow()
    private var serverBrowser: SsdpBrowser? = null
    private val smbConnections = ConcurrentHashMap<String, SmbConnection>()

    // ---- saved shares ----------------------------------------------------------------------

    fun add(source: NetworkSource): NetworkSource {
        val s = if (source.id.isEmpty()) source.copy(id = UUID.randomUUID().toString().take(8)) else source
        _sources.value = _sources.value.filter { it.id != s.id } + s
        persist()
        return s
    }

    fun remove(id: String) {
        _sources.value = _sources.value.filter { it.id != id }
        smbConnections.remove(id)?.close()
        persist()
    }

    fun get(id: String): NetworkSource? = _sources.value.firstOrNull { it.id == id }

    private fun load(): List<NetworkSource> = try {
        val arr = JSONArray(prefs.getString("sources", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NetworkSource(
                id = o.getString("id"),
                kind = SourceKind.valueOf(o.getString("kind")),
                name = o.optString("name"),
                address = o.optString("address"),
                share = o.optString("share"),
                user = o.optString("user"),
                password = Secrets.decrypt(o.optString("password")),
                domain = o.optString("domain"),
                trustAll = o.optBoolean("trustAll"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist() {
        val arr = JSONArray()
        for (s in _sources.value) {
            arr.put(
                JSONObject().put("id", s.id).put("kind", s.kind.name).put("name", s.name).put("address", s.address)
                    .put("share", s.share).put("user", s.user).put("password", Secrets.encrypt(s.password))
                    .put("domain", s.domain).put("trustAll", s.trustAll),
            )
        }
        prefs.edit { putString("sources", arr.toString()) }
    }

    // ---- browsing ------------------------------------------------------------------------------

    /** One shared connection per SMB source (also used while casting). */
    fun smb(source: NetworkSource): SmbConnection = smbConnections.getOrPut(source.id) { SmbConnection(source.smbTarget) }

    suspend fun list(source: NetworkSource, path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        when (source.kind) {
            SourceKind.SMB -> smb(source).list(path)
            SourceKind.WEBDAV -> WebDav.list(path, source.user, source.password, source.trustAll)
        }
    }

    /** Checks that a new source works before it is saved. */
    suspend fun test(source: NetworkSource) = withContext(Dispatchers.IO) {
        when (source.kind) {
            SourceKind.SMB -> SmbConnection(source.smbTarget).use { it.list("") }
            SourceKind.WEBDAV -> WebDav.list(source.address, source.user, source.password, source.trustAll)
        }
        Unit
    }

    /** Media items for the playable files of a folder listing. */
    fun itemsFor(source: NetworkSource, entries: List<RemoteEntry>): List<MediaItem> {
        val files = entries.filter { !it.isFolder }
        val subtitles = files.filter { io.github.patschgoo.castapp.stream.Subtitles.isSubtitleFile(it.name) }
        return files.mapNotNull { e ->
            val mime = e.mimeType?.takeIf { it.startsWith("video/") || it.startsWith("audio/") || it.startsWith("image/") }
                ?: MediaItem.mimeForUrl(e.name).takeIf { isMediaName(e.name) }
                ?: return@mapNotNull null
            val base = e.name.substringBeforeLast('.')
            val sub = if (mime.startsWith("video/")) subtitles.firstOrNull { it.name.substringBeforeLast('.').startsWith(base, true) } else null
            when (source.kind) {
                SourceKind.SMB -> MediaItem(
                    title = e.name,
                    uri = "smb://${source.id}/${e.path}",
                    mimeType = mime,
                    subtitleUri = sub?.let { "smb://${source.id}/${it.path}" },
                )
                SourceKind.WEBDAV -> MediaItem(
                    title = e.name,
                    uri = e.path,
                    mimeType = mime,
                    subtitleUri = sub?.path,
                    headers = WebDav.authHeader(source.user, source.password),
                    trustAll = source.trustAll,
                )
            }
        }
    }

    // ---- DLNA media servers ----------------------------------------------------------------------

    fun startServerDiscovery(ifaces: List<NetworkInterface>) {
        if (serverBrowser != null) return
        serverBrowser = SsdpBrowser(Dlna.MEDIA_SERVER) { location, d ->
            val cd = d.contentDirectoryUrl ?: return@SsdpBrowser
            val server = MediaServerInfo(d.udn ?: location, d.friendlyName, cd, d.iconUrl)
            _servers.value = (_servers.value.filter { it.id != server.id } + server).sortedBy { it.name.lowercase() }
        }.also { it.start(ifaces) }
    }

    fun stopServerDiscovery() {
        serverBrowser?.stop()
        serverBrowser = null
    }

    suspend fun browseServer(server: MediaServerInfo, objectId: String): List<Dlna.Entry> =
        withContext(Dispatchers.IO) { Dlna.browse(server.contentDirectoryUrl, objectId) }

    companion object {
        private val MEDIA_EXT = listOf(
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "wmv", "3gp",
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma",
            "jpg", "jpeg", "png", "webp", "heic", "gif",
        )

        fun isMediaName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in MEDIA_EXT
    }
}
