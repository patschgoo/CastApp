package io.github.patschgoo.castapp.automation

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import io.github.patschgoo.castapp.service.ActiveCast
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.ResumeRules
import java.util.concurrent.Executors

/** Where the playback broadcasts of one CAST_QUEUE request go, and the caller's ids of its items. */
class ReportTarget(
    val packageName: String,
    val requestId: String?,
    /** The items' addresses in the order of the request. */
    val urls: List<String>,
    private val ids: List<String?>,
) {
    data class Item(val id: String?, val index: Int)

    private val byUrl: Map<String, Item> = urls.withIndex().associate { (i, url) -> url to Item(ids.getOrNull(i), i) }

    fun itemFor(url: String): Item? = byUrl[url]

    companion object {
        /** Null when the request asked for no reports. */
        fun of(queue: CastQueueRequest): ReportTarget? = queue.reportTo?.let { pkg ->
            ReportTarget(pkg, queue.requestId, queue.items.map { it.url }, queue.items.map { it.id })
        }
    }
}

/** One PLAYBACK broadcast. */
data class PlaybackEvent(
    val state: String,
    val itemId: String?,
    val itemIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val device: String,
    val reason: String? = null,
    val message: String? = null,
) {
    /** The broadcast's extras (no Android code, so the contract is tested on the JVM). */
    fun extras(requestId: String?): Map<String, Any> = buildMap {
        put(AutomationApi.PLAYBACK_EXTRA_STATE, state)
        itemId?.let { put(AutomationApi.PLAYBACK_EXTRA_ITEM_ID, it) }
        put(AutomationApi.PLAYBACK_EXTRA_ITEM_INDEX, itemIndex)
        put(AutomationApi.PLAYBACK_EXTRA_POSITION, positionMs)
        put(AutomationApi.PLAYBACK_EXTRA_DURATION, durationMs)
        put(AutomationApi.PLAYBACK_EXTRA_DEVICE, device)
        reason?.let { put(AutomationApi.PLAYBACK_EXTRA_REASON, it) }
        message?.let { put(AutomationApi.PLAYBACK_EXTRA_MESSAGE, it) }
        requestId?.let { put(AutomationApi.EXTRA_REQUEST_ID, it) }
    }
}

/**
 * Turns what the cast session reports about one queue (the same calls it makes for Plex /
 * Jellyfin) into PLAYBACK events: "started" once per item, "paused" / "resumed" when that
 * changes, "progress" at most every [progressEveryMs] while playing, then one of "stopped",
 * "finished" or "failed". Late calls for an item that already ended are ignored. No Android code.
 */
class PlaybackReporter(
    private val target: ReportTarget,
    private val clock: () -> Long,
    private val progressEveryMs: Long = PROGRESS_EVERY_MS,
    private val emit: (PlaybackEvent) -> Unit,
) {
    private class Playing(val url: String, val item: ReportTarget.Item, var device: String, var position: Long, var duration: Long, var paused: Boolean, var lastSent: Long)

    private val lock = Any()
    private var playing: Playing? = null
    /** Items that ended since they last started. */
    private val ended = HashSet<String>()

    /** The TV began playing [url]; again for the same item (a reload, another quality) only updates it. */
    fun started(url: String, positionMs: Long, durationMs: Long, device: String) {
        synchronized(lock) {
            val item = target.itemFor(url) ?: return
            val now = clock()
            val p = playing
            if (p != null && p.url == url) {
                p.position = positionMs
                if (durationMs > 0) p.duration = durationMs
                p.device = device
                if (p.paused) {
                    p.paused = false
                    p.lastSent = now
                    send(AutomationApi.STATE_RESUMED, p)
                }
                return
            }
            // It went on to this one without saying the last one stopped.
            if (p != null) end(p, p.position, p.duration, playedToEnd = false)
            ended.remove(url)
            val started = Playing(url, item, device, positionMs, durationMs, paused = false, lastSent = now)
            playing = started
            send(AutomationApi.STATE_STARTED, started)
        }
    }

    fun progress(url: String, positionMs: Long, durationMs: Long, paused: Boolean, device: String) {
        synchronized(lock) {
            val p = playing?.takeIf { it.url == url } ?: return
            p.position = positionMs
            if (durationMs > 0) p.duration = durationMs
            p.device = device
            val now = clock()
            if (paused != p.paused) {
                p.paused = paused
                p.lastSent = now
                send(if (paused) AutomationApi.STATE_PAUSED else AutomationApi.STATE_RESUMED, p)
            } else if (!paused && now - p.lastSent >= progressEveryMs) {
                p.lastSent = now
                send(AutomationApi.STATE_PROGRESS, p)
            }
        }
    }

    /** Playback of [url] ended; [playedToEnd]: the TV said it reached the end. */
    fun stopped(url: String, positionMs: Long, durationMs: Long, playedToEnd: Boolean, device: String) {
        synchronized(lock) {
            val p = playing?.takeIf { it.url == url } ?: return
            p.device = device
            // The TV had not said where it was yet: keep the last position it did say.
            val position = if (positionMs > 0 || playedToEnd) positionMs else p.position
            end(p, position, if (durationMs > 0) durationMs else p.duration, playedToEnd)
        }
    }

    /** [url] could not be played (or stopped playing because of an error); [reason] is a REASON_ value. */
    fun failed(url: String, reason: String, device: String) {
        synchronized(lock) {
            val item = target.itemFor(url) ?: return
            val p = playing?.takeIf { it.url == url }
            if (p == null && url in ended) return
            if (p != null) playing = null
            ended += url
            emit(PlaybackEvent(AutomationApi.STATE_FAILED, item.id, item.index, p?.position ?: 0, p?.duration ?: 0, device, reason, messageFor(reason)))
        }
    }

    /** The queue is no longer this one (replaced, or the cast ended): the playing item stopped where it was seen last. */
    fun close() {
        synchronized(lock) {
            playing?.let { end(it, it.position, it.duration, playedToEnd = false) }
        }
    }

    private fun end(p: Playing, positionMs: Long, durationMs: Long, playedToEnd: Boolean) {
        playing = null
        ended += p.url
        val finished = playedToEnd || isFinished(positionMs, durationMs)
        p.position = if (playedToEnd && durationMs > 0) durationMs else positionMs
        p.duration = durationMs
        send(if (finished) AutomationApi.STATE_FINISHED else AutomationApi.STATE_STOPPED, p)
    }

    private fun send(state: String, p: Playing) = emit(PlaybackEvent(state, p.item.id, p.item.index, p.position, p.duration, p.device))

    companion object {
        const val PROGRESS_EVERY_MS = 30_000L

        /**
         * Stopped near the end counts as watched: at most 10 % left (what Plex and Jellyfin count
         * as played), or what "Continue watching" counts as watched ([ResumeRules.isFinished]).
         */
        fun isFinished(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (positionMs * 10 >= durationMs * 9 || ResumeRules.isFinished(positionMs, durationMs))

        fun messageFor(reason: String): String = when (reason) {
            AutomationApi.REASON_CANNOT_PLAY -> "The TV could not play this item"
            AutomationApi.REASON_CONNECTION_LOST -> "The connection to the TV was lost"
            AutomationApi.REASON_CAST_FAILED -> "CastApp could not start casting to the TV"
            else -> "Playback failed"
        }
    }
}

/**
 * Which cast session plays a reported queue. The executor announces the queue ([expect]) just
 * before it hands it to the casting service; the session that then starts with exactly these
 * items (or replaces its play list with them) is bound to it, until its play list is replaced
 * by something else or it ends. Sessions that play anything else never report. No Android code.
 */
class ReportBinding(
    private val clock: () -> Long,
    private val newReporter: (ReportTarget) -> PlaybackReporter,
    private val pendingForMs: Long = 60_000,
) {
    private var pending: ReportTarget? = null
    private var pendingAt = 0L
    private var session: Any? = null
    private var reporter: PlaybackReporter? = null

    /** Nothing announced and nothing bound: the session's calls cost nothing. */
    val idle: Boolean @Synchronized get() = pending == null && session == null

    @Synchronized fun expect(target: ReportTarget) {
        pending = target
        pendingAt = clock()
    }

    /** The cast could not be started after all. */
    @Synchronized fun cancel(target: ReportTarget) {
        if (pending === target) pending = null
    }

    /** [session] started with [urls], or replaced its whole play list with them. */
    @Synchronized fun onQueue(session: Any, urls: List<String>) {
        val p = pending?.takeIf { clock() - pendingAt <= pendingForMs }
        if (p == null) pending = null
        if (p != null && p.urls == urls) {
            reporter?.close()
            this.session = session
            reporter = newReporter(p)
            pending = null
        } else if (this.session === session) {
            unbind()
        }
    }

    @Synchronized fun reporterFor(session: Any): PlaybackReporter? = if (this.session === session) reporter else null

    @Synchronized fun ended(session: Any) {
        if (this.session === session) unbind()
    }

    private fun unbind() {
        reporter?.close()
        reporter = null
        session = null
    }
}

/**
 * The hooks the media cast session calls (next to its Plex / Jellyfin reporting), and the
 * broadcasts: explicit, to the reporting app's package only, sent on their own thread.
 */
object AppReports {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "automation-reports").apply { isDaemon = true } }
    @Volatile private var appContext: Context? = null
    private val binding = ReportBinding(SystemClock::elapsedRealtime, { target ->
        PlaybackReporter(target, SystemClock::elapsedRealtime) { event -> post(target, event) }
    })

    fun expect(context: Context, target: ReportTarget) {
        appContext = context.applicationContext
        binding.expect(target)
    }

    fun cancel(target: ReportTarget) = binding.cancel(target)

    // ---- called by MediaCastSession ---------------------------------------------------------------

    fun onQueue(session: ActiveCast, items: List<MediaItem>) {
        if (!binding.idle) binding.onQueue(session, items.map { it.uri })
    }

    fun started(session: ActiveCast, item: MediaItem, positionMs: Long, durationMs: Long) {
        if (!binding.idle) binding.reporterFor(session)?.started(item.uri, positionMs, durationMs, session.device.name)
    }

    fun progress(session: ActiveCast, item: MediaItem, positionMs: Long, durationMs: Long, paused: Boolean) {
        if (!binding.idle) binding.reporterFor(session)?.progress(item.uri, positionMs, durationMs, paused, session.device.name)
    }

    fun stopped(session: ActiveCast, item: MediaItem, positionMs: Long, durationMs: Long, playedToEnd: Boolean) {
        if (!binding.idle) binding.reporterFor(session)?.stopped(item.uri, positionMs, durationMs, playedToEnd, session.device.name)
    }

    fun failed(session: ActiveCast, item: MediaItem, reason: String) {
        if (!binding.idle) binding.reporterFor(session)?.failed(item.uri, reason, session.device.name)
    }

    fun ended(session: ActiveCast) {
        if (!binding.idle) binding.ended(session)
    }

    // ---- sending ------------------------------------------------------------------------------------

    private fun post(target: ReportTarget, event: PlaybackEvent) {
        val context = appContext ?: return
        executor.execute { send(context, target, event) }
    }

    private fun send(context: Context, target: ReportTarget, event: PlaybackEvent) {
        val intent = Intent(AutomationApi.ACTION_PLAYBACK).setPackage(target.packageName)
        for ((key, value) in event.extras(target.requestId)) {
            when (value) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Lets the receiver check getSentFromPackage(): nobody else can fake CastApp's reports.
                context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            } else {
                context.sendBroadcast(intent)
            }
        } catch (_: Exception) {
        }
    }
}
