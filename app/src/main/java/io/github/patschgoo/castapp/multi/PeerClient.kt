package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.tv.ReceiverGuard
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to another CastApp device's peer server ([PeerServer]). Short timeouts everywhere: a
 * device that doesn't answer is simply not shown, nothing waits for it.
 */
object PeerClient {
    /** GET /castapp/session, or null when the device doesn't answer (in time). */
    fun session(base: String, timeoutMs: Int = 1500): JSONObject? {
        val (code, text) = get("$base/castapp/session", timeoutMs)
        if (code != 200) return null
        return try { JSONObject(text) } catch (_: Exception) { null }
    }

    /** GET of a timeline address ("http://host:port/castapp/timeline[?viewer=id]"): whether the host casts, and the timeline. */
    fun timelineAt(url: String, timeoutMs: Int = 1500): Pair<Boolean, Timeline?>? {
        val (code, text) = get(url, timeoutMs)
        if (code != 200) return null
        val o = try { JSONObject(text) } catch (_: Exception) { return null }
        return o.optBoolean("casting") to Timeline.fromJson(o)
    }

    /** A few NTP-style round trips to [base]; [localClock] must be the clock the player uses. */
    fun clockSamples(base: String, count: Int = ClockSync.SAMPLES, localClock: () -> Long): List<ClockSample> {
        val out = ArrayList<ClockSample>(count)
        repeat(count) {
            val t0 = localClock()
            val (code, text) = get("$base${PeerServer.TIME_PATH}", 1500)
            val t3 = localClock()
            if (code == 200) {
                try {
                    val o = JSONObject(text)
                    out += ClockSample(t0, o.getLong("t1"), o.getLong("t2"), t3)
                } catch (_: Exception) {
                }
            }
        }
        return out
    }

    sealed interface JoinResult {
        data class Joined(val answer: JSONObject) : JoinResult
        /** The host's owner hasn't answered yet; ask again. */
        data object Waiting : JoinResult
        data class Denied(val error: String, val message: String) : JoinResult
        data object Unreachable : JoinResult
    }

    fun join(base: String, name: String): JoinResult {
        val (code, text) = post("$base/castapp/join", JSONObject().put("name", name).toString(), mapOf(ReceiverGuard.NAME_HEADER to ascii(name)), readTimeoutMs = 12_000)
        val o = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
        return when {
            code == -1 -> JoinResult.Unreachable
            code == 200 -> JoinResult.Joined(o)
            code == 202 -> JoinResult.Waiting
            else -> JoinResult.Denied(o.optString("error", "denied"), o.optString("message"))
        }
    }

    /** POSTs [json] to [base] + [path]; status (-1 = unreachable) and answer text. */
    fun post(base: String, path: String, json: JSONObject, readTimeoutMs: Int = 6000): Pair<Int, String> =
        post(base + path, json.toString(), emptyMap(), readTimeoutMs)

    private fun ascii(s: String) = s.filter { it.code in 32..126 }.trim().take(40)

    private fun get(url: String, timeoutMs: Int): Pair<Int, String> = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        -1 to ""
    } catch (_: IllegalArgumentException) {
        -1 to ""
    }

    private fun post(url: String, body: String, headers: Map<String, String>, readTimeoutMs: Int): Pair<Int, String> = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> if (v.isNotEmpty()) conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        -1 to ""
    } catch (_: IllegalArgumentException) {
        -1 to ""
    }
}

/** Another CastApp device on the network, from its advertisement and its /castapp/session answer. */
data class PeerInfo(
    val id: String,
    val name: String,
    /** "phone", "tablet" or "tv". */
    val role: String,
    /** "http://host:port" of its peer server. */
    val base: String,
    val casting: Boolean = false,
    val kind: String = "",
    val title: String = "",
    val joinable: Boolean = false,
    val watchers: Int = 0,
    val screens: List<String> = emptyList(),
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val live: Boolean = false,
    val lastSeen: Long = 0,
) {
    val host: String get() = IncomingHandOver.baseHost(base).orEmpty()

    /** A phone or tablet (what a cast can be handed over to). */
    val isHandheld: Boolean get() = role == "phone" || role == "tablet"

    companion object {
        fun fromSession(base: String, o: JSONObject, seen: Long): PeerInfo? {
            val id = o.optString("id").ifEmpty { return null }
            val screens = o.optJSONArray("screens")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return PeerInfo(
                id = id,
                name = o.optString("name").filter { !it.isISOControl() }.take(60).ifBlank { "CastApp" },
                role = o.optString("role", "phone"),
                base = base,
                casting = o.optBoolean("casting"),
                kind = o.optString("kind"),
                title = o.optString("title").filter { !it.isISOControl() }.take(120),
                joinable = o.optBoolean("joinable"),
                watchers = o.optInt("watchers"),
                screens = screens,
                positionMs = o.optLong("positionMs"),
                durationMs = o.optLong("durationMs"),
                paused = o.optBoolean("paused"),
                live = o.optBoolean("live"),
                lastSeen = seen,
            )
        }
    }
}
