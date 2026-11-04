package com.sebasgl1.streamiocast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class RangeBytes(val bytes: ByteArray, val total: Long?)

class Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Respuesta vacía")
        }
    }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.bytes() ?: throw IOException("Respuesta vacía")
        }
    }

    /**
     * Pide un rango de bytes ("0-65535"). Devuelve null si el servidor no soporta Range,
     * para no terminar descargando el archivo entero.
     */
    suspend fun getRange(url: String, range: String): RangeBytes? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Range", "bytes=$range").build()
        client.newCall(request).execute().use { response ->
            if (response.code != 206) return@use null
            val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            val bytes = response.body?.bytes() ?: return@use null
            RangeBytes(bytes, total)
        }
    }

    suspend fun postJson(url: String, json: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Respuesta vacía")
        }
    }
}
