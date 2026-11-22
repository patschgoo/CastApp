package io.github.patschgoo.castapp.ui

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {
    private fun entry(id: String, online: Boolean = true, video: Boolean = true, host: String = "192.168.1.$id", port: Int = 8009) =
        DeviceEntry(CastDevice(id = id, name = "TV $id", host = host, port = port, video = video), saved = true, online = online)

    private val livingRoom = entry("10")
    private val bedroom = entry("11")
    private val speaker = entry("20", video = false)
    private val office = entry("30", online = false)

    @Test
    fun nothingFoundMeansNoTarget() {
        assertNull(HomeLogic.target(emptyList(), lastUsedId = null, audioMode = false))
        assertNull(HomeLogic.target(emptyList(), lastUsedId = "10", audioMode = false))
    }

    @Test
    fun theTvUsedLastWins() {
        val devices = listOf(livingRoom, bedroom)
        assertEquals(bedroom, HomeLogic.target(devices, lastUsedId = "11", audioMode = false))
    }

    @Test
    fun withoutALastTvTheFirstOneThatIsThereIsUsed() {
        assertEquals(livingRoom, HomeLogic.target(listOf(office, livingRoom, bedroom), lastUsedId = null, audioMode = false))
    }

    @Test
    fun theLastTvStaysTheTargetWhileItIsNotFound() {
        // A TV that is slow to answer (or off) must not silently be replaced by another one.
        assertEquals(office, HomeLogic.target(listOf(livingRoom, office), lastUsedId = "30", audioMode = false))
    }

    @Test
    fun onlyTvsThatAreNotFoundMeansNoTargetWithoutALastOne() {
        assertNull(HomeLogic.target(listOf(office), lastUsedId = null, audioMode = false))
    }

    @Test
    fun aForgottenLastTvFallsBackToWhatIsThere() {
        assertEquals(livingRoom, HomeLogic.target(listOf(livingRoom, bedroom), lastUsedId = "gone", audioMode = false))
    }

    @Test
    fun speakersAreOnlyTargetsInAudioMode() {
        val devices = listOf(speaker, livingRoom)
        assertEquals(livingRoom, HomeLogic.target(devices, lastUsedId = "20", audioMode = false))
        assertEquals(speaker, HomeLogic.target(devices, lastUsedId = "20", audioMode = true))
        assertEquals(listOf(livingRoom), HomeLogic.listed(devices, audioMode = false))
        assertEquals(devices, HomeLogic.listed(devices, audioMode = true))
    }

    @Test
    fun sameDeviceByIdOrByEndpoint() {
        val group = CastDevice(id = "group", name = "Everywhere", host = "192.168.1.20", port = 32000, video = false, group = true)
        val member = CastDevice(id = "member", name = "Kitchen", host = "192.168.1.20", port = 8009, video = false)
        assertTrue(HomeLogic.sameDevice(livingRoom.device, livingRoom.device.copy(name = "Renamed")))
        assertTrue(HomeLogic.sameDevice(member, member.copy(id = "host:192.168.1.20")))
        assertFalse(HomeLogic.sameDevice(group, member))
        assertFalse(HomeLogic.sameDevice(livingRoom.device, bedroom.device))
    }

    @Test
    fun videosAndPhotosNeedAPicture() {
        assertTrue(HomeLogic.needsPicture(FilePick.VIDEOS))
        assertTrue(HomeLogic.needsPicture(FilePick.PHOTOS))
        assertFalse(HomeLogic.needsPicture(FilePick.MUSIC))
        assertFalse(HomeLogic.needsPicture(FilePick.ANY))
    }

    @Test
    fun aSpeakerOnlyGetsSound() {
        for (pick in FilePick.entries) assertArrayEquals(arrayOf("audio/*"), HomeLogic.mimeTypes(pick, picture = false))
    }

    @Test
    fun eachTileOffersItsOwnKindOfFile() {
        assertArrayEquals(arrayOf("image/*"), HomeLogic.mimeTypes(FilePick.PHOTOS, picture = true))
        assertArrayEquals(arrayOf("audio/*"), HomeLogic.mimeTypes(FilePick.MUSIC, picture = true))
        val videos = HomeLogic.mimeTypes(FilePick.VIDEOS, picture = true).toList()
        assertTrue("video/*" in videos)
        assertFalse("image/*" in videos)
        assertFalse("audio/*" in videos)
        // Subtitle files are picked together with the video.
        assertTrue("application/x-subrip" in videos)
        assertTrue("text/vtt" in videos)
    }

    @Test
    fun anyFileKeepsWhatTheOldPickerOffered() {
        assertArrayEquals(
            arrayOf("video/*", "audio/*", "image/*", "application/x-subrip", "text/vtt", "application/octet-stream"),
            HomeLogic.mimeTypes(FilePick.ANY, picture = true),
        )
    }
}
