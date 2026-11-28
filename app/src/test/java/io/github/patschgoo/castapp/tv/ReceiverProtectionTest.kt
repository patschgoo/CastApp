package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.dlna.SsdpBrowser
import io.github.patschgoo.castapp.render.DlnaRenderer
import io.github.patschgoo.castapp.render.RendererMedia
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** The receiver's protection over real HTTP: DLNA requests, CastApp's commands and the pairing endpoint. */
class ReceiverProtectionTest {
    private class FakeTv : RendererBackend {
        val calls = ArrayList<String>()
        override var snapshot = TvSnapshot()
        override fun setMedia(media: TvMedia) { calls += "set"; snapshot = snapshot.copy(state = TransportState.STOPPED) }
        override fun play() { calls += "play"; snapshot = snapshot.copy(state = TransportState.PLAYING) }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun seek(positionMs: Long) { calls += "seek" }
        override fun setVolume(percent: Int) { calls += "vol:$percent" }
        override fun setMuted(muted: Boolean) {}
    }

    private class Commands : CastAppCommands {
        val calls = ArrayList<String>()
        override fun mirrorTo(descriptionUrl: String): String? { calls += "mirror"; return null }
        override fun stopMirror() { calls += "stop" }
    }

    private class MemoryTokens : TokenStore {
        val map = HashMap<String, String>()
        override fun get(deviceId: String) = map[deviceId]
        override fun put(deviceId: String, token: String) { map[deviceId] = token }
        override fun remove(deviceId: String) { map.remove(deviceId) }
    }

    private val tv = FakeTv()
    private val commands = Commands()
    private val renderer = UpnpRenderer("Living Room Fire TV", "1234-abcd", tv, "3.1.0")
    private val guard = ReceiverGuard(MemoryGuardStore(), ProtectionMode.ANYONE, askTimeoutMs = 3000)

    @After
    fun tearDown() = renderer.stop()

    private fun start(mode: ProtectionMode) {
        guard.setMode(mode)
        renderer.guard = guard
        renderer.commands = commands
        renderer.start(0, ssdp = false)
    }

    private val avt get() = "http://127.0.0.1:${renderer.port}/control/AVTransport"
    private val rc get() = "http://127.0.0.1:${renderer.port}/control/RenderingControl"
    private val setUri = listOf("InstanceID" to "0", "CurrentURI" to "http://192.168.1.5:8090/a.mp4", "CurrentURIMetaData" to "")

    private fun soapError(block: () -> Unit): String = try {
        block()
        fail("expected a SOAP error")
        ""
    } catch (e: Dlna.SoapException) {
        e.message!!
    }

    private fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${renderer.port}$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        return code to text
    }

    private fun device(): CastDevice {
        val location = "http://127.0.0.1:${renderer.port}/description.xml"
        val description = Dlna.parseDescription(Dlna.fetch(location)!!, location)
        return SsdpBrowser.rendererDevice(location, description)!!
    }

    private fun answerFirstPrompt(decision: Decision) = thread {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            guard.pending.value.firstOrNull()?.let { guard.answer(it.id, decision); return@thread }
            Thread.sleep(5)
        }
    }

    private val media = RendererMedia(url = "http://192.168.1.5:8090/a.mp4", mimeType = "video/mp4", title = "Clip", subtitle = "", live = false)

    @Test
    fun withoutProtectionEverythingWorksAsBefore() {
        renderer.guard = null
        renderer.start(0, ssdp = false)
        Dlna.call(avt, Dlna.AV_TRANSPORT, "SetAVTransportURI", setUri)
        assertEquals(listOf("set"), tv.calls)
        assertEquals(404, post("/castapp/pair", "{}").first)
    }

    @Test
    fun askFirstPromptsOnTheFirstCastAndRemembersTheAnswer() {
        start(ProtectionMode.ASK)
        val headers = mapOf("User-Agent" to "BubbleUPnP/3.4", ReceiverGuard.NAME_HEADER to "Anna's phone")
        // Without being allowed, only harmless things work: stopping something is refused at once.
        assertTrue(soapError { Dlna.call(avt, Dlna.AV_TRANSPORT, "Stop", listOf("InstanceID" to "0"), headers = headers) }.contains("Not allowed"))
        assertEquals(Dlna.tag(Dlna.call(avt, Dlna.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"), headers = headers), "CurrentTransportStatus"), "OK")
        assertTrue(tv.calls.isEmpty())

        var promptName: String? = null
        val owner = thread {
            val deadline = System.currentTimeMillis() + 3000
            while (guard.pending.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
            promptName = guard.pending.value.firstOrNull()?.name
            guard.pending.value.firstOrNull()?.let { guard.answer(it.id, Decision.ALLOW_ALWAYS) }
        }
        Dlna.call(avt, Dlna.AV_TRANSPORT, "SetAVTransportURI", setUri, headers = headers)
        owner.join()
        assertEquals("Anna's phone", promptName)
        Dlna.call(avt, Dlna.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"), headers = headers)
        assertEquals(listOf("set", "play"), tv.calls)

        // Next time without a question. Another app on the same phone is asked on its own.
        Dlna.call(avt, Dlna.AV_TRANSPORT, "SetAVTransportURI", setUri, headers = headers)
        assertTrue(guard.pending.value.isEmpty())
        val blocker = answerFirstPrompt(Decision.BLOCK)
        val message = soapError { Dlna.call(avt, Dlna.AV_TRANSPORT, "SetAVTransportURI", setUri, headers = mapOf("User-Agent" to "Other/1")) }
        blocker.join()
        assertTrue(message, message.contains("Blocked"))
        assertEquals(listOf("set", "play", "set"), tv.calls)
    }

    @Test
    fun askFirstAnswersInTimeWhenNobodyIsThere() {
        guard.setMode(ProtectionMode.ASK)
        val quick = ReceiverGuard(MemoryGuardStore(), ProtectionMode.ASK, askTimeoutMs = 150)
        renderer.guard = quick
        renderer.start(0, ssdp = false)
        val message = soapError { Dlna.call(avt, Dlna.AV_TRANSPORT, "SetAVTransportURI", setUri) }
        assertTrue(message, message.contains("Waiting for permission"))
        assertTrue(tv.calls.isEmpty())
    }

    /** A raw SUBSCRIBE (HttpURLConnection can't send that method): the status code. */
    private fun subscribe(): Int = java.net.Socket("127.0.0.1", renderer.port).use { s ->
        s.soTimeout = 5000
        s.getOutputStream().write(
            ("SUBSCRIBE /event/AVTransport HTTP/1.1\r\nHost: 127.0.0.1:${renderer.port}\r\n" +
                "CALLBACK: <http://127.0.0.1:9/cb>\r\nNT: upnp:event\r\nTIMEOUT: Second-300\r\nContent-Length: 0\r\n\r\n").toByteArray(),
        )
        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
        line.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
    }

    @Test
    fun eventsNeedPairingAndAreCapped() {
        start(ProtectionMode.PAIRED)
        assertEquals(401, subscribe()) // unpaired devices don't hear what plays
        guard.setMode(ProtectionMode.ANYONE)
        val codes = (1..17).map { subscribe() }
        assertEquals(List(16) { 200 }, codes.take(16))
        assertEquals(503, codes.last())
    }

    @Test
    fun otherDlnaAppsGetAClearErrorWhenOnlyPairedPhonesMayCast() {
        start(ProtectionMode.PAIRED)
        val message = soapError { Dlna.call(avt, Dlna.AV_TRANSPORT, "SetAVTransportURI", setUri, headers = mapOf("User-Agent" to "BubbleUPnP/3.4")) }
        assertTrue(message, message.startsWith("SetAVTransportURI failed: Pairing required"))
        assertTrue(soapError { Dlna.call(rc, Dlna.RENDERING_CONTROL, "SetVolume", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "50")) }.contains("Pairing required"))
        // Discovery and capability queries still work, so the TV shows up in their lists.
        assertNotNull(Dlna.call(rc, Dlna.RENDERING_CONTROL, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")))
        assertTrue(tv.calls.isEmpty())
        assertEquals(401, post("/castapp/mirror", """{"description":"http://127.0.0.1:1/description.xml"}""").first)
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun pairEndpointFollowsTheProtocol() {
        start(ProtectionMode.ANYONE)
        val open = post("/castapp/pair", """{"name":"Pixel"}""")
        assertEquals(200, open.first)
        assertEquals(false, JSONObject(open.second).getBoolean("paired"))

        guard.setMode(ProtectionMode.PAIRED)
        val needs = post("/castapp/pair", """{"name":"Pixel"}""")
        assertEquals(401, needs.first)
        assertEquals("code_required", JSONObject(needs.second).getString("error"))

        val wrong = post("/castapp/pair", """{"name":"Pixel","code":"${if (guard.code == "0000") "1111" else "0000"}"}""")
        assertEquals(403, wrong.first)
        assertEquals("wrong_code", JSONObject(wrong.second).getString("error"))

        val ok = post("/castapp/pair", """{"name":"Pixel","code":"${guard.code}"}""")
        assertEquals(200, ok.first)
        val token = JSONObject(ok.second).getString("token")

        // With the token: known, and CastApp's commands pass.
        val known = post("/castapp/pair", """{"name":"Pixel"}""", mapOf(ReceiverGuard.TOKEN_HEADER to token))
        assertEquals(true, JSONObject(known.second).getBoolean("paired"))
        assertFalse(JSONObject(known.second).has("token"))
        val mirror = post(
            "/castapp/mirror", """{"description":"http://127.0.0.1:49595/description.xml"}""",
            mapOf(ReceiverGuard.TOKEN_HEADER to token),
        )
        assertEquals(200, mirror.first)
        assertEquals(200, post("/castapp/stop-mirror", "{}", mapOf(ReceiverGuard.TOKEN_HEADER to token)).first)
        assertEquals(401, post("/castapp/stop-mirror", "{}").first)
        assertEquals(listOf("mirror", "stop"), commands.calls)
        Dlna.call(avt, Dlna.AV_TRANSPORT, "SetAVTransportURI", setUri, headers = mapOf(ReceiverGuard.TOKEN_HEADER to token))
        assertEquals(listOf("set"), tv.calls)
    }

    @Test
    fun castAppPhonePairsOnceAndThenCastsWithItsToken() = runBlocking {
        start(ProtectionMode.PAIRED)
        val tokens = MemoryTokens()
        val asked = ArrayList<String>()
        val client = PairingClient(tokens, askCode = { name -> asked += name; guard.code }, clientName = { "Pixel 8" })

        val first = DlnaRenderer(device(), client)
        first.connect()
        assertEquals(listOf("Living Room Fire TV"), asked)
        assertEquals(1, tokens.map.size)
        first.load(media)
        assertEquals(listOf("stop", "set", "play"), tv.calls.filter { it != "vol" })
        first.close()

        // The next time the stored token is enough: no code asked.
        val second = DlnaRenderer(device(), client)
        second.connect()
        second.load(media)
        assertEquals(1, asked.size)
        second.close()

        // The receiver forgets the phone: connecting asks for the (new) code again.
        guard.forgetAll()
        val third = DlnaRenderer(device(), client)
        third.connect()
        assertEquals(2, asked.size)
        third.close()
    }

    @Test
    fun aTvThatForgotThePhoneBetweenConnectAndCastPairsAgain() = runBlocking {
        start(ProtectionMode.PAIRED)
        val tokens = MemoryTokens()
        var asked = 0
        val client = PairingClient(tokens, askCode = { asked++; guard.code }, clientName = { "Pixel 8" })
        val r = DlnaRenderer(device(), client)
        r.connect()
        guard.forgetAll() // while connected
        r.load(media)
        assertEquals(2, asked)
        assertTrue(tv.calls.contains("set"))
        r.close()
    }

    @Test
    fun cancellingOrFailingTheCodeStopsTheCast() = runBlocking {
        start(ProtectionMode.PAIRED)
        val cancelled = DlnaRenderer(device(), PairingClient(MemoryTokens(), askCode = { null }, clientName = { "Pixel" }))
        try {
            cancelled.connect()
            fail("expected a pairing error")
        } catch (e: CastException) {
            assertTrue(e.message!!, e.message!!.contains("only accepts paired phones"))
        }
        cancelled.close()

        val wrong = if (guard.code == "0000") "1111" else "0000"
        var tries = 0
        val guessing = DlnaRenderer(device(), PairingClient(MemoryTokens(), askCode = { tries++; wrong }, clientName = { "Pixel" }))
        try {
            guessing.connect()
            fail("expected a pairing error")
        } catch (e: CastException) {
            assertTrue(e.message!!, e.message!!.startsWith("Wrong code"))
        }
        assertEquals(3, tries)
        guessing.close()
    }

    @Test
    fun askFirstDoesNotNeedACodeAndSendsItsName() = runBlocking {
        start(ProtectionMode.ASK)
        val tokens = MemoryTokens()
        var asked = 0
        val client = PairingClient(tokens, askCode = { asked++; null }, clientName = { "Pixel 8" })
        assertEquals("Pixel 8", client.headersFor(device())[ReceiverGuard.NAME_HEADER])
        val r = DlnaRenderer(device(), client)
        r.connect()
        assertEquals(0, asked)
        assertTrue(tokens.map.isEmpty())
        val owner = answerFirstPrompt(Decision.ALLOW_ONCE)
        r.load(media)
        owner.join()
        assertTrue(tv.calls.contains("set"))
        r.close()
    }

    @Test
    fun pairingClientToleratesOldTvsAndUnreachableOnes() = runBlocking {
        val tokens = MemoryTokens()
        val dev = CastDevice("x", "TV", "127.0.0.1")
        val old = PairingClient(tokens, { null }, { "P" }, post = { _, _, _ -> 404 to "" })
        assertNull(old.ensure(dev, "http://127.0.0.1:1"))
        val down = PairingClient(tokens, { null }, { "P" }, post = { _, _, _ -> -1 to "" })
        assertNull(down.ensure(dev, "http://127.0.0.1:1"))
        val locked = PairingClient(tokens, { "1234" }, { "P" }, post = { _, _, _ -> 429 to """{"error":"locked","retryInSeconds":42}""" })
        try {
            locked.ensure(dev, "http://127.0.0.1:1")
            fail()
        } catch (e: PairingClient.PairingException) {
            assertTrue(e.message!!.contains("42"))
        }
    }

    @Test
    fun tokenHeadersAreAsciiAndShort() {
        val tokens = MemoryTokens().also { it.put("x", "abc") }
        val client = PairingClient(tokens, { null }, { "Pixel 8 é\n" + "x".repeat(100) })
        val headers = client.headersFor(CastDevice("x", "TV", "h"))
        assertEquals("abc", headers[ReceiverGuard.TOKEN_HEADER])
        val name = headers.getValue(ReceiverGuard.NAME_HEADER)
        assertTrue(name.length <= 40 && name.all { it.code in 32..126 })
    }
}
