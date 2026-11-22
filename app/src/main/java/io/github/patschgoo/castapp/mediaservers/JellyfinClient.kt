package io.github.patschgoo.castapp.mediaservers

import io.github.patschgoo.castapp.mediaservers.ServerHttp.enc
import io.github.patschgoo.castapp.service.MediaItem
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/** Facts a server tells about itself before anybody signs in. */
data class ServerPublicInfo(val name: String, val id: String, val version: String, val kind: ServerKind)

/** Sign-in and addresses of Jellyfin and Emby. */
object JellyfinAuth {
    /** `MediaBrowser Client="CastApp", Device="Pixel 8", DeviceId="...", Version="4.0.0", Token="..."` */
    fun authorizationValue(info: ClientInfo, token: String?): String {
        fun q(s: String) = s.replace("\"", "").replace(",", " ")
        val parts = mutableListOf(
            "Client=\"${q(info.product)}\"",
            "Device=\"${q(info.deviceName)}\"",
            "DeviceId=\"${q(info.deviceId)}\"",
            "Version=\"${q(info.version)}\"",
        )
        if (!token.isNullOrEmpty()) parts += "Token=\"${q(token)}\""
        return "MediaBrowser " + parts.joinToString(", ")
    }

    fun headers(kind: ServerKind, info: ClientInfo, token: String?): Map<String, String> {
        val value = authorizationValue(info, token)
        val h = linkedMapOf("X-Emby-Authorization" to value)
        if (kind == ServerKind.JELLYFIN) h["Authorization"] = value
        if (kind == ServerKind.EMBY && !token.isNullOrEmpty()) h["X-Emby-Token"] = token
        return h
    }

    /** Makes an address out of what the user typed: "nas" becomes http://nas:8096, "https://x.org/jf/" stays. */
    fun normalizeAddress(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return ""
        val hasScheme = s.contains("://")
        if (!hasScheme) {
            val hostPart = s.substringBefore('/')
            val host = hostPart.substringBefore(':')
            val hasPort = hostPart.contains(':')
            val local = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || !host.contains('.') ||
                host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".fritz.box")
            s = if (local || hasPort) "http://" + (if (!hasPort) "$hostPart:8096" + s.removePrefix(hostPart) else s) else "https://$s"
        }
        // The address of the web page of the server is fine too.
        s = s.substringBefore('#').substringBefore('?')
        val web = s.indexOf("/web/")
        if (web > 8) s = s.substring(0, web)
        if (s.endsWith("/web")) s = s.removeSuffix("/web")
        return s.trimEnd('/')
    }

    /** True for addresses that only exist at home: private IP ranges, "nas", "box.local". */
    fun isPrivateHost(address: String): Boolean {
        val host = address.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || !host.contains('.')) return true
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".internal") || host.endsWith(".fritz.box")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
    }

    fun publicInfo(baseUrl: String, trustAll: Boolean = false): ServerPublicInfo {
        val o = ServerHttp.json("GET", "$baseUrl/System/Info/Public", trustAll = trustAll)
        val product = o.str("ProductName").orEmpty()
        val version = o.str("Version").orEmpty()
        val major = version.substringBefore('.').toIntOrNull() ?: 0
        val kind = if (product.contains("jellyfin", true) || major >= 10) ServerKind.JELLYFIN else ServerKind.EMBY
        return ServerPublicInfo(o.str("ServerName") ?: "Server", o.str("Id").orEmpty(), version, kind)
    }

    /** Signs in with a user name and password; the result carries the access token. */
    fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
        info: ClientInfo,
        trustAll: Boolean = false,
        accountId: String,
    ): ServerAccount {
        val server = publicInfo(baseUrl, trustAll)
        val body = JSONObject().put("Username", username).put("Pw", password).toString()
        val o = ServerHttp.json("POST", "$baseUrl/Users/AuthenticateByName", headers(server.kind, info, null), body, trustAll)
        val token = o.str("AccessToken") ?: throw ServerHttpException(401, "No access token")
        val user = o.optJSONObject("User")
        return ServerAccount(
            id = accountId,
            kind = server.kind,
            name = server.name,
            baseUrl = baseUrl,
            token = token,
            userId = user?.str("Id").orEmpty(),
            userName = user?.str("Name") ?: username,
            serverId = o.str("ServerId") ?: server.id,
            trustAll = trustAll,
        )
    }
}

/** Addresses on a Jellyfin / Emby server. */
internal class JellyfinUrls(private val account: ServerAccount, private val info: ClientInfo, private val caps: PlaybackCaps) {
    val base = account.baseUrl.trimEnd('/')

    private val key get() = "api_key=" + enc(account.token)

    fun image(itemId: String, tag: String?, type: String = "Primary", maxHeight: Int = 480, index: Int? = null): String {
        val path = "$base/Items/$itemId/Images/$type" + (index?.let { "/$it" } ?: "")
        return "$path?maxHeight=$maxHeight&quality=90" + (tag?.let { "&tag=" + enc(it) } ?: "") + "&$key"
    }

    /** The same on every call, so the watch history finds the item again. */
    fun playSessionId(itemId: String, sourceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${info.deviceId}/$itemId/$sourceId".toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun directVideo(itemId: String, sourceId: String, media: MediaInfo?): String =
        "$base/Videos/$itemId/stream.${PlaybackPlanner.videoExtension(media)}?Static=true&MediaSourceId=${enc(sourceId)}&DeviceId=${enc(info.deviceId)}&$key"

    fun directAudio(itemId: String, sourceId: String, container: String?): String =
        "$base/Audio/$itemId/stream.${PlaybackPlanner.audioExtension(container)}?Static=true&MediaSourceId=${enc(sourceId)}&DeviceId=${enc(info.deviceId)}&$key"

    /** The server converts to H.264 / AAC in HLS segments (copying what already fits). */
    fun hls(itemId: String, sourceId: String, audioIndex: Int? = null): String {
        val q = ServerHttp.query(
            "MediaSourceId" to sourceId,
            "DeviceId" to info.deviceId,
            "PlaySessionId" to playSessionId(itemId, sourceId),
            "VideoCodec" to "h264",
            "AudioCodec" to "aac,mp3",
            "AudioStreamIndex" to audioIndex,
            "VideoBitrate" to caps.transcodeBitrate,
            "AudioBitrate" to 192_000,
            "MaxAudioChannels" to caps.transcodeAudioChannels,
            "TranscodingMaxAudioChannels" to caps.transcodeAudioChannels,
            "MaxWidth" to caps.maxWidth,
            "SegmentContainer" to "ts",
            "MinSegments" to 1,
            "BreakOnNonKeyFrames" to true,
            "h264-profile" to "high,main,baseline,constrainedbaseline",
            "h264-level" to 41,
            "TranscodeReasons" to "ContainerNotSupported,VideoCodecNotSupported,AudioCodecNotSupported",
        )
        return "$base/Videos/$itemId/master.m3u8?$q&$key"
    }

    fun transcodedAudio(itemId: String, sourceId: String): String =
        "$base/Audio/$itemId/stream.mp3?MediaSourceId=${enc(sourceId)}&DeviceId=${enc(info.deviceId)}&AudioCodec=mp3&AudioBitRate=320000&MaxAudioChannels=2&" +
            "PlaySessionId=${playSessionId(itemId, sourceId)}&$key"

    fun photo(itemId: String): String = "$base/Items/$itemId/Images/Primary?maxWidth=1920&quality=90&$key"

    /** A subtitle track converted to WebVTT by the server. */
    fun subtitle(itemId: String, sourceId: String, index: Int): String =
        "$base/Videos/$itemId/${enc(sourceId)}/Subtitles/$index/0/Stream.vtt?$key"
}

/** Jellyfin (and Emby, which speaks the same language) as a [MediaServerClient]. */
class JellyfinClient(
    override val account: ServerAccount,
    private val info: ClientInfo,
    private val caps: PlaybackCaps = PlaybackCaps.TV,
    private val deviceLanguage: String = Locale.getDefault().language,
) : MediaServerClient {
    private val urls = JellyfinUrls(account, info, caps)
    private val base get() = urls.base
    private val uid get() = account.userId
    private val headers get() = JellyfinAuth.headers(account.kind, info, account.token)

    private fun getJson(path: String): JSONObject = ServerHttp.json("GET", "$base$path", headers, trustAll = account.trustAll)

    private fun send(method: String, path: String, body: JSONObject? = null) {
        ServerHttp.request(method, "$base$path", headers, body?.toString(), account.trustAll, timeoutMs = 10_000)
    }

    override fun libraries(): List<ServerItem> =
        getJson("/Users/$uid/Views").objects("Items").map { JellyfinParser.item(it, urls).copy(kind = ItemKind.LIBRARY) }

    override fun children(parent: ServerItem): List<ServerItem> {
        val sort = if (parent.kind == ItemKind.SEASON || parent.kind == ItemKind.ALBUM) "ParentIndexNumber,IndexNumber,SortName" else "SortName"
        val q = ServerHttp.query(
            "ParentId" to parent.id,
            "SortBy" to sort,
            "SortOrder" to "Ascending",
            "Fields" to LIST_FIELDS,
            "ImageTypeLimit" to 1,
            "EnableImageTypes" to "Primary,Backdrop",
            "EnableTotalRecordCount" to false,
        )
        return getJson("/Users/$uid/Items?$q").objects("Items").map { JellyfinParser.item(it, urls) }
    }

    override fun continueWatching(): List<ServerItem> {
        val q = ServerHttp.query("Limit" to 24, "Recursive" to true, "Fields" to LIST_FIELDS, "MediaTypes" to "Video", "EnableTotalRecordCount" to false)
        return getJson("/Users/$uid/Items/Resume?$q").objects("Items").map { JellyfinParser.item(it, urls) }
    }

    override fun nextUp(): List<ServerItem> {
        val q = ServerHttp.query("UserId" to uid, "Limit" to 24, "Fields" to LIST_FIELDS, "EnableTotalRecordCount" to false)
        return getJson("/Shows/NextUp?$q").objects("Items").map { JellyfinParser.item(it, urls) }
    }

    override fun detail(item: ServerItem): ServerItem = JellyfinParser.item(getJson("/Users/$uid/Items/${item.id}"), urls)

    override fun plan(item: ServerItem, queueFollowing: Boolean): PlaybackPlan {
        val first = if (item.media == null && item.kind != ItemKind.PHOTO) detail(item) else item
        val entries = mutableListOf(entryFor(first))
        if (queueFollowing) entries += following(first).map { entryFor(it) }
        return PlaybackPlan(entries)
    }

    private fun following(item: ServerItem): List<ServerItem> = try {
        when (item.kind) {
            ItemKind.EPISODE -> item.seriesId?.let { series ->
                val q = ServerHttp.query("UserId" to uid, "StartItemId" to item.id, "Limit" to MediaServerClient.QUEUE_AHEAD + 1, "Fields" to "$LIST_FIELDS,MediaSources")
                val list = getJson("/Shows/$series/Episodes?$q").objects("Items").map { JellyfinParser.item(it, urls) }
                if (list.firstOrNull()?.id == item.id) list.drop(1) else emptyList()
            }.orEmpty()
            ItemKind.TRACK -> item.parentId?.let { album ->
                val q = ServerHttp.query("ParentId" to album, "SortBy" to "ParentIndexNumber,IndexNumber,SortName", "Fields" to "MediaSources")
                val list = getJson("/Users/$uid/Items?$q").objects("Items").map { JellyfinParser.item(it, urls) }
                val at = list.indexOfFirst { it.id == item.id }
                if (at >= 0) list.drop(at + 1).take(MediaServerClient.QUEUE_AHEAD) else emptyList()
            }.orEmpty()
            else -> emptyList()
        }.filter { it.isPlayable }
    } catch (_: ServerHttpException) {
        emptyList() // the queue is a bonus: play what was asked for
    }

    /** The playable address of [item], by direct stream or server-side conversion. */
    internal fun entryFor(item: ServerItem): PlanEntry {
        val media = item.media
        val sourceId = media?.sourceId ?: item.id
        val audio = item.kind == ItemKind.TRACK
        val session = urls.playSessionId(item.id, sourceId)
        val title = displayTitle(item)
        fun ref(method: PlayMethod) = ItemRef(account.kind, account.id, item.id, sourceId, session, method).encode()
        val mediaItem = when {
            item.kind == ItemKind.PHOTO -> MediaItem(title, urls.photo(item.id), "image/jpeg", serverRef = null, trustAll = account.trustAll)
            audio -> {
                val method = PlaybackPlanner.decideAudio(media, caps)
                if (method == PlayMethod.DIRECT) {
                    MediaItem(title, urls.directAudio(item.id, sourceId, media?.container), PlaybackPlanner.mimeForAudio(media?.container), serverRef = ref(method), trustAll = account.trustAll)
                } else {
                    MediaItem(title, urls.transcodedAudio(item.id, sourceId), "audio/mpeg", serverRef = ref(method), trustAll = account.trustAll)
                }
            }
            else -> {
                val method = PlaybackPlanner.decideVideo(media, caps)
                val subtitle = media?.let { PlaybackPlanner.chooseSubtitle(it.subtitles, it.audioLanguage, deviceLanguage) }
                    ?.let { urls.subtitle(item.id, sourceId, it.index) }
                if (method == PlayMethod.DIRECT) {
                    MediaItem(title, urls.directVideo(item.id, sourceId, media), PlaybackPlanner.mimeForVideo(media), subtitleUri = subtitle, serverRef = ref(method), trustAll = account.trustAll)
                } else {
                    MediaItem(
                        title, urls.hls(item.id, sourceId), "application/x-mpegurl", subtitleUri = subtitle, serverRef = ref(method), trustAll = account.trustAll,
                        // CastApp TV receivers try the file itself first (no converting on the server).
                        original = urls.directVideo(item.id, sourceId, media), originalMime = PlaybackPlanner.mimeForVideo(media),
                    )
                }
            }
        }
        return PlanEntry(mediaItem, item.resumeMs, item.runtimeMs)
    }

    override fun setWatched(item: ServerItem, watched: Boolean) {
        send(if (watched) "POST" else "DELETE", "/Users/$uid/PlayedItems/${item.id}")
    }

    // ---- progress -----------------------------------------------------------------------------------

    private fun ticks(ms: Long) = ms.coerceAtLeast(0) * 10_000

    private fun playbackBody(ref: ItemRef, positionMs: Long, paused: Boolean): JSONObject = JSONObject()
        .put("ItemId", ref.itemId)
        .put("MediaSourceId", ref.sourceId)
        .put("PlaySessionId", ref.sessionId)
        .put("PositionTicks", ticks(positionMs))
        .put("CanSeek", true)
        .put("IsPaused", paused)
        .put("IsMuted", false)
        .put("PlayMethod", if (ref.method == PlayMethod.DIRECT) "DirectPlay" else "Transcode")

    override fun reportStart(ref: ItemRef, positionMs: Long, durationMs: Long) =
        send("POST", "/Sessions/Playing", playbackBody(ref, positionMs, false))

    override fun reportProgress(ref: ItemRef, positionMs: Long, durationMs: Long, paused: Boolean) =
        send("POST", "/Sessions/Playing/Progress", playbackBody(ref, positionMs, paused).put("EventName", if (paused) "Pause" else "TimeUpdate"))

    override fun reportStop(ref: ItemRef, positionMs: Long, durationMs: Long, finished: Boolean) {
        val position = if (finished && durationMs > 0) durationMs else positionMs
        send(
            "POST", "/Sessions/Playing/Stopped",
            JSONObject()
                .put("ItemId", ref.itemId)
                .put("MediaSourceId", ref.sourceId)
                .put("PlaySessionId", ref.sessionId)
                .put("PositionTicks", ticks(position))
                .put("Failed", false),
        )
    }

    companion object {
        const val LIST_FIELDS = "Overview,ProductionYear,ChildCount,PrimaryImageAspectRatio"
    }
}

/** Reads the JSON of Jellyfin / Emby. */
internal object JellyfinParser {
    fun item(o: JSONObject, urls: JellyfinUrls): ServerItem {
        val id = o.str("Id").orEmpty()
        val kind = when (o.str("Type")) {
            "Movie" -> ItemKind.MOVIE
            "Series" -> ItemKind.SERIES
            "Season" -> ItemKind.SEASON
            "Episode" -> ItemKind.EPISODE
            "BoxSet", "Playlist" -> ItemKind.COLLECTION
            "Folder", "CollectionFolder", "UserView", "PlaylistsFolder", "AggregateFolder" -> ItemKind.FOLDER
            "MusicAlbum" -> ItemKind.ALBUM
            "MusicArtist" -> ItemKind.ARTIST
            "Audio" -> ItemKind.TRACK
            "Video", "MusicVideo", "Trailer" -> ItemKind.VIDEO
            "Photo" -> ItemKind.PHOTO
            else -> ItemKind.OTHER
        }
        val userData = o.optJSONObject("UserData")
        val tags = o.optJSONObject("ImageTags")
        val primary = tags?.str("Primary")
        val poster = when {
            primary != null -> urls.image(id, primary)
            o.str("SeriesPrimaryImageTag") != null && o.str("SeriesId") != null -> urls.image(o.str("SeriesId")!!, o.str("SeriesPrimaryImageTag"))
            o.str("ParentPrimaryImageTag") != null && o.str("ParentPrimaryImageItemId") != null ->
                urls.image(o.str("ParentPrimaryImageItemId")!!, o.str("ParentPrimaryImageTag"))
            o.str("AlbumPrimaryImageTag") != null && o.str("AlbumId") != null -> urls.image(o.str("AlbumId")!!, o.str("AlbumPrimaryImageTag"))
            else -> null
        }
        val backdropTag = o.optJSONArray("BackdropImageTags")?.optString(0)?.ifEmpty { null }
        val backdrop = backdropTag?.let { urls.image(id, it, "Backdrop", 720, 0) }
        val artist = o.optJSONArray("Artists")?.optString(0)?.ifEmpty { null } ?: o.str("AlbumArtist")
        val index = o.int("IndexNumber")
        val parentIndex = o.int("ParentIndexNumber")
        val year = o.int("ProductionYear")
        val subtitle = when (kind) {
            ItemKind.EPISODE -> o.str("SeriesName")
            ItemKind.TRACK, ItemKind.ALBUM -> artist
            ItemKind.MOVIE, ItemKind.SERIES -> year?.toString()
            else -> null
        }
        return ServerItem(
            id = id,
            kind = kind,
            title = o.str("Name") ?: "?",
            subtitle = subtitle,
            year = year,
            overview = o.str("Overview"),
            runtimeMs = (o.long("RunTimeTicks") ?: 0) / 10_000,
            resumeMs = (userData?.long("PlaybackPositionTicks") ?: 0) / 10_000,
            played = userData?.bool("Played") == true,
            posterUrl = poster,
            backdropUrl = backdrop,
            wide = kind == ItemKind.EPISODE || kind == ItemKind.VIDEO,
            childCount = o.int("ChildCount"),
            unplayedCount = userData?.int("UnplayedItemCount"),
            parentId = o.str("ParentId"),
            seriesId = o.str("SeriesId"),
            seriesTitle = o.str("SeriesName"),
            seasonNumber = if (kind == ItemKind.SEASON) index else parentIndex,
            episodeNumber = if (kind == ItemKind.EPISODE) index else null,
            collectionType = o.str("CollectionType"),
            media = o.objects("MediaSources").firstOrNull()?.let(::mediaInfo),
        )
    }

    fun mediaInfo(source: JSONObject): MediaInfo {
        val streams = source.objects("MediaStreams")
        val video = streams.firstOrNull { it.str("Type") == "Video" }
        val audios = streams.filter { it.str("Type") == "Audio" }
        val audio = audios.firstOrNull { it.bool("IsDefault") } ?: audios.firstOrNull()
        val subs = streams.filter { it.str("Type") == "Subtitle" }.map { s ->
            SubtitleTrack(
                index = s.optInt("Index"),
                codec = s.str("Codec").orEmpty(),
                language = s.str("Language"),
                title = s.str("DisplayTitle") ?: s.str("Title"),
                isDefault = s.bool("IsDefault"),
                isForced = s.bool("IsForced"),
                isExternal = s.bool("IsExternal"),
            )
        }
        return MediaInfo(
            sourceId = source.str("Id").orEmpty(),
            container = source.str("Container"),
            videoCodec = video?.str("Codec"),
            audioCodec = audio?.str("Codec"),
            width = video?.int("Width"),
            height = video?.int("Height"),
            bitDepth = video?.int("BitDepth"),
            audioChannels = audio?.int("Channels"),
            bitrate = source.long("Bitrate"),
            audioLanguage = audio?.str("Language"),
            subtitles = subs,
        )
    }
}

/** The answer of a UDP broadcast: where a Jellyfin / Emby server is. */
data class FoundServer(val name: String, val address: String, val id: String, val kind: ServerKind)
