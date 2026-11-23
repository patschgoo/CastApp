package io.github.patschgoo.castapp.mediaservers

import io.github.patschgoo.castapp.tv.TrackHint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sound track Plex or Jellyfin picked travels with the item, for a CastApp TV playing the file itself. Read-only. */
class ServerAudioTrackTest {
    private val info = ClientInfo(deviceId = "dev-1", deviceName = "Pixel 8", version = "4.1.0")

    private val plexAccount = ServerAccount("plex-mach1", ServerKind.PLEX, "Living room PC", "http://192.168.1.5:32400", token = "srvtok", serverId = "mach1")
    private val plexUrls = PlexUrls(plexAccount, info, PlaybackCaps.TV)
    private val plex = PlexClient(plexAccount, info, deviceLanguage = "de")

    /** A dual-audio episode: Japanese first in the file, the German dub picked in Plex. */
    private fun plexEpisode(selected: String, audioOnly: Boolean = false) = """
        {"ratingKey":"201","type":"episode","title":"Pilot","grandparentTitle":"Vela","grandparentRatingKey":"200","index":1,"parentIndex":1,"duration":1440000,
         "Media":[{"id":511,"videoCodec":"hevc","audioCodec":"aac","container":"mkv","Part":[{"id":611,"key":"/library/parts/611/1/file.mkv","container":"mkv","Stream":[
           {"id":1,"streamType":1,"codec":"hevc","index":0,"bitDepth":10},
           ${if (audioOnly) "" else """{"id":3,"streamType":2,"codec":"aac","index":2,"languageCode":"ger","title":"German Dub","displayTitle":"Deutsch (AAC Stereo)"${if (selected == "ger") ",\"selected\":true" else ""}},"""}
           {"id":2,"streamType":2,"codec":"aac","index":1,"languageCode":"jpn","displayTitle":"Japanese (AAC Stereo)"${if (selected == "jpn") ",\"selected\":1" else ""}},
           {"id":4,"streamType":3,"codec":"ass","index":3,"languageCode":"eng"}
         ]}]}]}
    """.trimIndent()

    @Test
    fun plexsPickedSoundTrack() {
        val media = PlexParser.item(JSONObject(plexEpisode("ger")), plexUrls).media!!
        // Listed out of order: its place in the file counts (Japanese is the first sound track).
        assertEquals(ChosenAudio("ger", "German Dub", 1), media.chosenAudio)
        val item = plex.entryFor(PlexParser.item(JSONObject(plexEpisode("ger")), plexUrls)).item
        assertEquals(TrackHint(track = 1, language = "ger", title = "German Dub"), TrackHint.decode(item.audioTrack))
        assertEquals(ChosenAudio("jpn", null, 0), PlexParser.item(JSONObject(plexEpisode("jpn")), plexUrls).media!!.chosenAudio)
        // Nothing marked, or only one sound track: nothing to tell.
        assertNull(PlexParser.item(JSONObject(plexEpisode("none")), plexUrls).media!!.chosenAudio)
        assertNull(plex.entryFor(PlexParser.item(JSONObject(plexEpisode("jpn", audioOnly = true)), plexUrls)).item.audioTrack)
        // It survives the way to the casting service.
        assertEquals(item.audioTrack, io.github.patschgoo.castapp.service.MediaItem.fromJson(JSONObject(item.toJson().toString())).audioTrack)
    }

    private val jfAccount = ServerAccount("acc1", ServerKind.JELLYFIN, "Home server", "http://jf.local:8096", token = "tok123", userId = "user1", userName = "anna", serverId = "srvid")
    private val jfUrls = JellyfinUrls(jfAccount, info, PlaybackCaps.TV)
    private val jellyfin = JellyfinClient(jfAccount, info, deviceLanguage = "de")

    private fun jellyfinEpisode(defaultAudio: Int?) = """
        {"Name":"Pilot","Id":"e1","Type":"Episode","SeriesName":"Vela","SeriesId":"s1","RunTimeTicks":14400000000,
         "MediaSources":[{"Id":"src1","Container":"mkv",${defaultAudio?.let { "\"DefaultAudioStreamIndex\":$it," } ?: ""}"MediaStreams":[
           {"Type":"Video","Codec":"hevc","Width":1920,"Height":1080,"BitDepth":10,"Index":0},
           {"Type":"Audio","Codec":"aac","Language":"jpn","Index":1,"IsDefault":true},
           {"Type":"Audio","Codec":"aac","Language":"ger","Title":"German Dub","Index":2},
           {"Type":"Subtitle","Codec":"ass","Language":"eng","Index":3}
         ]}]}
    """.trimIndent()

    @Test
    fun jellyfinsDefaultSoundTrack() {
        val item = jellyfin.entryFor(JellyfinParser.item(JSONObject(jellyfinEpisode(2)), jfUrls)).item
        assertEquals(TrackHint(track = 1, language = "ger", title = "German Dub"), TrackHint.decode(item.audioTrack))
        assertEquals(ChosenAudio("jpn", null, 0), JellyfinParser.item(JSONObject(jellyfinEpisode(1)), jfUrls).media!!.chosenAudio)
        // Not told, or an index that isn't a sound track: nothing.
        assertNull(jellyfin.entryFor(JellyfinParser.item(JSONObject(jellyfinEpisode(null)), jfUrls)).item.audioTrack)
        assertNull(JellyfinParser.item(JSONObject(jellyfinEpisode(3)), jfUrls).media!!.chosenAudio)
    }
}
