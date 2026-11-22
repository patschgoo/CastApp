package io.github.patschgoo.castapp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryTest {
    private class MemoryStore(var text: String? = null) : WatchHistory.Store {
        var writes = 0
        override fun read() = text
        override fun write(text: String) {
            this.text = text
            writes++
        }
    }

    private val movie = MediaItem("Film.mkv", "content://media/film", "video/x-matroska", subtitleUri = "content://media/film.srt")
    private var now = 1_000_000L

    @Test
    fun resumeRules() {
        val hour = 3_600_000L
        assertNull(ResumeRules.resumeAt(10_000, hour, finished = false)) // barely started
        assertEquals(10 * 60_000L - 3000, ResumeRules.resumeAt(10 * 60_000L, hour, finished = false)) // a few seconds earlier
        assertNull(ResumeRules.resumeAt(hour - 60_000, hour, finished = false)) // last 5 %: watched
        assertNull(ResumeRules.resumeAt(20 * 60_000L, hour, finished = true))
        assertEquals(120_000L - 3000, ResumeRules.resumeAt(120_000, 0, finished = false)) // length unknown
        assertTrue(ResumeRules.isFinished(9 * 60_000L + 40_000, 10 * 60_000L)) // short video: 30 s margin
        assertFalse(ResumeRules.isFinished(5 * 60_000L, 10 * 60_000L))
        assertFalse(ResumeRules.isFinished(5 * 60_000L, 0))
    }

    @Test
    fun remembersVideosAndLongRecordingsOnly() {
        assertTrue(ResumeRules.remembers(movie, 0))
        val song = MediaItem("Song.mp3", "content://a", "audio/mpeg")
        assertFalse(ResumeRules.remembers(song, 4 * 60_000L))
        assertTrue(ResumeRules.remembers(song, 40 * 60_000L))
        assertFalse(ResumeRules.remembers(movie.copy(live = true), 0))
        assertFalse(ResumeRules.remembers(MediaItem("p.jpg", "content://p", "image/jpeg"), 0))
    }

    @Test
    fun recordsAndResumesTheSameItem() {
        val store = MemoryStore()
        val h = WatchHistory(store, clock = { now })
        h.record(movie, 754_000, 5_400_000, force = true)
        assertEquals(751_000L, h.resumePosition(movie))
        assertEquals(1, h.continueWatching().size)
        val again = WatchHistory(store, clock = { now }) // read back from the file
        val e = again.entries.value.single()
        assertEquals("Film.mkv", e.title)
        assertEquals(754_000L, e.positionMs)
        assertEquals(movie, e.item) // enough to play it again, subtitles included
        assertEquals(754_000f / 5_400_000f, e.progress, 0.0001f)
    }

    @Test
    fun ignoresShortGlancesAtNewItems() {
        val h = WatchHistory(MemoryStore(), clock = { now })
        h.record(movie, 5_000, 5_400_000)
        assertTrue(h.entries.value.isEmpty())
    }

    @Test
    fun finishedItemsLeaveContinueWatching() {
        val h = WatchHistory(MemoryStore(), clock = { now })
        h.record(movie, 3_000_000, 5_400_000, force = true)
        h.markFinished(movie, 5_400_000)
        assertNull(h.resumePosition(movie))
        assertTrue(h.continueWatching().isEmpty())
        assertTrue(h.entries.value.single().finished)
    }

    @Test
    fun keepsTheLengthWhenTheTvDoesNotReportIt() {
        val h = WatchHistory(MemoryStore(), clock = { now })
        h.record(movie, 600_000, 5_400_000, force = true)
        h.record(movie, 700_000, 0, force = true)
        assertEquals(5_400_000L, h.entries.value.single().durationMs)
    }

    @Test
    fun newestFirstRemoveAndClear() {
        val store = MemoryStore()
        val h = WatchHistory(store, clock = { now }, saveEveryMs = 60_000)
        val other = movie.copy(title = "Other", uri = "content://media/other")
        h.record(movie, 600_000, 5_400_000, force = true)
        now += 1000
        h.record(other, 600_000, 5_400_000, force = true)
        assertEquals(listOf("Other", "Film.mkv"), h.continueWatching().map { it.title })
        h.remove(other.uri)
        assertEquals(listOf("Film.mkv"), h.entries.value.map { it.title })
        h.clear()
        assertTrue(h.entries.value.isEmpty())
        assertTrue(WatchHistory(store).entries.value.isEmpty())
    }

    @Test
    fun throttlesWritesWhilePlaying() {
        val store = MemoryStore()
        val h = WatchHistory(store, clock = { now }, saveEveryMs = 15_000)
        h.record(movie, 600_000, 5_400_000, force = true)
        val after = store.writes
        now += 5000
        h.record(movie, 605_000, 5_400_000)
        assertEquals(after, store.writes)
        now += 15_000
        h.record(movie, 620_000, 5_400_000)
        assertEquals(after + 1, store.writes)
        assertNotNull(store.text)
    }

    @Test
    fun survivesABrokenFile() {
        assertTrue(WatchHistory(MemoryStore("not json")).entries.value.isEmpty())
    }
}
