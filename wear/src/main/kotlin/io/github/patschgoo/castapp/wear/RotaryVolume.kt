package io.github.patschgoo.castapp.wear

import io.github.patschgoo.castapp.wearproto.NowPlaying
import io.github.patschgoo.castapp.wearproto.WearCodec
import io.github.patschgoo.castapp.wearproto.WearCommand
import io.github.patschgoo.castapp.wearproto.WearMessage
import kotlin.math.abs

/**
 * Turns rotary scroll deltas into volume steps; clockwise (a positive delta) is up.
 *
 * A physical bezel (Galaxy Watch Classic) and the Galaxy touch bezel report one event per notch
 * ([discrete]): each event is one step, whatever its size. A crown (Pixel Watch, TicWatch) reports
 * many small deltas: [notchPx] of them in one direction make a step, and what is left over counts
 * for the next one. Turning back starts from zero, and a pause of [pauseMs] forgets a half-turned
 * notch.
 *
 * Steps in one direction come at most [maxStepsPerSecond] times a second (faster notches are
 * dropped), so a fast spin cannot throw the TV to full volume; turning back is always taken at once.
 * No Android code, so it is tested on the JVM.
 */
class RotarySteps(
    private val discrete: Boolean,
    private val notchPx: Float,
    maxStepsPerSecond: Int = MAX_STEPS_PER_SECOND,
    private val pauseMs: Long = PAUSE_MS,
) {
    private val minStepIntervalMs = 1000L / maxStepsPerSecond.coerceAtLeast(1)
    private var accumulated = 0f
    private var lastEventAt: Long? = null
    private var lastStepAt: Long? = null
    private var lastDirection = 0

    /** Feeds one rotary event; returns +1 (up), -1 (down) or 0 (no step yet, or too fast). */
    fun onDelta(deltaPx: Float, timeMs: Long): Int {
        if (deltaPx == 0f || deltaPx.isNaN()) return 0
        val direction = if (deltaPx > 0f) 1 else -1
        val last = lastEventAt
        lastEventAt = timeMs
        // A new turn, or turning back: start from zero instead of first undoing the partial notch.
        if (last == null || timeMs - last > pauseMs || accumulated * direction < 0f) accumulated = 0f
        if (discrete) return take(direction, timeMs)

        accumulated += deltaPx
        if (abs(accumulated) < notchPx) return 0
        val step = take(direction, timeMs)
        if (step != 0) accumulated -= direction * notchPx
        // At most one notch waits for the rate limit, so a fast flick does not keep stepping after it.
        accumulated = accumulated.coerceIn(-notchPx, notchPx)
        return step
    }

    private fun take(direction: Int, timeMs: Long): Int {
        val last = lastStepAt
        if (direction == lastDirection && last != null && timeMs - last < minStepIntervalMs) return 0
        lastStepAt = timeMs
        lastDirection = direction
        return direction
    }

    companion object {
        const val MAX_STEPS_PER_SECOND = 8
        const val PAUSE_MS = 250L
    }
}

/**
 * Where the steps of a turn go, and which level the watch shows meanwhile.
 *
 * It predicts the level from what the phone reported, so the indicator follows the hand at once,
 * and sends as little as it can:
 * - a phone that takes levels ([WearCodec.REVISION_VOLUME_LEVELS]) and knows the TV volume gets the
 *   level to set ([WearMessage.SetVolume]),
 * - one that takes levels but does not know the volume gets the steps (VOLUME_UP / VOLUME_DOWN with
 *   a repeat count),
 * - an older phone gets one VOLUME_UP / VOLUME_DOWN per step, as if the button was tapped.
 *
 * Levels and repeats are sent at most every [sendIntervalMs]: the first step of a turn at once, the
 * rest of a fast turn as one message when the interval is over ([flush], [nextFlushAt]). For [holdMs]
 * after the last step the watch keeps showing (and counting from) its own level, so late answers from
 * the phone do not make it jump back; after that the phone's level counts again.
 * No Android code, so it is tested on the JVM.
 */
class VolumeDial(
    private val stepPercent: Int = STEP_PERCENT,
    private val sendIntervalMs: Long = SEND_INTERVAL_MS,
    private val holdMs: Long = HOLD_MS,
) {
    /** One step of a turn, for the indicator, the haptics and the Data Layer. */
    data class Turn(
        /** The level after this step, 0..100, or -1 when the phone does not know the TV volume. */
        val level: Int,
        val up: Boolean,
        /** Already at 0 or 100 %: nothing changes and nothing is sent. */
        val atLimit: Boolean,
        /** Messages to send now. */
        val send: List<WearMessage>,
    )

    private var reported = -1
    private var revision = 1
    private var target = -1
    private var turnedAt = 0L
    private var sentAt: Long? = null
    private var lastSentLevel = -1
    private var pendingLevel = -1
    private var pendingSteps = 0

    private val takesLevels: Boolean get() = revision >= WearCodec.REVISION_VOLUME_LEVELS

    /** What the phone sent last. */
    fun onPhone(playing: NowPlaying) {
        revision = playing.revision
        reported = if (playing.active) playing.volumePercent else -1
        if (!playing.active) forget()
    }

    /** An on-screen volume button was tapped: from now on the phone's level counts. */
    fun onButton() = forget()

    /** The level to show at [now]: the own one during a turn and [holdMs] after it, else the phone's; -1 unknown. */
    fun level(now: Long): Int = if (holding(now)) target else reported

    /** [steps] (from [RotarySteps]: +1 up, -1 down) at [now]. Null for no steps. */
    fun turn(steps: Int, now: Long): Turn? {
        if (steps == 0) return null
        val up = steps > 0
        val holding = holding(now)
        if (!holding) {
            target = -1
            lastSentLevel = -1
        }
        val base = if (holding) target else reported
        turnedAt = now
        if (base < 0) {
            // The phone does not know the TV volume: only relative steps work.
            val send = if (takesLevels) {
                pendingSteps += steps
                flush(now)
            } else {
                singles(steps)
            }
            return Turn(-1, up, atLimit = false, send = send)
        }
        // Steps still waiting from before the phone knew the volume are not in its level yet.
        val level = (base + (steps + pendingSteps) * stepPercent).coerceIn(0, 100)
        pendingSteps = 0
        target = level
        if (level == base) return Turn(level, up, atLimit = true, send = emptyList())
        val send = if (takesLevels) {
            pendingLevel = level
            flush(now)
        } else {
            singles(steps)
        }
        return Turn(level, up, atLimit = false, send = send)
    }

    /** What is due at [now] of a fast turn (at most one message); empty if nothing or not yet. */
    fun flush(now: Long): List<WearMessage> {
        val last = sentAt
        if (last != null && now - last < sendIntervalMs) return emptyList()
        val message: WearMessage = when {
            pendingLevel >= 0 -> {
                val level = pendingLevel
                pendingLevel = -1
                // Turned away and back within one interval: the phone already has it.
                if (level == lastSentLevel) return emptyList()
                lastSentLevel = level
                WearMessage.SetVolume(level)
            }
            pendingSteps != 0 -> {
                val n = pendingSteps.coerceIn(-WearCodec.MAX_REPEAT, WearCodec.MAX_REPEAT)
                pendingSteps -= n
                WearMessage.Command(if (n > 0) WearCommand.VOLUME_UP else WearCommand.VOLUME_DOWN, abs(n))
            }
            else -> return emptyList()
        }
        sentAt = now
        return listOf(message)
    }

    /** When [flush] has something to send, or null when nothing waits. */
    fun nextFlushAt(): Long? =
        if (pendingLevel >= 0 || pendingSteps != 0) (sentAt ?: 0L) + sendIntervalMs else null

    private fun holding(now: Long) = target >= 0 && now - turnedAt < holdMs

    private fun singles(steps: Int): List<WearMessage> =
        List(abs(steps)) { WearMessage.Command(if (steps > 0) WearCommand.VOLUME_UP else WearCommand.VOLUME_DOWN) }

    private fun forget() {
        target = -1
        lastSentLevel = -1
        pendingLevel = -1
        pendingSteps = 0
    }

    companion object {
        /** One step, in percent: the same as a tap on the volume buttons (CastService uses 5 %). */
        const val STEP_PERCENT = 5

        /** At most 4 volume messages a second while turning. */
        const val SEND_INTERVAL_MS = 250L

        /** How long the own level is shown (and counted from) after the last step. */
        const val HOLD_MS = 2_000L
    }
}
