package io.github.patschgoo.castapp.remote.net

import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.remote.RemoteKey
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Roku blocks control by phones ("Control by mobile apps" is off). */
class RokuBlockedException : IOException("Control by mobile apps is off")

/** Roku players and Roku TVs: External Control Protocol key presses over HTTP (port 8060). */
class RokuRemoteClient(private val host: String, private val port: Int = 8060, private val timeoutMs: Int = 4000) {
    private val base get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"

    class Info(val name: String, val serial: String?, val isTv: Boolean, val model: String?, val poweredOn: Boolean)

    fun info(): Info? = Dlna.fetch("$base/query/device-info", timeoutMs)?.let(::parseInfo)

    fun keypress(key: String) {
        val conn = URL("$base/keypress/$key").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.close()
            val code = conn.responseCode
            if (code == 403) throw RokuBlockedException()
            if (code !in 200..299) throw IOException("Roku answered $code")
        } finally {
            conn.disconnect()
        }
    }

    fun press(key: RemoteKey) {
        if (key == RemoteKey.POWER) {
            // Roku TVs: there are separate on and off keys; ask which one applies.
            keypress(if (info()?.poweredOn == false) "PowerOn" else "PowerOff")
            return
        }
        keypress(keyName(key) ?: return)
    }

    /** Types text into the Roku's on-screen keyboard, one character at a time. */
    fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            keypress(literal(String(Character.toChars(cp))))
            i += Character.charCount(cp)
        }
    }

    companion object {
        const val SSDP_TARGET = "roku:ecp"

        private val names = mapOf(
            RemoteKey.UP to "Up", RemoteKey.DOWN to "Down", RemoteKey.LEFT to "Left", RemoteKey.RIGHT to "Right",
            RemoteKey.OK to "Select", RemoteKey.BACK to "Back", RemoteKey.HOME to "Home",
            // The "*" options button.
            RemoteKey.MENU to "Info",
            RemoteKey.PLAY_PAUSE to "Play", RemoteKey.REWIND to "Rev", RemoteKey.FAST_FORWARD to "Fwd",
            // Roku TVs only:
            RemoteKey.VOLUME_UP to "VolumeUp", RemoteKey.VOLUME_DOWN to "VolumeDown", RemoteKey.MUTE to "VolumeMute",
            RemoteKey.CHANNEL_UP to "ChannelUp", RemoteKey.CHANNEL_DOWN to "ChannelDown",
        )

        private val tvOnly = setOf(
            RemoteKey.POWER, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.MUTE, RemoteKey.CHANNEL_UP, RemoteKey.CHANNEL_DOWN,
        )

        fun keyName(key: RemoteKey): String? = names[key]

        /** What works: players lack power, volume and channels (they're on the TV's own remote). */
        fun keys(isTv: Boolean): Set<RemoteKey> = (names.keys + RemoteKey.POWER).filter { isTv || it !in tvOnly }.toSet()

        fun literal(ch: String): String = "Lit_" + URLEncoder.encode(ch, "UTF-8").replace("+", "%20")

        fun parseInfo(xml: String): Info {
            fun t(name: String) = Dlna.tag(xml, name)?.let(Dlna::unescape)?.takeIf { it.isNotBlank() }
            return Info(
                name = t("user-device-name") ?: t("friendly-device-name") ?: t("default-device-name") ?: "Roku",
                serial = t("serial-number") ?: t("device-id"),
                isTv = t("is-tv").equals("true", ignoreCase = true),
                model = listOfNotNull(t("vendor-name"), t("model-name")).joinToString(" ").ifEmpty { null },
                poweredOn = t("power-mode")?.equals("PowerOn", ignoreCase = true) ?: true,
            )
        }
    }
}
