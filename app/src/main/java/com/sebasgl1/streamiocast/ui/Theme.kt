package com.sebasgl1.streamiocast.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF9D86FF),
    onPrimary = Color(0xFF1B0F5C),
    secondary = Color(0xFF7BDCB5),
    background = Color(0xFF0F0E17),
    onBackground = Color(0xFFECEAF5),
    surface = Color(0xFF16151F),
    onSurface = Color(0xFFECEAF5),
    surfaceVariant = Color(0xFF24222F),
    onSurfaceVariant = Color(0xFFB4B0C6),
)

@Composable
fun StreamioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Typography(),
        content = content,
    )
}
