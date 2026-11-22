package io.github.patschgoo.castapp.render

import com.sun.net.httpserver.HttpServer
import io.github.patschgoo.castapp.discovery.DeviceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

/** Roku's External Control Protocol, against a fake Roku. */
class RokuRendererTest {
    private val requests = ArrayList<String>()
    @Volatile private var playerXml = """<player error="false" state="close"/>"""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val line = ex.requestMethod + " " + ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            synchronized(requests) { requests += line }
            val body = when (ex.requestURI.path) {
                "/query/device-info" -> """<?xml version="1.0"?><device-info><serial-number>X00ABC</serial-number><vendor-name>TCL</vendor-name><model-name>Roku TV</model-name><user-device-name>Bedroom Roku</user-device-name></device-info>"""
                "/query/media-player" -> playerXml
                else -> ""
            }.toByteArray()
            ex.sendResponseHeaders(200, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        start()
    }
    private val port = server.address.port

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun playsAndControls() = runBlocking {
        val device = RokuRenderer.deviceFrom("http://127.0.0.1:$port/")
        assertNotNull(device)
        device!!
        assertEquals("Bedroom Roku", device.name)
        assertEquals("roku:X00ABC", device.id)
        assertEquals(DeviceKind.ROKU, device.kind)
        assertEquals("TCL Roku TV", device.model)

        val r = RokuRenderer(device)
        r.connect()
        r.load(RendererMedia("http://192.168.1.5:8090/t/file/0/My Movie.mkv?a=1&b=2", "video/x-matroska", "My Movie & more", "TV"))
        val input = synchronized(requests) { requests.last { it.startsWith("POST /input/") } }
        assertTrue(input, input.startsWith("POST /input/15985?t=v&u="))
        val params = input.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("http://192.168.1.5:8090/t/file/0/My Movie.mkv?a=1&b=2", params["u"])
        assertEquals("My Movie & more", params["videoName"])
        assertEquals("mkv", params["videoFormat"])

        playerXml = """<player error="false" state="play"><position>12345 ms</position><duration>600000 ms</duration></player>"""
        val s = r.refresh()!!
        assertEquals(PlayState.PLAYING, s.state)
        assertEquals(12_345L, s.positionMs)
        assertEquals(600_000L, s.durationMs)
        r.pause()
        assertTrue(synchronized(requests) { requests.contains("POST /keypress/Play") })
        r.setVolume(0.9)
        assertTrue(synchronized(requests) { requests.contains("POST /keypress/VolumeUp") })
        playerXml = """<player error="false" state="close"/>"""
        assertEquals(PlayState.STOPPED, r.refresh()!!.state)
        r.close()
    }
}
