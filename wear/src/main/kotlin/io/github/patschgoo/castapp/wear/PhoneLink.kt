package io.github.patschgoo.castapp.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import io.github.patschgoo.castapp.wearproto.NowPlaying
import io.github.patschgoo.castapp.wearproto.WearCodec
import io.github.patschgoo.castapp.wearproto.WearCommand
import io.github.patschgoo.castapp.wearproto.WearPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Link { SEARCHING, CONNECTED, NO_PHONE, NO_SERVICES }

data class WatchState(
    val link: Link = Link.SEARCHING,
    val playing: NowPlaying = NowPlaying.IDLE,
    /** The phone was asked to show its screen-capture prompt. */
    val mirrorRequested: Boolean = false,
)

/**
 * The connection to the CastApp on the paired phone: finds it, sends it commands and keeps the
 * latest "now playing" it pushed. Every Wear call is guarded; a watch without the Data Layer just
 * shows [Link.NO_SERVICES].
 */
class PhoneLink(context: Context) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    @Volatile private var phone: Node? = null
    private var started = false

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path == WearPaths.STATE) {
            WearCodec.decodeState(event.data)?.let { playing -> _state.update { it.copy(playing = playing) } }
        }
    }

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { refresh() }

    fun start() {
        if (started) return
        try {
            Wearable.getMessageClient(app).addListener(messageListener)
            Wearable.getCapabilityClient(app).addListener(capabilityListener, WearPaths.CAPABILITY_PHONE)
            started = true
        } catch (_: Throwable) {
            _state.update { it.copy(link = Link.NO_SERVICES) }
            return
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            Wearable.getMessageClient(app).removeListener(messageListener)
            Wearable.getCapabilityClient(app).removeListener(capabilityListener)
        } catch (_: Throwable) {
        }
    }

    /** Looks for the phone again and asks it what is playing. */
    fun refresh() {
        try {
            Wearable.getCapabilityClient(app)
                .getCapability(WearPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info ->
                    val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
                    phone = node
                    if (node == null) {
                        _state.update { it.copy(link = Link.NO_PHONE, playing = NowPlaying.IDLE) }
                    } else {
                        _state.update { it.copy(link = Link.CONNECTED) }
                        send(WearPaths.STATE_REQUEST, ByteArray(0))
                    }
                }
                .addOnFailureListener { _state.update { it.copy(link = Link.NO_PHONE) } }
        } catch (_: Throwable) {
            _state.update { it.copy(link = Link.NO_SERVICES) }
        }
    }

    fun send(command: WearCommand) {
        send(WearPaths.COMMAND, WearCodec.encodeCommand(command))
        if (command == WearCommand.MIRROR_LAST) _state.update { it.copy(mirrorRequested = true) }
    }

    fun clearMirrorNotice() = _state.update { it.copy(mirrorRequested = false) }

    private fun send(path: String, payload: ByteArray) {
        val node = phone
        if (node == null) {
            refresh()
            return
        }
        try {
            Wearable.getMessageClient(app).sendMessage(node.id, path, payload)
                .addOnFailureListener { refresh() }
        } catch (_: Throwable) {
            _state.update { it.copy(link = Link.NO_SERVICES) }
        }
    }
}
