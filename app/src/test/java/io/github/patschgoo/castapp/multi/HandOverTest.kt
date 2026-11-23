package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.service.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-over state machines: offer, accept, decline, time out, done. */
class HandOverTest {
    private val tv = CastDevice("tv-1", "Living Room", "192.168.1.20", 8009)
    private val speaker = CastDevice("dlna-2", "Kitchen", "192.168.1.21", 1400, kind = DeviceKind.DLNA, avTransportUrl = "http://192.168.1.21:1400/avt")

    private fun offer(id: String = "o1", base: String = "http://192.168.1.10:8765", mode: HandOverMode = HandOverMode.TAKE_OVER) = HandOverOffer(
        id = id, fromId = "a", fromName = "Pixel A", fromBase = base, screens = listOf(tv, speaker),
        kind = HandOverOffer.KIND_FILE, mode = mode,
        items = listOf(MediaItem("Film", "https://example.com/film.mp4", "video/mp4")), index = 0, positionMs = 61_000, title = "Film",
    )

    @Test
    fun offerAcceptedThenDone() {
        var now = 0L
        val a = OutgoingHandOver({ now })
        assertTrue(a.offer(offer(), "192.168.1.11"))
        assertFalse("one at a time", a.offer(offer("o2"), "192.168.1.12"))
        now = 5_000
        assertEquals(OutgoingHandOver.Answer.ACCEPTED, a.answer("o1", "192.168.1.11", accept = true))
        assertTrue(a.state.value is OutgoingHandOver.State.Accepted)
        assertTrue(a.busy)
        now = 30_000
        assertNotNull(a.done("o1", "192.168.1.11", ok = true))
        assertTrue(a.state.value is OutgoingHandOver.State.Completed)
        assertFalse(a.busy)
        a.reset()
        assertEquals(OutgoingHandOver.State.Idle, a.state.value)
    }

    @Test
    fun answersCountOnlyFromTheDeviceAsked() {
        val a = OutgoingHandOver({ 0L })
        a.offer(offer(), "192.168.1.11")
        assertEquals(OutgoingHandOver.Answer.UNKNOWN, a.answer("o1", "192.168.1.66", accept = true))
        assertEquals(OutgoingHandOver.Answer.UNKNOWN, a.answer("other", "192.168.1.11", accept = true))
        assertNull("not accepted yet", a.done("o1", "192.168.1.11", ok = true))
        assertEquals(OutgoingHandOver.Answer.ACCEPTED, a.answer("o1", "192.168.1.11", accept = true))
        assertNull(a.done("o1", "192.168.1.66", ok = true))
    }

    @Test
    fun declined() {
        val a = OutgoingHandOver({ 0L })
        a.offer(offer(), "192.168.1.11")
        assertEquals(OutgoingHandOver.Answer.DECLINED, a.answer("o1", "192.168.1.11", accept = false))
        assertTrue(a.state.value is OutgoingHandOver.State.Declined)
        assertTrue("a new offer may follow", a.offer(offer("o2"), "192.168.1.11"))
    }

    @Test
    fun noAnswerTimesOut() {
        var now = 0L
        val a = OutgoingHandOver({ now }, answerTimeoutMs = 60_000, completeTimeoutMs = 90_000)
        a.offer(offer(), "192.168.1.11")
        now = 59_000
        assertTrue(a.tick() is OutgoingHandOver.State.Offered)
        now = 61_000
        assertTrue(a.tick() is OutgoingHandOver.State.TimedOut)
        assertEquals("too late", OutgoingHandOver.Answer.UNKNOWN, a.answer("o1", "192.168.1.11", accept = true))
    }

    @Test
    fun acceptedButNeverStartedTimesOut() {
        var now = 0L
        val a = OutgoingHandOver({ now }, answerTimeoutMs = 60_000, completeTimeoutMs = 90_000)
        a.offer(offer(), "192.168.1.11")
        now = 10_000
        a.answer("o1", "192.168.1.11", accept = true)
        now = 99_000
        assertTrue(a.tick() is OutgoingHandOver.State.Accepted)
        now = 101_000
        assertTrue(a.tick() is OutgoingHandOver.State.TimedOut)
    }

    @Test
    fun otherSideCouldNotStart() {
        val a = OutgoingHandOver({ 0L })
        a.offer(offer(), "192.168.1.11")
        a.answer("o1", "192.168.1.11", accept = true)
        assertNotNull(a.done("o1", "192.168.1.11", ok = false))
        assertTrue(a.state.value is OutgoingHandOver.State.Failed)
    }

    @Test
    fun incomingOfferPromptsAcceptOrDecline() {
        var now = 0L
        val b = IncomingHandOver({ now }, lifetimeMs = 60_000)
        assertEquals(IncomingHandOver.Receipt.PROMPT, b.receive(offer(), "192.168.1.10"))
        assertEquals(IncomingHandOver.Receipt.BUSY, b.receive(offer("o2"), "192.168.1.10"))
        assertNull("wrong id", b.accept("o2"))
        assertNotNull(b.accept("o1"))
        assertNull(b.pending.value)

        b.receive(offer("o3"), "192.168.1.10")
        assertNotNull(b.decline("o3"))
        assertNull(b.accept("o3"))
    }

    @Test
    fun incomingOfferRunsOut() {
        var now = 0L
        val b = IncomingHandOver({ now }, lifetimeMs = 60_000)
        b.receive(offer(), "192.168.1.10")
        now = 61_000
        assertNull(b.accept("o1"))
        assertNull(b.pending.value)
    }

    @Test
    fun anOfferMustComeFromTheDeviceItNames() {
        val b = IncomingHandOver({ 0L })
        // Someone else sends an offer in A's name: B's answers would go to A, so it is refused.
        assertEquals(IncomingHandOver.Receipt.INVALID, b.receive(offer(), "192.168.1.66"))
        assertNull(b.pending.value)
    }

    @Test
    fun askedForItNoSecondQuestion() {
        var now = 0L
        val b = IncomingHandOver({ now }, requestLifetimeMs = 120_000)
        b.expectOfferFrom("192.168.1.10")
        assertTrue(b.hasRequestTo("192.168.1.10"))
        assertEquals(IncomingHandOver.Receipt.AUTO_ACCEPT, b.receive(offer(), "192.168.1.10"))
        // Only once.
        assertEquals(IncomingHandOver.Receipt.PROMPT, b.receive(offer(), "192.168.1.10"))
        // And not forever.
        b.decline("o1")
        b.expectOfferFrom("192.168.1.10")
        now = 121_000
        assertEquals(IncomingHandOver.Receipt.PROMPT, b.receive(offer("o9"), "192.168.1.10"))
    }

    @Test
    fun castRequestsOnePerDevice() {
        var now = 0L
        val r = CastRequests({ now }, lifetimeMs = 60_000)
        assertNull("base must be the sender", r.add("Tab", "http://192.168.1.30:8765", "192.168.1.31"))
        val first = r.add("Tab", "http://192.168.1.30:8765", "192.168.1.30")!!
        assertEquals(first, r.add("Tab", "http://192.168.1.30:8765", "192.168.1.30"))
        assertNull("someone else is already asking", r.add("Phone", "http://192.168.1.40:8765", "192.168.1.40"))
        assertEquals(first, r.take(first.id))
        r.add("Phone", "http://192.168.1.40:8765", "192.168.1.40")
        now = 61_000
        r.prune()
        assertNull(r.pending.value)
    }

    @Test
    fun whatCanBeTakenOver() {
        val web = MediaItem("Film", "https://example.com/film.mp4", "video/mp4")
        val nas = MediaItem("NAS", "http://192.168.1.5:8200/MediaItems/12.mkv", "video/x-matroska")
        val channel = MediaItem("News", "http://iptv.example/live/1.m3u8", "application/x-mpegurl", live = true)
        val phoneFile = MediaItem("Holiday", "content://media/external/video/media/42", "video/mp4")
        val share = MediaItem("Share", "smb://src1/Movies/film.mkv", "video/x-matroska")
        assertEquals(HandOverMode.TAKE_OVER, HandOverOffer.modeFor(HandOverOffer.KIND_SCREEN, emptyList()))
        assertEquals(HandOverMode.TAKE_OVER, HandOverOffer.modeFor(HandOverOffer.KIND_FILE, listOf(web, nas, channel)))
        assertEquals(HandOverMode.CONTROL, HandOverOffer.modeFor(HandOverOffer.KIND_FILE, listOf(web, phoneFile)))
        assertEquals(HandOverMode.CONTROL, HandOverOffer.modeFor(HandOverOffer.KIND_FILE, listOf(share)))
        assertEquals(HandOverMode.CONTROL, HandOverOffer.modeFor(HandOverOffer.KIND_FILE, listOf(web.copy(trustAll = true))))
        // Subtitles on the phone stay behind, web subtitles travel along.
        val withSubs = HandOverOffer.portable(listOf(web.copy(subtitleUri = "content://x/1.srt"), nas.copy(subtitleUri = "http://192.168.1.5/1.srt")))
        assertNull(withSubs[0].subtitleUri)
        assertEquals("http://192.168.1.5/1.srt", withSubs[1].subtitleUri)
        // Of several subtitle files only those the other device can read go along, the first of them shown.
        val many = HandOverOffer.portable(
            listOf(
                share.copy(subtitleUri = "smb://src1/Movies/film.English.srt", moreSubtitles = listOf("smb://src1/Movies/film.German.srt")),
                nas.copy(subtitleUri = "content://x/1.srt", moreSubtitles = listOf("http://192.168.1.5/1.de.srt", "content://x/1.fr.srt", "https://nas.local/1.es.ass")),
                nas.copy(subtitleUri = "http://192.168.1.5/2.srt", moreSubtitles = listOf("http://192.168.1.5/2.de.srt")),
            ),
        )
        assertNull(many[0].subtitleUri)
        assertEquals(emptyList<String>(), many[0].moreSubtitles)
        assertEquals("http://192.168.1.5/1.de.srt", many[1].subtitleUri)
        assertEquals(listOf("https://nas.local/1.es.ass"), many[1].moreSubtitles)
        assertEquals(listOf("http://192.168.1.5/2.srt", "http://192.168.1.5/2.de.srt"), many[2].subtitleFiles)
        // And they travel in the offer.
        val offered = offer().copy(items = many)
        assertEquals(many, HandOverOffer.fromJson(offered.toJson())!!.items)
    }

    /** A WebDAV folder of [n] episodes with a subtitle file in each of [languages]. */
    private fun episodes(n: Int, languages: Int) = (1..n).map { e ->
        val subs = (1..languages).map { l -> "https://dav.example/Show/Tern $e.Lang$l.srt" }
        MediaItem("Tern $e.mkv", "https://dav.example/Show/Tern $e.mkv", "video/x-matroska", subtitleUri = subs.first(), moreSubtitles = subs.drop(1))
    }

    @Test
    fun otherLanguagesTravelOnlyAroundTheItemPlaying() {
        val queue = episodes(10, 29)
        val portable = HandOverOffer.portable(queue, index = 5)
        for ((i, item) in portable.withIndex()) {
            assertEquals("the one shown always goes", queue[i].subtitleUri, item.subtitleUri)
            assertEquals("item $i", if (i in 3..7) 28 else 0, item.moreSubtitles.size)
        }
        // At the start of the queue: the first ones.
        assertEquals(listOf(28, 28, 28, 0), HandOverOffer.portable(queue, index = 0).take(4).map { it.moreSubtitles.size })
    }

    @Test
    fun aBigOfferIsTrimmedToFitThePeerServer() {
        val queue = episodes(600, 6)
        val base = offer().copy(items = queue.map { it.copy(headers = mapOf("Authorization" to "Basic dTpw")) }, index = 300)
        val full = base.copy(items = HandOverOffer.portable(base.items, base.index))
        assertTrue(full.sizeBytes() <= HandOverOffer.MAX_OFFER_BYTES)
        assertEquals(full, full.fitted())
        // Everything kept (an older offer builder): too big, so only the item playing keeps all its files.
        assertTrue(base.sizeBytes() > HandOverOffer.MAX_OFFER_BYTES)
        val fitted = base.fitted()
        assertTrue(fitted.sizeBytes() <= HandOverOffer.MAX_OFFER_BYTES)
        assertEquals(600, fitted.items.size)
        assertEquals(5, fitted.items[300].moreSubtitles.size)
        assertEquals(0, fitted.items[299].moreSubtitles.size)
        assertEquals(queue.map { it.subtitleUri }, fitted.items.map { it.subtitleUri })
        assertTrue(HandOverOffer.MAX_OFFER_BYTES < PeerServer.MAX_BODY)
    }

    @Test
    fun picturesTravelAndAreDroppedLastWhenTheOfferIsTooBig() {
        val poster = "https://plex.example:32400/photo/:/transcode?width=480&height=270&url=%2Flibrary%2Fmetadata%2F201%2Fthumb&X-Plex-Token=srvtok"
        val withArt = offer().copy(items = listOf(MediaItem("Film", "https://example.com/film.mp4", "video/mp4", artUri = poster)))
        assertEquals(poster, HandOverOffer.fromJson(withArt.toJson())!!.items.single().artUri)

        // 1000 episodes with long poster addresses: too big for the peer server because of the pictures.
        val queue = (1..1000).map { e ->
            MediaItem("Tern $e.mkv", "https://dav.example/Show/Tern $e.mkv", "video/x-matroska", artUri = "$poster&episode=$e")
        }
        val base = offer().copy(items = queue, index = 500)
        assertTrue(base.sizeBytes() > HandOverOffer.MAX_OFFER_BYTES)
        val fitted = base.fitted()
        assertTrue(fitted.sizeBytes() <= HandOverOffer.MAX_OFFER_BYTES)
        assertEquals(1000, fitted.items.size)
        assertEquals("$poster&episode=501", fitted.items[500].artUri) // the one playing keeps its picture
        assertNull(fitted.items[499].artUri)
        assertEquals(queue.map { it.uri }, fitted.items.map { it.uri })
    }

    @Test
    fun offerTravelsAsJson() {
        val o = offer()
        val back = HandOverOffer.fromJson(o.toJson())!!
        assertEquals(o, back)
        assertEquals(2, back.screens.size)
        assertEquals(DeviceKind.DLNA, back.screens[1].kind)
        // A control offer needs no play list; a take-over of files does.
        assertNotNull(HandOverOffer.fromJson(o.copy(mode = HandOverMode.CONTROL, items = emptyList()).toJson()))
        assertNull(HandOverOffer.fromJson(o.copy(items = emptyList()).toJson()))
        assertNull(HandOverOffer.fromJson(o.copy(screens = emptyList()).toJson()))
    }
}
