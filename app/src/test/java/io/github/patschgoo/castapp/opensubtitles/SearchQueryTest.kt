package io.github.patschgoo.castapp.opensubtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryTest {
    @Test
    fun movieWithYearAndTags() {
        assertEquals(SearchQuery("the matrix", 1999), SearchQuery.fromFileName("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv"))
        assertEquals(SearchQuery("blade runner 2049", 2017), SearchQuery.fromFileName("Blade Runner 2049 (2017) [2160p] [HDR].mp4"))
    }

    @Test
    fun yearAtTheStartBelongsToTheTitle() {
        assertEquals(SearchQuery("2001 a space odyssey", 1968), SearchQuery.fromFileName("2001.A.Space.Odyssey.1968.REMASTERED.mkv"))
    }

    @Test
    fun episodes() {
        assertEquals(SearchQuery("breaking bad", season = 2, episode = 7), SearchQuery.fromFileName("Breaking_Bad_S02E07_720p_HDTV.mkv"))
        assertEquals(SearchQuery("the office us", season = 3, episode = 12), SearchQuery.fromFileName("The Office US 3x12 Traveling Salesmen.avi"))
    }

    @Test
    fun plainNamesAndPaths() {
        assertEquals(SearchQuery("holiday video"), SearchQuery.fromFileName("Holiday video.mp4"))
        assertEquals(SearchQuery("charlotte's web", 2006), SearchQuery.fromFileName("Movies/Charlotte's.Web.2006.DVDRip.XviD.avi"))
        assertEquals(SearchQuery("arrival"), SearchQuery.fromFileName("Arrival.WEB-DL.H.264.mkv"))
    }
}
