package io.github.patschgoo.castapp.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV channels carry their logo as the picture ([io.github.patschgoo.castapp.service.MediaItem.artUri]). */
class ChannelArtTest {
    @Test
    fun channelsCarryTheirLogo() {
        val m3u = "#EXTM3U\n" +
            "#EXTINF:-1 tvg-logo=\"https://logo.example/ard.png\" group-title=\"News\",Das Erste HD\n" +
            "https://live.example/ard/master.m3u8\n" +
            "#EXTINF:-1 group-title=\"Radio\",Radio One\n" +
            "http://radio.example/stream.mp3\n" +
            "#EXTINF:-1 tvg-logo=\"logos/zdf.png\",ZDF\n" +
            "https://live.example/zdf/master.m3u8\n"
        val items = IptvRepository.toItems(M3uParser.parse(m3u))
        assertEquals(3, items.size)
        assertEquals("https://logo.example/ard.png", items[0].artUri)
        assertTrue(items[0].live)
        assertNull(items[1].artUri) // no logo
        assertNull(items[2].artUri) // a path without a server is no address the phone can load
    }

    @Test
    fun theLogoTravelsWithTheItem() {
        val item = IptvRepository.toItems(listOf(Channel("News 24", "http://tv/news.ts", logo = "https://logo.example/n24.png"))).single()
        val back = io.github.patschgoo.castapp.service.MediaItem.fromJson(item.toJson())
        assertEquals("https://logo.example/n24.png", back.artUri)
    }
}
