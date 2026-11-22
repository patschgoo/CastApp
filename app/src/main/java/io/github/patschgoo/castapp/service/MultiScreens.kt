package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.multi.ScreenInfo
import io.github.patschgoo.castapp.multi.ScreenState

/** Puts the screens of a cast with several screens into its state (both kinds of cast use this). */
object MultiScreens {
    /**
     * [state] with its screen list: empty while the cast has one screen (so a plain cast looks
     * exactly as before), else the first screen (unless it was taken out) and the added ones.
     */
    fun withScreens(state: CastState.Active, extras: List<ScreenInfo>, primaryDetached: Boolean): CastState.Active {
        if (extras.isEmpty() && !primaryDetached) return if (state.screens.isEmpty()) state else state.copy(screens = emptyList())
        val primary = if (primaryDetached) null else primaryInfo(state.device, state)
        return state.copy(screens = listOfNotNull(primary) + extras)
    }

    fun primaryInfo(device: CastDevice, state: CastState.Active) = ScreenInfo(
        id = device.id,
        name = device.name,
        kindLabel = device.kindLabel,
        state = when {
            state.phase == CastPhase.LIVE && state.paused -> ScreenState.PAUSED
            state.phase == CastPhase.LIVE -> ScreenState.PLAYING
            state.phase == CastPhase.CONNECTING || state.phase == CastPhase.RECONNECTING -> ScreenState.CONNECTING
            else -> ScreenState.BUFFERING
        },
        volume = state.volume,
        muted = state.muted,
        primary = true,
    )

    /** The cast's phase once its first screen is gone: live while any screen plays. */
    fun groupPhase(screens: List<ScreenInfo>): CastPhase = when {
        screens.any { it.state == ScreenState.PLAYING || it.state == ScreenState.PAUSED } -> CastPhase.LIVE
        screens.any { it.state == ScreenState.BUFFERING } -> CastPhase.BUFFERING
        else -> CastPhase.CONNECTING
    }
}
