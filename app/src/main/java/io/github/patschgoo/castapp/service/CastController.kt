package io.github.patschgoo.castapp.service

import androidx.annotation.StringRes
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.discovery.CastDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CastPhase(@StringRes val labelRes: Int) {
    CONNECTING(R.string.phase_connecting),
    STARTING(R.string.phase_starting),
    BUFFERING(R.string.phase_buffering),
    RECONNECTING(R.string.phase_reconnecting),
    CONVERTING(R.string.phase_converting),
    LIVE(R.string.phase_live);

    val label: String get() = Txt.s(labelRes)
}

sealed interface CastState {
    data object Idle : CastState

    data class Active(
        val device: CastDevice,
        val phase: CastPhase,
        val kind: CastKind = CastKind.SCREEN,
        val compatibilityMode: Boolean = false,
        val volume: Float? = null,
        val muted: Boolean = false,
        val audio: Boolean = false,
        val resolution: String = "",
        /** Single-app screen casting on Android 14+: the phone screen can be blacked out. */
        val canDarkScreen: Boolean = false,
        /** Mirroring shares one app (Android 14+), not the whole screen. */
        val singleApp: Boolean = false,
        /** Sound only (speakers, or a TV without picture). */
        val audioOnly: Boolean = false,
        /** Current video bitrate (it adapts to the Wi-Fi). */
        val bitrateKbps: Int = 0,
        /** Using the optional low-latency TV player. */
        val lowLatencyPlayer: Boolean = false,
        /** File casting: position in the play list. */
        val queueIndex: Int = 0,
        val queueSize: Int = 1,
        val isPhoto: Boolean = false,
        val hasSubtitles: Boolean = false,
        val subtitlesOn: Boolean = false,
        /** The phone is converting this video because the TV couldn't play it. */
        val converting: Boolean = false,
        /** A live stream (TV channel): no seeking. */
        val live: Boolean = false,
        /** Picture quality that can be switched at any time (mirroring resolution, file/stream quality). */
        val qualityLabel: String = "",
        val qualityOptions: List<String> = emptyList(),
        /** Creating subtitles: 0..100, or -1 when not running. */
        val subtitleProgress: Int = -1,
        val canCreateSubtitles: Boolean = false,
        val darkScreen: Boolean = false,
        /** File casting: name, playback position and state as last reported by the TV. */
        val title: String? = null,
        val paused: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        /** SystemClock.elapsedRealtime() when [positionMs] was measured. */
        val positionAt: Long = 0,
        /** Mirroring: the video codec in use ("H.264" / "HEVC"). */
        val videoCodec: String = "",
        /** Mirroring: the phone's microphone is mixed into the sound. */
        val microphone: Boolean = false,
        /**
         * Several screens at once: every screen of the cast with its state (the first one is the
         * cast's own [device]). Empty while there is only one screen.
         */
        val screens: List<io.github.patschgoo.castapp.multi.ScreenInfo> = emptyList(),
        /** Another CastApp device is taking this cast over. */
        val handingOver: Boolean = false,
    ) : CastState {
        /** Current playback position, extrapolated while playing. */
        fun positionNow(nowElapsed: Long): Long {
            if (kind != CastKind.FILE || phase != CastPhase.LIVE || paused || live) return positionMs
            val p = positionMs + (nowElapsed - positionAt)
            return if (durationMs > 0) p.coerceAtMost(durationMs) else p
        }
    }
}

enum class CastKind { SCREEN, FILE }

/** Process-wide casting state shared by the service, the UI and the Quick Settings tile. */
object CastController {
    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off messages for the user (errors, "casting ended"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val isActive: Boolean get() = _state.value is CastState.Active

    /** An AirPlay TV shows a code that the user has to type in (device name), or null. */
    private val _pinRequest = MutableStateFlow<String?>(null)
    val pinRequest: StateFlow<String?> = _pinRequest.asStateFlow()
    @Volatile private var pinAnswer: kotlinx.coroutines.CompletableDeferred<String?>? = null

    /** Asks the user for the code [deviceName] shows; null if cancelled or after 3 minutes. */
    suspend fun askPin(deviceName: String): String? {
        val answer = kotlinx.coroutines.CompletableDeferred<String?>()
        pinAnswer = answer
        _pinRequest.value = deviceName
        try {
            val app = io.github.patschgoo.castapp.CastApp.instance
            app.startActivity(
                android.content.Intent(app, io.github.patschgoo.castapp.PinActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
        return try {
            kotlinx.coroutines.withTimeoutOrNull(180_000) { answer.await() }
        } finally {
            _pinRequest.value = null
            pinAnswer = null
        }
    }

    fun answerPin(pin: String?) {
        pinAnswer?.complete(pin?.trim()?.takeIf { it.isNotEmpty() })
        _pinRequest.value = null
    }

    /** The session that currently owns the state; updates from older sessions are ignored. */
    @Volatile private var owner: Any? = null

    /** The running cast, for screens that talk to it directly (e.g. to hand it subtitles). */
    val activeCast: ActiveCast? get() = owner as? ActiveCast

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
