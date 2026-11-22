package io.github.patschgoo.castapp.network

import io.github.patschgoo.castapp.stream.MediaServer
import io.github.patschgoo.castapp.stream.MediaSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress

class HttpMediaSourceTest {
    private val data = ByteArray(300_000) { (it * 13 + 5).toByte() }
    private val source = object : MediaSource {
        override val length: Long = data.size.toLong()
        override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }
    private val server = MediaServer(0).also { it.start() }
    private val rangedUrl = server.urlFor(InetAddress.getLoopbackAddress(), server.addRoute("f.mp4", server.ranged(source, "video/mp4")))
    // A server that ignores Range headers and always sends the whole file.
    private val plainUrl = server.urlFor(InetAddress.getLoopbackAddress(), server.addRoute("plain.mp4", server.bytes("video/mp4") { data }))

    @After
    fun tearDown() = server.stop()

    @Test
    fun readsLengthAndAnyPosition() {
        val s = HttpMediaSource(rangedUrl, mapOf("Authorization" to "Basic eDp5"))
        assertEquals(data.size.toLong(), s.length)
        val part = s.open(123_456).use { HttpMediaSource.readUpTo(it, 1_000_000)!! }
        assertArrayEquals(data.copyOfRange(123_456, data.size), part)
    }

    @Test
    fun skipsAheadWhenTheServerIgnoresRanges() {
        val s = HttpMediaSource(plainUrl)
        assertEquals(data.size.toLong(), s.length)
        val part = s.open(250_000).use { HttpMediaSource.readUpTo(it, 1_000_000)!! }
        assertArrayEquals(data.copyOfRange(250_000, data.size), part)
    }

    @Test
    fun readUpToStopsAtTheLimit() {
        assertEquals(null, HttpMediaSource.readUpTo(ByteArrayInputStream(data), 1000))
        assertEquals(data.size, HttpMediaSource.readUpTo(ByteArrayInputStream(data), data.size)!!.size)
    }
}
