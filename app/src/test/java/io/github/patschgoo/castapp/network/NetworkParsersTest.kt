package io.github.patschgoo.castapp.network

import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.stream.HlsVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URI

class NetworkParsersTest {
    @Test
    fun didlFoldersAndFiles() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <container id="1$2" parentID="0" childCount="12" restricted="1"><dc:title>Movies &amp; Shows</dc:title><upnp:class>object.container</upnp:class></container>
            <item id="v42" parentID="1" restricted="1"><dc:title>Holiday</dc:title><upnp:class>object.item.videoItem</upnp:class>
              <upnp:albumArtURI>http://nas:32400/thumb/42</upnp:albumArtURI>
              <res protocolInfo="http-get:*:video/x-matroska:DLNA.ORG_OP=01" size="123456" duration="1:02:03.000" resolution="1920x1080">http://192.168.1.10:32469/object/42/file.mkv?a=1&amp;b=2</res>
              <res protocolInfo="http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4">http://192.168.1.10:32469/transcode/42.mp4</res>
            </item>
            <item id="broken"><dc:title>No resource</dc:title></item>
        </DIDL-Lite>"""
        val e = Dlna.parseDidl(didl)
        assertEquals(2, e.size)
        assertTrue(e[0].isFolder)
        assertEquals("Movies & Shows", e[0].title)
        assertEquals("1$2", e[0].id)
        val v = e[1]
        assertFalse(v.isFolder)
        assertEquals("http://192.168.1.10:32469/object/42/file.mkv?a=1&b=2", v.url) // first (original) resource
        assertEquals("video/x-matroska", v.mimeType)
        assertEquals(123456L, v.size)
        assertEquals(3_723_000L, v.durationMs)
        assertEquals("1920x1080", v.resolution)
        assertEquals("http://nas:32400/thumb/42", v.artUrl)
    }

    @Test
    fun serverDescription() {
        val xml = """<root><URLBase>http://192.168.1.10:32469/</URLBase><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>Plex Media Server: SHIELD</friendlyName><iconList><icon><url>/icon.png</url></icon></iconList>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/ContentDirectory/control</controlURL></service></serviceList></device></root>"""
        val d = Dlna.parseDescription(xml, "http://192.168.1.10:32469/DeviceDescription.xml")
        assertTrue(d.isServer)
        assertEquals("http://192.168.1.10:32469/ContentDirectory/control", d.contentDirectoryUrl)
        assertEquals("http://192.168.1.10:32469/icon.png", d.iconUrl)
        assertEquals("Plex Media Server: SHIELD", d.friendlyName)
    }

    @Test
    fun webDavMultistatus() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response><d:href>/remote.php/dav/files/pat/Videos/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/pat/Videos/Summer%20Trip/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/pat/Videos/clip%201.mp4</d:href><d:propstat><d:prop>
                <d:getcontenttype>video/mp4</d:getcontenttype><d:getcontentlength>5000</d:getcontentlength><d:resourcetype/></d:prop></d:propstat></d:response>
            </d:multistatus>"""
        val list = WebDav.parse(xml, "https://cloud.example/remote.php/dav/files/pat/Videos/")
        assertEquals(2, list.size)
        assertEquals("Summer Trip", list[0].name)
        assertTrue(list[0].isFolder)
        assertEquals("clip 1.mp4", list[1].name)
        assertEquals("https://cloud.example/remote.php/dav/files/pat/Videos/clip%201.mp4", list[1].path)
        assertEquals("video/mp4", list[1].mimeType)
        assertEquals(5000L, list[1].size)
        assertEquals(mapOf("Authorization" to "Basic dTpw"), WebDav.authHeader("u", "p"))
    }

    @Test
    fun rawHttpReadsChunkedBodies() {
        val wire = "HTTP/1.1 207 Multi-Status\r\nTransfer-Encoding: chunked\r\nContent-Type: application/xml\r\n\r\n5\r\nHello\r\n7\r\n, world\r\n0\r\n\r\n"
        val r = RawHttp.readResponse(ByteArrayInputStream(wire.toByteArray()))
        assertEquals(207, r.status)
        assertEquals("Hello, world", r.text)
        val plain = RawHttp.readResponse(ByteArrayInputStream("HTTP/1.0 401 No\r\nContent-Length: 3\r\n\r\nabcdef".toByteArray()))
        assertEquals(401, plain.status)
        assertEquals("abc", plain.text)
    }

    @Test
    fun hlsQualityLevels() {
        val master = """#EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
            360p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud"
            720p.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI="iframes.m3u8"
        """.trimIndent()
        val base = URI("https://tv.example/live/master.m3u8")
        assertTrue(HlsVariants.isMaster(master))
        val v = HlsVariants.parse(master, base)
        assertEquals(listOf("1080p", "720p", "360p"), v.map { it.label })
        assertEquals("https://tv.example/live/720p.m3u8", v[1].url)
        val only720 = HlsVariants.keepOnly(master, base, v[1].url)
        assertTrue(only720.contains("720p.m3u8"))
        assertFalse(only720.contains("1080p.m3u8"))
        assertFalse(only720.contains("360p.m3u8"))
        assertFalse(only720.contains("iframes"))
        assertTrue(only720.contains("#EXT-X-MEDIA:TYPE=AUDIO")) // audio group kept
    }
}
