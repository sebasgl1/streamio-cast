package com.sebasgl1.streamiocast.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Muestra el informe del último cierre inesperado para poder copiarlo y enviarlo. */
@Composable
fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("La app se cerró inesperadamente") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Copia este informe y pégaselo a Claude para encontrar el fallo:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                clipboard.setText(AnnotatedString(report))
                Toast.makeText(context, "Informe copiado", Toast.LENGTH_SHORT).show()
            }) { Text("Copiar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
