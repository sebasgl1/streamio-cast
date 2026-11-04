package com.sebasgl1.streamiocast.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

private val LAN_PREFIXES = listOf("wlan", "swlan", "ap", "eth", "rndis")

/** ¿Se está usando datos móviles (y no Wi-Fi)? */
fun isOnMobileData(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

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
