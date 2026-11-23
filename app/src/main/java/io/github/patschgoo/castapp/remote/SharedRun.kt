package io.github.patschgoo.castapp.remote

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts something several users share (a remote provider's discovery) with the first user and
 * stops it once the last one is gone. Each user holds a [Lease] and closes it when done; closing
 * a lease twice counts once, so a stray close can't stop someone else's discovery.
 *
 * The stop waits [lingerMs]: a screen that turns (configuration change) or the home remote handing
 * over to the full remote releases and acquires again within that time, and discovery keeps running
 * instead of being torn down and started afresh. Thread-safe; [start] and [stop] run under the lock,
 * so they never overlap and always alternate.
 *
 * Nothing is saved: after the process dies every count starts at zero, and the screens that come
 * back acquire again.
 */
class SharedRun(
    private val lingerMs: Long,
    private val timer: Timer,
    private val start: () -> Unit,
    private val stop: () -> Unit,
) {
    /** Runs a task later, on any thread. */
    fun interface Timer {
        fun schedule(delayMs: Long, task: () -> Unit): Cancel
    }

    fun interface Cancel {
        fun cancel()
    }

    private var users = 0
    private var running = false
    private var pendingStop: Cancel? = null
    /** Tells a scheduled stop that it was overtaken by a later acquire / release. */
    private var generation = 0L

    val activeUsers: Int @Synchronized get() = users
    val isRunning: Boolean @Synchronized get() = running

    @Synchronized
    fun acquire(): Lease {
        users++
        generation++
        pendingStop?.cancel()
        pendingStop = null
        if (!running) {
            running = true
            // A start that fails half-way still counts as running: the stop tidies up what it began.
            try { start() } catch (_: Exception) {}
        }
        return Lease(this)
    }

    @Synchronized
    private fun release() {
        if (users == 0) return
        users--
        if (users > 0 || !running) return
        if (lingerMs <= 0) {
            stopNow()
            return
        }
        val mine = ++generation
        pendingStop = timer.schedule(lingerMs) { stopIfUnused(mine) }
    }

    @Synchronized
    private fun stopIfUnused(scheduled: Long) {
        if (scheduled != generation || users > 0 || !running) return
        pendingStop = null
        stopNow()
    }

    private fun stopNow() {
        running = false
        try { stop() } catch (_: Exception) {}
    }

    /** One user's hold on the run. */
    class Lease internal constructor(private val owner: SharedRun) : Closeable {
        private val closed = AtomicBoolean(false)
        val isClosed: Boolean get() = closed.get()

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.release()
        }
    }
}
