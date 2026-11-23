package io.github.patschgoo.castapp.mediaservers

import io.github.patschgoo.castapp.mediaservers.ServerHttp.enc
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.subtitles.Sidecars
import org.json.JSONObject
import java.security.MessageDigest

/** Addresses on a Plex Media Server. */
internal class PlexUrls(private val account: ServerAccount, private val info: ClientInfo, private val caps: PlaybackCaps) {
    val base = account.baseUrl.trimEnd('/')

    private val token get() = "X-Plex-Token=" + enc(account.token)

    /** A poster, scaled by the server. */
    fun image(path: String?, width: Int = 320, height: Int = 480): String? {
        if (path.isNullOrEmpty()) return null
        return "$base/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=${enc(path)}&$token"
    }

    /** The same on every call, so the watch history finds the item again. */
    fun session(itemId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${info.deviceId}/$itemId".toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun direct(partKey: String): String = "$base$partKey" + (if (partKey.contains('?')) "&" else "?") + token

    fun stream(streamKey: String): String = "$base$streamKey" + (if (streamKey.contains('?')) "&" else "?") + token

    /**
     * The server is at home (a private address, or a plex.direct name of one): only then is a
     * subtitle track read out of the video file on the phone, which means fetching the whole file.
     */
    val local: Boolean get() = isLocal(base)

    /**
     * Where the subtitle file of [track] is read from, as [ServerSubtitle.attachTo] marks it, or null
     * when it can't be read (a picture one, or a track inside the file of a server far away).
     * External files come from /library/streams/{id} in UTF-8 (SAMI converted to SRT), as Plex's
     * own players fetch them; tracks inside the file ([SubtitleTrack.fileOrdinal]) from the file at [partKey].
     */
    fun subtitle(track: SubtitleTrack, partKey: String?): String? {
        if (!track.isText) return null
        val description = ServerSubtitle(
            language = track.language,
            title = track.name,
            codec = track.codec.ifEmpty { null },
            forced = track.isForced,
            hearingImpaired = track.isSdh,
            track = track.fileOrdinal.takeIf { track.key == null },
        )
        val key = track.key
        val address = when {
            key != null -> stream(key + (if (key.contains('?')) "&" else "?") + "encoding=utf-8" + (if (track.codec.equals("smi", true)) "&format=srt" else ""))
            track.fileOrdinal != null && partKey != null && local -> direct(partKey)
            else -> return null
        }
        return description.attachTo(address)
    }

    /**
     * Plex's universal transcoder: HLS in H.264 / AAC, copying what already fits. [ownSubtitles]:
     * CastApp sends the subtitles next to the stream, so the transcoder leaves all subtitle streams
     * out ("none" in the API documentation, skipSubtitles as Plex's Kodi client asks) instead of
     * burning the one picked in Plex into the picture (twice the subtitles, and a full conversion).
     */
    fun hls(itemId: String, ownSubtitles: Boolean = false): String {
        val q = ServerHttp.query(
            "hasMDE" to 1,
            "path" to "/library/metadata/$itemId",
            "mediaIndex" to 0,
            "partIndex" to 0,
            "protocol" to "hls",
            "fastSeek" to 1,
            "directPlay" to 0,
            "directStream" to 1,
            "directStreamAudio" to 1,
            "subtitleSize" to 100,
            "subtitles" to "none".takeIf { ownSubtitles },
            "skipSubtitles" to 1.takeIf { ownSubtitles },
            "audioBoost" to 100,
            "maxVideoBitrate" to caps.transcodeBitrate / 1000,
            "videoResolution" to "${caps.maxWidth}x${caps.maxHeight.coerceAtMost(1080)}",
            "offset" to 0,
            "session" to session(itemId),
            "X-Plex-Session-Identifier" to session(itemId),
            "X-Plex-Client-Identifier" to info.deviceId,
            "X-Plex-Product" to info.product,
            "X-Plex-Version" to info.version,
            "X-Plex-Platform" to "Chromecast",
            "X-Plex-Device-Name" to info.deviceName,
        )
        return "$base/video/:/transcode/universal/start.m3u8?$q&$token"
    }

    fun transcodedAudio(itemId: String): String {
        val q = ServerHttp.query(
            "path" to "/library/metadata/$itemId",
            "mediaIndex" to 0,
            "partIndex" to 0,
            "protocol" to "http",
            "audioCodec" to "mp3",
            "maxAudioBitrate" to 320,
            "session" to session(itemId),
            "X-Plex-Client-Identifier" to info.deviceId,
            "X-Plex-Product" to info.product,
            "X-Plex-Platform" to "Chromecast",
        )
        return "$base/music/:/transcode/universal/start.mp3?$q&$token"
    }

    companion object {
        /** A private address ("http://192.168.1.5:32400", "nas") or a plex.direct name of one ("https://192-168-1-5.abc.plex.direct:32400"). */
        fun isLocal(address: String): Boolean {
            if (JellyfinAuth.isPrivateHost(address)) return true
            val host = address.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            if (!host.endsWith(".plex.direct")) return false
            val ip = host.substringBefore('.').replace('-', '.')
            return ip.split('.').size == 4 && JellyfinAuth.isPrivateHost("http://$ip")
        }
    }
}

/** Plex Media Server as a [MediaServerClient]. */
class PlexClient(
    override val account: ServerAccount,
    private val info: ClientInfo,
    private val caps: PlaybackCaps = PlaybackCaps.TV,
    /** The languages subtitles are wanted in, best first (the subtitle setting's, then the phone's, then English). */
    private val subtitleLanguages: () -> List<String> = { Sidecars.preferredLanguages() },
) : MediaServerClient {
    private val urls = PlexUrls(account, info, caps)
    private val base get() = urls.base

    private val headers: Map<String, String>
        get() = mapOf(
            "Accept" to "application/json",
            "X-Plex-Token" to account.token,
            "X-Plex-Client-Identifier" to info.deviceId,
            "X-Plex-Product" to info.product,
            "X-Plex-Version" to info.version,
            "X-Plex-Device-Name" to info.deviceName,
            "X-Plex-Platform" to "Android",
        )

    private fun getJson(path: String): JSONObject =
        ServerHttp.json("GET", "$base$path", headers, trustAll = account.trustAll).optJSONObject("MediaContainer") ?: JSONObject()

    private fun metadata(path: String): List<ServerItem> = getJson(path).objects("Metadata").map { PlexParser.item(it, urls) }

    override fun libraries(): List<ServerItem> = getJson("/library/sections").objects("Directory").map { d ->
        ServerItem(
            id = d.str("key").orEmpty(),
            kind = ItemKind.LIBRARY,
            title = d.str("title") ?: "?",
            collectionType = d.str("type"),
            posterUrl = urls.image(d.str("composite") ?: d.str("thumb")),
        )
    }

    override fun children(parent: ServerItem): List<ServerItem> =
        if (parent.kind == ItemKind.LIBRARY) metadata("/library/sections/${parent.id}/all?sort=titleSort")
        else metadata("/library/metadata/${parent.id}/children")

    private fun onDeck(): List<ServerItem> = metadata("/library/onDeck")

    override fun continueWatching(): List<ServerItem> = onDeck().filter { it.resumeMs > 0 }

    override fun nextUp(): List<ServerItem> = onDeck().filter { it.resumeMs == 0L }

    override fun detail(item: ServerItem): ServerItem =
        metadata("/library/metadata/${item.id}").firstOrNull() ?: item

    override fun plan(item: ServerItem, queueFollowing: Boolean): PlaybackPlan {
        // Lists leave out the file's streams (subtitles, sound languages): ask for the item itself.
        val first = if (item.media?.streamDetails != true && item.kind != ItemKind.PHOTO) detail(item) else item
        remember(first)
        val entries = mutableListOf(entryFor(first))
        if (queueFollowing) entries += withStreams(following(first)).map { entryFor(it) }
        return PlaybackPlan(entries)
    }

    // ---- the streams of queued episodes ---------------------------------------------------------------

    private fun needsStreams(item: ServerItem): Boolean =
        (item.kind == ItemKind.EPISODE || item.kind == ItemKind.MOVIE || item.kind == ItemKind.VIDEO) &&
            item.media?.partKey != null && item.media.streamDetails != true

    private fun cacheKey(partKey: String) = "${account.id}|$partKey"

    private fun remember(item: ServerItem) {
        val media = item.media?.takeIf { it.streamDetails } ?: return
        val part = media.partKey ?: return
        synchronized(streamCache) { streamCache[cacheKey(part)] = media }
    }

    private fun cached(item: ServerItem): MediaInfo? = item.media?.partKey?.let { part -> synchronized(streamCache) { streamCache[cacheKey(part)] } }

    /**
     * [items] (from a list) with their streams, so queued episodes get subtitles too: from the
     * cache (by the file's address, which changes with the file), else asked for all at once
     * (/library/metadata/1,2,3, as python-plexapi does), else one by one for a few seconds.
     * The positions and watched marks stay the list's: only the streams are taken.
     */
    internal fun withStreams(items: List<ServerItem>): List<ServerItem> {
        val missing = items.filter(::needsStreams)
        if (missing.isEmpty()) return items
        val found = HashMap<String, MediaInfo>()
        for (item in missing) cached(item)?.let { found[item.id] = it }
        fun take(details: ServerItem) {
            val media = details.media?.takeIf { it.streamDetails } ?: return
            found[details.id] = media
            remember(details)
        }
        missing.filter { it.id !in found }.chunked(BATCH).forEach { chunk ->
            try {
                metadata("/library/metadata/" + chunk.joinToString(",") { enc(it.id) }).forEach(::take)
            } catch (_: ServerHttpException) {
                // Older servers may not take several at once.
            }
        }
        val until = System.nanoTime() + ONE_BY_ONE_NS
        for (item in missing) {
            if (item.id in found) continue
            if (System.nanoTime() > until) break
            try {
                metadata("/library/metadata/${enc(item.id)}").firstOrNull()?.let(::take)
            } catch (_: ServerHttpException) {
                // The queue is a bonus: this one plays without subtitles.
            }
        }
        return items.map { item -> found[item.id]?.takeIf { needsStreams(item) }?.let { item.copy(media = it) } ?: item }
    }

    private fun following(item: ServerItem): List<ServerItem> = try {
        val list = when (item.kind) {
            ItemKind.EPISODE -> item.seriesId?.let { metadata("/library/metadata/$it/allLeaves") }
            ItemKind.TRACK -> item.parentId?.let { metadata("/library/metadata/$it/children") }
            else -> null
        }.orEmpty()
        val at = list.indexOfFirst { it.id == item.id }
        if (at >= 0) list.drop(at + 1).filter { it.isPlayable }.take(MediaServerClient.QUEUE_AHEAD) else emptyList()
    } catch (_: ServerHttpException) {
        emptyList() // the queue is a bonus: play what was asked for
    }

    internal fun entryFor(item: ServerItem): PlanEntry {
        val media = item.media
        val title = displayTitle(item)
        val partKey = media?.partKey
        fun ref(method: PlayMethod) = ItemRef(account.kind, account.id, item.id, media?.sourceId.orEmpty(), urls.session(item.id), method).encode()
        val mediaItem = when {
            item.kind == ItemKind.PHOTO && partKey != null ->
                MediaItem(title, urls.direct(partKey), MediaItem.mimeForUrl(partKey), trustAll = account.trustAll)
            item.kind == ItemKind.TRACK -> {
                val method = if (partKey == null) PlayMethod.TRANSCODE else PlaybackPlanner.decideAudio(media, caps)
                if (method == PlayMethod.DIRECT) {
                    MediaItem(title, urls.direct(partKey!!), PlaybackPlanner.mimeForAudio(media.container), serverRef = ref(method), trustAll = account.trustAll)
                } else {
                    MediaItem(title, urls.transcodedAudio(item.id), "audio/mpeg", serverRef = ref(method), trustAll = account.trustAll)
                }
            }
            else -> {
                val method = if (partKey == null) PlayMethod.TRANSCODE else PlaybackPlanner.decideVideo(media, caps)
                val subtitles = subtitleFiles(media)
                if (method == PlayMethod.DIRECT) {
                    MediaItem(
                        title, urls.direct(partKey!!), PlaybackPlanner.mimeForVideo(media), subtitleUri = subtitles.firstOrNull(),
                        moreSubtitles = subtitles.drop(1), serverRef = ref(method), trustAll = account.trustAll,
                    )
                } else {
                    MediaItem(
                        title, urls.hls(item.id, ownSubtitles = subtitles.isNotEmpty()), "application/x-mpegurl",
                        subtitleUri = subtitles.firstOrNull(), moreSubtitles = subtitles.drop(1),
                        serverRef = ref(method), trustAll = account.trustAll,
                        // CastApp TV receivers try the file itself first (no converting on the server).
                        original = partKey?.let { urls.direct(it) }, originalMime = partKey?.let { PlaybackPlanner.mimeForVideo(media) },
                    )
                }
            }
        }
        // The poster or episode still, so "Continue watching" shows it (the address carries the token, like the stream).
        return PlanEntry(mediaItem.copy(artUri = item.posterUrl), item.resumeMs, item.runtimeMs)
    }

    /**
     * The subtitle files of a video for the picker, the one to show first ([PlaybackPlanner.chooseSubtitle]
     * in the [subtitleLanguages]): external text files and the text tracks inside the file. None when
     * none is to be shown (the picker would show the first one by itself).
     */
    internal fun subtitleFiles(media: MediaInfo?): List<String> {
        media ?: return emptyList()
        val readable = media.subtitles.mapNotNull { t -> urls.subtitle(t, media.partKey)?.let { t to it } }
        if (readable.isEmpty()) return emptyList()
        val languages = subtitleLanguages()
        val shown = PlaybackPlanner.chooseSubtitle(readable.map { it.first }, media.audioLanguage, languages) ?: return emptyList()
        val others = PlaybackPlanner.rankSubtitles(readable.map { it.first }.filter { it !== shown }, languages)
        return (listOf(shown) + others).mapNotNull { t -> readable.firstOrNull { it.first === t }?.second }
    }

    override fun setWatched(item: ServerItem, watched: Boolean) {
        val action = if (watched) "scrobble" else "unscrobble"
        ServerHttp.request("GET", "$base/:/$action?key=${enc(item.id)}&identifier=com.plexapp.plugins.library", headers, trustAll = account.trustAll, timeoutMs = 10_000)
    }

    // ---- progress ------------------------------------------------------------------------------------

    private fun timeline(ref: ItemRef, state: String, positionMs: Long, durationMs: Long) {
        val q = ServerHttp.query(
            "ratingKey" to ref.itemId,
            "key" to "/library/metadata/${ref.itemId}",
            "identifier" to "com.plexapp.plugins.library",
            "state" to state,
            "time" to positionMs.coerceAtLeast(0),
            "duration" to durationMs.coerceAtLeast(0).takeIf { it > 0 },
            "hasMDE" to 1,
            "playbackTime" to positionMs.coerceAtLeast(0),
        )
        ServerHttp.request(
            "GET", "$base/:/timeline?$q", headers + ("X-Plex-Session-Identifier" to ref.sessionId),
            trustAll = account.trustAll, timeoutMs = 10_000,
        )
    }

    override fun reportStart(ref: ItemRef, positionMs: Long, durationMs: Long) = timeline(ref, "playing", positionMs, durationMs)

    override fun reportProgress(ref: ItemRef, positionMs: Long, durationMs: Long, paused: Boolean) =
        timeline(ref, if (paused) "paused" else "playing", positionMs, durationMs)

    override fun reportStop(ref: ItemRef, positionMs: Long, durationMs: Long, finished: Boolean) {
        val position = if (finished && durationMs > 0) durationMs else positionMs
        timeline(ref, "stopped", position, durationMs)
        // Plex counts an item as watched from 90 %: say so, so that the next episode is "on deck".
        if (finished || (durationMs > 0 && position * 10 >= durationMs * 9)) {
            ServerHttp.request(
                "GET", "$base/:/scrobble?key=${enc(ref.itemId)}&identifier=com.plexapp.plugins.library", headers,
                trustAll = account.trustAll, timeoutMs = 10_000,
            )
        }
    }

    companion object {
        /** Queued episodes asked for in one request. */
        private const val BATCH = 20
        /** How long queued episodes are asked for one by one when a server won't take several at once. */
        private const val ONE_BY_ONE_NS = 4_000_000_000L

        /** Streams of files, by account and the file's address (/library/parts/{id}/{changed}/file.mkv): a few hundred episodes. */
        private val streamCache = object : LinkedHashMap<String, MediaInfo>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaInfo>?) = size > 400
        }

        internal fun forgetStreams() = synchronized(streamCache) { streamCache.clear() }

        /** Checks that a server answers at [address] (no sign-in needed for /identity); returns its machine id. */
        fun identify(address: String, trustAll: Boolean = false, timeoutMs: Int = 4000): String? = try {
            val r = ServerHttp.json("GET", address.trimEnd('/') + "/identity", trustAll = trustAll, timeoutMs = timeoutMs)
            r.optJSONObject("MediaContainer")?.str("machineIdentifier")
        } catch (_: ServerHttpException) {
            null
        }
    }
}

/** Reads the JSON of Plex Media Server. */
internal object PlexParser {
    fun item(o: JSONObject, urls: PlexUrls): ServerItem {
        val id = o.str("ratingKey") ?: o.str("key").orEmpty()
        val type = o.str("type")
        val kind = when (type) {
            "movie" -> ItemKind.MOVIE
            "show" -> ItemKind.SERIES
            "season" -> ItemKind.SEASON
            "episode" -> ItemKind.EPISODE
            "album" -> ItemKind.ALBUM
            "artist" -> ItemKind.ARTIST
            "track" -> ItemKind.TRACK
            "clip" -> ItemKind.VIDEO
            "photo" -> ItemKind.PHOTO
            "collection", "playlist" -> ItemKind.COLLECTION
            else -> ItemKind.OTHER
        }
        val thumb = o.str("thumb") ?: o.str("parentThumb") ?: o.str("grandparentThumb")
        val wide = kind == ItemKind.EPISODE || kind == ItemKind.VIDEO
        val leaves = o.int("leafCount")
        val viewedLeaves = o.int("viewedLeafCount")
        val subtitle = when (kind) {
            ItemKind.EPISODE, ItemKind.TRACK -> o.str("grandparentTitle")
            ItemKind.ALBUM -> o.str("parentTitle")
            ItemKind.MOVIE, ItemKind.SERIES -> o.int("year")?.toString()
            else -> null
        }
        val resume = o.long("viewOffset") ?: 0
        return ServerItem(
            id = id,
            kind = kind,
            title = o.str("title") ?: "?",
            subtitle = subtitle,
            year = o.int("year"),
            overview = o.str("summary"),
            runtimeMs = o.long("duration") ?: 0,
            resumeMs = resume,
            played = (o.int("viewCount") ?: 0) > 0 || (leaves != null && leaves > 0 && viewedLeaves == leaves),
            posterUrl = if (wide) urls.image(thumb, 480, 270) else urls.image(thumb),
            backdropUrl = urls.image(o.str("art"), 1280, 720),
            wide = wide,
            childCount = o.int("childCount") ?: leaves,
            unplayedCount = if (leaves != null && viewedLeaves != null) leaves - viewedLeaves else null,
            parentId = o.str("parentRatingKey"),
            seriesId = if (kind == ItemKind.EPISODE) o.str("grandparentRatingKey") else null,
            seriesTitle = if (kind == ItemKind.EPISODE) o.str("grandparentTitle") else null,
            seasonNumber = if (kind == ItemKind.SEASON) o.int("index") else o.int("parentIndex"),
            episodeNumber = if (kind == ItemKind.EPISODE) o.int("index") else null,
            media = o.objects("Media").firstOrNull()?.let(::mediaInfo),
        )
    }

    fun mediaInfo(m: JSONObject): MediaInfo {
        val part = m.objects("Part").firstOrNull()
        val streams = part?.objects("Stream").orEmpty()
        val video = streams.firstOrNull { it.optInt("streamType") == 1 }
        val audios = streams.filter { it.optInt("streamType") == 2 }
        val audio = audios.firstOrNull { it.bool("selected") } ?: audios.firstOrNull()
        val subtitleStreams = streams.filter { it.optInt("streamType") == 3 }
        // Tracks of their own inside the file, in the file's order (closed captions inside the picture aren't).
        val inFile = subtitleStreams.filter { it.str("key") == null && !it.flag("embeddedInVideo") }
            .withIndex().sortedWith(compareBy({ it.value.int("index") ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        val subs = subtitleStreams.map { s ->
            SubtitleTrack(
                index = s.optInt("id"),
                codec = s.str("codec") ?: s.str("format").orEmpty(),
                language = s.str("languageCode") ?: s.str("languageTag") ?: s.str("language"),
                title = s.str("displayTitle"),
                isDefault = s.flag("default"),
                isForced = s.flag("forced"),
                isExternal = s.str("key") != null,
                key = s.str("key"),
                name = s.str("title"),
                selected = s.flag("selected"),
                hearingImpaired = s.flag("hearingImpaired"),
                fileOrdinal = inFile.indexOfFirst { it === s }.takeIf { it >= 0 },
            )
        }
        return MediaInfo(
            sourceId = m.str("id").orEmpty(),
            container = m.str("container") ?: part?.str("container"),
            videoCodec = m.str("videoCodec") ?: video?.str("codec"),
            audioCodec = m.str("audioCodec") ?: audio?.str("codec"),
            width = m.int("width") ?: video?.int("width"),
            height = m.int("height") ?: video?.int("height"),
            bitDepth = video?.int("bitDepth"),
            audioChannels = m.int("audioChannels") ?: audio?.int("channels"),
            // Plex gives kilobits per second.
            bitrate = m.long("bitrate")?.let { it * 1000 },
            partKey = part?.str("key"),
            audioLanguage = audio?.str("languageCode") ?: audio?.str("languageTag") ?: audio?.str("language"),
            subtitles = subs,
            streamDetails = streams.isNotEmpty(),
        )
    }

    /** Plex's flags come as true, 1 or "1". */
    private fun JSONObject.flag(key: String): Boolean = when (val v = opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", true)
        else -> false
    }
}
