package io.github.patschgoo.castapp.remote.net

import io.github.patschgoo.castapp.remote.RemoteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG webOS TVs: SSAP (Simple Service Access Protocol) as JSON over a WebSocket on port 3000
 * (`wss://` on 3001 for newer firmware). Registration with a manifest makes the TV ask
 * "Allow?" once and hand out a client key. Navigation keys go through a second "pointer input"
 * socket in a small line-based format.
 */
object LgProtocol {
    const val PLAIN_PORT = 3000
    const val SECURE_PORT = 3001
    const val SSDP_TARGET = "urn:lge-com:service:webos-second-screen:1"

    /** Done with an SSAP request instead of the pointer socket. */
    val requests: Map<RemoteKey, String> = mapOf(
        RemoteKey.POWER to "ssap://system/turnOff",
        RemoteKey.VOLUME_UP to "ssap://audio/volumeUp",
        RemoteKey.VOLUME_DOWN to "ssap://audio/volumeDown",
    )

    /** Pointer-socket button names. PLAY_PAUSE alternates PAUSE and PLAY (webOS has no toggle). */
    val buttons: Map<RemoteKey, String> = mapOf(
        RemoteKey.UP to "UP", RemoteKey.DOWN to "DOWN", RemoteKey.LEFT to "LEFT", RemoteKey.RIGHT to "RIGHT",
        RemoteKey.OK to "ENTER", RemoteKey.BACK to "BACK", RemoteKey.HOME to "HOME", RemoteKey.MENU to "MENU",
        RemoteKey.MUTE to "MUTE", RemoteKey.CHANNEL_UP to "CHANNELUP", RemoteKey.CHANNEL_DOWN to "CHANNELDOWN",
        RemoteKey.PLAY_PAUSE to "PAUSE", RemoteKey.STOP to "STOP", RemoteKey.REWIND to "REWIND", RemoteKey.FAST_FORWARD to "FASTFORWARD",
        RemoteKey.INPUT to "INPUT_HUB", RemoteKey.INFO to "INFO", RemoteKey.GUIDE to "GUIDE",
    ) + (0..9).associate { RemoteKey.valueOf("NUM_$it") to "$it" }

    val keys: Set<RemoteKey> get() = requests.keys + buttons.keys

    fun button(name: String): String = "type:button\nname:$name\n\n"

    fun request(id: String, uri: String, payload: JSONObject = JSONObject()): String =
        JSONObject().put("id", id).put("type", "request").put("uri", uri).put("payload", payload).toString()

    fun hello(): String = JSONObject().put("id", "hello").put("type", "hello").put("payload", JSONObject()).toString()

    fun insertText(id: String, text: String): String =
        request(id, "ssap://com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", 0))

    private const val SIGNATURE =
        "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9." +
            "hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128q" +
            "vIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n" +
            "50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwa" +
            "CViZNCP8iC9aO0q9fQojoa7NQnAtw=="

    private val PERMISSIONS = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED", "CONTROL_AUDIO",
        "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_TV_SCREEN", "READ_APP_STATUS", "READ_CURRENT_CHANNEL",
        "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST",
        "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE", "READ_COUNTRY_INFO", "CONTROL_INPUT_TEXT",
        "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS", "READ_SETTINGS",
    )

    private val SIGNED_PERMISSIONS = listOf(
        "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS", "READ_LGE_SDX",
        "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT", "CONTROL_POWER",
        "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP",
        "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME",
    )

    /**
     * The registration message with the widely used LG remote-app manifest (as in aiowebostv).
     * [signed] false leaves the signature out and merges the permissions; newer firmware wants that.
     */
    fun register(clientKey: String?, signed: Boolean = true): String {
        val manifest = JSONObject().put("appVersion", "1.1").put("manifestVersion", 1)
        if (signed) {
            manifest.put("permissions", JSONArray(PERMISSIONS))
            manifest.put("signatures", JSONArray().put(JSONObject().put("signature", SIGNATURE).put("signatureVersion", 1)))
            manifest.put(
                "signed",
                JSONObject()
                    .put("appId", "com.lge.test")
                    .put("created", "20140509")
                    .put("localizedAppNames", JSONObject().put("", "LG Remote App").put("ko-KR", "리모컨 앱").put("zxx-XX", "ЛГ Rэмotэ AПП"))
                    .put("localizedVendorNames", JSONObject().put("", "LG Electronics"))
                    .put("permissions", JSONArray(SIGNED_PERMISSIONS))
                    .put("serial", "2f930e2d2cfe083771f68e4fe7bb07")
                    .put("vendorId", "com.lge"),
            )
        } else {
            manifest.put("permissions", JSONArray((PERMISSIONS + SIGNED_PERMISSIONS).toSortedSet().toList()))
        }
        val payload = JSONObject().put("forcePairing", false).put("pairingType", "PROMPT").put("manifest", manifest)
        if (!clientKey.isNullOrEmpty()) payload.put("client-key", clientKey)
        return JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString()
    }
}

class LgRemoteClient(
    private val host: String,
    private val urls: List<String> = listOf("wss://$host:${LgProtocol.SECURE_PORT}", "ws://$host:${LgProtocol.PLAIN_PORT}"),
) {
    /**
     * Connects and registers. [onPrompt] is called when the TV asks the user to accept the phone.
     * The connection's [LgConnection.clientKey] is the key to save.
     */
    suspend fun connect(clientKey: String?, onPrompt: () -> Unit, answerTimeoutMs: Long = 60_000): LgConnection = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        var ws: WebSocket? = null
        for (url in urls) {
            try {
                ws = WebSocket.connect(url, connectTimeoutMs = 4000, readTimeoutMs = 5000)
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        val main = ws ?: throw lastError ?: IOException("No connection")
        try {
            // Newer firmware wants "hello" and the system info before registering; older ones may not answer.
            main.sendText(LgProtocol.hello())
            awaitId(main, "hello", 3000)
            main.sendText(LgProtocol.request("get_sys_info", "ssap://system/getSystemInfo"))
            awaitId(main, "get_sys_info", 3000)

            var key = clientKey
            var signed = true
            var registered = false
            val deadline = System.currentTimeMillis() + answerTimeoutMs
            main.sendText(LgProtocol.register(key, signed))
            main.readTimeoutMs = 5000
            while (!registered) {
                if (System.currentTimeMillis() > deadline) throw RemoteNoAnswerException()
                val msg = try {
                    main.receiveText()?.let { JSONObject(it) } ?: throw IOException("The TV closed the connection")
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (msg.optString("id") != "register_0") continue
                when (msg.optString("type")) {
                    "response" -> if (msg.optJSONObject("payload")?.optString("pairingType") == "PROMPT") onPrompt()
                    "registered" -> {
                        key = msg.optJSONObject("payload")?.optString("client-key")?.ifEmpty { null } ?: key
                        registered = true
                    }
                    "error" -> {
                        val error = msg.optString("error")
                        if (signed && !error.contains("denied", ignoreCase = true) && !error.contains("cancel", ignoreCase = true)) {
                            signed = false
                            main.sendText(LgProtocol.register(key, signed = false))
                        } else if (error.contains("denied", ignoreCase = true) || error.contains("cancel", ignoreCase = true)) {
                            throw RemoteDeniedException()
                        } else {
                            throw IOException("Registration failed: $error")
                        }
                    }
                }
            }
            if (key.isNullOrEmpty()) throw IOException("The TV sent no client key")

            main.sendText(LgProtocol.request("pointer", "ssap://com.webos.service.networkinput/getPointerInputSocket"))
            val socketPath = awaitId(main, "pointer", 5000)?.optJSONObject("payload")?.optString("socketPath")?.ifEmpty { null }
                ?: throw IOException("No pointer socket")
            val pointer = openPointer(socketPath)
            main.readTimeoutMs = 0
            LgConnection(main, pointer, key)
        } catch (e: Exception) {
            main.close()
            throw e
        }
    }

    private fun openPointer(path: String): WebSocket = try {
        WebSocket.connect(path, connectTimeoutMs = 4000, readTimeoutMs = 0)
    } catch (e: IOException) {
        // Some firmware hands out a ws:// path but only serves it securely.
        val uri = try { URI(path) } catch (_: Exception) { throw e }
        if (uri.scheme != "ws") throw e
        WebSocket.connect("wss://${uri.host}:${LgProtocol.SECURE_PORT}${uri.rawPath}", connectTimeoutMs = 4000, readTimeoutMs = 0)
    }

    /** Waits for the answer with [id]; null on timeout. */
    private fun awaitId(ws: WebSocket, id: String, timeoutMs: Long): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            ws.readTimeoutMs = left.toInt().coerceAtLeast(1)
            val text = try { ws.receiveText() } catch (_: SocketTimeoutException) { return null } ?: throw IOException("The TV closed the connection")
            val msg = try { JSONObject(text) } catch (_: Exception) { continue }
            if (msg.optString("id") == id) return msg
        }
    }
}

/** A registered webOS connection: the SSAP socket plus the pointer-input socket. */
class LgConnection internal constructor(private val main: WebSocket, private val pointer: WebSocket, val clientKey: String) : Closeable {
    private val ids = AtomicInteger(1)
    @Volatile private var playing = true

    init {
        for ((ws, name) in listOf(main to "lg-ssap", pointer to "lg-pointer")) {
            Thread({
                try { while (ws.receive() != null) { /* answers and events are not needed */ } } catch (_: Exception) {}
                close()
            }, name).apply { isDaemon = true; start() }
        }
    }

    val isOpen: Boolean get() = !main.isClosed && !pointer.isClosed

    fun press(key: RemoteKey) {
        LgProtocol.requests[key]?.let {
            main.sendText(LgProtocol.request("c${ids.getAndIncrement()}", it))
            return
        }
        var name = LgProtocol.buttons[key] ?: return
        if (key == RemoteKey.PLAY_PAUSE) {
            name = if (playing) "PAUSE" else "PLAY"
            playing = !playing
        }
        if (key == RemoteKey.STOP) playing = false
        pointer.sendText(LgProtocol.button(name))
    }

    /** Types into the TV's keyboard while a text field is open. */
    fun type(text: String) {
        if (text.isEmpty()) return
        main.sendText(LgProtocol.insertText("t${ids.getAndIncrement()}", text))
    }

    override fun close() {
        pointer.close()
        main.close()
    }
}
