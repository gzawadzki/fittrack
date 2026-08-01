package com.fittrack.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF126B52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F4D8),
    secondary = Color(0xFF415F91),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AD7BC),
    primaryContainer = Color(0xFF00513C),
    secondary = Color(0xFFAAC7FF),
    background = Color(0xFF101412),
    surface = Color(0xFF171C19),
)

@Composable
fun FitTrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

