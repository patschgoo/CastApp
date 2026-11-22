package io.github.patschgoo.castapp.tv

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** The TV's capabilities endpoint (HEVC) and the "open in YouTube" command. */
class CastAppExtrasTest {
    private class NoTv : RendererBackend {
        override val snapshot = TvSnapshot()
        override fun setMedia(media: TvMedia) {}
        override fun play() {}
        override fun pause() {}
        override fun stop() {}
        override fun seek(positionMs: Long) {}
        override fun setVolume(percent: Int) {}
        override fun setMuted(muted: Boolean) {}
    }

    private class Commands(val hevc: Boolean) : CastAppCommands {
        val opened = ArrayList<String>()
        override fun mirrorTo(descriptionUrl: String): String? = null
        override fun stopMirror() {}
        override fun capabilities(): Map<String, Any> = mapOf("hevc" to hevc, "youtube" to true)
        override fun openYouTube(url: String): String? {
            opened += url
            return null
        }
    }

    private val renderer = UpnpRenderer("TV", "uuid-1", NoTv())

    @After
    fun tearDown() = renderer.stop()

    private fun request(method: String, path: String, body: String? = null): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${renderer.port}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        return code to text
    }

    @Test
    fun capabilitiesTellTheCodec() {
        renderer.commands = Commands(hevc = true)
        renderer.start(0, ssdp = false)
        val (code, text) = request("GET", "/castapp/capabilities")
        assertEquals(200, code)
        val json = JSONObject(text)
        assertTrue(json.getBoolean("hevc"))
        assertEquals(UpnpRenderer.CAPABILITIES_VERSION, json.getInt("version"))
    }

    @Test
    fun noCommandsNoCapabilities() {
        renderer.start(0, ssdp = false)
        assertEquals(404, request("GET", "/castapp/capabilities").first)
    }

    @Test
    fun opensOnlyCleanYouTubeLinks() {
        val commands = Commands(hevc = false)
        renderer.commands = commands
        renderer.start(0, ssdp = false)
        val (code, _) = request("POST", "/castapp/youtube", """{"url":"https://youtu.be/dQw4w9WgXcQ?t=42&si=tracking"}""")
        assertEquals(200, code)
        assertEquals(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"), commands.opened)

        assertEquals(400, request("POST", "/castapp/youtube", """{"url":"https://evil.example/watch?v=dQw4w9WgXcQ"}""").first)
        assertEquals(400, request("POST", "/castapp/youtube", """{"url":"intent:#Intent;action=x;end"}""").first)
        assertEquals(400, request("POST", "/castapp/youtube", "not json").first)
        assertEquals(1, commands.opened.size)
    }
}
