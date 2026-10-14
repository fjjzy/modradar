package com.modradar.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF8C8CFF)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF0E0E12),
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF17171D),
    onSurfaceVariant = Color(0xFFA9A9B8),
    outline = Color(0xFF2A2A33),
)

@Composable
fun ModRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
