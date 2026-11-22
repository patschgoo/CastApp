package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.discovery.CastDevice

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
}
