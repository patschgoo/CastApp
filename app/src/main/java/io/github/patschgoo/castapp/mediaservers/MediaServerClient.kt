package io.github.patschgoo.castapp.mediaservers

/**
 * What the screens and the casting code need from a Plex or Jellyfin server. All calls
 * block (they are made on a background thread) and throw [ServerHttpException].
 */
interface MediaServerClient {
    val account: ServerAccount

    /** The server's libraries (movies, shows, music...). */
    fun libraries(): List<ServerItem>

    /** What is inside a library, folder, show, season, album... */
    fun children(parent: ServerItem): List<ServerItem>

    /** Started and not finished: movies and episodes, most recent first. */
    fun continueWatching(): List<ServerItem>

    /** The next unwatched episode of the shows being followed. */
    fun nextUp(): List<ServerItem>

    /** The item with everything needed to play it (media streams, subtitles, resume position). */
    fun detail(item: ServerItem): ServerItem

    /** The play list for [item]: the item itself and, for episodes and tracks, the ones that follow it. */
    fun plan(item: ServerItem, queueFollowing: Boolean = true): PlaybackPlan

    fun setWatched(item: ServerItem, watched: Boolean)

    fun reportStart(ref: ItemRef, positionMs: Long, durationMs: Long)
    fun reportProgress(ref: ItemRef, positionMs: Long, durationMs: Long, paused: Boolean)
    fun reportStop(ref: ItemRef, positionMs: Long, durationMs: Long, finished: Boolean)

    companion object {
        /** Episodes queued behind the one that was picked. */
        const val QUEUE_AHEAD = 30
    }
}

/** "Show · S1 E3 · Title" for the TV and the notification. */
internal fun displayTitle(item: ServerItem): String = when (item.kind) {
    ItemKind.EPISODE -> listOfNotNull(item.seriesTitle, item.episodeLabel, item.title).joinToString(" · ")
    ItemKind.TRACK -> listOfNotNull(item.subtitle, item.title).joinToString(" - ")
    else -> item.title
}
