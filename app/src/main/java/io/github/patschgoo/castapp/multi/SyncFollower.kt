package io.github.patschgoo.castapp.multi

/**
 * A receiver's side of synced playback: measures how its clock relates to the host's (a few
 * NTP-style round trips, repeated now and then) and keeps reading the host's shared timeline
 * from [syncUrl] ("http://host:port/castapp/timeline[?viewer=id]"). The player asks [correction]
 * a few times a second. Runs on its own thread; everything it does is best effort.
 */
class SyncFollower(
    val syncUrl: String,
    /** The clock the player's position is measured against (elapsedRealtime on Android). */
    private val clock: () -> Long,
    private val timelineEveryMs: Long = 2_000,
    private val clockEveryMs: Long = 30_000,
) {
    @Volatile var estimate: ClockEstimate? = null
        private set
    @Volatile var timeline: Timeline? = null
        private set
    /** False once the host says it stopped casting. */
    @Volatile var hostCasting: Boolean = true
        private set
    /** The item of the first timeline seen; a different one means the host moved on. */
    @Volatile var firstItem: String? = null
        private set

    /** Called (on the follower's thread) when the host moved to another item or stopped casting. */
    @Volatile var onHostChanged: ((itemChanged: Boolean, stopped: Boolean) -> Unit)? = null

    private val base: String = syncUrl.substringBefore("/castapp/")
    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastSeekAt = Long.MIN_VALUE / 2

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "sync-follower").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** One round of measuring and reading, for tests and for the first moment after loading. */
    fun refreshNow(measureClock: Boolean) {
        if (measureClock || estimate == null) {
            estimate = ClockSync.combine(estimate, ClockSync.estimate(PeerClient.clockSamples(base, localClock = clock)))
        }
        val answer = PeerClient.timelineAt(syncUrl) ?: return
        val (casting, t) = answer
        val wasCasting = hostCasting
        hostCasting = casting
        if (t != null) {
            if (firstItem == null) firstItem = t.itemKey
            val changed = timeline?.itemKey != null && timeline?.itemKey != t.itemKey
            timeline = t
            if (changed) onHostChanged?.invoke(true, false)
        }
        if (wasCasting && !casting) onHostChanged?.invoke(false, true)
    }

    private fun loop() {
        var lastClock = Long.MIN_VALUE / 2
        while (running) {
            try {
                val now = clock()
                val measure = now - lastClock >= clockEveryMs || estimate == null
                refreshNow(measure)
                if (measure) lastClock = now
                Thread.sleep(timelineEveryMs)
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
            }
        }
    }

    /**
     * What the player should do now, or null when there is nothing to follow (live stream, no
     * clock yet). A jump right after another is skipped: the player is still buffering from it.
     */
    fun correction(localPositionMs: Long, localPlaying: Boolean, currentRate: Float): Correction? {
        val t = timeline ?: return null
        val e = estimate ?: return null
        if (t.live || !hostCasting) return null
        val now = clock()
        val target = t.positionAt(e.toHost(now))
        val c = DriftCorrector.decide(localPositionMs, localPlaying, target, t.playing, currentRate)
        if (c.seekToMs != null) {
            if (now - lastSeekAt < SEEK_COOLDOWN_MS) return c.copy(seekToMs = null, rate = 1f)
            lastSeekAt = now
        }
        return c
    }

    companion object {
        const val SEEK_COOLDOWN_MS = 4_000L
    }
}
