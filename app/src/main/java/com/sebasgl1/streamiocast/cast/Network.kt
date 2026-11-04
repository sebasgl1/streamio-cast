package com.sebasgl1.streamiocast.cast

import java.net.Inet4Address
import java.net.NetworkInterface

private val LAN_PREFIXES = listOf("wlan", "swlan", "ap", "eth", "rndis")

/**
 * IP del celular en la red local (Wi-Fi), para que el Chromecast pueda pedirle el vídeo.
 * Ignora los datos móviles y las VPN: el Chromecast no los alcanza.
 */
fun lanAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { iface -> iface.isUp && !iface.isLoopback && LAN_PREFIXES.any { iface.name.startsWith(it) } }
        .sortedBy { iface -> LAN_PREFIXES.indexOfFirst { iface.name.startsWith(it) } }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
