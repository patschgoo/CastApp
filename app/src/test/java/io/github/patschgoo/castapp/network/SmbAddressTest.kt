package io.github.patschgoo.castapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbAddressTest {
    @Test
    fun plainAddresses() {
        assertEquals(SmbAddress("nas", "Media", ""), SmbAddress.parse("\\\\nas\\Media"))
        assertEquals(SmbAddress("nas", "Media", ""), SmbAddress.parse("smb://nas/Media/"))
        assertEquals(SmbAddress("nas", "Media", ""), SmbAddress.parse(" nas/Media "))
        assertEquals(SmbAddress("192.168.1.5:4450", "Media", ""), SmbAddress.parse("192.168.1.5:4450\\Media"))
    }

    @Test
    fun shieldDrivesWithSpacesAndFolders() {
        // As Windows shows the NVIDIA Shield's shared USB drives.
        assertEquals(
            SmbAddress("192.168.178.20", "External USB drive-1A2B3C4D", "NVIDIA_SHIELD/Anime"),
            SmbAddress.parse("\\\\192.168.178.20\\External USB drive-1A2B3C4D\\NVIDIA_SHIELD\\Anime"),
        )
        assertEquals(
            SmbAddress("192.168.178.20", "External USB drive-5E6F7A8B", "NVIDIA_SHIELD"),
            SmbAddress.parse("\\\\192.168.178.20\\External USB drive-5E6F7A8B\\NVIDIA_SHIELD"),
        )
        assertEquals(SmbAddress("192.168.178.20", "internal", ""), SmbAddress.parse("\\\\192.168.178.20\\internal"))
        assertEquals("Anime", SmbAddress.parse("\\\\192.168.178.20\\External USB drive-1A2B3C4D\\NVIDIA_SHIELD\\Anime")!!.displayName)
        assertEquals("SanDisk", SmbAddress.parse("\\\\192.168.178.20\\SanDisk")!!.displayName)
    }

    @Test
    fun shareTypedSeparately() {
        assertEquals(SmbAddress("nas", "Media", ""), SmbAddress.parse("\\\\nas", "Media"))
        assertEquals(SmbAddress("nas", "Media", "Movies/4K"), SmbAddress.parse("nas", "\\Media\\Movies\\4K\\"))
        // The same share in both fields: the address's folder stays.
        assertEquals(SmbAddress("nas", "media", "Movies"), SmbAddress.parse("\\\\nas\\Media\\Movies", "media"))
        // A different share typed: it wins, the address's folder belonged to the other share.
        assertEquals(SmbAddress("nas", "Music", ""), SmbAddress.parse("\\\\nas\\Media\\Movies", "Music"))
    }

    @Test
    fun incomplete() {
        assertNull(SmbAddress.parse(""))
        assertNull(SmbAddress.parse("\\\\nas"))
        assertNull(SmbAddress.parse("smb://"))
    }
}
