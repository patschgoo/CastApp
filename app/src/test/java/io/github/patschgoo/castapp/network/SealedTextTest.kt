package io.github.patschgoo.castapp.network

import io.github.patschgoo.castapp.iptv.IptvRepository
import io.github.patschgoo.castapp.iptv.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedTextTest {
    // A stand-in for the Keystore: reversible, and visibly not the plain text.
    private val encrypt: (String) -> String = { it.reversed() + "~" }
    private val decrypt: (String) -> String = { if (it.endsWith("~")) it.dropLast(1).reversed() else "" }

    @Test
    fun sealsAndOpens() {
        val plain = """[{"id":"a1","source":"http://iptv.example:8080/get.php?username=u&password=p"}]"""
        val stored = SealedText.seal(plain, encrypt)
        assertTrue(SealedText.isSealed(stored))
        assertFalse("password must not be readable", stored.contains("password=p"))
        assertEquals(plain, SealedText.open(stored, decrypt))
    }

    @Test
    fun oldPlainTextIsStillRead() {
        assertEquals("[]", SealedText.open("[]", decrypt))
        assertFalse(SealedText.isSealed("[]"))
    }

    @Test
    fun unreadableGivesNull() {
        // Restored onto another phone: the Keystore key isn't there, Secrets.decrypt answers "".
        assertNull(SealedText.open(SealedText.PREFIX + "garbage", { "" }))
        assertEquals("", SealedText.open(SealedText.PREFIX, decrypt))
    }

    @Test
    fun brokenKeystoreKeepsPlainText() {
        assertEquals("x", SealedText.seal("x") { throw IllegalStateException("no keystore") })
        assertEquals("x", SealedText.seal("x") { "" })
        assertEquals(SealedText.PREFIX, SealedText.seal("", encrypt).take(SealedText.PREFIX.length))
    }

    @Test
    fun playlistsRoundTrip() {
        val list = listOf(
            Playlist("a1", "Provider", "http://iptv.example:8080/get.php?username=u&password=p&type=m3u_plus", "http://iptv.example/xmltv.php"),
            Playlist("b2", "Free channels", "https://example.org/free.m3u"),
        )
        val stored = SealedText.seal(IptvRepository.encodePlaylists(list), encrypt)
        assertEquals(list, IptvRepository.decodePlaylists(SealedText.open(stored, decrypt)!!))
        assertEquals(emptyList<Playlist>(), IptvRepository.decodePlaylists("not json"))
        assertEquals(emptyList<Playlist>(), IptvRepository.decodePlaylists(""))
    }
}
