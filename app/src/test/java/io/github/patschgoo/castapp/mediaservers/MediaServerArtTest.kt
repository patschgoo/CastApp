package io.github.patschgoo.castapp.mediaservers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plex and Jellyfin items carry their poster or episode still ([io.github.patschgoo.castapp.service.MediaItem.artUri]). */
class MediaServerArtTest {
    private val info = ClientInfo(deviceId = "dev-1", deviceName = "Pixel 8", version = "4.0.0")

    // ---- Plex ----------------------------------------------------------------------------------------------

    private val plexAccount = ServerAccount("plex-mach1", ServerKind.PLEX, "Living room PC", "http://192.168.1.5:32400", token = "srvtok", serverId = "mach1")
    private val plexUrls = PlexUrls(plexAccount, info, PlaybackCaps.TV)
    private val plex = PlexClient(plexAccount, info, deviceLanguage = "de")

    @Test
    fun plexEpisodeShowsItsStill() {
        val episode = """
            {"ratingKey":"201","type":"episode","title":"Pilot","grandparentTitle":"The Show","grandparentRatingKey":"200",
             "index":5,"parentIndex":2,"thumb":"/library/metadata/201/thumb/1","grandparentThumb":"/library/metadata/200/thumb/9","duration":2700000,"viewOffset":600000,
             "Media":[{"id":511,"videoCodec":"hevc","audioCodec":"eac3","container":"mkv","Part":[{"id":611,"key":"/library/parts/611/1/file.mkv","container":"mkv"}]}]}
        """.trimIndent()
        val entry = plex.entryFor(PlexParser.item(JSONObject(episode), plexUrls))
        val art = entry.item.artUri!!
        assertTrue(art, art.startsWith("http://192.168.1.5:32400/photo/:/transcode?width=480&height=270"))
        assertTrue(art, art.contains("url=%2Flibrary%2Fmetadata%2F201%2Fthumb%2F1"))
        assertTrue("with the token, as the app's own poster grid loads it", art.endsWith("X-Plex-Token=srvtok"))
    }

    @Test
    fun plexEpisodeWithoutStillShowsTheSeriesPoster() {
        val episode = """
            {"ratingKey":"202","type":"episode","title":"Two","grandparentRatingKey":"200","grandparentThumb":"/library/metadata/200/thumb/9","duration":2700000,
             "Media":[{"id":512,"videoCodec":"h264","audioCodec":"aac","container":"mp4","Part":[{"id":612,"key":"/library/parts/612/1/file.mp4","container":"mp4"}]}]}
        """.trimIndent()
        val art = plex.entryFor(PlexParser.item(JSONObject(episode), plexUrls)).item.artUri!!
        assertTrue(art, art.contains("url=%2Flibrary%2Fmetadata%2F200%2Fthumb%2F9"))
    }

    @Test
    fun plexMovieShowsItsPoster() {
        val movie = """
            {"ratingKey":"101","type":"movie","title":"Big Buck Bunny","thumb":"/library/metadata/101/thumb/1700000000","duration":6300000,
             "Media":[{"id":501,"videoCodec":"h264","audioCodec":"aac","container":"mp4","Part":[{"id":601,"key":"/library/parts/601/1700000000/file.mp4","container":"mp4"}]}]}
        """.trimIndent()
        val item = plex.entryFor(PlexParser.item(JSONObject(movie), plexUrls)).item
        assertEquals(
            "http://192.168.1.5:32400/photo/:/transcode?width=320&height=480&minSize=1&upscale=1&url=%2Flibrary%2Fmetadata%2F101%2Fthumb%2F1700000000&X-Plex-Token=srvtok",
            item.artUri,
        )
    }

    @Test
    fun plexItemWithoutPictureHasNone() {
        val clip = """{"ratingKey":"301","type":"clip","title":"Clip","Media":[{"id":1,"container":"mp4","Part":[{"id":2,"key":"/library/parts/2/file.mp4"}]}]}"""
        assertNull(plex.entryFor(PlexParser.item(JSONObject(clip), plexUrls)).item.artUri)
    }

    // ---- Jellyfin ------------------------------------------------------------------------------------------

    private val jfAccount = ServerAccount("acc1", ServerKind.JELLYFIN, "Home server", "http://jf.local:8096", token = "tok123", userId = "user1", userName = "anna", serverId = "srvid")
    private val jfUrls = JellyfinUrls(jfAccount, info, PlaybackCaps.TV)
    private val jellyfin = JellyfinClient(jfAccount, info, deviceLanguage = "de")

    @Test
    fun jellyfinEpisodeShowsItsOwnPicture() {
        val episode = """
            {"Name":"Pilot","Id":"e1","Type":"Episode","SeriesName":"The Show","SeriesId":"s1","SeriesPrimaryImageTag":"stag","IndexNumber":1,"ParentIndexNumber":1,
             "RunTimeTicks":27000000000,"ImageTags":{"Primary":"etag"},
             "MediaSources":[{"Id":"src1","Container":"mp4","MediaStreams":[{"Type":"Video","Codec":"h264","Width":1920,"Height":1080,"Index":0}]}]}
        """.trimIndent()
        val item = jellyfin.entryFor(JellyfinParser.item(JSONObject(episode), jfUrls)).item
        assertEquals("http://jf.local:8096/Items/e1/Images/Primary?maxHeight=480&quality=90&tag=etag&api_key=tok123", item.artUri)
    }

    @Test
    fun jellyfinEpisodeWithoutPictureShowsTheSeriesPoster() {
        val episode = """
            {"Name":"Two","Id":"e2","Type":"Episode","SeriesName":"The Show","SeriesId":"s1","SeriesPrimaryImageTag":"stag","RunTimeTicks":27000000000,
             "MediaSources":[{"Id":"src2","Container":"mp4","MediaStreams":[{"Type":"Video","Codec":"h264","Width":1920,"Height":1080,"Index":0}]}]}
        """.trimIndent()
        val item = jellyfin.entryFor(JellyfinParser.item(JSONObject(episode), jfUrls)).item
        assertEquals("http://jf.local:8096/Items/s1/Images/Primary?maxHeight=480&quality=90&tag=stag&api_key=tok123", item.artUri)
    }

    @Test
    fun jellyfinPictureSurvivesTheWatchHistoryJson() {
        val movie = """
            {"Name":"Big Buck Bunny","Id":"m1","Type":"Movie","RunTimeTicks":63000000000,"ImageTags":{"Primary":"imgtag1"},
             "MediaSources":[{"Id":"src1","Container":"matroska,webm","MediaStreams":[{"Type":"Video","Codec":"hevc","Width":3840,"Height":2160,"BitDepth":10,"Index":0}]}]}
        """.trimIndent()
        val item = jellyfin.entryFor(JellyfinParser.item(JSONObject(movie), jfUrls)).item
        assertTrue(item.isHls) // converted by the server: the picture still comes along
        val back = io.github.patschgoo.castapp.service.MediaItem.fromJson(JSONObject(item.toJson().toString()))
        assertEquals("http://jf.local:8096/Items/m1/Images/Primary?maxHeight=480&quality=90&tag=imgtag1&api_key=tok123", back.artUri)
    }
}
