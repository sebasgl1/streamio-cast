package com.sebasgl1.streamiocast.cast

import android.content.Context
import com.sebasgl1.streamiocast.data.AudioCodec
import com.sebasgl1.streamiocast.data.CastProfile
import com.sebasgl1.streamiocast.data.TvProfile
import com.sebasgl1.streamiocast.data.Verified
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.sebasgl1.streamiocast.data.castProfileFor
import com.sebasgl1.streamiocast.data.tvProfileFor
import com.sebasgl1.streamiocast.dlna.DlnaDevice

/** El aparato al que se va a transmitir, elegido desde cualquier pantalla. */
sealed interface TargetDevice {
    val name: String

    /** Identifica el modelo, para recordar qué audios sonaron mudos en él. */
    val key: String

    data class Chromecast(override val name: String, val model: String?) : TargetDevice {
        override val key: String get() = "cast:${model?.ifBlank { null } ?: name}"
    }

    data class Tv(val device: DlnaDevice) : TargetDevice {
        override val name: String get() = device.name
        override val key: String get() = "dlna:${device.model.ifBlank { device.udn }}"
    }
}

/** Lo que la app aprendió con el botón "No tiene sonido": audios mudos por modelo de aparato. */
class CompatMemory(context: Context) {
    private val prefs = context.getSharedPreferences("compat", Context.MODE_PRIVATE)

    fun muted(key: String): Set<AudioCodec> =
        prefs.getStringSet(key, emptySet()).orEmpty()
            .mapNotNull { name -> runCatching { AudioCodec.valueOf(name) }.getOrNull() }
            .toSet()

    private val verifiedPrefs = context.getSharedPreferences("verified", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)

    /** Sube cada vez que se aprende algo (para que la lista de streams se recalcule). */
    val changes: StateFlow<Int> = _changes

    /** Cómo le fue a este stream en la revisión real en este aparato, si ya se probó. */
    fun verified(deviceKey: String, streamKey: String): Verified? {
        val value = verifiedPrefs.getString("$deviceKey|$streamKey", null) ?: return null
        return Verified(value.removePrefix("BAD:").takeIf { value.startsWith("BAD:") })
    }

    fun markVerified(deviceKey: String, streamKey: String, problem: String?) {
        verifiedPrefs.edit().putString("$deviceKey|$streamKey", problem?.let { "BAD:$it" } ?: "OK").apply()
        _changes.value += 1
    }

    fun markMuted(key: String, codec: AudioCodec) {
        val updated = prefs.getStringSet(key, emptySet()).orEmpty() + codec.name
        prefs.edit().putStringSet(key, updated).apply()
        _changes.value += 1
    }

    fun forget(key: String) {
        prefs.edit().remove(key).apply()
        // También lo revisado en ese aparato.
        val e = verifiedPrefs.edit()
        verifiedPrefs.all.keys.filter { it.startsWith("$key|") }.forEach { e.remove(it) }
        e.apply()
        _changes.value += 1
    }

    fun tvProfile(device: TargetDevice.Tv?): TvProfile {
        if (device == null) return TvProfile.GENERIC
        val d = device.device
        return tvProfileFor(d.manufacturer, d.model, d.name, muted(device.key))
    }

    fun castProfile(device: TargetDevice.Chromecast?): CastProfile {
        if (device == null) return CastProfile.GEN2
        return castProfileFor(device.model, muted(device.key))
    }
}
