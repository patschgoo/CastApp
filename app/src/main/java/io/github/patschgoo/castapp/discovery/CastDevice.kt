package io.github.patschgoo.castapp.discovery

import org.json.JSONObject

enum class DeviceKind {
    /** Google Cast: Chromecast, Google TV, NVIDIA Shield, Nest speakers, TVs with Chromecast built in. */
    CAST,

    /** UPnP/DLNA media renderer: most Samsung, LG, Sony, Philips smart TVs, Kodi, and CastApp TV on Fire TV / Android TV. */
    DLNA,

    /** Apple TV, and TVs with AirPlay built in. */
    AIRPLAY,

    /** Roku players and Roku TVs (their own "play this link" control). */
    ROKU,
}

/** A TV or speaker on the local network. */
data class CastDevice(
    /** Stable device id when known, otherwise "host:<ip>". */
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 8009,
    val model: String? = null,
    /** What the device is currently showing, e.g. "YouTube" (from mDNS). */
    val status: String? = null,
    val kind: DeviceKind = DeviceKind.CAST,
    /** False for speakers and speaker groups that can only play sound. */
    val video: Boolean = true,
    /** A Cast speaker group (several speakers playing together). */
    val group: Boolean = false,
    /** DLNA: control URLs of the AVTransport and RenderingControl services. */
    val avTransportUrl: String? = null,
    val renderingControlUrl: String? = null,
    /** AirPlay: the "features" value it announces (which protocol and pairing it uses). */
    val airplayFeatures: String? = null,
    /** Wi-Fi MAC address ("AA:BB:CC:DD:EE:FF") used to wake the TV with Wake-on-LAN, when known. */
    val mac: String? = null,
) {
    val isDlna: Boolean get() = kind == DeviceKind.DLNA

    /** CastApp's own receiver on a Fire TV / Android TV box: fast mirroring, everything else too. */
    val isCastAppTv: Boolean get() = kind == DeviceKind.DLNA && model?.contains("CastApp TV") == true

    /** Plays by URL (DLNA, AirPlay, Roku) instead of Google Cast. */
    val isUrlRenderer: Boolean get() = kind != DeviceKind.CAST

    /** Short label shown next to the name; null for Chromecasts. */
    val kindLabel: String?
        get() = when (kind) {
            DeviceKind.CAST -> null
            DeviceKind.DLNA -> when {
                isCastAppTv -> "CastApp TV"
                model?.contains("Kodi", ignoreCase = true) == true -> "Kodi"
                else -> "DLNA"
            }
            DeviceKind.AIRPLAY -> "AirPlay"
            DeviceKind.ROKU -> "Roku"
        }

    /** Screen mirroring goes through a buffering player here: a few seconds of delay. */
    val slowMirroring: Boolean get() = isUrlRenderer && !isCastAppTv

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("model", model ?: "")
        .put("kind", kind.name)
        .put("video", video)
        .put("group", group)
        .put("avt", avTransportUrl ?: "")
        .put("rc", renderingControlUrl ?: "")
        .put("apf", airplayFeatures ?: "")
        .put("mac", mac ?: "")

    companion object {
        fun fromJson(o: JSONObject): CastDevice? {
            val host = o.optString("host").ifEmpty { return null }
            return CastDevice(
                id = o.optString("id").ifEmpty { "host:$host" },
                name = o.optString("name").ifEmpty { host },
                host = host,
                port = o.optInt("port", 8009),
                model = o.optString("model").ifEmpty { null },
                kind = DeviceKind.entries.firstOrNull { it.name == o.optString("kind") } ?: DeviceKind.CAST,
                video = o.optBoolean("video", true),
                group = o.optBoolean("group", false),
                avTransportUrl = o.optString("avt").ifEmpty { null },
                renderingControlUrl = o.optString("rc").ifEmpty { null },
                airplayFeatures = o.optString("apf").ifEmpty { null },
                mac = o.optString("mac").ifEmpty { null },
            )
        }
    }
}
