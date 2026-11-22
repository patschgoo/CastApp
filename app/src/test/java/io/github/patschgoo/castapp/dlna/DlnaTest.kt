package io.github.patschgoo.castapp.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaTest {
    private val samsungDescription = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:sec="http://www.sec.co.kr/dlna">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[TV] Samsung Q80 &amp; Co</friendlyName>
            <manufacturer>Samsung Electronics</manufacturer>
            <modelName>QE55Q80T</modelName>
            <UDN>uuid:1234-abcd</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>upnp/control/AVTransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun parsesDeviceDescription() {
        val d = Dlna.parseDescription(samsungDescription, "http://192.168.1.80:9197/dmr")
        assertEquals("[TV] Samsung Q80 & Co", d.friendlyName)
        assertEquals("QE55Q80T", d.modelName)
        assertEquals("uuid:1234-abcd", d.udn)
        assertTrue(d.isRenderer)
        assertEquals("http://192.168.1.80:9197/upnp/control/RenderingControl1", d.renderingControlUrl)
        assertEquals("http://192.168.1.80:9197/upnp/control/AVTransport1", d.avTransportUrl)
    }

    @Test
    fun parsesSsdpResponse() {
        val h = Dlna.parseSsdp("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.80:9197/dmr\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")
        assertEquals("http://192.168.1.80:9197/dmr", h["location"])
        assertTrue(Dlna.searchMessage().startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(Dlna.searchMessage().endsWith("\r\n\r\n"))
    }

    @Test
    fun buildsSoapAndDidl() {
        val url = "http://192.168.1.5:8090/t/file/0/a&b.mp4"
        val didl = Dlna.didl("Tom & Jerry", url, "video/mp4", live = false, srtUrl = "http://x/sub.srt")
        assertTrue(didl.contains("<dc:title>Tom &amp; Jerry</dc:title>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01;"))
        assertTrue(didl.contains("object.item.videoItem"))
        assertTrue(didl.contains("sec:CaptionInfoEx"))
        val soap = Dlna.soapEnvelope(Dlna.AV_TRANSPORT, "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to didl))
        assertTrue(soap.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(soap.contains("<CurrentURI>http://192.168.1.5:8090/t/file/0/a&amp;b.mp4</CurrentURI>"))
        // The metadata is XML inside XML, so it is escaped once more.
        assertTrue(soap.contains("&lt;DIDL-Lite"))
        assertFalse(soap.substringAfter("<CurrentURIMetaData>").startsWith("<DIDL"))
    }

    @Test
    fun timesAndTags() {
        assertEquals(3_723_500L, Dlna.parseTime("1:02:03.500"))
        assertEquals(0L, Dlna.parseTime("00:00:00"))
        assertNull(Dlna.parseTime("NOT_IMPLEMENTED"))
        assertEquals("01:02:03", Dlna.formatTime(3_723_999))
        val response = "<s:Envelope><s:Body><u:GetTransportInfoResponse><CurrentTransportState>PLAYING</CurrentTransportState></u:GetTransportInfoResponse></s:Body></s:Envelope>"
        assertEquals("PLAYING", Dlna.tag(response, "CurrentTransportState"))
    }
}
