package io.github.patschgoo.castapp.discovery

import org.json.JSONObject

enum class DeviceKind {
    /** Google Cast: Chromecast, Google TV, NVIDIA Shield, Nest speakers, TVs with Chromecast built in. */
    CAST,

    /** UPnP/DLNA media renderer: most Samsung, LG, Sony, Philips smart TVs. */
    DLNA,
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
) {
    val isDlna: Boolean get() = kind == DeviceKind.DLNA

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
            )
        }
    }
}
