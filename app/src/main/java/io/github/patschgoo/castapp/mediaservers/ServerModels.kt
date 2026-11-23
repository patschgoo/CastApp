package io.github.patschgoo.castapp.mediaservers

import io.github.patschgoo.castapp.service.MediaItem
import java.net.URLDecoder
import java.net.URLEncoder

enum class ServerKind(val label: String) {
    JELLYFIN("Jellyfin"),
    EMBY("Emby"),
    PLEX("Plex");

    val isPlex: Boolean get() = this == PLEX
}

/** A signed-in server (the sign-in token is kept encrypted by [MediaServers]). */
data class ServerAccount(
    val id: String,
    val kind: ServerKind,
    val name: String,
    /** Address of the server, e.g. http://192.168.1.5:8096, without a trailing slash. */
    val baseUrl: String,
    val token: String,
    /** Jellyfin / Emby user id. */
    val userId: String = "",
    val userName: String = "",
    /** Plex machine identifier, Jellyfin server id. */
    val serverId: String = "",
    /** Accept a self-signed HTTPS certificate (own server). */
    val trustAll: Boolean = false,
    /** Other addresses of the same server, tried when [baseUrl] doesn't answer (Plex connections). */
    val alternates: List<String> = emptyList(),
)

/** What the server needs to know about this app. */
data class ClientInfo(
    val deviceId: String,
    val deviceName: String,
    val version: String,
    val product: String = "CastApp",
)

enum class ItemKind { LIBRARY, MOVIE, SERIES, SEASON, EPISODE, ALBUM, ARTIST, TRACK, FOLDER, COLLECTION, VIDEO, PHOTO, OTHER }

/** One subtitle track of a video. */
data class SubtitleTrack(
    /** Jellyfin: stream index. Plex: stream id. */
    val index: Int,
    val codec: String,
    val language: String?,
    val title: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean,
    /** Plex: where the file of an external subtitle can be fetched. */
    val key: String? = null,
    /** The track's own name in the file ("Signs & Songs", "Full"), where the server tells it apart from [title]. */
    val name: String? = null,
    /** Plex: the one picked for this item in Plex (by hand, or by the account's subtitle settings). */
    val selected: Boolean = false,
    /** For the deaf and hard of hearing (SDH). */
    val hearingImpaired: Boolean = false,
    /**
     * Plex: a track inside the video file, its place among the file's subtitle tracks (in the
     * file's order, picture ones included: the order Media3 reads them in). Null for external ones.
     */
    val fileOrdinal: Int? = null,
) {
    val isText: Boolean get() = codec.lowercase() in TEXT_CODECS

    private val words: String get() = listOfNotNull(name, title).joinToString(" ")

    /** Only part of what is said: forced, or "Signs & Songs" (anime releases rarely flag those forced). */
    val isPartial: Boolean get() = isForced || (PARTIAL.containsMatchIn(words) && !FULL.containsMatchIn(words))

    val isSdh: Boolean get() = hearingImpaired || SDH.containsMatchIn(words)

    companion object {
        val TEXT_CODECS = setOf("subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "ttml", "text", "smi", "subviewer")

        private val PARTIAL = Regex("""(?i)\b(signs?|songs?|forced|karaoke|s\s*&\s*s)\b""")
        private val FULL = Regex("""(?i)\b(full|dialog(ue)?s?)\b""")
        private val SDH = Regex("""(?i)\b(sdh|cc|hearing[ -]impaired)\b""")
    }
}

/** How a file is stored on the server, to decide whether the TV can play it as it is. */
data class MediaInfo(
    /** Jellyfin media source id; Plex media id. */
    val sourceId: String,
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int?,
    val height: Int?,
    val bitDepth: Int?,
    val audioChannels: Int?,
    /** Overall bit rate in bits per second. */
    val bitrate: Long?,
    /** Plex: the file's address on the server (/library/parts/...). */
    val partKey: String? = null,
    val audioLanguage: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    /** Plex: the answer listed the file's streams; lists of items leave them out ([subtitles] is then empty). */
    val streamDetails: Boolean = false,
)

/** A library, folder, show, season, movie, episode, album or track on a server. */
data class ServerItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    /** A second line: the artist, the show, the year... */
    val subtitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val runtimeMs: Long = 0,
    /** Where the user stopped watching (0 = not started). */
    val resumeMs: Long = 0,
    val played: Boolean = false,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** Episode thumbnails and the like are wide, not tall. */
    val wide: Boolean = false,
    val childCount: Int? = null,
    val unplayedCount: Int? = null,
    /** Show / album the item belongs to. */
    val parentId: String? = null,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** Library type of a [ItemKind.LIBRARY]: movies, tvshows, music... */
    val collectionType: String? = null,
    val media: MediaInfo? = null,
) {
    val isPlayable: Boolean
        get() = kind == ItemKind.MOVIE || kind == ItemKind.EPISODE || kind == ItemKind.TRACK || kind == ItemKind.VIDEO || kind == ItemKind.PHOTO

    /** 0..1 how far the item was watched, or -1. */
    val progress: Float get() = if (resumeMs > 0 && runtimeMs > 0) (resumeMs.toFloat() / runtimeMs).coerceIn(0f, 1f) else -1f

    /** "S2 E5" */
    val episodeLabel: String?
        get() = when {
            seasonNumber != null && episodeNumber != null -> "S$seasonNumber E$episodeNumber"
            episodeNumber != null -> "E$episodeNumber"
            else -> null
        }
}

/** One entry of a [PlaybackPlan]. */
class PlanEntry(val item: MediaItem, val resumeMs: Long, val durationMs: Long)

/** The play list built for one tap: the item and the ones that follow it. */
class PlaybackPlan(val entries: List<PlanEntry>) {
    val items: List<MediaItem> get() = entries.map { it.item }
    val resumeMs: Long get() = entries.firstOrNull()?.resumeMs ?: 0
}

enum class PlayMethod { DIRECT, TRANSCODE }

/**
 * What goes along in a [MediaItem] so the casting code can report progress to the right
 * server without knowing anything about servers.
 */
data class ItemRef(
    val kind: ServerKind,
    val accountId: String,
    val itemId: String,
    /** Jellyfin media source id. */
    val sourceId: String,
    val sessionId: String,
    val method: PlayMethod,
) {
    fun encode(): String = listOf(PREFIX, kind.name, accountId, itemId, sourceId, sessionId, method.name).joinToString("|") { enc(it) }

    companion object {
        private const val PREFIX = "ms1"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun decode(text: String?): ItemRef? {
            if (text == null || !text.startsWith("$PREFIX|")) return null
            val p = text.split('|')
            if (p.size != 7) return null
            return try {
                val d = p.map { URLDecoder.decode(it, "UTF-8") }
                ItemRef(ServerKind.valueOf(d[1]), d[2], d[3], d[4], d[5], PlayMethod.valueOf(d[6]))
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Failed request; [code] is the HTTP status (0 = no answer). */
class ServerHttpException(val code: Int, message: String) : java.io.IOException(message)
