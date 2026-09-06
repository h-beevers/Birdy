package com.henrybeevers.birdy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Cream = Color(0xFFF4EDE0)
private val Ink = Color(0xFF2E2620)
private val Accent = Color(0xFF5B7C99)

private val Scheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    secondary = Color(0xFFD6E4D2),
    onSecondary = Ink,
)

@Composable
fun BirdyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
