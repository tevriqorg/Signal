package com.wb.signalpro

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SignalColors = darkColorScheme(
    primary = Color(0xFF00DFA5),
    onPrimary = Color(0xFF00382A),
    secondary = Color(0xFF8BC7FF),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF0F1520),
    surfaceVariant = Color(0xFF18202C),
    onBackground = Color(0xFFE7EEF7),
    onSurface = Color(0xFFE7EEF7),
    onSurfaceVariant = Color(0xFFA9B7C7),
    error = Color(0xFFFFB4AB)
)

@Composable
fun SignalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SignalColors, content = content)
}
