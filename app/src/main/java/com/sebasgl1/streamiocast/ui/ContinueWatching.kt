@file:OptIn(ExperimentalFoundationApi::class)

package com.sebasgl1.streamiocast.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sebasgl1.streamiocast.data.MetaItem
import com.sebasgl1.streamiocast.history.MIN_RESUME_MS
import com.sebasgl1.streamiocast.history.WatchEntry
import com.sebasgl1.streamiocast.history.formatClock
import com.sebasgl1.streamiocast.history.remainingLabel

/** Fila de Inicio con lo que dejaste a medias. Mantén presionado para quitar algo. */
@Composable
fun ContinueRow(entries: List<WatchEntry>, onOpen: (MetaItem) -> Unit, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    Column {
        Text(
            text = "Continuar viendo",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { it.target.videoId }) { entry ->
                val t = entry.target
                Column(
                    Modifier
                        .width(128.dp)
                        .combinedClickable(
                            onClick = {
                                onOpen(MetaItem(id = t.metaId, type = t.type, name = t.name, poster = t.poster))
                            },
                            onLongClick = {
                                onRemove(t.metaId)
                                Toast.makeText(context, "Quitado de Continuar viendo", Toast.LENGTH_SHORT).show()
                            },
                        ),
                ) {
                    AsyncImage(
                        model = t.poster,
                        contentDescription = t.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    LinearProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                    Text(
                        text = t.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    val detail = listOfNotNull(
                        if (t.season != null && t.episode != null) "T${t.season} E${t.episode}" else null,
                        remainingLabel(entry),
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** "✓ Visto" o una barra con "Vas en 41:23 de 2:10:00". */
@Composable
fun WatchProgress(entry: WatchEntry?, modifier: Modifier = Modifier) {
    if (entry == null) return
    if (entry.watched) {
        Text(
            text = "✓ Visto",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = modifier,
        )
        return
    }
    if (entry.positionMs < MIN_RESUME_MS) return
    Column(modifier) {
        if (entry.durationMs > 0) {
            LinearProgressIndicator(
                progress = { entry.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
        Text(
            text = if (entry.durationMs > 0) {
                "Vas en ${formatClock(entry.positionMs)} de ${formatClock(entry.durationMs)}"
            } else {
                "Vas en ${formatClock(entry.positionMs)}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
