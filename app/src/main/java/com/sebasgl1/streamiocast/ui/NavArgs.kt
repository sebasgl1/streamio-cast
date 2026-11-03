package com.sebasgl1.streamiocast.ui

import android.util.Base64

/**
 * Los títulos pueden traer "%", "/" o tildes que rompen las rutas de navegación;
 * en Base64 seguro para URL viajan sin problemas.
 */
private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

fun encodeArg(text: String): String =
    Base64.encodeToString(text.ifBlank { "Streams" }.toByteArray(Charsets.UTF_8), FLAGS)

fun decodeArg(text: String): String =
    runCatching { String(Base64.decode(text, FLAGS), Charsets.UTF_8) }.getOrDefault("Streams")
