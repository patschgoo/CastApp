package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.discovery.CastDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CastPhase(val label: String) {
    CONNECTING("Connecting to TV…"),
    STARTING("Starting the player on the TV…"),
    BUFFERING("Buffering…"),
    LIVE("Live"),
}

sealed interface CastState {
    data object Idle : CastState

    data class Active(
        val device: CastDevice,
        val phase: CastPhase,
        val compatibilityMode: Boolean = false,
        val volume: Float? = null,
        val muted: Boolean = false,
        val audio: Boolean = false,
        val resolution: String = "",
    ) : CastState
}

/** Process-wide casting state shared by the service, the UI and the Quick Settings tile. */
object CastController {
    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off messages for the user (errors, "casting ended"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val isActive: Boolean get() = _state.value is CastState.Active

    /** The session that currently owns the state; updates from older sessions are ignored. */
    @Volatile private var owner: Any? = null

    @Synchronized
    internal fun begin(owner: Any, state: CastState.Active) {
        this.owner = owner
        _state.value = state
    }

    @Synchronized
    internal fun update(owner: Any, transform: (CastState.Active) -> CastState.Active) {
        if (this.owner !== owner) return
        val current = _state.value
        if (current is CastState.Active) _state.value = transform(current)
    }

    @Synchronized
    internal fun end(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        _state.value = CastState.Idle
    }

    internal fun message(text: String) {
        _messages.tryEmit(text)
    }
}
