package io.github.patschgoo.castapp.discovery

import org.json.JSONObject

/** A Cast receiver on the local network. */
data class CastDevice(
    /** Stable Cast device id when known, otherwise "host:<ip>". */
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 8009,
    val model: String? = null,
    /** What the device is currently showing, e.g. "YouTube" (from mDNS). */
    val status: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("model", model ?: "")

    companion object {
        fun fromJson(o: JSONObject): CastDevice? {
            val host = o.optString("host").ifEmpty { return null }
            return CastDevice(
                id = o.optString("id").ifEmpty { "host:$host" },
                name = o.optString("name").ifEmpty { host },
                host = host,
                port = o.optInt("port", 8009),
                model = o.optString("model").ifEmpty { null },
            )
        }
    }
}
