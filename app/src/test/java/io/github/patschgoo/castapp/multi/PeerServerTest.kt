package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.tv.Decision
import io.github.patschgoo.castapp.tv.MemoryGuardStore
import io.github.patschgoo.castapp.tv.ProtectionMode
import io.github.patschgoo.castapp.tv.ReceiverGuard
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** The peer endpoints CastApp devices use with each other, over real HTTP. */
class PeerServerTest {
    private class FakeCast : LocalCast {
        @Volatile var casting = true
        @Volatile var timeline: Timeline? = null
        val commands = CopyOnWriteArrayList<String>()
        override fun info(): CastInfo? =
            if (casting) CastInfo(HandOverOffer.KIND_FILE, "Film", listOf("Living Room", "Kitchen"), positionMs = 61_000, durationMs = 5_400_000) else null
        override fun joinMedia(remote: InetAddress?): JoinMedia? =
            if (casting) JoinMedia("http://127.0.0.1:9/tok/file/1/film.mp4", "video/mp4", "Film", lowLatency = false, live = false) else null
        override fun timeline(): Timeline? = timeline
        override fun control(action: String, positionMs: Long): Boolean {
            commands += "$action:$positionMs"
            return casting
        }
    }

    private class Events : PeerEvents {
        val log = CopyOnWriteArrayList<String>()
        override fun offerReceived(pending: IncomingHandOver.Pending, auto: Boolean) { log += "offer:${pending.offer.id}:$auto" }
        override fun offerAnswered(offer: HandOverOffer, accepted: Boolean) { log += "answered:${offer.id}:$accepted" }
        override fun handOverDone(offer: HandOverOffer, ok: Boolean) { log += "done:${offer.id}:$ok" }
        override fun castRequested(request: CastRequests.Request) { log += "request:${request.name}" }
        override fun requestDeclined(fromName: String) { log += "declined:$fromName" }
    }

    /** One CastApp device: its cast, its hub and its peer server; the host clock runs [skew] ms ahead. */
    private inner class Device(name: String, skew: Long = 0) {
        val cast = FakeCast()
        val events = Events()
        val guard = ReceiverGuard(MemoryGuardStore(), ProtectionMode.ASK, askTimeoutMs = 300, promptLifetimeMs = 30_000)
        val clock = { System.nanoTime() / 1_000_000 + skew }
        val hub = PeerHub({ SelfInfo("id-$name", name, "phone") }, cast, events, guard, clock)
        val server = PeerServer(hub::handle, clock).also { it.start(0); servers += it }
        val base get() = "http://127.0.0.1:${server.port}"
    }

    private val servers = ArrayList<PeerServer>()

    @After
    fun tearDown() = servers.forEach { it.stop() }

    @Test
    fun sessionSaysWhatIsCast() {
        val a = Device("Pixel A")
        val s = PeerClient.session(a.base)!!
        assertEquals("id-Pixel A", s.getString("id"))
        assertEquals("Pixel A", s.getString("name"))
        assertTrue(s.getBoolean("casting"))
        assertEquals("Film", s.getString("title"))
        assertTrue(s.getBoolean("joinable"))
        assertEquals("ask", s.getString("policy"))
        val peer = PeerInfo.fromSession(a.base, s, 0)!!
        assertEquals(listOf("Living Room", "Kitchen"), peer.screens)
        assertTrue(peer.isHandheld)
        assertEquals("127.0.0.1", peer.host)

        a.cast.casting = false
        val idle = PeerClient.session(a.base)!!
        assertFalse(idle.getBoolean("casting"))
        assertFalse(idle.has("title"))
    }

    @Test
    fun clockOffsetOverHttp() {
        // The host's clock is 7 s ahead of ours.
        val a = Device("Host", skew = 7_000)
        val samples = PeerClient.clockSamples(a.base, 6) { System.nanoTime() / 1_000_000 }
        assertEquals(6, samples.size)
        val e = ClockSync.estimate(samples)!!
        assertTrue("offset ${e.offsetMs}", abs(e.offsetMs - 7_000) <= 20)
        assertTrue(e.roundTripMs < 200)
    }

    @Test
    fun joiningAsksTheHostFirst() {
        val a = Device("Pixel A")
        // Nobody answers the prompt in time: the viewer is told to wait and asks again.
        assertEquals(PeerClient.JoinResult.Waiting, PeerClient.join(a.base, "Tablet B"))
        val prompt = a.guard.pending.value.single()
        assertEquals("Tablet B", prompt.name)
        a.guard.answer(prompt.id, Decision.ALLOW_ONCE)
        val joined = PeerClient.join(a.base, "Tablet B") as PeerClient.JoinResult.Joined
        assertEquals("http://127.0.0.1:9/tok/file/1/film.mp4", joined.answer.getString("url"))
        assertEquals("/castapp/timeline", joined.answer.getString("timeline"))
        val viewer = joined.answer.getString("viewer")
        assertEquals(1, a.hub.watchers.value)

        // The viewer follows the timeline, which also tells the host it is still watching.
        a.cast.timeline = Timeline("1", 61_000, a.clock(), playing = true, live = false, generation = 1)
        val (casting, t) = PeerClient.timelineAt("${a.base}/castapp/timeline?viewer=$viewer")!!
        assertTrue(casting)
        assertEquals(61_000, t!!.anchorPositionMs)
        assertEquals(1, PeerClient.session(a.base)!!.getInt("watchers"))

        assertEquals(200, PeerClient.post(a.base, "/castapp/leave", JSONObject().put("viewer", viewer)).first)
        assertEquals(0, a.hub.watchers.value)
    }

    @Test
    fun blockedViewersStayOut() {
        val a = Device("Pixel A")
        PeerClient.join(a.base, "Stranger")
        a.guard.answer(a.guard.pending.value.single().id, Decision.BLOCK)
        val r = PeerClient.join(a.base, "Stranger") as PeerClient.JoinResult.Denied
        assertEquals("blocked", r.error)
    }

    @Test
    fun joinPolicies() {
        val a = Device("Pixel A")
        a.hub.policy = JoinPolicy.OFF
        assertEquals("off", (PeerClient.join(a.base, "B") as PeerClient.JoinResult.Denied).error)
        assertFalse(PeerClient.session(a.base)!!.getBoolean("joinable"))
        a.hub.policy = JoinPolicy.ALLOW
        assertTrue("no question", PeerClient.join(a.base, "B") is PeerClient.JoinResult.Joined)
        assertTrue(a.guard.pending.value.isEmpty())
        a.cast.casting = false
        assertEquals("not_casting", (PeerClient.join(a.base, "B") as PeerClient.JoinResult.Denied).error)
    }

    private val tv = CastDevice("tv-1", "Living Room", "192.168.1.20")

    private fun offerFrom(a: Device, mode: HandOverMode = HandOverMode.TAKE_OVER, id: String = "offer-1") = HandOverOffer(
        id = id, fromId = "id-A", fromName = "Pixel A", fromBase = a.base, screens = listOf(tv),
        kind = HandOverOffer.KIND_FILE, mode = mode,
        items = if (mode == HandOverMode.TAKE_OVER) listOf(MediaItem("Film", "https://example.com/film.mp4", "video/mp4")) else emptyList(),
        positionMs = 61_000, title = "Film",
    )

    @Test
    fun handOverOfferAcceptDone() {
        val a = Device("Pixel A")
        val b = Device("Tablet B")
        val offer = offerFrom(a)
        assertTrue(a.hub.outgoing.offer(offer, "127.0.0.1"))
        assertEquals(202, PeerClient.post(b.base, "/castapp/handover/offer", offer.toJson()).first)
        assertEquals("offer:offer-1:false", b.events.log.single())
        val pending = b.hub.incoming.accept("offer-1")!!
        assertEquals(61_000, pending.offer.positionMs)

        // B says yes to A, starts casting, then tells A it's done: A stops.
        assertEquals(200, PeerClient.post(a.base, "/castapp/handover/answer", JSONObject().put("id", "offer-1").put("accept", true)).first)
        assertEquals("answered:offer-1:true", a.events.log.last())
        assertTrue(a.hub.outgoing.state.value is OutgoingHandOver.State.Accepted)
        assertEquals(200, PeerClient.post(a.base, "/castapp/handover/done", JSONObject().put("id", "offer-1").put("ok", true)).first)
        assertEquals("done:offer-1:true", a.events.log.last())
        assertTrue(a.hub.outgoing.state.value is OutgoingHandOver.State.Completed)
        // A second "done" is not about anything.
        assertEquals(404, PeerClient.post(a.base, "/castapp/handover/done", JSONObject().put("id", "offer-1")).first)
    }

    @Test
    fun handOverDeclined() {
        val a = Device("Pixel A")
        val b = Device("Tablet B")
        val offer = offerFrom(a)
        a.hub.outgoing.offer(offer, "127.0.0.1")
        PeerClient.post(b.base, "/castapp/handover/offer", offer.toJson())
        b.hub.incoming.decline("offer-1")
        assertEquals(200, PeerClient.post(a.base, "/castapp/handover/answer", JSONObject().put("id", "offer-1").put("accept", false)).first)
        assertEquals("answered:offer-1:false", a.events.log.last())
        assertTrue(a.hub.outgoing.state.value is OutgoingHandOver.State.Declined)
    }

    @Test
    fun offersInSomeoneElsesNameAreRefused() {
        val b = Device("Tablet B")
        val forged = HandOverOffer(
            id = "x", fromId = "id-A", fromName = "Pixel A", fromBase = "http://192.168.1.10:8765", screens = listOf(tv),
            kind = HandOverOffer.KIND_SCREEN,
        )
        assertEquals(403, PeerClient.post(b.base, "/castapp/handover/offer", forged.toJson()).first)
        assertNull(b.hub.incoming.pending.value)
        assertEquals(400, PeerClient.post(b.base, "/castapp/handover/offer", JSONObject().put("id", "y")).first)
        // An answer to an offer nobody made.
        assertEquals(404, PeerClient.post(b.base, "/castapp/handover/answer", JSONObject().put("id", "x").put("accept", true)).first)
    }

    @Test
    fun controlHandOverGivesATokenForTheControls() {
        val a = Device("Pixel A")
        val offer = offerFrom(a, HandOverMode.CONTROL)
        a.hub.outgoing.offer(offer, "127.0.0.1")
        val (code, text) = PeerClient.post(a.base, "/castapp/handover/answer", JSONObject().put("id", "offer-1").put("accept", true))
        assertEquals(200, code)
        val token = JSONObject(text).getString("token")
        assertTrue(token.length >= 32)
        // Nothing to wait for: A keeps casting.
        assertTrue(a.hub.outgoing.state.value is OutgoingHandOver.State.Completed)

        assertEquals(401, PeerClient.post(a.base, "/castapp/control", JSONObject().put("action", "pause")).first)
        assertEquals(401, PeerClient.post(a.base, "/castapp/control", JSONObject().put("token", "nope").put("action", "pause")).first)
        assertEquals(200, PeerClient.post(a.base, "/castapp/control", JSONObject().put("token", token).put("action", "seek").put("positionMs", 90_000)).first)
        assertEquals(200, PeerClient.post(a.base, "/castapp/control", JSONObject().put("token", token).put("action", "next")).first)
        assertEquals(listOf("seek:90000", "next:0"), a.cast.commands.toList())
        assertEquals(400, PeerClient.post(a.base, "/castapp/control", JSONObject().put("token", token).put("action", "format-disk")).first)
        // Given back: the token is dead.
        assertEquals(200, PeerClient.post(a.base, "/castapp/control", JSONObject().put("token", token).put("action", "release")).first)
        assertEquals(401, PeerClient.post(a.base, "/castapp/control", JSONObject().put("token", token).put("action", "pause")).first)
    }

    @Test
    fun askToCastAndTheAnswer() {
        val a = Device("Pixel A")
        val b = Device("Tablet B")
        // B asks A; A's owner gets "Tablet B wants to cast".
        b.hub.incoming.expectOfferFrom("127.0.0.1")
        val ask = JSONObject().put("name", "Tablet B").put("base", b.base)
        assertEquals(202, PeerClient.post(a.base, "/castapp/handover/request", ask).first)
        assertEquals("request:Tablet B", a.events.log.single())
        assertNotNull(a.hub.requests.pending.value)

        // A says yes: its offer to B needs no second yes on B.
        val offer = offerFrom(a, id = "offer-2")
        a.hub.outgoing.offer(offer, "127.0.0.1")
        assertEquals(202, PeerClient.post(b.base, "/castapp/handover/offer", offer.toJson()).first)
        assertEquals("offer:offer-2:true", b.events.log.single())

        // Another time A says no.
        b.hub.incoming.expectOfferFrom("127.0.0.1")
        assertEquals(200, PeerClient.post(b.base, "/castapp/handover/declined", JSONObject().put("name", "Pixel A")).first)
        assertEquals("declined:Pixel A", b.events.log.last())
        // Without having asked, a "no" means nothing.
        assertEquals(404, PeerClient.post(b.base, "/castapp/handover/declined", JSONObject().put("name", "Pixel A")).first)
    }

    @Test
    fun nothingToHandOverWhenNotCasting() {
        val a = Device("Pixel A")
        a.cast.casting = false
        val ask = JSONObject().put("name", "Tablet B").put("base", "http://127.0.0.1:1")
        assertEquals(409, PeerClient.post(a.base, "/castapp/handover/request", ask).first)
    }

    @Test
    fun unknownPathsAndMethods() {
        val a = Device("Pixel A")
        assertEquals(404, PeerClient.post(a.base, "/castapp/explode", JSONObject()).first)
        assertNull(PeerClient.session("http://127.0.0.1:1"))
    }

    @Test
    fun followerKeepsUpWithTheHostOverHttp() {
        val a = Device("Host", skew = 3_000)
        val start = a.clock()
        a.cast.timeline = Timeline("1", 10_000, start, playing = true, live = false, generation = 1)
        val local = { System.nanoTime() / 1_000_000 }
        val f = SyncFollower("${a.base}/castapp/timeline", local)
        f.refreshNow(measureClock = true)
        assertNotNull(f.estimate)
        assertTrue("offset ${f.estimate!!.offsetMs}", abs(f.estimate!!.offsetMs - 3_000) <= 20)

        // Where the host's timeline is right now, on our clock.
        val target = 10_000 + (f.estimate!!.toHost(local()) - start)
        // Far behind: jump there.
        val jump = f.correction(target - 5_000, localPlaying = true, currentRate = 1f)!!
        assertTrue("jumps to about $target: ${jump.seekToMs}", abs(jump.seekToMs!! - target) < 100)
        // Right after a jump: no second one (still buffering from the first).
        assertNull(f.correction(target - 5_000, localPlaying = true, currentRate = 1f)!!.seekToMs)
        // A bit ahead: slow down.
        val ahead = f.correction(target + 300, localPlaying = true, currentRate = 1f)!!
        assertTrue(ahead.rate < 1f)

        // The host moves on to the next item, then stops casting.
        val changes = CopyOnWriteArrayList<String>()
        f.onHostChanged = { item, stopped -> changes += "$item/$stopped" }
        a.cast.timeline = Timeline("2", 0, a.clock(), playing = true, live = false, generation = 2)
        f.refreshNow(measureClock = false)
        a.cast.casting = false
        a.cast.timeline = null
        f.refreshNow(measureClock = false)
        assertEquals(listOf("true/false", "false/true"), changes.toList())
        assertNull("nothing to follow any more", f.correction(0, true, 1f))
    }
}
