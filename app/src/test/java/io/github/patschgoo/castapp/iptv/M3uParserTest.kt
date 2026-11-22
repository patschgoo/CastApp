package io.github.patschgoo.castapp.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    @Test
    fun parsesTypicalPlaylist() {
        val m3u = "﻿#EXTM3U url-tvg=\"https://epg.example/guide.xml\"\r\n" +
            "#EXTINF:-1 tvg-id=\"daserste.de\" tvg-name=\"Das Erste\" tvg-logo=\"https://logo.example/ard.png\" group-title=\"News, Germany\",Das Erste HD\r\n" +
            "https://live.example/ard/master.m3u8\r\n" +
            "#EXTINF:-1 group-title=\"Radio\",Radio One\n" +
            "#EXTVLCOPT:http-user-agent=VLC/3.0\n" +
            "#EXTVLCOPT:http-referrer=https://radio.example/\n" +
            "http://radio.example/stream.mp3\n" +
            "#EXTINF:0,\n" +
            "#EXTGRP:Movies\n" +
            "https://cdn.example/films/Big%20Movie.mp4|User-Agent=Kodi%2F20&Referer=https://site.example/\n" +
            "# a comment\n" +
            "not a url\n"
        val ch = M3uParser.parse(m3u)
        assertEquals(3, ch.size)
        assertEquals("Das Erste HD", ch[0].name)
        assertEquals("News, Germany", ch[0].group) // comma inside quotes is not the name separator
        assertEquals("https://logo.example/ard.png", ch[0].logo)
        assertEquals("daserste.de", ch[0].tvgId)
        assertEquals("VLC/3.0", ch[1].userAgent)
        assertEquals("https://radio.example/", ch[1].referrer)
        assertEquals(mapOf("User-Agent" to "VLC/3.0", "Referer" to "https://radio.example/"), ch[1].headers)
        assertEquals("Big%20Movie.mp4", ch[2].name)
        assertEquals("Movies", ch[2].group)
        assertEquals("https://cdn.example/films/Big%20Movie.mp4", ch[2].url)
        assertEquals("Kodi/20", ch[2].userAgent)
        assertNull(ch[0].userAgent) // options don't leak to the next channel
        assertEquals(ch[0], Channel.fromJson(ch[0].toJson()))
    }
}
