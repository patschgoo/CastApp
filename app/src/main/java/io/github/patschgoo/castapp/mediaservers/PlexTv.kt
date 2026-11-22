package io.github.patschgoo.castapp.mediaservers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One way to reach a Plex server, as plex.tv lists it. */
data class PlexConnection(
    val uri: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val local: Boolean,
    val relay: Boolean,
    val ipv6: Boolean,
)

/** A server (or other device) on a Plex account, from plex.tv. */
data class PlexResource(
    val name: String,
    val machineId: String,
    val product: String,
    val owned: Boolean,
    /** The token for this server (shared servers have their own). */
    val accessToken: String?,
    val connections: List<PlexConnection>,
) {
    val isServer: Boolean get() = product.contains("Media Server", ignoreCase = true)

    /**
     * Addresses to try, best first: on the home network (also where the Wi-Fi's own GDM
     * broadcast found the server), then over the internet, the relay last.
     */
    fun addressesToTry(gdmHosts: Collection<String> = emptyList()): List<String> {
        val found = gdmHosts.map { "http://$it" }
        val ordered = connections.filter { !it.ipv6 }.sortedWith(
            compareBy<PlexConnection> { it.relay }.thenBy { !it.local }.thenBy { it.protocol != "http" || !it.local },
        ).map { it.uri.trimEnd('/') }
        return (found + ordered).distinct()
    }
}

/** The Plex PIN sign-in: the only conversation with plex.tv. */
class PlexTv(private val info: ClientInfo, private val baseUrl: String = "https://plex.tv") {
    data class Pin(val id: Long, val code: String, val expiresInSeconds: Int)

    fun headers(token: String? = null): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Product", info.product)
        put("X-Plex-Version", info.version)
        put("X-Plex-Client-Identifier", info.deviceId)
        put("X-Plex-Device-Name", info.deviceName)
        put("X-Plex-Platform", "Android")
        if (!token.isNullOrEmpty()) put("X-Plex-Token", token)
    }

    /** Asks plex.tv for a four-letter code that the user types at plex.tv/link. */
    fun createPin(): Pin {
        val o = ServerHttp.json("POST", "$baseUrl/api/v2/pins?strong=false", headers(), body = "")
        return Pin(o.optLong("id"), o.str("code") ?: throw ServerHttpException(0, "No code"), o.int("expiresIn") ?: 900)
    }

    /** The token once the user has entered the code, else null. A 404 means the code ran out. */
    fun checkPin(pin: Pin): String? {
        val o = ServerHttp.json("GET", "$baseUrl/api/v2/pins/${pin.id}?code=${ServerHttp.enc(pin.code)}", headers())
        return o.str("authToken")
    }

    fun username(token: String): String? = try {
        val o = ServerHttp.json("GET", "$baseUrl/api/v2/user", headers(token))
        o.str("username") ?: o.str("title")
    } catch (_: ServerHttpException) {
        null
    }

    fun resources(token: String): List<PlexResource> {
        val q = "includeHttps=1&includeRelay=1&includeIPv6=1"
        val array = ServerHttp.jsonArray("GET", "$baseUrl/api/v2/resources?$q", headers(token))
        return parseResources(array).filter { it.isServer }
    }

    companion object {
        fun parseResources(array: JSONArray): List<PlexResource> = array.objects().map { r ->
            PlexResource(
                name = r.str("name") ?: "Plex",
                machineId = r.str("clientIdentifier").orEmpty(),
                product = r.str("product").orEmpty(),
                owned = r.bool("owned"),
                accessToken = r.str("accessToken"),
                connections = r.objects("connections").map { c ->
                    PlexConnection(
                        uri = c.str("uri").orEmpty(),
                        protocol = c.str("protocol") ?: "http",
                        address = c.str("address").orEmpty(),
                        port = c.int("port") ?: 32400,
                        local = c.bool("local"),
                        relay = c.bool("relay"),
                        ipv6 = c.bool("IPv6"),
                    )
                }.filter { it.uri.isNotEmpty() },
            )
        }
    }
}

sealed interface PinResult {
    class Token(val token: String) : PinResult
    data object Expired : PinResult
}

/** Waits for the user to enter the code from [PlexTv.createPin]. */
class PlexPinFlow(
    private val tv: PlexTv,
    private val intervalMs: Long = 1500,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 6,
) {
    /**
     * Polls until the code is claimed (the token), expires, or [timeoutMs] have passed.
     * Short network hiccups are ridden out; [maxFailures] in a row give up with the error.
     */
    suspend fun waitForToken(pin: PlexTv.Pin, onWaiting: () -> Unit = {}): PinResult {
        val deadline = clock() + pin.expiresInSeconds * 1000L
        var failures = 0
        while (clock() < deadline) {
            try {
                val token = withContext(Dispatchers.IO) { tv.checkPin(pin) }
                failures = 0
                if (token != null) return PinResult.Token(token)
            } catch (e: ServerHttpException) {
                if (e.code == 404) return PinResult.Expired
                if (++failures >= maxFailures) throw e
            }
            onWaiting()
            sleep(intervalMs)
        }
        return PinResult.Expired
    }
}
