package io.github.patschgoo.castapp.automation

import android.content.ContentResolver
import android.content.Context
import androidx.core.net.toUri
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.multi.PendingStart
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.WatchHistory

/** Hands a checked CAST_QUEUE to the casting service: from the automation API and from the "Cast to TV" sheet. */
object QueueCast {
    /**
     * The play list, with names and types of content:// addresses from the ContentResolver, asked
     * only for what the caller left out (this runs on the main thread).
     */
    fun mediaItems(resolver: ContentResolver, queue: CastQueueRequest): List<MediaItem> {
        val names = HashMap<String, String>()
        val types = HashMap<String, String>()
        for (item in queue.items) {
            if (item.isContent && (item.title == null || item.mime == null)) {
                MediaItem.displayName(resolver, item.url.toUri())?.let { names[item.url] = it }
            }
            if (item.isContent && item.mime == null) {
                try {
                    resolver.getType(item.url.toUri())?.let { types[item.url] = it }
                } catch (_: Exception) {
                }
            }
            for (sub in item.subtitles) {
                if (isContentUri(sub.url) && sub.language == null && sub.label == null) {
                    MediaItem.displayName(resolver, sub.url.toUri())?.let { names[sub.url] = it }
                }
            }
        }
        return queue.items.map { QueueMapping.toMediaItem(it, names, types) }
    }

    /**
     * Starts the cast (and its reports, when asked for). Throws what starting the service throws:
     * Android 12+ refuses it from the background unless CastApp is exempt from battery optimization.
     */
    fun start(context: Context, device: CastDevice, queue: CastQueueRequest, items: List<MediaItem>) {
        val first = items[queue.startIndex]
        val at = queue.startPositionMs
        if (at != null && at > 0) PendingStart.set(first.uri, at)
        val target = ReportTarget.of(queue)
        target?.let { AppReports.expect(context, it) }
        try {
            CastService.startMedia(context, device, items, queue.startIndex)
        } catch (e: Exception) {
            target?.let(AppReports::cancel)
            if (at != null && at > 0) PendingStart.take(first.uri)
            throw e
        }
        // 0: from the beginning, so forget where this phone stopped it (the session reads that only once it runs).
        if (at == 0L) {
            val history = WatchHistory.get(context)
            if (history.resumePosition(first) != null) history.remove(first.uri)
        }
    }
}
