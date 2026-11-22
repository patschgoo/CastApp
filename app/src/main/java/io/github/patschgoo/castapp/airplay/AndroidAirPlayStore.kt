package io.github.patschgoo.castapp.airplay

import android.content.Context
import androidx.core.content.edit
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.network.Secrets

/** Apple TV pairings, encrypted with a key from the Android Keystore. */
object AndroidAirPlayStore : AirPlayPairingStore {
    private val prefs get() = CastApp.instance.getSharedPreferences("airplay_pairings", Context.MODE_PRIVATE)

    override fun load(deviceId: String): AirPlayCredentials? =
        prefs.getString(deviceId, null)?.let { Secrets.decrypt(it) }?.takeIf { it.isNotEmpty() }?.let { AirPlayCredentials.fromJson(it) }

    override fun save(deviceId: String, credentials: AirPlayCredentials) {
        prefs.edit { putString(deviceId, Secrets.encrypt(credentials.toJson())) }
    }

    override fun clear(deviceId: String) {
        prefs.edit { remove(deviceId) }
    }
}
