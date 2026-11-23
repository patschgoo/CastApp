package io.github.patschgoo.castapp.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** [MediaItem.artUri] in the JSON that the watch history, hand-over, join and the service share. */
class MediaItemArtTest {
    private val poster = "https://jf.local:8096/Items/e1/Images/Primary?maxHeight=480&quality=90&tag=t1&api_key=tok123"

    @Test
    fun thePictureTravelsInTheJson() {
        val item = MediaItem("Pilot", "https://jf.local:8096/Videos/e1/stream.mkv?Static=true&api_key=tok123", "video/x-matroska", artUri = poster)
        val json = item.toJson()
        assertEquals(poster, json.getString("art"))
        assertEquals(item, MediaItem.fromJson(JSONObject(json.toString())))
        assertEquals(listOf(item), MediaItem.listFromJson(MediaItem.listToJson(listOf(item))))
    }

    @Test
    fun withoutAPictureTheJsonIsAsBefore() {
        val item = MediaItem("Film.mkv", "smb://src1/Movies/Film.mkv", "video/x-matroska")
        assertFalse(item.toJson().has("art"))
        assertNull(MediaItem.fromJson(item.toJson()).artUri)
    }

    @Test
    fun olderJsonStaysReadable() {
        // As 4.0.0 wrote it (watch history, hand-over offers): no "art" key.
        val old = """{"title":"Film","uri":"https://example.com/film.mp4","mime":"video/mp4","sub":"","headers":{},"live":false,""" +
            """"trustAll":false,"ref":"","orig":"","origMime":""}"""
        val item = MediaItem.fromJson(JSONObject(old))
        assertEquals("Film", item.title)
        assertEquals("https://example.com/film.mp4", item.uri)
        assertNull(item.artUri)
        // An empty one, as some writer might leave it, is no picture.
        assertNull(MediaItem.fromJson(JSONObject(old).put("art", "")).artUri)
    }

    private class MemoryStore : WatchHistory.Store {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    @Test
    fun theWatchHistoryKeepsThePicture() {
        val store = MemoryStore()
        val item = MediaItem("Pilot", "https://jf.local:8096/Videos/e1/master.m3u8", "application/x-mpegurl", artUri = poster)
        WatchHistory(store).record(item, 10 * 60_000L, 45 * 60_000L, force = true)
        val again = WatchHistory(store) // read back from the file
        assertEquals(poster, again.entries.value.single().item?.artUri)
        assertEquals(item, again.entries.value.single().item)
    }

    @Test
    fun onlyWebAddressesArePictures() {
        assertEquals("https://img.example/logo.png", MediaItem.pictureAddress(" https://img.example/logo.png "))
        assertEquals("HTTP://img.example/a.jpg", MediaItem.pictureAddress("HTTP://img.example/a.jpg"))
        assertNull(MediaItem.pictureAddress("data:image/png;base64,AAAA"))
        assertNull(MediaItem.pictureAddress("/images/poster.jpg"))
        assertNull(MediaItem.pictureAddress("blob:https://x/1"))
        assertNull(MediaItem.pictureAddress(""))
        assertNull(MediaItem.pictureAddress(null))
        assertNull(MediaItem.pictureAddress("https://x/" + "a".repeat(5000)))
    }
}
