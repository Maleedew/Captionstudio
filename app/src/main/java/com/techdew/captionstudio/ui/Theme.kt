package com.techdew.captionstudio.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Brand palette: electric blue, deep black, smoke white. */
object Pal {
    val Electric = Color(0xFF00B7FF)
    val Black = Color(0xFF07080A)
    val Surface = Color(0xFF111318)
    val Surface2 = Color(0xFF1A1D24)
    val Outline = Color(0xFF2A2E38)
    val Smoke = Color(0xFFF2F4F7)
    val SmokeDim = Color(0xFF9AA1AD)
    val Danger = Color(0xFFFF5C6C)
}

private val Scheme = darkColorScheme(
    primary = Pal.Electric,
    onPrimary = Pal.Black,
    secondary = Pal.Electric,
    onSecondary = Pal.Black,
    background = Pal.Black,
    onBackground = Pal.Smoke,
    surface = Pal.Surface,
    onSurface = Pal.Smoke,
    surfaceVariant = Pal.Surface2,
    onSurfaceVariant = Pal.SmokeDim,
    outline = Pal.Outline,
    error = Pal.Danger
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun CaptionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = AppShapes, content = content)
}
