package io.github.patschgoo.castapp.wear

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScrollFeedbackProvider
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.github.patschgoo.castapp.wearproto.NowPlaying
import io.github.patschgoo.castapp.wearproto.WearMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The TV volume on the bezel or crown of the remote screen: rotary events become steps
 * ([RotarySteps]), steps become messages to the phone ([VolumeDial]), each step ticks and shows the
 * level for a moment ([shown]). Lives on the main thread.
 */
@Stable
class RotaryVolume internal constructor(
    discrete: Boolean,
    notchPx: Float,
    private val haptics: VolumeHaptics,
    private val scope: CoroutineScope,
    private val send: (WearMessage) -> Unit,
) {
    private val steps = RotarySteps(discrete, notchPx)
    private val dial = VolumeDial()
    private var flushJob: Job? = null
    private var hideJob: Job? = null

    /** The last step while the indicator is up (until [VolumeDial.HOLD_MS] after it), else null. */
    var shown: VolumeDial.Turn? by mutableStateOf(null)
        private set

    fun onPhone(playing: NowPlaying) {
        dial.onPhone(playing)
        if (!playing.active) hide()
    }

    /** An on-screen volume button was tapped. */
    fun onButton() {
        dial.onButton()
        hide()
    }

    fun onRotary(deltaPx: Float, eventTimeMs: Long, inputDeviceId: Int) {
        val n = steps.onDelta(deltaPx, eventTimeMs)
        if (n == 0) return
        val now = SystemClock.uptimeMillis()
        val turn = dial.turn(n, now) ?: return
        if (turn.atLimit) haptics.limit(inputDeviceId, up = turn.up) else haptics.tick(inputDeviceId)
        turn.send.forEach(send)
        scheduleFlush()
        shown = turn
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(VolumeDial.HOLD_MS)
            shown = null
        }
    }

    /** Sends the rest of a fast turn once the send interval allows it. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        val at = dial.nextFlushAt() ?: return
        flushJob = scope.launch {
            delay((at - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            dial.flush(SystemClock.uptimeMillis()).forEach(send)
            flushJob = null
            scheduleFlush()
        }
    }

    private fun hide() {
        hideJob?.cancel()
        shown = null
    }
}

/** The watch's rotary input is a bezel that clicks (Galaxy Watch) rather than a smooth crown. */
private const val FEATURE_LOW_RES_ROTARY = "android.hardware.rotaryencoder.lowres"

/** Crown turn for one volume step. Needs checking on real crowns (Pixel Watch, TicWatch). */
private val CROWN_NOTCH = 24.dp

@Composable
fun rememberRotaryVolume(send: (WearMessage) -> Unit): RotaryVolume {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentSend by rememberUpdatedState(send)
    return remember {
        RotaryVolume(
            discrete = try {
                context.packageManager.hasSystemFeature(FEATURE_LOW_RES_ROTARY)
            } catch (_: Exception) {
                false
            },
            notchPx = with(density) { CROWN_NOTCH.toPx() },
            haptics = VolumeHaptics(view),
            scope = scope,
            send = { currentSend(it) },
        )
    }
}

/**
 * Takes the rotary input for [volume]: put it on the remote screen only, where the list then must
 * not scroll by rotary itself (ScalingLazyColumn's rotaryScrollableBehavior = null). Other screens
 * keep scrolling with the bezel or crown. Rotary events go to the focused node, so this takes the
 * focus when the screen is shown, and the caller also asks [focusRequester] for it.
 */
fun Modifier.rotaryVolume(volume: RotaryVolume, focusRequester: FocusRequester): Modifier = this
    .requestFocusOnHierarchyActive()
    .focusRequester(focusRequester)
    .onRotaryScrollEvent { event ->
        volume.onRotary(event.verticalScrollPixels, event.uptimeMillis, event.inputDeviceId)
        true
    }
    .focusTarget()

/** The level on the edge of the screen and a short label, while turning; read out by TalkBack. */
@Composable
fun BoxScope.RotaryVolumeIndicator(turn: VolumeDial.Turn?) {
    if (turn == null) return
    if (turn.level >= 0) {
        LevelIndicator(
            value = { turn.level / 100f },
            modifier = Modifier.align(Alignment.CenterEnd).clearAndSetSemantics { },
        )
    }
    val text = when {
        turn.level >= 0 -> stringResource(R.string.rotary_volume_level, turn.level)
        turn.up -> stringResource(R.string.rotary_volume_up)
        else -> stringResource(R.string.rotary_volume_down)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 14.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clearAndSetSemantics {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Icon(
            painterResource(if (turn.up) R.drawable.ic_w_volume_up else R.drawable.ic_w_volume_down),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A tick per volume step and a different one at 0 or 100 %, chosen the way Wear Compose chooses
 * them for its rotary lists: the platform's scroll feedback on Wear OS 6, Samsung's own effects on
 * Galaxy Watches, the rotary constants on Wear OS 4 and 5, a clock tick before. Silent where the
 * watch has none, or haptics are off.
 */
internal class VolumeHaptics(private val view: View) {
    private val galaxyWatch = Build.MANUFACTURER.contains("samsung", ignoreCase = true) && Build.MODEL.startsWith("SM-R")
    private val platform: PlatformFeedback? = if (Build.VERSION.SDK_INT >= 36) {
        try {
            PlatformFeedback(view)
        } catch (_: Throwable) {
            null
        }
    } else null

    fun tick(inputDeviceId: Int) {
        if (Build.VERSION.SDK_INT >= 36 && platform != null) {
            guarded { platform.tick(inputDeviceId) }
            return
        }
        perform(
            when {
                galaxyWatch -> GALAXY_TICK
                Build.VERSION.SDK_INT >= 33 -> ROTARY_SCROLL_TICK
                else -> HapticFeedbackConstants.CLOCK_TICK
            },
        )
    }

    fun limit(inputDeviceId: Int, up: Boolean) {
        if (Build.VERSION.SDK_INT >= 36 && platform != null) {
            // Turning down ends at the "start", like a list scrolled back to its top.
            guarded { platform.limit(inputDeviceId, atStart = !up) }
            return
        }
        when {
            galaxyWatch -> perform(GALAXY_LIMIT)
            Build.VERSION.SDK_INT >= 33 -> perform(ROTARY_SCROLL_LIMIT)
            // Wear OS 3: no limit effect; the missing tick says it.
        }
    }

    /** Wear OS 6 (API 36): the haptics the watch maker tuned for rotary scrolling. */
    @RequiresApi(36)
    private class PlatformFeedback(view: View) {
        private val provider = ScrollFeedbackProvider.createProvider(view)

        fun tick(inputDeviceId: Int) =
            provider.onSnapToItem(inputDeviceId, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL)

        fun limit(inputDeviceId: Int, atStart: Boolean) =
            provider.onScrollLimit(inputDeviceId, InputDevice.SOURCE_ROTARY_ENCODER, MotionEvent.AXIS_SCROLL, atStart)
    }

    // Constants that View.performHapticFeedback takes on watches but that are not all public SDK
    // values; unknown ones are ignored by the platform.
    @android.annotation.SuppressLint("WrongConstant")
    private fun perform(constant: Int) = guarded { view.performHapticFeedback(constant) }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        /** HapticFeedbackConstants.ROTARY_SCROLL_TICK / _LIMIT (public in API 34, present on Wear OS 4). */
        const val ROTARY_SCROLL_TICK = 18
        const val ROTARY_SCROLL_LIMIT = 20

        /** Samsung's rotary tick and limit effects on Galaxy Watches (as used by androidx.wear.compose). */
        const val GALAXY_TICK = 101
        const val GALAXY_LIMIT = 50107
    }
}
