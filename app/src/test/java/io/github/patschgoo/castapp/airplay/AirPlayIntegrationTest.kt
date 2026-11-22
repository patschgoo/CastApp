package io.github.patschgoo.castapp.airplay

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.render.AirPlayRenderer
import io.github.patschgoo.castapp.render.MediaRenderer
import io.github.patschgoo.castapp.render.PlayState
import io.github.patschgoo.castapp.render.RendererMedia
import io.github.patschgoo.castapp.render.RendererStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the AirPlay renderer against a fake Apple TV built from pyatv's server-side
 * pairing code. Opt-in: CASTAPP_AIRPLAY_TEST="host|port|logfile|transient-or-pin".
 */
class AirPlayIntegrationTest {
    private class MemoryStore : AirPlayPairingStore {
        val saved = HashMap<String, AirPlayCredentials>()
        override fun load(deviceId: String) = saved[deviceId]
        override fun save(deviceId: String, credentials: AirPlayCredentials) { saved[deviceId] = credentials }
        override fun clear(deviceId: String) { saved.remove(deviceId) }
    }

    @Test
    fun playsOnFakeAppleTv() = runBlocking {
        val env = System.getenv("CASTAPP_AIRPLAY_TEST")
        assumeTrue("set CASTAPP_AIRPLAY_TEST to run", env != null)
        val (host, port, logPath, mode) = env!!.split('|')
        val device = CastDevice(
            "airplay:AA:BB", "Fake TV", host, port.toInt(),
            kind = DeviceKind.AIRPLAY, airplayFeatures = "0x4A7FDFD5,0xBC157FDE",
        )
        val store = MemoryStore()
        var asked = 0

        suspend fun session(start: Long) {
            val r = AirPlayRenderer(device, store) { asked++; "1111" }
            val statuses = ArrayList<RendererStatus>()
            r.listener = object : MediaRenderer.Listener {
                override fun onStatus(status: RendererStatus) { synchronized(statuses) { statuses += status } }
                override fun onLost(error: Throwable?) {}
                override fun onClosedOnDevice() {}
            }
            r.connect()
            assertNotNull(r.localAddress)
            r.load(RendererMedia("http://192.168.1.5:8090/tok/file/0/movie.mp4", "video/mp4", "Movie", "TV", startMs = start))
            val playing = r.refresh()!!
            assertEquals(PlayState.PLAYING, playing.state)
            assertEquals(start, playing.positionMs)
            assertEquals(600_000L, playing.durationMs)
            r.pause()
            assertEquals(PlayState.PAUSED, r.refresh()!!.state)
            r.seek(90_000)
            assertEquals(90_000L, r.refresh()!!.positionMs)
            r.setVolume(0.5)
            delay(1500) // event channel + timing checks run on the fake TV
            r.stop()
            r.close()
        }

        session(30_000)
        if (mode == "pin") {
            assertEquals(1, asked)
            assertTrue(store.saved.containsKey(device.id))
            // A second cast reuses the pairing: no code needed.
            session(0)
            assertEquals(1, asked)
        } else {
            assertEquals(0, asked)
        }
        delay(300)

        val events = JSONArray(File(logPath).readText())
        val kinds = (0 until events.length()).map { events.getJSONObject(it).getString("kind") }
        fun first(kind: String): JSONObject = (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("kind") == kind }
        assertTrue("no errors: $kinds", kinds.none { it == "error" || it == "unknown" || it == "unencrypted-request" })
        assertTrue(kinds.contains(if (mode == "pin") "transient-refused" else "transient"))
        if (mode == "pin") assertTrue(kinds.contains("verified"))
        assertTrue(kinds.containsAll(listOf("encrypted", "setup", "record", "play", "property", "rate", "scrub", "set_parameter", "stop")))
        assertEquals("NTP", first("setup").getString("timingProtocol"))
        assertEquals("http://192.168.1.5:8090/tok/file/0/movie.mp4", first("play").getString("url"))
        assertEquals(30.0, first("play").getDouble("start"), 0.001)
        assertEquals("HTTP", first("play").getString("protocol"))
        assertEquals("volume: -15.000000", first("set_parameter").getString("body"))
        assertTrue("event channel answered", first("event-reply").getBoolean("ok"))
        assertTrue("timing answered", first("timing-reply").getBoolean("ok"))
    }

    @Test
    fun plistRoundTrip() {
        val value = mapOf(
            "Content-Location" to "http://x/ä.mp4",
            "Start-Position-Seconds" to 12.5,
            "rate" to 1.0,
            "streamType" to 1L,
            "big" to 5_000_000_000L,
            "flag" to true,
            "list" to listOf("a", 2L, false),
            "data" to byteArrayOf(1, 2, 3),
            "nested" to mapOf("value" to 0L),
        )
        @Suppress("UNCHECKED_CAST")
        val back = BinaryPlist.decode(BinaryPlist.encode(value)) as Map<String, Any?>
        assertEquals("http://x/ä.mp4", back["Content-Location"])
        assertEquals(12.5, back["Start-Position-Seconds"])
        assertEquals(1L, back["streamType"])
        assertEquals(5_000_000_000L, back["big"])
        assertEquals(true, back["flag"])
        assertEquals(listOf("a", 2L, false), back["list"])
        assertTrue((back["data"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(mapOf("value" to 0L), back["nested"])
    }

    @Test
    fun readsPlistWrittenByPython() {
        // plistlib.dumps({"eventPort": 52713, "timingPort": 0, "ok": True, "name": "TV"}, fmt=FMT_BINARY)
        val hex = "62706c6973743030d40102030405060708596576656e74506f7274546e616d65526f6b5a74696d696e67506f727411cde952545609100008111b20232e3134350000000000000101000000000000000900000000000000000000000000000037"
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        @Suppress("UNCHECKED_CAST")
        val map = BinaryPlist.decode(bytes) as Map<String, Any?>
        assertEquals(52713L, map["eventPort"])
        assertEquals(0L, map["timingPort"])
        assertEquals(true, map["ok"])
        assertEquals("TV", map["name"])
    }

    @Test
    fun tlvSplitsLongValues() {
        val key = ByteArray(384) { it.toByte() }
        val encoded = Tlv8.encode(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to key)
        assertEquals(3 + 2 + 255 + 2 + 129, encoded.size)
        val decoded = Tlv8.decode(encoded)
        assertTrue(decoded[Tlv8.PUBLIC_KEY]!!.contentEquals(key))
        assertEquals(3, decoded[Tlv8.STATE]!![0].toInt())
    }

    @Test
    fun featureBits() {
        val appleTv = AirPlayFeatures.parse("0x4A7FDFD5,0xBC157FDE")
        assertTrue(AirPlayFeatures.video(appleTv))
        assertTrue(AirPlayFeatures.needsPairing(appleTv))
        assertTrue(AirPlayFeatures.isV2(appleTv))
        val old = AirPlayFeatures.parse("0x5A7FFFF7")
        assertTrue(AirPlayFeatures.video(old))
        assertTrue(!AirPlayFeatures.isV2(old))
    }
}
