package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.multi.JoinMedia
import io.github.patschgoo.castapp.multi.Timeline
import java.net.InetAddress

/** A running cast (screen mirroring or a file), as seen by [CastService]. */
interface ActiveCast {
    val device: CastDevice

    /**
     * Ends the cast. [message] is shown to the user (null when the user stopped it).
     * [stopReceiver] closes the player on the TV; skipped when a new cast takes over the same TV.
     */
    fun finish(message: String?, stopReceiver: Boolean = true)

    fun changeVolume(delta: Double)

    fun toggleMute()

    /** Records whether the phone's "dark screen" overlay is showing. */
    fun setDarkScreen(on: Boolean) = CastController.update(this) { it.copy(darkScreen = on) }

    // ---- several screens at once ---------------------------------------------------------------

    /** Adds another TV or speaker that plays the same (no-op if it already does). */
    fun addScreen(device: CastDevice) {}

    /** Takes screen [id] out of the cast; the cast ends with its last screen. */
    fun removeScreen(id: String) {}

    fun changeScreenVolume(id: String, delta: Double) {}

    fun toggleScreenMute(id: String) {}

    /** Every screen of the cast, the first one first. */
    val screenDevices: List<CastDevice> get() = listOf(device)

    // ---- for other CastApp devices -------------------------------------------------------------

    /**
     * Another device takes this cast over: from now on nothing closes the player on the TV
     * (the other device's player is about to replace ours) and a lost TV isn't reconnected.
     */
    var handingOver: Boolean

    /** What a viewer who joins from [local]'s network plays; null if this cast can't be watched elsewhere. */
    fun joinMedia(local: InetAddress): JoinMedia? = null

    /** Where playback is on this device's clock, for synced viewers (file casts). */
    fun timeline(): Timeline? = null

    /** File casts: the play list and where it is, for a hand-over. */
    fun handOverQueue(): Pair<List<MediaItem>, Int>? = null
}
