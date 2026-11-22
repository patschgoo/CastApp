package io.github.patschgoo.castapp.remote.net

import io.github.patschgoo.castapp.remote.RemoteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import kotlin.io.encoding.Base64

/** The TV answered "Deny" (or was set to block this phone). */
class RemoteDeniedException : IOException("The TV refused the remote")

/** The TV didn't answer the "Allow this device?" question in time. */
class RemoteNoAnswerException : IOException("No answer from the TV")

/**
 * Samsung Tizen TVs (2016 and newer): the WebSocket remote of the SmartThings / Smart View apps.
 * 2018+ models want `wss://…:8002` with a token the TV hands out after "Allow";
 * older ones only have `ws://…:8001`.
 */
object SamsungProtocol {
    const val SECURE_PORT = 8002
    const val PLAIN_PORT = 8001

    fun url(host: String, secure: Boolean, appName: String, token: String?): String {
        val name = URLEncoder.encode(Base64.Default.encode(appName.toByteArray(Charsets.UTF_8)), "UTF-8")
        val scheme = if (secure) "wss" else "ws"
        val port = if (secure) SECURE_PORT else PLAIN_PORT
        val tokenPart = if (secure && !token.isNullOrEmpty()) "&token=" + URLEncoder.encode(token, "UTF-8") else ""
        return "$scheme://$host:$port/api/v2/channels/samsung.remote.control?name=$name$tokenPart"
    }

    fun click(key: String): String = JSONObject()
        .put("method", "ms.remote.control")
        .put("params", JSONObject().put("Cmd", "Click").put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
        .toString()

    fun inputString(text: String): String = JSONObject()
        .put("method", "ms.remote.control")
        .put(
            "params",
            JSONObject()
                .put("Cmd", Base64.Default.encode(text.toByteArray(Charsets.UTF_8)))
                .put("DataOfCmd", "base64")
                .put("TypeOfRemote", "SendInputString"),
        )
        .toString()

    fun inputEnd(): String = JSONObject()
        .put("method", "ms.remote.control")
        .put("params", JSONObject().put("TypeOfRemote", "SendInputEnd"))
        .toString()

    /** Key names; PLAY_PAUSE alternates between KEY_PAUSE and KEY_PLAY (there's no toggle key). */
    val keys: Map<RemoteKey, String> = mapOf(
        RemoteKey.POWER to "KEY_POWER",
        RemoteKey.UP to "KEY_UP", RemoteKey.DOWN to "KEY_DOWN", RemoteKey.LEFT to "KEY_LEFT", RemoteKey.RIGHT to "KEY_RIGHT",
        RemoteKey.OK to "KEY_ENTER", RemoteKey.BACK to "KEY_RETURN", RemoteKey.HOME to "KEY_HOME", RemoteKey.MENU to "KEY_MENU",
        RemoteKey.VOLUME_UP to "KEY_VOLUP", RemoteKey.VOLUME_DOWN to "KEY_VOLDOWN", RemoteKey.MUTE to "KEY_MUTE",
        RemoteKey.CHANNEL_UP to "KEY_CHUP", RemoteKey.CHANNEL_DOWN to "KEY_CHDOWN",
        RemoteKey.PLAY_PAUSE to "KEY_PAUSE", RemoteKey.STOP to "KEY_STOP", RemoteKey.REWIND to "KEY_REWIND", RemoteKey.FAST_FORWARD to "KEY_FF",
        RemoteKey.INPUT to "KEY_SOURCE", RemoteKey.INFO to "KEY_INFO", RemoteKey.GUIDE to "KEY_GUIDE",
    ) + (0..9).associate { RemoteKey.valueOf("NUM_$it") to "KEY_$it" }

    class Info(val name: String, val model: String?, val tokenAuth: Boolean, val id: String?)

    /** Device info from `http://<ip>:8001/api/v2/`; null if it isn't a Tizen Samsung TV. */
    fun parseInfo(json: String): Info? = try {
        val root = JSONObject(json)
        val device = root.optJSONObject("device") ?: JSONObject()
        val type = device.optString("type").ifEmpty { root.optString("type") }
        if (!type.contains("Samsung", ignoreCase = true) && !root.optString("uri").contains("api/v2")) {
            null
        } else {
            Info(
                name = device.optString("name").ifEmpty { root.optString("name") }.ifEmpty { "Samsung TV" },
                model = device.optString("modelName").ifEmpty { null },
                tokenAuth = device.optString("TokenAuthSupport").equals("true", ignoreCase = true),
                id = device.optString("id").ifEmpty { root.optString("id") }.ifEmpty { null },
            )
        }
    } catch (_: Exception) {
        null
    }

    fun fetchInfo(host: String, port: Int = PLAIN_PORT, timeoutMs: Int = 2500): Info? = try {
        val conn = URL("http://$host:$port/api/v2/").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        try {
            if (conn.responseCode == 200) parseInfo(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

class SamsungRemoteClient(
    private val host: String,
    private val appName: String,
    private val securePort: Boolean = true,
    private val urlFor: (secure: Boolean, token: String?) -> String = { secure, token -> SamsungProtocol.url(host, secure, appName, token) },
) {
    /**
     * Connects; the TV may ask "Allow this device?" first, then [onPrompt] is called.
     * [token] is the one saved from last time; the connection's [SamsungConnection.token] is the one to save now.
     */
    suspend fun connect(
        token: String?,
        onPrompt: () -> Unit,
        answerTimeoutMs: Int = 45_000,
        /** Silence after which we assume the TV shows its "Allow?" question. */
        promptAfterMs: Int = 2500,
    ): SamsungConnection = withContext(Dispatchers.IO) {
        val first = securePort
        val ws = try {
            WebSocket.connect(urlFor(first, token), connectTimeoutMs = 4000, readTimeoutMs = promptAfterMs)
        } catch (e: IOException) {
            if (!first) throw e
            // Older Tizen TVs (2016/2017) have no secure port.
            WebSocket.connect(urlFor(false, null), connectTimeoutMs = 4000, readTimeoutMs = promptAfterMs)
        }
        try {
            var prompted = false
            val started = System.currentTimeMillis()
            while (true) {
                val text = try {
                    ws.receiveText() ?: throw if (prompted) RemoteDeniedException() else IOException("The TV closed the connection")
                } catch (e: SocketTimeoutException) {
                    if (!prompted) {
                        prompted = true
                        onPrompt()
                        ws.readTimeoutMs = 5000
                    }
                    if (System.currentTimeMillis() - started > answerTimeoutMs) throw RemoteNoAnswerException()
                    continue
                }
                val msg = try { JSONObject(text) } catch (_: Exception) { continue }
                when (msg.optString("event")) {
                    "ms.channel.connect" -> {
                        val newToken = msg.optJSONObject("data")?.optString("token")?.ifEmpty { null }
                        ws.readTimeoutMs = 0
                        return@withContext SamsungConnection(ws, newToken ?: token)
                    }
                    "ms.channel.unauthorized" -> throw RemoteDeniedException()
                    "ms.error" -> throw IOException(msg.optJSONObject("data")?.optString("message") ?: "Samsung error")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        } catch (e: Exception) {
            ws.close()
            throw e
        }
    }
}

/** An open Samsung remote channel. */
class SamsungConnection internal constructor(private val ws: WebSocket, val token: String?) : Closeable {
    @Volatile private var playing = true
    @Volatile var keyboardOpen = false
        private set

    init {
        Thread({
            try {
                while (true) {
                    val text = ws.receiveText() ?: break
                    val event = try { JSONObject(text).optString("event") } catch (_: Exception) { "" }
                    if (event == "ms.remote.imeStart") keyboardOpen = true
                    if (event == "ms.remote.imeEnd") keyboardOpen = false
                }
            } catch (_: Exception) {
            }
            ws.close()
        }, "samsung-remote").apply { isDaemon = true; start() }
    }

    val isOpen: Boolean get() = !ws.isClosed

    fun press(key: RemoteKey) {
        var name = SamsungProtocol.keys[key] ?: return
        if (key == RemoteKey.PLAY_PAUSE) {
            name = if (playing) "KEY_PAUSE" else "KEY_PLAY"
            playing = !playing
        }
        if (key == RemoteKey.STOP) playing = false
        ws.sendText(SamsungProtocol.click(name))
    }

    /** Types into the TV's on-screen keyboard (works while a text field is open on the TV). */
    fun type(text: String) {
        if (text.isEmpty()) return
        ws.sendText(SamsungProtocol.inputString(text))
        ws.sendText(SamsungProtocol.inputEnd())
    }

    override fun close() = ws.close()
}
