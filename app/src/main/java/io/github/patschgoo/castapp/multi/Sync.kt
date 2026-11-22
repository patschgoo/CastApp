package io.github.patschgoo.castapp.multi

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One NTP-style round trip to the host: [t0] sent and [t3] answer received on the local
 * clock, [t1] request received and [t2] answer sent on the host's clock (milliseconds).
 */
data class ClockSample(val t0: Long, val t1: Long, val t2: Long, val t3: Long) {
    /** Host clock minus local clock, assuming the way there took as long as the way back. */
    val offsetMs: Double get() = ((t1 - t0) + (t2 - t3)) / 2.0

    /** Time on the network (without the host's own processing time). */
    val roundTripMs: Long get() = (t3 - t0) - (t2 - t1)
}

/** How the host's clock relates to ours: host = local + [offsetMs]. */
data class ClockEstimate(val offsetMs: Long, val roundTripMs: Long) {
    fun toHost(localMs: Long): Long = localMs + offsetMs
    fun toLocal(hostMs: Long): Long = hostMs - offsetMs

    /** Half the round trip: how far off the estimate can be at most. */
    val uncertaintyMs: Long get() = roundTripMs / 2
}

object ClockSync {
    /** Round trips to take per estimate. */
    const val SAMPLES = 6

    /**
     * The clock offset from a few round trips. Wi-Fi queues make some trips slow and lopsided,
     * so only the quickest trips count (their offsets are averaged). Null without a usable sample.
     */
    fun estimate(samples: List<ClockSample>): ClockEstimate? {
        val valid = samples.filter { it.t3 >= it.t0 && it.t2 >= it.t1 && it.roundTripMs >= 0 }
        if (valid.isEmpty()) return null
        val fastest = valid.minOf { it.roundTripMs }
        // Anything within a few ms of the fastest trip is as good; slower trips are skewed.
        val good = valid.filter { it.roundTripMs <= fastest + GOOD_EXTRA_MS }
        val offset = good.sumOf { it.offsetMs } / good.size
        return ClockEstimate(offset.roundToLongCompat(), fastest)
    }

    /**
     * Keeps the estimate steady: a new estimate from slower round trips than the one in use
     * only nudges it, one from equally quick trips replaces it.
     */
    fun combine(previous: ClockEstimate?, next: ClockEstimate?): ClockEstimate? {
        if (previous == null) return next
        if (next == null) return previous
        val jump = abs(next.offsetMs - previous.offsetMs)
        return when {
            next.roundTripMs <= previous.roundTripMs + GOOD_EXTRA_MS -> next
            // A slower measurement that disagrees a lot: the clocks really moved (sleep, NTP update).
            jump > next.uncertaintyMs + previous.uncertaintyMs -> next
            else -> ClockEstimate((previous.offsetMs * 3 + next.offsetMs) / 4, (previous.roundTripMs * 3 + next.roundTripMs) / 4)
        }
    }

    private const val GOOD_EXTRA_MS = 4L

    private fun Double.roundToLongCompat(): Long = if (this >= 0) (this + 0.5).toLong() else -((-this + 0.5).toLong())
}

/**
 * Where playback is on the host's clock: at host time [anchorHostMs] the item [itemKey] was
 * at [anchorPositionMs] and goes on from there in real time while [playing].
 * [generation] changes with every jump (new item, seek, pause), so followers can tell.
 */
data class Timeline(
    val itemKey: String,
    val anchorPositionMs: Long,
    val anchorHostMs: Long,
    val playing: Boolean,
    val live: Boolean,
    val generation: Int,
) {
    fun positionAt(hostMs: Long): Long = if (!playing || live) anchorPositionMs else anchorPositionMs + (hostMs - anchorHostMs)

    fun toJson(): JSONObject = JSONObject()
        .put("item", itemKey)
        .put("position", anchorPositionMs)
        .put("at", anchorHostMs)
        .put("playing", playing)
        .put("live", live)
        .put("generation", generation)

    companion object {
        fun fromJson(o: JSONObject?): Timeline? {
            if (o == null || !o.has("at")) return null
            return Timeline(
                itemKey = o.optString("item"),
                anchorPositionMs = o.optLong("position"),
                anchorHostMs = o.optLong("at"),
                playing = o.optBoolean("playing"),
                live = o.optBoolean("live"),
                generation = o.optInt("generation"),
            )
        }
    }
}

/**
 * The host's shared timeline of a file cast. The first screen (whatever kind of TV it is) is the
 * reference: its reports move the timeline when they disagree by more than [reanchorMs].
 * CastApp receivers and viewers who joined follow the timeline.
 */
class HostTimeline(private val clock: () -> Long, private val reanchorMs: Long = REANCHOR_MS) {
    @Volatile var current: Timeline? = null
        private set
    private var generation = 0

    @Synchronized
    fun start(itemKey: String, positionMs: Long, playing: Boolean, live: Boolean, atHostMs: Long = clock()) {
        current = Timeline(itemKey, positionMs.coerceAtLeast(0), atHostMs, playing, live, ++generation)
    }

    /** The reference screen reported [positionMs] for [itemKey] just now. */
    @Synchronized
    fun observe(itemKey: String, positionMs: Long, playing: Boolean, live: Boolean) {
        val now = clock()
        val t = current
        if (t == null || t.itemKey != itemKey || t.playing != playing || t.live != live) {
            start(itemKey, positionMs, playing, live, now)
            return
        }
        if (!live && abs(t.positionAt(now) - positionMs) > reanchorMs) start(itemKey, positionMs, playing, live, now)
    }

    /** Pause, play or seek from this device: everybody moves at [atHostMs] (a moment ahead, so all can be there). */
    @Synchronized
    fun jump(positionMs: Long, playing: Boolean, atHostMs: Long = clock()) {
        val t = current ?: return
        current = Timeline(t.itemKey, positionMs.coerceAtLeast(0), atHostMs, playing, t.live, ++generation)
    }

    @Synchronized
    fun clear() {
        current = null
    }

    companion object {
        /** Reports from a DLNA TV are only accurate to the second: move the timeline only beyond that. */
        const val REANCHOR_MS = 1200L
    }
}

/** What a following player should do to stay with the timeline. */
data class Correction(
    /** Jump there first (the player is too far off to catch up smoothly). */
    val seekToMs: Long? = null,
    /** Playback speed from now on (1 = normal). */
    val rate: Float = 1f,
    /** Start (true) or pause (false) playback; null = leave it. */
    val play: Boolean? = null,
) {
    companion object {
        val NONE = Correction()
    }
}

/**
 * Decides how a receiver keeps in sync: small differences are evened out by playing up to
 * [MAX_ADJUST] faster or slower (inaudible for speech and music), big ones with a jump.
 */
object DriftCorrector {
    /** Closer than this counts as in sync. */
    const val DEADBAND_MS = 40L
    /** While already correcting: keep going until this close (no flapping at the edge). */
    const val SETTLE_MS = 15L
    /** Further off than this: jump instead of catching up. */
    const val SEEK_MS = 1500L
    /** A paused player this far from the pause position is moved there. */
    const val PAUSED_SEEK_MS = 250L
    const val MAX_ADJUST = 0.05f
    /** Speed change per ms of difference: 100 ms off → 5 % faster or slower, done in about 2 s. */
    private const val GAIN = 0.0005f

    /**
     * [localPositionMs] where the player is, [targetMs] where it should be (the timeline at the
     * host's "now"), [currentRate] its speed right now.
     */
    fun decide(
        localPositionMs: Long,
        localPlaying: Boolean,
        targetMs: Long,
        targetPlaying: Boolean,
        currentRate: Float = 1f,
    ): Correction {
        if (!targetPlaying) {
            val seek = if (abs(localPositionMs - targetMs) > PAUSED_SEEK_MS) targetMs.coerceAtLeast(0) else null
            return Correction(seekToMs = seek, rate = 1f, play = if (localPlaying) false else null)
        }
        if (targetMs < 0) {
            // The shared start is still ahead: wait at the beginning.
            return Correction(seekToMs = if (localPositionMs > PAUSED_SEEK_MS) 0 else null, play = if (localPlaying) false else null)
        }
        val drift = localPositionMs - targetMs
        val play = if (localPlaying) null else true
        if (abs(drift) > SEEK_MS) return Correction(seekToMs = targetMs, rate = 1f, play = play)
        val correcting = currentRate != 1f
        val limit = if (correcting) SETTLE_MS else DEADBAND_MS
        if (abs(drift) <= limit) return Correction(rate = 1f, play = play)
        val adjust = (drift * GAIN).coerceIn(-MAX_ADJUST, MAX_ADJUST)
        // Whole percents only, so the player isn't given a new speed on every tick.
        val rate = ((1f - adjust) * 100f).roundToInt() / 100f
        return Correction(rate = if (rate == 1f) (if (drift > 0) 0.99f else 1.01f) else rate, play = play)
    }
}
