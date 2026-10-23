package com.group16.dinoblog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF32694A), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EBD9), onPrimaryContainer = Color(0xFF123923),
    secondary = Color(0xFF656441), secondaryContainer = Color(0xFFECEBC6),
    background = Color(0xFFF8F9F2), surface = Color(0xFFF8F9F2),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA0D3AC), primaryContainer = Color(0xFF234F35),
    secondary = Color(0xFFD0CFA5), background = Color(0xFF101711), surface = Color(0xFF101711),
)

@Composable
fun DinoblogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
