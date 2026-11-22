package io.github.patschgoo.castapp.tv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** The "swap direction" commands CastApp devices send each other. */
class SwapCommandsTest {
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

    private class Commands : CastAppCommands {
        val calls = ArrayList<String>()
        var answer: String? = null
        override fun mirrorTo(descriptionUrl: String): String? {
            calls += "mirror:$descriptionUrl"
            return answer
        }
        override fun stopMirror() {
            calls += "stop"
        }
    }

    private val renderer = UpnpRenderer("TV", "uuid-1", NoTv())

    @After
    fun tearDown() = renderer.stop()

    private fun post(path: String, body: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${renderer.port}$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        return code to text
    }

    @Test
    fun mirrorAndStopFromTheLocalNetwork() {
        val commands = Commands()
        renderer.commands = commands
        renderer.start(0, ssdp = false)

        val (code, _) = post("/castapp/mirror", """{"description":"http://127.0.0.1:49595/description.xml"}""")
        assertEquals(200, code)
        assertEquals("mirror:http://127.0.0.1:49595/description.xml", commands.calls.last())

        commands.answer = "The phone isn't ready"
        val (busy, text) = post("/castapp/mirror", """{"description":"http://127.0.0.1:49595/description.xml"}""")
        assertEquals(409, busy)
        assertEquals("The phone isn't ready", text)

        assertEquals(200, post("/castapp/stop-mirror", "{}").first)
        assertEquals("stop", commands.calls.last())
        assertEquals(404, post("/castapp/explode", "{}").first)
    }

    @Test
    fun refusesToMirrorToSomeoneElse() {
        val commands = Commands()
        renderer.commands = commands
        renderer.start(0, ssdp = false)
        // A device may only ask for the screen to be sent to itself.
        assertEquals(403, post("/castapp/mirror", """{"description":"http://192.168.1.99:49595/description.xml"}""").first)
        assertEquals(403, post("/castapp/mirror", """not json""").first)
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun notOfferedWithoutCommands() {
        renderer.start(0, ssdp = false)
        assertEquals(404, post("/castapp/mirror", """{"description":"http://127.0.0.1:1/description.xml"}""").first)
    }

    @Test
    fun addressRules() {
        val phone = InetAddress.getByName("192.168.1.20")
        assertTrue(UpnpRenderer.mirrorAllowed(phone, "http://192.168.1.20:49595/description.xml"))
        assertFalse(UpnpRenderer.mirrorAllowed(phone, "http://192.168.1.21:49595/description.xml"))
        assertFalse(UpnpRenderer.mirrorAllowed(phone, "https://192.168.1.20:49595/description.xml"))
        assertFalse(UpnpRenderer.mirrorAllowed(phone, "http://192.168.1.20/description.xml"))
        assertFalse(UpnpRenderer.mirrorAllowed(phone, "garbage"))
        assertFalse(UpnpRenderer.mirrorAllowed(null, "http://192.168.1.20:49595/description.xml"))
        val internet = InetAddress.getByName("8.8.8.8")
        assertFalse(UpnpRenderer.isLocal(internet))
        assertFalse(UpnpRenderer.mirrorAllowed(internet, "http://8.8.8.8:49595/description.xml"))
        assertTrue(UpnpRenderer.isLocal(InetAddress.getByName("10.0.0.5")))
        assertTrue(UpnpRenderer.isLocal(InetAddress.getByName("172.16.3.4")))
        assertTrue(UpnpRenderer.isLocal(InetAddress.getByName("169.254.1.1")))
    }
}
