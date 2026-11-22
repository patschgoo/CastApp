package io.github.patschgoo.castapp.mediaservers

import android.content.Context
import io.github.patschgoo.castapp.service.MediaItem
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tells Plex / Jellyfin what the TV is playing, so "continue watching", resume positions and
 * watched marks stay right on the server and in the server's other apps.
 *
 * The cast session calls [started], [progress] and [stopped] for every file it plays; this
 * class works out which of them become requests (one "playing", an update every few
 * seconds or when pausing, one "stopped") and sends them one after the other on its own thread.
 */
class ProgressReporter(
    private val clientFor: (String) -> MediaServerClient?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 4000,
    private val retryDelayMs: Long = 2000,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "server-progress").apply { isDaemon = true } },
) {
    private class Active(val ref: ItemRef, var paused: Boolean, var lastSent: Long)

    private val lock = Any()
    private var active: Active? = null

    /** The TV began playing [ref]. Calling it again for the same item (a reload) only updates the position. */
    fun started(ref: ItemRef, positionMs: Long, durationMs: Long) {
        val now = clock()
        synchronized(lock) {
            val a = active
            if (a != null && a.ref == ref) {
                a.lastSent = now
                a.paused = false
                send(ref) { it.reportProgress(ref, positionMs, durationMs, false) }
            } else {
                active = Active(ref, false, now)
                send(ref) { it.reportStart(ref, positionMs, durationMs) }
            }
        }
    }

    fun progress(ref: ItemRef, positionMs: Long, durationMs: Long, paused: Boolean) {
        val now = clock()
        synchronized(lock) {
            val a = active ?: return
            if (a.ref != ref) return
            if (paused == a.paused && now - a.lastSent < minIntervalMs) return
            a.paused = paused
            a.lastSent = now
            send(ref) { it.reportProgress(ref, positionMs, durationMs, paused) }
        }
    }

    /** Playback of [ref] ended; [finished]: it played to the end. Further calls for it are ignored. */
    fun stopped(ref: ItemRef, positionMs: Long, durationMs: Long, finished: Boolean) {
        synchronized(lock) {
            val a = active ?: return
            if (a.ref != ref) return
            active = null
            send(ref, retries = 2) { it.reportStop(ref, positionMs, durationMs, finished) }
        }
    }

    private fun send(ref: ItemRef, retries: Int = 0, call: (MediaServerClient) -> Unit) {
        executor.execute {
            // The account may be gone (signed out): then there is nobody to tell.
            val client = clientFor(ref.accountId) ?: return@execute
            var left = retries
            while (true) {
                try {
                    call(client)
                    return@execute
                } catch (e: ServerHttpException) {
                    if (left-- <= 0 || e.code in 400..499) return@execute
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                }
            }
        }
    }

    /** Waits until everything queued so far was sent (tests). */
    fun drain(timeoutMs: Long = 5000) {
        executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

/**
 * The hook the cast session calls for every item it plays. Items that don't come from a
 * Plex / Jellyfin server (no [MediaItem.serverRef]) cost nothing.
 */
object ServerReporting {
    @Volatile private var reporter: ProgressReporter? = null

    private fun reporter(context: Context): ProgressReporter = reporter ?: synchronized(this) {
        reporter ?: run {
            val servers = MediaServers.get(context)
            ProgressReporter({ id -> servers.clientFor(id) }).also { reporter = it }
        }
    }

    fun started(context: Context, item: MediaItem, positionMs: Long, durationMs: Long) {
        val ref = ItemRef.decode(item.serverRef) ?: return
        reporter(context).started(ref, positionMs, durationMs)
    }

    fun progress(context: Context, item: MediaItem, positionMs: Long, durationMs: Long, paused: Boolean) {
        val ref = ItemRef.decode(item.serverRef) ?: return
        reporter(context).progress(ref, positionMs, durationMs, paused)
    }

    fun stopped(context: Context, item: MediaItem, positionMs: Long, durationMs: Long, finished: Boolean) {
        val ref = ItemRef.decode(item.serverRef) ?: return
        reporter(context).stopped(ref, positionMs, durationMs, finished)
    }
}
