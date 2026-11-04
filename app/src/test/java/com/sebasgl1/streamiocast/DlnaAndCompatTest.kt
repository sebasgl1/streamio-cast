package com.sebasgl1.streamiocast

import com.sebasgl1.streamiocast.dlna.didlLite
import com.sebasgl1.streamiocast.dlna.dlnaMime
import com.sebasgl1.streamiocast.dlna.formatDlnaTime
import com.sebasgl1.streamiocast.dlna.parseDeviceDescription
import com.sebasgl1.streamiocast.dlna.parseDlnaTime
import com.sebasgl1.streamiocast.dlna.parseSsdpLocation
import com.sebasgl1.streamiocast.dlna.soapEnvelope
import com.sebasgl1.streamiocast.dlna.soapFault
import com.sebasgl1.streamiocast.dlna.soapValue
import com.sebasgl1.streamiocast.player.VideoFacts
import com.sebasgl1.streamiocast.player.checkCastCompat
import com.sebasgl1.streamiocast.subtitles.SubCue
import com.sebasgl1.streamiocast.subtitles.toSrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaAndCompatTest {

    private val samsungDescription = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:sec="http://www.sec.co.kr/dlna">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[TV] Samsung 6 Series (43)</friendlyName>
            <manufacturer>Samsung Electronics</manufacturer>
            <modelName>UN43MU6100</modelName>
            <UDN>uuid:abc-123</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/upnp/control/AVTransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>"""

    @Test
    fun ssdpLocation() {
        val response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.20:9197/dmr\r\nST: x\r\n\r\n"
        assertEquals("http://192.168.1.20:9197/dmr", parseSsdpLocation(response))
        assertNull(parseSsdpLocation("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test
    fun readsSamsungDescription() {
        val device = parseDeviceDescription(samsungDescription, "http://192.168.1.20:9197/dmr")!!
        assertEquals("[TV] Samsung 6 Series (43)", device.name)
        assertEquals("UN43MU6100", device.model)
        assertEquals("uuid:abc-123", device.udn)
        assertTrue(device.isSamsung)
        assertEquals("http://192.168.1.20:9197/upnp/control/AVTransport1", device.avTransportUrl)
    }

    @Test
    fun deviceWithoutAvTransportIsIgnored() {
        assertNull(parseDeviceDescription("<root><device><friendlyName>Parlante</friendlyName></device></root>", "http://x/"))
        assertNull(parseDeviceDescription("no es xml", "http://x/"))
    }

    @Test
    fun soapAndDidl() {
        val didl = didlLite("Mi peli & más", "http://192.168.1.5:4000/stream/t/a b.mkv", "video/x-mkv", "http://192.168.1.5:4000/s.srt")
        assertTrue(didl.contains("<dc:title>Mi peli &amp; más</dc:title>"))
        assertTrue(didl.contains("http-get:*:video/x-mkv:DLNA.ORG_OP=01"))
        assertTrue(didl.contains("<sec:CaptionInfoEx sec:type=\"srt\">http://192.168.1.5:4000/s.srt</sec:CaptionInfoEx>"))
        assertFalse(didlLite("x", "http://u", "video/mp4", null).contains("CaptionInfo"))

        val envelope = soapEnvelope("SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURIMetaData" to didl))
        assertTrue(envelope.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        // El DIDL va escapado dentro del SOAP (doble escape del &).
        assertTrue(envelope.contains("Mi peli &amp;amp; más"))
        assertTrue(envelope.contains("&lt;DIDL-Lite"))
        // Y al leerlo de vuelta, sale el DIDL original.
        assertEquals(didl, soapValue(envelope, "CurrentURIMetaData"))
    }

    @Test
    fun soapResponses() {
        val ok = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <Track>1</Track><TrackDuration>1:30:00</TrackDuration><RelTime>0:12:34</RelTime>
            </u:GetPositionInfoResponse></s:Body></s:Envelope>"""
        assertEquals("0:12:34", soapValue(ok, "RelTime"))
        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultstring>UPnPError</faultstring><detail><UPnPError><errorCode>714</errorCode>
            <errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        assertEquals("Illegal MIME-type", soapFault(fault))
    }

    @Test
    fun times() {
        assertEquals("0:12:34", formatDlnaTime(754_000))
        assertEquals("1:30:00", formatDlnaTime(5_400_000))
        assertEquals(754_000L, parseDlnaTime("0:12:34"))
        assertEquals(754_500L, parseDlnaTime("00:12:34.500"))
        assertNull(parseDlnaTime("NOT_IMPLEMENTED"))
    }

    @Test
    fun mimes() {
        assertEquals("video/x-mkv", dlnaMime("a.mkv", samsung = true))
        assertEquals("video/x-matroska", dlnaMime("a.mkv", samsung = false))
        assertEquals("video/mp4", dlnaMime("a.mp4", samsung = true))
    }

    @Test
    fun srtOutput() {
        val srt = toSrt(listOf(SubCue(1000, 2500, "Hola"), SubCue(0, 300, "x")), offsetMs = -500)
        assertEquals("1\n00:00:00,500 --> 00:00:02,000\nHola\n\n", srt)
    }

    @Test
    fun castCompatFromRealTracks() {
        val ok = checkCastCompat(VideoFacts("video/avc", 1920, 1080, "avc1.640028", 23.976f), "audio/mp4a-latm")
        assertTrue(ok.blocking.isEmpty() && ok.warnings.isEmpty())

        val hevc4k = checkCastCompat(VideoFacts("video/hevc", 3840, 2160, "hvc1.2.4.L150", 24f), "audio/mp4a-latm")
        assertEquals(2, hevc4k.blocking.size)

        val hi10 = checkCastCompat(VideoFacts("video/avc", 1920, 1080, "avc1.6E0028", 24f), null)
        assertTrue(hi10.blocking.single().contains("10 bits"))

        val ac3 = checkCastCompat(VideoFacts("video/avc", 1280, 720, null, 25f), "audio/ac3")
        assertTrue(ac3.blocking.isEmpty())
        assertTrue(ac3.warnings.single().contains("AC3"))

        val fps60 = checkCastCompat(VideoFacts("video/avc", 1920, 1080, null, 59.94f), "audio/mpeg")
        assertTrue(fps60.warnings.single().contains("59 fps"))

        val unknown = checkCastCompat(null, null)
        assertTrue(unknown.blocking.isEmpty() && unknown.warnings.isEmpty())
    }
}
