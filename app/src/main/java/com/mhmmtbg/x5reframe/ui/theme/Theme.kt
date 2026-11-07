package com.mhmmtbg.x5reframe.ui.theme

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Uygulama teması: sistemi izle, açık ya da koyu (AMOLED, saf siyah). */
enum class ThemeMode(val label: String, val night: Int) {
    SYSTEM("Otomatik", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("Açık", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("Koyu", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        private const val KEY = "theme"

        fun load(context: Context): ThemeMode =
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { n -> values().firstOrNull { it.name == n } } ?: SYSTEM

        /** Seçimi kaydet ve uygula (açık etkinlikler yeni temayla yeniden oluşturulur). */
        fun set(context: Context, mode: ThemeMode) {
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
            AppCompatDelegate.setDefaultNightMode(mode.night)
        }

        fun apply(context: Context) = AppCompatDelegate.setDefaultNightMode(load(context).night)
    }
}

/**
 * Tasarım sistemi: tek renkli (siyah-beyaz-gri) zemin, tek vurgu rengi kobalt mavisi.
 * Düz, gölgesiz, parlamasız. Koyu tema saf siyah zeminlidir (AMOLED'de piksel kapalı).
 * Vurgu yalnızca birincil eylem, seçili durum, kesme tutamakları ve anahtar kareler içindir.
 */
object Palette {
    var dark by mutableStateOf(true)
        internal set

    val Background get() = if (dark) Color(0xFF000000) else Color(0xFFF7F7F8)
    val Surface get() = if (dark) Color(0xFF0E0F11) else Color(0xFFFFFFFF)
    val SurfaceHigh get() = if (dark) Color(0xFF1C1E22) else Color(0xFFEEEFF2)
    val Outline get() = if (dark) Color(0xFF2C2F35) else Color(0xFFDCDEE3)
    val Text get() = if (dark) Color(0xFFF3F4F6) else Color(0xFF111318)
    val Muted get() = if (dark) Color(0xFF9CA3AF) else Color(0xFF5B6270)
    /** Etkisiz düğme/simge: okunur ama soluk. */
    val Disabled get() = if (dark) Color(0xFF6B7280) else Color(0xFF9AA0AA)
    /** Kobalt: koyu zeminde biraz açık, açık zeminde koyu (her ikisinde de beyaz yazı okunur). */
    val Accent get() = if (dark) Color(0xFF3D6DF2) else Color(0xFF2754D6)
    val OnAccent get() = Color(0xFFFFFFFF)
    /** Vurgunun metin/ince çizgi olarak kullanımı (koyu zeminde daha açık). */
    val AccentText get() = if (dark) Color(0xFF8DAEFF) else Color(0xFF2754D6)
    val Keyframe get() = AccentText
    val Success get() = if (dark) Color(0xFF4ADE80) else Color(0xFF15803D)
    val Error get() = if (dark) Color(0xFFFF6B6B) else Color(0xFFC62828)

    /** Video üstü yüzeyler (temadan bağımsız). */
    val Scrim = Color(0x99000000)
    val OnVideo = Color(0xFFFFFFFF)
}

private fun scheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Palette.Accent, onPrimary = Palette.OnAccent, secondary = Palette.Keyframe, onSecondary = Palette.OnAccent,
    background = Palette.Background, onBackground = Palette.Text, surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.Muted, surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh, surfaceContainerHighest = Palette.SurfaceHigh, surfaceContainerLow = Palette.Surface,
    surfaceContainerLowest = Palette.Background, outline = Palette.Outline, outlineVariant = Palette.Outline, error = Palette.Error,
) else lightColorScheme(
    primary = Palette.Accent, onPrimary = Palette.OnAccent, secondary = Palette.Keyframe, onSecondary = Palette.OnAccent,
    background = Palette.Background, onBackground = Palette.Text, surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.Muted, surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh, surfaceContainerHighest = Palette.SurfaceHigh, surfaceContainerLow = Palette.Surface,
    surfaceContainerLowest = Palette.Background, outline = Palette.Outline, outlineVariant = Palette.Outline, error = Palette.Error,
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
)

@Composable
fun X5Theme(content: @Composable () -> Unit) {
    // Uygulama içi seçim AppCompat gece modu olarak uygulanır; burada yapılandırmadan okunur.
    val dark = isSystemInDarkTheme()
    if (Palette.dark != dark) Palette.dark = dark
    ThemedSystemBars()
    MaterialTheme(colorScheme = scheme(dark), typography = typography) {
        // Renk verilmemiş metinler zemine göre okunur renkte olsun
        CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
    }
}

/** Sistem çubuklarını temanın zemin rengine getir. */
@Composable
fun ThemedSystemBars() {
    val view = LocalView.current
    val dark = Palette.dark
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = Palette.Background.toArgb()
        window.navigationBarColor = Palette.Background.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/** Video üstündeki tam ekran sayfalar (oynatıcı, fotoğraf): temadan bağımsız siyah sistem çubukları. */
@Composable
fun BlackSystemBars() {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
