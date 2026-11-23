@file:OptIn(ExperimentalMaterial3Api::class)

package com.sebasgl1.streamiocast.player

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Slider
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.sebasgl1.streamiocast.cast.TransmitButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.sebasgl1.streamiocast.subtitles.languageName
import com.sebasgl1.streamiocast.subtitles.normalizeLang
import com.sebasgl1.streamiocast.torrent.TorrentStats
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.annotation.OptIn as AndroidOptIn

private val SIZE_SCALE = floatArrayOf(0.8f, 1f, 1.3f)
private val SIZE_SP = intArrayOf(16, 20, 26)

@AndroidOptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, model: PlayerViewModel = viewModel()) {
    val current by model.session.collectAsStateWithLifecycle()
    val vm = current
    if (vm == null) {
        NoSession(onBack)
        return
    }
    // Mientras está a la vista, la sesión sabe que alguien mira el celular.
    DisposableEffect(vm) {
        vm.attached = true
        onDispose { vm.attached = false }
    }
    // Salir: si se está transmitiendo, el TV sigue y vuelves a la app; si no, se cierra todo.
    val close: () -> Unit = {
        if (!vm.isCasting) model.close()
        onBack()
    }
    BackHandler(onBack = close)
    val ui by vm.ui.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    val castUi = ui.cast
    ImmersiveMode()

    // Android 13+: permiso para la notificación que mantiene viva la descarga.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val phase = ui.phase) {
            PlayerPhase.Playing -> if (castUi != null) {
                CastPanel(
                    ui = ui,
                    cast = castUi,
                    vm = vm,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Una vista nueva por cada sesión (cada episodio trae su propio reproductor): si no,
                // la vista seguía conectada al reproductor del episodio anterior, ya liberado, y
                // quedaba congelado su último cuadro mientras sonaba el audio del siguiente.
                key(vm) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = vm.player
                            keepScreenOn = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            // Los subtítulos se eligen en nuestro menú (incluye los de los addons).
                            setShowSubtitleButton(false)
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { visibility ->
                                    controlsVisible = visibility == View.VISIBLE
                                },
                            )
                        }
                    },
                    update = { view ->
                        if (view.player !== vm.player) view.player = vm.player
                        view.subtitleView?.setFractionalTextSize(
                            SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SIZE_SCALE[ui.subtitleSize.coerceIn(0, 2)],
                        )
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                }

                if (ui.externalSelected != null) {
                    SubtitleOverlay(
                        vm = vm,
                        size = ui.subtitleSize,
                        raised = controlsVisible,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                val stats = ui.stats
                if (ui.buffering && stats != null && ui.startWait == null) {
                    Text(
                        text = (ui.rebufferSec?.let { "Llenando $it s de buffer" } ?: "Cargando") +
                            " · ${statsLine(stats)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 96.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            is PlayerPhase.Preparing -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
                Text(phase.message, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                if (ui.isTorrent) {
                    Text(
                        text = "La primera vez puede tardar: hay que encontrar peers y bajar el inicio del vídeo.",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            is PlayerPhase.Failed -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(phase.message, color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = close) { Text("Volver a los streams") }
            }
        }

        if (controlsVisible || castUi != null || ui.phase !is PlayerPhase.Playing) {
            TopBar(
                title = ui.title,
                showTracks = ui.phase is PlayerPhase.Playing,
                onBack = close,
                onTracks = { menuOpen = true },
                modifier = Modifier.align(Alignment.TopCenter),
                transmit = {
                    TransmitButton(
                        connectedTo = castUi?.device,
                        onChromecast = vm::prepareForChromecast,
                        onDlna = vm::connectDlna,
                        onStop = vm::stopCasting,
                    )
                },
            )
        }

        ui.resumedFromMs?.let { at ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Retomando en ${formatTime(at)}", color = Color.White)
                TextButton(onClick = vm::restartFromBeginning) { Text("Desde el inicio") }
            }
        }

        ui.startWait?.let { wait ->
            if (ui.phase is PlayerPhase.Playing && castUi == null) {
                StartWaitCard(
                    wait = wait,
                    stats = ui.stats,
                    casting = castUi != null,
                    onStart = vm::startNow,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
                )
            }
        }

        // Siguiente episodio: aparece en el último minuto y cuenta 10 s al terminar.
        var nearEnd by remember(vm) { mutableStateOf(false) }
        LaunchedEffect(vm, ui.nextUp) {
            while (ui.nextUp != null) {
                nearEnd = vm.nearEnd()
                delay(1000)
            }
        }
        val nextLabel = ui.nextUp
        if (castUi == null && nextLabel != null && (nearEnd || ui.nextCountdown != null || ui.nextLoading)) {
            NextUpCard(
                label = nextLabel,
                countdown = ui.nextCountdown,
                loading = ui.nextLoading,
                onPlay = vm::playNext,
                onCancel = vm::cancelNext,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 110.dp),
            )
        }

        ui.castMessage?.let { message ->
            // Los avisos se van solos a los 15 s (o al tocarlos).
            LaunchedEffect(message) {
                delay(15_000)
                vm.dismissMessage(message)
            }
            if (castUi == null) {
                Text(
                    text = message,
                    color = Color(0xFFFFC857),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .clickable { vm.dismissMessage(message) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (menuOpen) {
        TracksSheet(ui = ui, vm = vm, onDismiss = { menuOpen = false })
    }
}

@Composable
private fun TopBar(
    title: String,
    showTracks: Boolean,
    onBack: () -> Unit,
    onTracks: () -> Unit,
    modifier: Modifier = Modifier,
    transmit: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        transmit()
        if (showTracks) {
            TextButton(
                onClick = onTracks,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
            ) {
                Text("Audio y subtítulos", color = Color.White)
            }
        }
    }
}

/** Dibuja los subtítulos externos (los de los addons), con retraso y tamaño ajustables. */
@Composable
private fun SubtitleOverlay(vm: PlaybackSession, size: Int, raised: Boolean, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(vm) {
        while (true) {
            text = vm.currentCueText()
            delay(100)
        }
    }
    if (text.isNotEmpty()) {
        Text(
            text = text,
            color = Color.White,
            textAlign = TextAlign.Center,
            fontSize = SIZE_SP[size.coerceIn(0, 2)].sp,
            style = TextStyle(shadow = Shadow(Color.Black, Offset(2f, 2f), 4f)),
            modifier = modifier
                .padding(start = 24.dp, end = 24.dp, bottom = if (raised) 120.dp else 36.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TracksSheet(ui: PlayerUi, vm: PlaybackSession, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pref = remember { vm.preferredSubtitleLang() }
    val langs = remember(ui.externalSubs) {
        ui.externalSubs
            .map { normalizeLang(it.lang) ?: it.lang }
            .distinct()
            .sortedWith(compareBy<String>({ it != pref }, { languageName(it, it) }))
    }
    val chosenLang = ui.externalSubs.firstOrNull { it.id == ui.externalSelected }?.let { normalizeLang(it.lang) ?: it.lang }
    var langFilter by remember(langs) { mutableStateOf(chosenLang?.takeIf { it in langs } ?: langs.firstOrNull()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            item { SectionTitle("Audio") }
            if (ui.audioTracks.isEmpty()) {
                item { Hint("Este vídeo no trae varias pistas de audio.") }
            }
            items(ui.audioTracks) { track ->
                val label = if (track.supported) track.label else "${track.label} · no compatible con este celular"
                OptionRow(label, track.selected) { vm.selectAudio(track.id) }
            }

            item { SectionTitle("Subtítulos") }
            item {
                OptionRow(
                    label = "Desactivados",
                    selected = ui.externalSelected == null && ui.textTracks.none { it.selected },
                ) { vm.disableSubtitles() }
            }
            items(ui.textTracks) { track ->
                OptionRow("${track.label} · del archivo", track.selected) { vm.selectEmbeddedText(track.id) }
            }

            if (ui.subsLoading) {
                item {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Buscando subtítulos…")
                    }
                }
            }

            if (langs.isNotEmpty()) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        items(langs) { code ->
                            FilterChip(
                                selected = code == langFilter,
                                onClick = { langFilter = code },
                                label = { Text(languageName(code, code)) },
                            )
                        }
                    }
                }
                val shown = ui.externalSubs.filter { (normalizeLang(it.lang) ?: it.lang) == langFilter }
                itemsIndexed(shown) { index, sub ->
                    val label = buildString {
                        append(languageName(sub.lang, sub.lang)).append(" #").append(index + 1)
                        if (sub.label.isNotBlank()) append(" · ").append(sub.label)
                        append(" · ").append(sub.source)
                    }
                    OptionRow(label, ui.externalSelected == sub.id) { vm.selectExternal(sub) }
                }
            } else if (!ui.subsLoading) {
                item { Hint("No se encontraron subtítulos en los addons para este vídeo.") }
            }

            ui.subtitleError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
            }

            item { SectionTitle("Ajustes de subtítulos") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Retraso", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.shiftSubtitles(-500) }) { Text("−0,5 s") }
                    Text(
                        text = formatOffset(ui.subtitleOffsetMs),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(72.dp),
                    )
                    OutlinedButton(onClick = { vm.shiftSubtitles(500) }) { Text("+0,5 s") }
                }
            }
            item { Hint("Si el subtítulo sale antes que la voz, súmale retraso. Aplica a los subtítulos externos.") }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    listOf("Pequeño", "Normal", "Grande").forEachIndexed { i, name ->
                        FilterChip(
                            selected = ui.subtitleSize == i,
                            onClick = { vm.setSubtitleSize(i) },
                            label = { Text(name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Mientras se llena el buffer: cuánto hay, cuánto falta y el botón para arrancar ya. */
@Composable
private fun StartWaitCard(
    wait: StartWait,
    stats: TorrentStats?,
    casting: Boolean,
    onStart: () -> Unit,
    modifier: Modifier,
) {
    val have = (wait.bufferedMs / 1000).toInt()
    val goal = (wait.targetMs / 1000).toInt()
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when {
                wait.ready && wait.manual -> "Buffer listo: $goal s"
                wait.ready -> "Buffer listo, arrancando…"
                else -> "Llenando el buffer: $have de $goal s"
            },
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
        )
        LinearProgressIndicator(
            progress = { if (goal > 0) (have.toFloat() / goal).coerceIn(0f, 1f) else 1f },
            modifier = Modifier.fillMaxWidth(),
        )
        val detail = listOfNotNull(
            wait.etaSec?.takeIf { !wait.ready }?.let { "listo en ~$it s" },
            stats?.let { statsLine(it) },
        ).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(detail, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onStart, modifier = Modifier.align(Alignment.End)) {
            Text(
                when {
                    casting && wait.ready -> "Reproducir en el TV"
                    wait.ready -> "Reproducir"
                    else -> "Reproducir ya"
                },
            )
        }
    }
}

@Composable
private fun NextUpCard(
    label: String,
    countdown: Int?,
    loading: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .width(280.dp)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Siguiente episodio", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlay, enabled = !loading) {
                Text(
                    when {
                        loading -> "Buscando stream…"
                        countdown != null -> "Ver ahora ($countdown)"
                        else -> "Ver ahora"
                    },
                )
            }
            if (countdown != null) {
                TextButton(onClick = onCancel) { Text("Cancelar", color = Color.White) }
            }
        }
    }
}

/** La sesión ya no existe (por ejemplo, la app se reinició): hay que volver a elegir el stream. */
@Composable
private fun NoSession(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Esta reproducción ya terminó. Vuelve a elegir el stream; seguirá donde ibas.",
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onBack) { Text("Volver") }
        }
    }
}

/** Control remoto del Chromecast: el vídeo se ve en el televisor y aquí se maneja. */
@Composable
private fun CastPanel(ui: PlayerUi, cast: CastUi, vm: PlaybackSession, modifier: Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var nearEnd by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(vm, ui.nextUp) {
        while (ui.nextUp != null) {
            nearEnd = vm.nearEnd()
            delay(1000)
        }
    }
    // Se desplaza: con avisos largos (o el celular de lado) los botones no quedan fuera de la pantalla.
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Transmitiendo a ${cast.device}",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(cast.state, color = Color.White.copy(alpha = 0.75f))
        ui.compatOk?.let { ok ->
            Text(
                text = "✓ $ok",
                color = Color(0xFF7BD88F),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        // La revisión del archivo real encontró algo que este aparato no reproduce.
        ui.castBlocked?.let { reason ->
            Text(
                text = reason,
                color = Color(0xFFFFC857),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            val offer = ui.convertOffer
            if (offer != null) {
                // Se puede arreglar en el celular: se dice exactamente qué se haría y qué cuesta.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Puedo arreglarlo en el celular",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(offer.what, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                    Text(offer.cost, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::convertAndCast, modifier = Modifier.align(Alignment.End)) {
                        Text("Convertir en el celular")
                    }
                }
            } else {
                Text(
                    text = "Elige otro stream marcado en verde para ${cast.device}, o intenta igual.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = vm::forceCast) { Text("Intentar igual", color = Color.White) }
                OutlinedButton(onClick = vm::stopCasting) { Text("Ver en el celular", color = Color.White) }
            }
        }
        ui.startWait?.let { wait ->
            StartWaitCard(wait = wait, stats = ui.stats, casting = true, onStart = vm::startNow, modifier = Modifier)
        }
        ui.converting?.let { status ->
            Text(
                text = "⚙ $status",
                color = Color(0xFF8AB4F8),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        ui.stats?.let { stats ->
            Text(
                text = statsLine(stats),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Sin nada cargado en el aparato (revisando, "No compatible"…) los controles no harían nada.
        val controls = ui.castBlocked == null && ui.startWait == null
        if (controls && cast.durationMs > 0) {
            Slider(
                value = (dragging ?: cast.positionMs.toFloat()).coerceIn(0f, cast.durationMs.toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.castSeek(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..cast.durationMs.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime((dragging ?: cast.positionMs.toFloat()).toLong()), color = Color.White)
                Spacer(Modifier.weight(1f))
                Text(formatTime(cast.durationMs), color = Color.White)
            }
        }
        if (controls) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { vm.castSkip(-10_000) }) { Text("−10 s", color = Color.White) }
                Button(onClick = vm::castTogglePlay) { Text(if (cast.playing) "Pausar" else "Reproducir") }
                OutlinedButton(onClick = { vm.castSkip(30_000) }) { Text("+30 s", color = Color.White) }
            }
        }
        val nextLabel = ui.nextUp
        if (nextLabel != null && (nearEnd || ui.nextCountdown != null || ui.nextLoading)) {
            NextUpCard(
                label = nextLabel,
                countdown = ui.nextCountdown,
                loading = ui.nextLoading,
                onPlay = vm::playNext,
                onCancel = vm::cancelNext,
                modifier = Modifier,
            )
        }
        ui.castMessage?.let { message ->
            Text(
                text = message,
                color = Color(0xFFFFC857),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable { vm.dismissMessage(message) }
                    .padding(4.dp),
            )
        }
        // Si se ve pero no se oye: la app aprende que ese audio no suena en este aparato.
        if (controls && !ui.noSoundReported) {
            OutlinedButton(onClick = vm::reportNoSound) { Text("🔇 No tiene sonido", color = Color.White) }
        }
        TextButton(onClick = vm::stopCasting) {
            Text("Dejar de transmitir y seguir en el celular", color = Color.White)
        }
        Text(
            text = "Puedes volver a la app con \"atrás\", usar otras apps o apagar la pantalla: el TV sigue. " +
                "Para terminar, usa \"Dejar de transmitir\" o Detener en la notificación.",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatOffset(ms: Long): String {
    val sign = if (ms > 0) "+" else ""
    return sign + String.format(Locale.forLanguageTag("es"), "%.1f s", ms / 1000.0)
}

private fun statsLine(s: TorrentStats): String =
    "${s.peers} peers · ${formatRate(s.downloadRate)} · ${(s.progress * 100).toInt()}%"

private fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    else -> "${bytesPerSecond / 1024} KB/s"
}

/** Pantalla completa mientras se ve el vídeo; al salir vuelven las barras del sistema. */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
