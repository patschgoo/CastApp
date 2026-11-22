package io.github.patschgoo.castapp.network

import io.github.patschgoo.castapp.stream.MediaServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/**
 * Runs against a real Samba server when CASTAPP_SMB_TEST is set to
 * "host:port:localSharePath" (skipped otherwise, e.g. in CI).
 */
class SmbIntegrationTest {
    private val env = System.getenv("CASTAPP_SMB_TEST")

    private fun target(share: String, user: String = "", password: String = ""): SmbTarget {
        val (host, port) = env!!.split(':')
        return SmbTarget(host, share, user, password, port = port.toInt())
    }

    private val localShare: File get() = File(env!!.split(':')[2])

    @Test
    fun guestShareListsAndReads() {
        assumeTrue(env != null)
        SmbConnection(target("media")).use { smb ->
            val top = smb.list("")
            assertTrue(top.any { it.name == "Movies" && it.isFolder })
            val movies = smb.list("Movies")
            val file = movies.first { it.name == "holiday.mp4" }
            assertEquals(File(localShare, "Movies/holiday.mp4").length(), file.size)
            assertEquals("Movies/holiday.mp4", file.path)
            // Random access: what the TV does when it seeks.
            val expected = File(localShare, "Movies/holiday.mp4").readBytes()
            val part = smb.open("Movies/holiday.mp4", 1_000_000).use { it.readNBytes(70_000) }
            assertArrayEquals(expected.copyOfRange(1_000_000, 1_070_000), part)
        }
    }

    @Test
    fun passwordShare() {
        assumeTrue(env != null)
        SmbConnection(target("private", "caster", "secret123")).use { smb ->
            assertTrue(smb.list("Movies").any { it.name == "holiday.srt" })
        }
        try {
            SmbConnection(target("private", "caster", "wrong")).use { it.list("") }
            throw AssertionError("expected a log-in error")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("password", ignoreCase = true) || e.message!!.contains("LOGON", true))
        }
    }

    @Test
    fun servedToTheTvWithRanges() {
        assumeTrue(env != null)
        SmbConnection(target("media")).use { smb ->
            val server = MediaServer(0).also { it.start() }
            try {
                val path = server.addRoute("smb/holiday.mp4", server.ranged(smb.mediaSource("Movies/holiday.mp4"), "video/mp4"))
                Socket(InetAddress.getLoopbackAddress(), server.port).use { s ->
                    s.getOutputStream().write("GET $path HTTP/1.1\r\nRange: bytes=2000000-2000009\r\n\r\n".toByteArray())
                    val reply = s.getInputStream().readBytes()
                    val text = String(reply, Charsets.ISO_8859_1)
                    assertTrue(text, text.startsWith("HTTP/1.1 206"))
                    val body = reply.copyOfRange(reply.size - 10, reply.size)
                    val expected = File(localShare, "Movies/holiday.mp4").readBytes().copyOfRange(2_000_000, 2_000_010)
                    assertArrayEquals(expected, body)
                }
            } finally {
                server.stop()
            }
        }
    }
}
