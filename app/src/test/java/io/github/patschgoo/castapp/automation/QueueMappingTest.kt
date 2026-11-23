package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.MediaType
import io.github.patschgoo.castapp.subtitles.Sidecars
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** A checked queue item becomes one of CastApp's play-list items. */
class QueueMappingTest {
    @Test
    fun webItemsGetTheirTitleAndTypeFromTheAddressWhenLeftOut() {
        val item = QueueMapping.toMediaItem(QueueItem("https://media.example/show/Episode%201.mkv?token=abc"))
        assertEquals("Episode 1.mkv", item.title)
        assertEquals("p", QueueMapping.toMediaItem(QueueItem("https://media.example/p/")).title)
        assertEquals("video/x-matroska", item.mimeType)
        assertEquals("https://media.example/show/Episode%201.mkv?token=abc", item.uri)
        assertEquals(MediaType.AUDIO, QueueMapping.toMediaItem(QueueItem("https://media.example/a.mp3")).type)
        // An HLS stream is recognised by its address.
        assertEquals("application/x-mpegurl", QueueMapping.toMediaItem(QueueItem("https://media.example/live/index.m3u8")).mimeType)
    }

    @Test
    fun theCallersTitleAndTypeWin() {
        val item = QueueMapping.toMediaItem(QueueItem("https://media.example/stream?id=7", title = "Episode 7", mime = "video/mp4"))
        assertEquals("Episode 7", item.title)
        assertEquals("video/mp4", item.mimeType)
    }

    @Test
    fun contentItemsUseWhatTheContentResolverSays() {
        val uri = "content://com.example.library.files/media/42"
        val item = QueueMapping.toMediaItem(QueueItem(uri), names = mapOf(uri to "Film.mkv"), types = mapOf(uri to "video/x-matroska"))
        assertEquals("Film.mkv", item.title)
        assertEquals("video/x-matroska", item.mimeType)
        // No type from the provider: guessed from the name.
        assertEquals("audio/flac", QueueMapping.toMediaItem(QueueItem(uri), names = mapOf(uri to "Song.flac"), types = mapOf(uri to "application/octet-stream")).mimeType)
    }

    @Test
    fun subtitlesKeepTheCallersOrderAndLanguage() {
        val item = QueueMapping.toMediaItem(
            QueueItem(
                "https://media.example/01.mkv",
                title = "Episode 1",
                subtitles = listOf(
                    QueueSubtitle("https://media.example/subs/1", "de", "Signs & Songs"),
                    QueueSubtitle("https://media.example/01.en.ass", "en"),
                    QueueSubtitle("https://media.example/01.ja.srt"),
                ),
            ),
        )
        assertEquals("https://media.example/subs/1", item.subtitleUri)
        assertEquals(listOf("https://media.example/01.en.ass", "https://media.example/01.ja.srt"), item.moreSubtitles)
        // The picker names them by the language the caller gave.
        fun label(uri: String) = Sidecars.label(item.title, item.subtitleName(uri), Locale.ENGLISH)
        assertEquals("German (Signs & Songs)", label("https://media.example/subs/1"))
        assertEquals("English", label("https://media.example/01.en.ass"))
        assertEquals("subtitles.en.ass", item.subtitleName("https://media.example/01.en.ass")) // the format stays visible
        // Without a language, the file's own name.
        assertEquals("01.ja.srt", item.subtitleName("https://media.example/01.ja.srt"))
    }

    @Test
    fun aLabelWithoutALanguageIsShownAsItIs() {
        assertEquals("subtitles.Commentary.srt", QueueMapping.subtitleName(QueueSubtitle("https://a/s", label = "Commentary"), "s"))
        assertEquals("Commentary", Sidecars.label("Film", "subtitles.Commentary.srt", Locale.ENGLISH))
        // A dot in the label would cut the name.
        assertEquals("subtitles.en [Dr  Who].vtt", QueueMapping.subtitleName(QueueSubtitle("https://a/s.vtt", "en", "Dr. Who"), "s.vtt"))
        assertNull(QueueMapping.subtitleName(QueueSubtitle("https://a/s.srt"), "s.srt"))
    }

    @Test
    fun theArtBecomesTheItemsPicture() {
        assertEquals("https://media.example/poster.jpg", QueueMapping.toMediaItem(QueueItem("https://media.example/01.mkv", art = "https://media.example/poster.jpg")).artUri)
        assertNull(QueueMapping.toMediaItem(QueueItem("https://media.example/01.mkv")).artUri)
    }

    @Test
    fun theItemsSurviveTheWayToTheCastingService() {
        // CastService gets the play list as JSON: nothing of the mapping may get lost on the way.
        val item = QueueMapping.toMediaItem(
            QueueItem(
                "https://media.example/01.mkv",
                title = "Episode 1",
                subtitles = listOf(QueueSubtitle("https://media.example/s", "en")),
                art = "https://media.example/poster.jpg",
            ),
        )
        assertEquals(item, MediaItem.fromJson(JSONObject(item.toJson().toString())))
    }

    @Test
    fun theSheetShowsTheQueuesTitleOrTheFirstItem() {
        val items = listOf(MediaItem("Episode 1", "https://a/1.mp4", "video/mp4"), MediaItem("Episode 2", "https://a/2.mp4", "video/mp4"))
        val queue = CastQueueRequest(listOf(QueueItem("https://a/1.mp4"), QueueItem("https://a/2.mp4")))
        val more = { first: String, others: Int -> "$first and $others more" }
        assertEquals("Season 1", QueueMapping.sheetTitle(queue.copy(title = "Season 1"), items, more))
        assertEquals("Episode 1 and 1 more", QueueMapping.sheetTitle(queue, items, more))
        assertEquals("Episode 2 and 1 more", QueueMapping.sheetTitle(queue.copy(startIndex = 1), items, more))
        assertEquals("Episode 1", QueueMapping.sheetTitle(queue.copy(items = queue.items.take(1)), items.take(1), more))
    }
}
