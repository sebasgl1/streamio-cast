package com.sebasgl1.streamiocast

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Guarda por qué se cerró la app para mostrarlo en el siguiente arranque:
 *  - Errores de Java/Kotlin: los captura un manejador y los escribe en un archivo.
 *  - Cierres nativos (libtorrent), bloqueos (ANR) o falta de memoria: los consulta al sistema
 *    (Android 11+), que guarda el historial de salidas de cada app.
 */
object CrashReporter {
    private const val FILE = "ultimo_error.txt"
    private const val PREFS = "crash"
    private const val KEY_SEEN = "exitSeen"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                file.writeText(
                    "== Error de Java/Kotlin ==\n" +
                        "Versión: ${versionName(context)}\n" +
                        "Fecha: ${Date()}\n" +
                        "Hilo: ${thread.name}\n\n" +
                        trace,
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Informe pendiente de mostrar, o null si la app no se cerró mal desde la última vez. */
    fun pendingReport(context: Context): String? {
        val parts = mutableListOf<String>()
        val file = File(context.filesDir, FILE)
        val hasJavaCrash = file.exists()
        if (hasJavaCrash) runCatching { parts += file.readText() }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { systemExit(context, skipJavaCrash = hasJavaCrash) }.getOrNull()?.let { parts += it }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")?.take(12_000)
    }

    /** El usuario ya vio el informe: no volver a mostrarlo. */
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val latest = recentExits(context).maxOfOrNull { it.timestamp } ?: return@runCatching
                prefs(context).edit().putLong(KEY_SEEN, latest).apply()
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @RequiresApi(30)
    private fun recentExits(context: Context): List<ApplicationExitInfo> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(null, 0, 10)
    }

    @RequiresApi(30)
    private fun systemExit(context: Context, skipJavaCrash: Boolean): String? {
        val seen = prefs(context).getLong(KEY_SEEN, 0)
        val exit = recentExits(context)
            .filter { it.timestamp > seen }
            .firstOrNull {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_ANR ||
                    it.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    (it.reason == ApplicationExitInfo.REASON_CRASH && !skipJavaCrash)
            } ?: return null

        val kind = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Cierre nativo (código C/C++, por ejemplo libtorrent)"
            ApplicationExitInfo.REASON_ANR -> "La app dejó de responder (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "El sistema cerró la app por falta de memoria"
            else -> "Error de Java/Kotlin"
        }
        val trace = runCatching { exit.traceInputStream?.use { readableText(it.readBytes()) } }.getOrNull()
        return buildString {
            appendLine("== $kind ==")
            appendLine("Versión: ${versionName(context)}")
            appendLine("Fecha: ${Date(exit.timestamp)}")
            exit.description?.let { appendLine("Descripción: $it") }
            if (!trace.isNullOrBlank()) {
                appendLine()
                append(trace)
            }
        }
    }

    /** El informe nativo viene en binario: nos quedamos con los fragmentos de texto legibles. */
    private fun readableText(bytes: ByteArray): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 5) out.append(run).append('\n')
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126 || c == 10) run.append(c.toChar()) else flush()
            if (out.length > 9_000) break
        }
        flush()
        return out.toString()
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
