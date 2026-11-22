package io.github.patschgoo.castapp.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uGuideHeaderTest {
    @Test
    fun readsUrlTvg() {
        assertEquals(
            listOf("https://epg.example/guide.xml"),
            M3uParser.guideUrls("#EXTM3U url-tvg=\"https://epg.example/guide.xml\"\n#EXTINF:-1,Ch\nhttp://x/1\n"),
        )
    }

    @Test
    fun readsSeveralCommaSeparatedAddressesAndOtherSpellings() {
        val text = "﻿#EXTM3U x-tvg-url=\"http://a/1.xml.gz, https://b/2.xml\" tvg-url=\"http://a/1.xml.gz\" tvg-shift=\"1\"\r\n#EXTINF:-1,Ch\r\nhttp://x/1\r\n"
        assertEquals(listOf("http://a/1.xml.gz", "https://b/2.xml"), M3uParser.guideUrls(text))
    }

    @Test
    fun ignoresPlaylistsWithoutAGuideAddress() {
        assertEquals(emptyList<String>(), M3uParser.guideUrls("#EXTM3U\n#EXTINF:-1,Ch\nhttp://x/1\n"))
        assertEquals(emptyList<String>(), M3uParser.guideUrls(""))
        // Not on the header line: ignored. Not an address: ignored.
        assertEquals(emptyList<String>(), M3uParser.guideUrls("#EXTM3U\n#EXTINF:-1 url-tvg=\"http://a/x.xml\",Ch\nhttp://x/1\n"))
        assertEquals(emptyList<String>(), M3uParser.guideUrls("#EXTM3U url-tvg=\"guide.xml\"\n"))
    }

    @Test
    fun theChannelListIsUnchangedByTheHeader() {
        val channels = M3uParser.parse("#EXTM3U url-tvg=\"http://a/x.xml\"\n#EXTINF:-1 tvg-id=\"a\",A\nhttp://x/1\n")
        assertEquals(listOf("A"), channels.map { it.name })
        assertEquals("a", channels[0].tvgId)
    }
}
