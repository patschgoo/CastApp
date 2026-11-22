package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.discovery.CastDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMatcherTest {
    private val living = CastDevice("id-1", "Living room TV", "10.0.0.2")
    private val bedroom = CastDevice("id-2", "Bedroom TV", "10.0.0.3")
    private val bedroomSpeaker = CastDevice("id-3", "Bedroom speaker", "10.0.0.4", video = false)
    private val all = listOf(living, bedroom, bedroomSpeaker)

    private fun resolve(ref: DeviceRef, last: CastDevice? = null) = DeviceMatcher.resolve(all, ref, last)

    private fun found(m: DeviceMatch): CastDevice {
        assertTrue("expected a device, got $m", m is DeviceMatch.Found)
        return (m as DeviceMatch.Found).device
    }

    private fun notFound(m: DeviceMatch): String {
        assertTrue("expected no device, got $m", m is DeviceMatch.NotFound)
        return (m as DeviceMatch.NotFound).message
    }

    @Test
    fun nothingGivenMeansTheLastUsedTv() {
        assertEquals(bedroom, found(resolve(DeviceRef(), last = bedroom)))
        assertTrue(notFound(resolve(DeviceRef(), last = null)).contains("none used yet"))
    }

    @Test
    fun anIdMustMatchExactly() {
        assertEquals(living, found(resolve(DeviceRef(id = "id-1"))))
        notFound(resolve(DeviceRef(id = "id")))
        // a name is not an id
        notFound(resolve(DeviceRef(id = "Living room TV")))
    }

    @Test
    fun aNameMatchesIgnoringCase() {
        assertEquals(living, found(resolve(DeviceRef(name = "living ROOM tv"))))
    }

    @Test
    fun aPartOfANameIsEnoughIfOnlyOneMatches() {
        assertEquals(living, found(resolve(DeviceRef(name = "living"))))
        assertEquals(bedroomSpeaker, found(resolve(DeviceRef(name = "speaker"))))
    }

    @Test
    fun twoMatchesAreAnErrorNotAGuess() {
        val message = notFound(resolve(DeviceRef(name = "bedroom")))
        assertTrue(message.contains("Bedroom TV") && message.contains("Bedroom speaker"))
    }

    @Test
    fun anExactNameBeatsPartialMatches() {
        val tv = CastDevice("id-4", "TV", "10.0.0.9")
        assertEquals(tv, found(DeviceMatcher.resolve(all + tv, DeviceRef(name = "tv"), null)))
    }

    @Test
    fun theEitherFormTriesTheIdFirstThenTheName() {
        assertEquals(bedroom, found(resolve(DeviceRef(either = "id-2"))))
        assertEquals(bedroom, found(resolve(DeviceRef(either = "Bedroom TV"))))
        notFound(resolve(DeviceRef(either = "Kitchen")))
    }

    @Test
    fun noDevicesKnownIsNotFound() {
        notFound(DeviceMatcher.resolve(emptyList(), DeviceRef(name = "x"), null))
    }
}
