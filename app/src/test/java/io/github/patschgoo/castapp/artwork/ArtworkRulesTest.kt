package io.github.patschgoo.castapp.artwork

import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.stream.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** The pictures of "Continue watching": where they come from, cache names, limits and give-up rules. */
class ArtworkRulesTest {
    private val dav = listOf("https://cloud.example.com/remote.php/dav/files/anna/Videos")

    @Test
    fun aKnownPictureComesFirst() {
        val plex = MediaItem("Pilot", "http://192.168.1.5:32400/video/:/transcode/universal/start.m3u8?x=1", "application/x-mpegurl",
            artUri = "http://192.168.1.5:32400/photo/:/transcode?width=480&height=270&url=%2Fthumb&X-Plex-Token=t")
        assertEquals(ArtSource.Picture(plex.artUri!!), ArtworkRules.sourceFor(plex, dav))
        // Even for a file on a share: the poster beats a frame.
        val share = MediaItem("Film.mkv", "smb://src1/Movies/Film.mkv", "video/x-matroska", artUri = "https://img.example/film.jpg")
        assertEquals(ArtSource.Picture("https://img.example/film.jpg"), ArtworkRules.sourceFor(share, dav))
        // Inline data is no address.
        val inline = share.copy(artUri = "data:image/png;base64,iVBORw0KGgo=")
        assertTrue(ArtworkRules.sourceFor(inline, dav) is ArtSource.ShareFile)
    }

    @Test
    fun filesOnThePhoneAndOnSharesGiveTheirOwn() {
        val phone = MediaItem("Holiday.mp4", "content://media/external/video/media/42", "video/mp4")
        assertEquals(ArtSource.PhoneFile("content://media/external/video/media/42"), ArtworkRules.sourceFor(phone, dav))

        val smb = MediaItem("Film.mkv", "smb://src1/Movies/Film.mkv", "video/x-matroska")
        assertEquals(ArtSource.ShareFile("smb:src1", audio = false), ArtworkRules.sourceFor(smb, dav))
        val book = MediaItem("Book.m4b", "smb://src1/Books/Book.m4b", "audio/mp4")
        assertEquals(ArtSource.ShareFile("smb:src1", audio = true), ArtworkRules.sourceFor(book, dav))

        // WebDAV: the server of a saved folder, whatever the spelling of the path.
        val webDav = MediaItem("Tern 1.mkv", "https://Cloud.Example.com:443/remote.php/dav/files/anna/Videos/Tern%201.mkv", "video/x-matroska")
        assertEquals(ArtSource.ShareFile("dav:https://cloud.example.com:443", audio = false), ArtworkRules.sourceFor(webDav, dav))
    }

    @Test
    fun nothingForLiveTvPhotosStreamsAndOtherWebVideos() {
        assertNull(ArtworkRules.sourceFor(MediaItem("News", "http://iptv.example/live/1.ts", "video/mp2t", live = true), dav))
        assertNull(ArtworkRules.sourceFor(MediaItem("p.jpg", "content://media/external/images/media/3", "image/jpeg"), dav))
        assertNull(ArtworkRules.sourceFor(MediaItem("Clip", "https://news.example/clip.mp4", "video/mp4"), dav))
        // A stream on the WebDAV server's host: no frame from a play list.
        assertNull(ArtworkRules.sourceFor(MediaItem("Live", "https://cloud.example.com/live/index.m3u8", "application/x-mpegurl"), dav))
        assertNull(ArtworkRules.sourceFor(MediaItem("Dash", "https://cloud.example.com/v/manifest.mpd", "application/dash+xml"), dav))
        // A media server's stream on the same host is the server's business.
        assertNull(ArtworkRules.sourceFor(MediaItem("Film", "https://cloud.example.com/Videos/1/stream.mkv", "video/x-matroska", serverRef = "jf|1"), dav))
    }

    @Test
    fun cacheKeyIsStablePerItemAndPicture() {
        val a = ArtworkRules.cacheKey("smb://src1/Movies/Film.mkv", null)
        assertEquals(a, ArtworkRules.cacheKey("smb://src1/Movies/Film.mkv", null))
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' }) // a safe file name, nothing of the address in it
        assertNotEquals(a, ArtworkRules.cacheKey("smb://src1/Movies/Film2.mkv", null))
        // A new poster (new token, new address) is a new picture.
        assertNotEquals(ArtworkRules.cacheKey("u", "https://a/1.jpg"), ArtworkRules.cacheKey("u", "https://a/2.jpg"))
        assertNotEquals(ArtworkRules.cacheKey("u", null), ArtworkRules.cacheKey("u", "https://a/1.jpg"))
        // No mixing of the two parts.
        assertNotEquals(ArtworkRules.cacheKey("ab", "c"), ArtworkRules.cacheKey("a", "bc"))
    }

    @Test
    fun theFrameIsATenthIn() {
        assertEquals(600_000_000L, ArtworkRules.frameTimeUs(6_000_000)) // 100 min → at 10 min
        assertEquals(60_000_000L, ArtworkRules.frameTimeUs(0)) // unknown → 60 s
        assertEquals(60_000_000L, ArtworkRules.frameTimeUs(-1))
        assertEquals(5_000_000L, ArtworkRules.frameTimeUs(10_000)) // a short clip: the middle
    }

    @Test
    fun picturesAreShrunkNeverEnlarged() {
        assertEquals(320 to 180, ArtworkRules.fitInside(1920, 1080))
        assertEquals(213 to 320, ArtworkRules.fitInside(1000, 1500))
        assertEquals(200 to 100, ArtworkRules.fitInside(200, 100))
        assertEquals(320 to 1, ArtworkRules.fitInside(4000, 2))
        assertEquals(0 to 0, ArtworkRules.fitInside(0, 100))
    }

    @Test
    fun picturesAreShownByTheirShape() {
        assertEquals(ArtworkRules.Fit.FILL, ArtworkRules.fitFor(320, 180, hasAlpha = false)) // still, frame
        assertEquals(ArtworkRules.Fit.POSTER, ArtworkRules.fitFor(213, 320, hasAlpha = false)) // film poster
        assertEquals(ArtworkRules.Fit.LOGO, ArtworkRules.fitFor(300, 300, hasAlpha = false)) // album cover
        assertEquals(ArtworkRules.Fit.LOGO, ArtworkRules.fitFor(320, 120, hasAlpha = true)) // see-through channel logo
    }

    @Test
    fun theCacheKeepsTheMostRecentlyUsed() {
        val files = (1..10).map { ArtworkRules.CachedFile("f$it.img", bytes = 1000, lastUsed = it * 1000L) }
        assertEquals(emptyList<ArtworkRules.CachedFile>(), ArtworkRules.toPrune(files, maxFiles = 10, maxBytes = 10_000))
        assertEquals(listOf("f2.img", "f1.img"), ArtworkRules.toPrune(files, maxFiles = 8, maxBytes = 1_000_000).map { it.name })
        assertEquals(listOf("f3.img", "f2.img", "f1.img"), ArtworkRules.toPrune(files, maxFiles = 100, maxBytes = 7_500).map { it.name })
        // One huge file goes, the small ones stay.
        val big = files + ArtworkRules.CachedFile("huge.img", bytes = 50_000, lastUsed = 99_000)
        assertEquals(listOf("huge.img"), ArtworkRules.toPrune(big, maxFiles = 100, maxBytes = 20_000).map { it.name })
    }

    @Test
    fun noPictureIsTriedAgainAfterSomeDays() {
        val at = 1_000_000L
        assertTrue(ArtworkRules.noPictureStillHolds(at, at + 3600_000))
        assertFalse(ArtworkRules.noPictureStillHolds(at, at + ArtworkRules.NO_PICTURE_RETRY_MS))
        assertFalse(ArtworkRules.noPictureStillHolds(at, at - 1)) // the clock went back: try again
    }

    @Test
    fun aShareThatIsOffIsLeftAloneForAWhile() {
        var now = 0L
        val memory = FailureMemory(retryAfterMs = 600_000, clock = { now })
        assertTrue(memory.mayTry("smb:src1"))
        memory.failed("smb:src1")
        assertFalse(memory.mayTry("smb:src1"))
        assertTrue(memory.mayTry("smb:src2")) // other shares are asked as usual
        now = 599_999
        assertFalse(memory.mayTry("smb:src1"))
        now = 600_000
        assertTrue(memory.mayTry("smb:src1"))
        memory.failed("smb:src1")
        memory.succeeded("smb:src1")
        assertTrue(memory.mayTry("smb:src1"))
    }

    // ---- reading from a share within limits ------------------------------------------------------

    private class FakeSource(private val data: ByteArray, private val fail: Boolean = false) : MediaSource {
        var opens = 0
        override val length: Long get() = data.size.toLong()
        override fun open(offset: Long): InputStream {
            opens++
            if (fail) throw IOException("STATUS_LOGON_FAILURE")
            return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        }
    }

    private fun InputStream.drain(): Long {
        val buf = ByteArray(4096)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) return total
            total += n
        }
    }

    @Test
    fun readsUpToTheByteLimitThenStops() {
        val source = BudgetedSource(FakeSource(ByteArray(100_000)), ReadBudget(maxBytes = 10_000, maxMs = 60_000, maxOpens = 10))
        val input = source.open(0)
        try {
            input.drain()
            fail("read past the limit")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("budget"))
        }
    }

    @Test
    fun aSmallFileIsReadWhole() {
        val budget = ReadBudget(maxBytes = 10_000, maxMs = 60_000, maxOpens = 10)
        val source = BudgetedSource(FakeSource(ByteArray(5_000)), budget)
        assertEquals(5_000L, source.open(0).drain())
        assertEquals(1_000L, source.open(4_000).drain())
        assertEquals(6_000L, budget.bytesRead)
        assertFalse(budget.exceeded)
    }

    @Test
    fun tooManySeeksOrTooSlowGiveUp() {
        val fake = FakeSource(ByteArray(1_000))
        val seeks = BudgetedSource(fake, ReadBudget(maxBytes = 1_000_000, maxMs = 60_000, maxOpens = 3))
        repeat(3) { seeks.open(it * 10L).close() }
        try {
            seeks.open(40)
            fail("opened a fourth time")
        } catch (_: IOException) {
        }
        assertEquals(3, fake.opens) // the fourth never reached the share

        var now = 0L
        val budget = ReadBudget(maxBytes = 1_000_000, maxMs = 20_000, maxOpens = 10, clock = { now })
        val slow = BudgetedSource(FakeSource(ByteArray(1_000)), budget).open(0)
        assertTrue(slow.read(ByteArray(10)) > 0)
        now = 20_001
        try {
            slow.read(ByteArray(10))
            fail("read after the time was up")
        } catch (_: IOException) {
        }
        assertTrue(budget.exceeded)
    }

    @Test
    fun aShareThatWontOpenIsToldApartFromABudgetUsedUp() {
        val budget = ReadBudget()
        try {
            BudgetedSource(FakeSource(ByteArray(10), fail = true), budget).open(0)
            fail()
        } catch (_: IOException) {
        }
        assertTrue(budget.openFailed)
        assertFalse(budget.exceeded)
        assertEquals(0L, budget.bytesRead)
    }
}
