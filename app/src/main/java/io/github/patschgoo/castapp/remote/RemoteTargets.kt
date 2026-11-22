package io.github.patschgoo.castapp.remote

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind

/** Which network remote fits a TV from the device list (null: let the user pick). */
object RemoteTargets {
    private val LG = Regex("\\bLG\\b", RegexOption.IGNORE_CASE)

    fun idFor(device: CastDevice): String? {
        val model = device.model.orEmpty()
        return when {
            !device.video -> null
            device.kind == DeviceKind.ROKU -> "roku:${device.host}"
            device.kind == DeviceKind.DLNA && model.contains("Samsung", ignoreCase = true) -> "samsung:${device.host}"
            device.kind == DeviceKind.DLNA && LG.containsMatchIn(model) -> "lg:${device.host}"
            // Google TV, Shield, Android TV (incl. CastApp TV on them); a Chromecast without Google TV
            // isn't listed by the remote and the screen falls back to its list.
            device.kind == DeviceKind.CAST || device.isCastAppTv -> "atv:${device.host}"
            else -> null
        }
    }
}
