package com.sebasgl1.streamiocast.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Configuración del Cast SDK: usamos el receptor por defecto de Google (Default Media Receiver),
 * que reproduce enlaces HTTP directos; no hace falta registrar una app receptora propia.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        // Sin la notificación propia de Cast: la de la app ya trae los controles (evita duplicadas).
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(null)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
