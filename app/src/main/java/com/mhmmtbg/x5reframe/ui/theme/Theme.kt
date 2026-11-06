package com.mhmmtbg.x5reframe.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tasarım sistemi: koyu, düz, gölgesiz. Tek vurgu rengi (amber) yalnızca birincil eylemler,
 * kesme tutamakları ve seçili durumlar için kullanılır.
 */
object Palette {
    val Background = Color(0xFF0F1114)
    val Surface = Color(0xFF181B20)
    val SurfaceHigh = Color(0xFF22262D)
    val Outline = Color(0xFF2E333B)
    val Text = Color(0xFFE8ECF1)
    val Muted = Color(0xFF8A96A6)
    val Accent = Color(0xFFFFC94D)
    val OnAccent = Color(0xFF1A1A1A)
    val Keyframe = Color(0xFF6EC6FF)
    val Error = Color(0xFFFF6B6B)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    secondary = Palette.Keyframe,
    onSecondary = Palette.OnAccent,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    surfaceContainerLow = Palette.Surface,
    surfaceContainerLowest = Palette.Background,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Error,
)

private val typography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
)

@Composable
fun X5Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
