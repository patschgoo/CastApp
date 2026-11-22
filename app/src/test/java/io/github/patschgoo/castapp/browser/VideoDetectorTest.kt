package io.github.patschgoo.castapp.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoDetectorTest {
    @Test
    fun classifiesUrls() {
        assertEquals(FoundVideo.Kind.HLS, VideoDetector.classify("https://cdn.x/live/master.m3u8?token=1"))
        assertEquals(FoundVideo.Kind.DASH, VideoDetector.classify("https://cdn.x/v/manifest.mpd"))
        assertEquals(FoundVideo.Kind.FILE, VideoDetector.classify("https://x.org/movies/big_buck_bunny.mp4"))
        assertEquals(FoundVideo.Kind.AUDIO, VideoDetector.classify("https://x.org/podcast/ep1.mp3"))
        assertEquals(FoundVideo.Kind.FILE, VideoDetector.classify("https://x.org/stream?id=4", "video/mp4"))
        // Pieces of a stream are not videos on their own.
        assertNull(VideoDetector.classify("https://cdn.x/live/seg_00042.ts"))
        assertNull(VideoDetector.classify("https://cdn.x/v/chunk-stream0-00001.m4s"))
        assertNull(VideoDetector.classify("https://cdn.x/v/segment-12.mp4"))
        assertNull(VideoDetector.classify("https://cdn.x/v/video.mp4?range=0-50000"))
        assertNull(VideoDetector.classify("https://x.org/app.js"))
        assertNull(VideoDetector.classify("https://x.org/subs.vtt"))
    }

    @Test
    fun collectsPerPageAndPutsStreamsFirst() {
        val d = VideoDetector()
        d.newPage("https://news.example/article")
        d.onUrl("https://news.example/clip.mp4")
        d.onUrl("https://cdn.example/hls/master.m3u8")
        d.onUrl("https://cdn.example/hls/master.m3u8") // duplicate
        d.onUrl("blob:https://news.example/123")
        d.updateTitle("Breaking news")
        val list = d.videos.value
        assertEquals(2, list.size)
        assertEquals(FoundVideo.Kind.HLS, list[0].kind)
        assertEquals("Breaking news", list[1].pageTitle)
        assertEquals("application/x-mpegurl", list[0].mimeType)
        d.newPage("https://news.example/article#comments") // same page
        assertEquals(2, d.videos.value.size)
        d.newPage("https://other.example/")
        assertEquals(0, d.videos.value.size)
    }
}
