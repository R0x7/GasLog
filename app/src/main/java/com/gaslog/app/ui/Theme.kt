package com.gaslog.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// secondaryContainer = "looks good" checks, tertiaryContainer = warnings.
private val LightColors = lightColorScheme(
    primary = Color(0xFFD9480F),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFE6F4EA),
    onSecondaryContainer = Color(0xFF1E6B34),
    tertiaryContainer = Color(0xFFFFF1DC),
    onTertiaryContainer = Color(0xFF8A4600),
    background = Color(0xFFF4F2EE),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0ECE6),
    outlineVariant = Color(0xFFE2DDD5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7A3D),
    onPrimary = Color(0xFF2A1004),
    secondaryContainer = Color(0xFF16301D),
    onSecondaryContainer = Color(0xFF8CE0A0),
    tertiaryContainer = Color(0xFF3A2A12),
    onTertiaryContainer = Color(0xFFFFC27A),
    background = Color(0xFF141312),
    surface = Color(0xFF1F1D1B),
    surfaceVariant = Color(0xFF2A2724),
    outlineVariant = Color(0xFF3A3632),
)

@Composable
fun GasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/** Border/label color for values read from a photo. */
@Composable
fun photoColor(): Color = if (isSystemInDarkTheme()) Color(0xFF74C0FC) else Color(0xFF1864AB)

/** Border/label color for values the app calculated. */
@Composable
fun calcColor(): Color = if (isSystemInDarkTheme()) Color(0xFFB197FC) else Color(0xFF7048E8)
