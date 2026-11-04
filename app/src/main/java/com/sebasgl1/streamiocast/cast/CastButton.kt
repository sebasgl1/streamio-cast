package com.sebasgl1.streamiocast.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/** El botón estándar de Cast: muestra los Chromecast de la red y conecta con el elegido. */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            MediaRouteButton(ctx).apply {
                runCatching { CastButtonFactory.setUpMediaRouteButton(ctx, this) }
            }
        },
        modifier = modifier,
    )
}
