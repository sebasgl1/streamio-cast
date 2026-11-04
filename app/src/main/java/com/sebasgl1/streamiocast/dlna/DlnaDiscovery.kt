package com.sebasgl1.streamiocast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Busca televisores DLNA en la red Wi-Fi (SSDP: un "¿quién reproduce vídeo?" por multicast). */
object DlnaDiscovery {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun discover(context: Context, timeoutMs: Long = 4_000): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        // Android descarta el multicast si no se pide este permiso temporal.
        val lock = runCatching {
            wifi?.createMulticastLock("streamiocast:ssdp")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) {
                    for (target in SEARCH_TARGETS) {
                        val message = mSearch(target).toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(message, message.size, group, 1900))
                    }
                }
                val buffer = ByteArray(8192)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    parseSsdpLocation(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let { locations += it }
                }
            }
        } catch (_: Exception) {
            // Sin Wi-Fi o sin permiso de red: simplemente no hay televisores.
        } finally {
            runCatching { lock?.release() }
        }

        locations.mapNotNull { location ->
            runCatching {
                val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() }
                    ?: return@runCatching null
                parseDeviceDescription(xml, location)
            }.getOrNull()
        }.distinctBy { it.udn }
    }
}
