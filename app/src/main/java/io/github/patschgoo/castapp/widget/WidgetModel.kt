package io.github.patschgoo.castapp.widget

import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastPhase
import io.github.patschgoo.castapp.service.CastState

enum class WidgetControl { PREVIOUS, PLAY_PAUSE, NEXT, VOLUME_DOWN, VOLUME_UP, STOP }

/**
 * What the home-screen widget shows. Only holds what the widget draws (not the playback position),
 * so two equal models mean "nothing to redraw" and the widget isn't touched every second.
 */
data class WidgetModel(
    val active: Boolean = false,
    val deviceName: String = "",
    val title: String? = null,
    val kind: CastKind = CastKind.SCREEN,
    val queueIndex: Int = 0,
    val queueSize: Int = 1,
    val paused: Boolean = false,
    val isPhoto: Boolean = false,
    val phase: CastPhase = CastPhase.LIVE,
    /** The last TV used: what "Mirror to ..." offers while nothing is cast. */
    val lastTv: String? = null,
) {
    /**
     * The buttons to show, in order. [room] is how many fit (4 in the small widget, 6 in a wide one);
     * volume comes after the playback buttons and drops out first.
     */
    fun controls(room: Int): List<WidgetControl> {
        if (!active) return emptyList()
        if (phase != CastPhase.LIVE) return listOf(WidgetControl.STOP)
        val list = ArrayList<WidgetControl>()
        if (kind == CastKind.FILE) {
            if (queueSize > 1) list += WidgetControl.PREVIOUS
            if (!isPhoto) list += WidgetControl.PLAY_PAUSE
            if (queueSize > 1) list += WidgetControl.NEXT
            if (room >= 6) list += listOf(WidgetControl.VOLUME_DOWN, WidgetControl.VOLUME_UP)
        } else {
            list += listOf(WidgetControl.VOLUME_DOWN, WidgetControl.VOLUME_UP)
        }
        list += WidgetControl.STOP
        return list
    }

    companion object {
        fun from(state: CastState, lastTv: String?): WidgetModel = when (state) {
            CastState.Idle -> WidgetModel(lastTv = lastTv)
            is CastState.Active -> WidgetModel(
                active = true,
                deviceName = state.device.name,
                title = state.title?.takeIf { it.isNotBlank() },
                kind = state.kind,
                queueIndex = state.queueIndex,
                queueSize = state.queueSize,
                paused = state.paused,
                isPhoto = state.isPhoto,
                phase = state.phase,
                lastTv = lastTv,
            )
        }

        /** Width (dp) from which the wide layout is used; and from which volume fits next to the playback buttons. */
        const val WIDE_MIN_DP = 250
        const val VOLUME_MIN_DP = 330
        /** Height (dp) below which the second text line is dropped. */
        const val TALL_MIN_DP = 100
    }
}
