package io.github.patschgoo.castapp.remote

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/** The buttons of a TV remote. Not every target supports every key (see [RemoteTarget.keys]). */
enum class RemoteKey {
    POWER, UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, MENU,
    VOLUME_UP, VOLUME_DOWN, MUTE, CHANNEL_UP, CHANNEL_DOWN,
    PLAY_PAUSE, STOP, REWIND, FAST_FORWARD, PREVIOUS, NEXT,
    INPUT, INFO, GUIDE, SETTINGS,
    NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9,
}

/**
 * How a remote talks to the user while connecting: a pairing code shown on the TV,
 * or an instruction ("Open Bluetooth settings on the TV and pick this phone").
 */
interface RemoteUi {
    /** Asks for a code shown on the TV; null if the user cancels. */
    suspend fun askCode(title: String, message: String): String?

    /** Shows a short status or instruction line while connecting. */
    fun showMessage(message: String)
}

/** One connected remote. Close it when the remote screen goes away. */
interface RemoteSession : Closeable {
    suspend fun press(key: RemoteKey)

    /** Types text into the focused text field on the TV (search boxes); only if [RemoteTarget.supportsText]. */
    suspend fun type(text: String) {}
}

/** Something the phone can steer: a TV or TV box over the network, Bluetooth or infrared. */
interface RemoteTarget {
    /** Stable id, e.g. "atv:192.168.1.20" or "ir:samsung". */
    val id: String
    val name: String
    /** Short kind label shown next to the name: "Google TV", "Roku", "Samsung", "LG", "Bluetooth", "Infrared". */
    val label: String
    val keys: Set<RemoteKey>
    val supportsText: Boolean get() = false

    /** Connects (pairing if needed). Throws an exception with a user-readable message if it can't. */
    suspend fun connect(context: Context, ui: RemoteUi): RemoteSession
}

/** A family of remotes (network, Bluetooth, infrared). */
interface RemoteProvider {
    /** Section title in the target list. */
    val title: String

    /** Targets available now; updated while discovery runs. */
    fun targets(context: Context): StateFlow<List<RemoteTarget>>

    fun startDiscovery(context: Context) {}
    fun stopDiscovery() {}
}

/** Every kind of remote the app has, in the order they're listed. */
object RemoteProviders {
    val all: List<RemoteProvider> get() = listOf(NetworkRemotes, BluetoothRemote, InfraredRemotes)
}
