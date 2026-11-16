package com.sebasgl1.streamiocast.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Un televisor (o reproductor) DLNA de la red que puede recibir vídeo. */
data class DlnaDevice(
    val udn: String,
    val name: String,
    val manufacturer: String,
    val model: String,
    /** URL de control del servicio AVTransport (cargar, play, pausa, seek). */
    val avTransportUrl: String,
) {
    val isSamsung: Boolean
        get() = manufacturer.contains("samsung", ignoreCase = true)
}

const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

val SEARCH_TARGETS = listOf(
    "urn:schemas-upnp-org:device:MediaRenderer:1",
    AV_TRANSPORT,
)

fun mSearch(searchTarget: String): String =
    "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: $searchTarget\r\n\r\n"

/** La cabecera LOCATION de una respuesta SSDP: dónde está la descripción del dispositivo. */
fun parseSsdpLocation(response: String): String? = response.lineSequence()
    .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
    ?.substringAfter(':')
    ?.trim()
    ?.takeIf { it.startsWith("http") }

private fun parseXml(xml: String): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isExpandEntityReferences = false
    // Datos de la red local: sin DTD ni entidades externas.
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
}

private fun Document.firstText(tag: String): String? =
    getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

/** Lee la descripción UPnP del dispositivo y encuentra su servicio AVTransport. */
fun parseDeviceDescription(xml: String, location: String): DlnaDevice? {
    val doc = runCatching { parseXml(xml) }.getOrNull() ?: return null
    val services = doc.getElementsByTagName("service")
    var control: String? = null
    for (i in 0 until services.length) {
        val service = services.item(i) as? Element ?: continue
        val type = service.getElementsByTagName("serviceType").item(0)?.textContent.orEmpty()
        if (type.contains("AVTransport")) {
            control = service.getElementsByTagName("controlURL").item(0)?.textContent?.trim()
            break
        }
    }
    val controlPath = control ?: return null
    val base = doc.firstText("URLBase") ?: location
    val url = runCatching { URL(URL(base), controlPath).toString() }.getOrNull() ?: return null
    return DlnaDevice(
        udn = doc.firstText("UDN") ?: location,
        name = doc.firstText("friendlyName") ?: "Televisor",
        manufacturer = doc.firstText("manufacturer").orEmpty(),
        model = doc.firstText("modelName").orEmpty(),
        avTransportUrl = url,
    )
}

fun xmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

fun soapEnvelope(action: String, args: List<Pair<String, String>>): String = buildString {
    append("""<?xml version="1.0" encoding="utf-8"?>""")
    append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
    append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
    append("<u:").append(action).append(" xmlns:u=\"").append(AV_TRANSPORT).append("\">")
    for ((name, value) in args) {
        append('<').append(name).append('>').append(xmlEscape(value)).append("</").append(name).append('>')
    }
    append("</u:").append(action).append("></s:Body></s:Envelope>")
}

private const val DLNA_FLAGS = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

/** Vídeo convertido en vivo: no se puede saltar por bytes (OP=00) y está convertido (CI=1). */
private const val DLNA_LIVE_FLAGS = "DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000"

/**
 * Metadatos DIDL-Lite del vídeo. Para televisores Samsung incluye los subtítulos (SRT) con sus
 * etiquetas propias ("sec:CaptionInfoEx"), que es como los muestra su reproductor.
 */
fun didlLite(
    title: String,
    url: String,
    mime: String,
    subtitleUrl: String?,
    live: Boolean = false,
    durationMs: Long = 0,
): String = buildString {
    append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
    append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
    append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
    append("""xmlns:sec="http://www.sec.co.kr/">""")
    append("""<item id="0" parentID="-1" restricted="1">""")
    append("<dc:title>").append(xmlEscape(title)).append("</dc:title>")
    append("<upnp:class>object.item.videoItem.movie</upnp:class>")
    append("<res protocolInfo=\"http-get:*:").append(mime).append(':')
        .append(if (live) DLNA_LIVE_FLAGS else DLNA_FLAGS).append('"')
    // En vivo el TV no puede medir la duración: se la decimos nosotros.
    if (live && durationMs > 0) append(" duration=\"").append(formatDlnaTime(durationMs)).append(".000\"")
    append(">")
    append(xmlEscape(url)).append("</res>")
    if (subtitleUrl != null) {
        val sub = xmlEscape(subtitleUrl)
        append("<res protocolInfo=\"http-get:*:text/srt:*\">").append(sub).append("</res>")
        append("<sec:CaptionInfoEx sec:type=\"srt\">").append(sub).append("</sec:CaptionInfoEx>")
        append("<sec:CaptionInfo sec:type=\"srt\">").append(sub).append("</sec:CaptionInfo>")
    }
    append("</item></DIDL-Lite>")
}

/** Tipo MIME que entienden los televisores (Samsung llama así a los MKV). */
fun dlnaMime(fileName: String, samsung: Boolean): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mkv" -> if (samsung) "video/x-mkv" else "video/x-matroska"
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "ts", "m2ts" -> "video/mp2t"
    else -> "video/mp4"
}

/** Cabeceras DLNA que algunos televisores exigen para reproducir y adelantar. */
val DLNA_VIDEO_HEADERS = mapOf(
    "transferMode.dlna.org" to "Streaming",
    "contentFeatures.dlna.org" to DLNA_FLAGS,
)

val DLNA_LIVE_HEADERS = mapOf(
    "transferMode.dlna.org" to "Streaming",
    "contentFeatures.dlna.org" to DLNA_LIVE_FLAGS,
)

fun formatDlnaTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
}

/** "0:12:34", "00:12:34.500" → milisegundos; "NOT_IMPLEMENTED" → null. */
fun parseDlnaTime(text: String?): Long? {
    val parts = text?.trim()?.split(':') ?: return null
    if (parts.size != 3) return null
    val h = parts[0].toLongOrNull() ?: return null
    val m = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toDoubleOrNull() ?: return null
    return (h * 3600 + m * 60) * 1000 + (seconds * 1000).toLong()
}

/** Valor de una etiqueta en la respuesta SOAP ("RelTime", "CurrentTransportState"...). */
fun soapValue(xml: String, tag: String): String? =
    runCatching { parseXml(xml).firstText(tag) }.getOrNull()

/** Mensaje de error de una respuesta SOAP fallida. */
fun soapFault(xml: String): String? =
    soapValue(xml, "errorDescription") ?: soapValue(xml, "faultstring")
