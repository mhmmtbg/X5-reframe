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
 * Tasarım sistemi: düz, gölgesiz. Koyu tema saf siyah zeminlidir (AMOLED'de piksel kapalı).
 * Tek vurgu rengi (amber) birincil eylemler, kesme tutamakları ve seçili durumlar içindir;
 * açık temada metin olarak okunabilsin diye koyulaştırılır.
 */
object Palette {
    var dark by mutableStateOf(true)
        internal set

    val Background get() = if (dark) Color(0xFF000000) else Color(0xFFF5F6F8)
    val Surface get() = if (dark) Color(0xFF0C0D0F) else Color(0xFFFFFFFF)
    val SurfaceHigh get() = if (dark) Color(0xFF1B1D21) else Color(0xFFE9ECF0)
    val Outline get() = if (dark) Color(0xFF30343B) else Color(0xFFD3D8DF)
    val Text get() = if (dark) Color(0xFFF2F4F7) else Color(0xFF12151A)
    val Muted get() = if (dark) Color(0xFFA3ABB7) else Color(0xFF59626E)
    /** Etkisiz düğme/simge: okunur ama soluk. */
    val Disabled get() = if (dark) Color(0xFF6B7280) else Color(0xFF9AA2AD)
    val Accent get() = if (dark) Color(0xFFFFC94D) else Color(0xFFC77A00)
    val OnAccent get() = Color(0xFF1A1A1A)
    val Keyframe get() = if (dark) Color(0xFF6EC6FF) else Color(0xFF1565C0)
    val Error get() = if (dark) Color(0xFFFF6B6B) else Color(0xFFC62828)
}

private fun scheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Palette.Accent, onPrimary = Palette.OnAccent, secondary = Palette.Keyframe, onSecondary = Palette.OnAccent,
    background = Palette.Background, onBackground = Palette.Text, surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.Muted, surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh, surfaceContainerHighest = Palette.SurfaceHigh, surfaceContainerLow = Palette.Surface,
    surfaceContainerLowest = Palette.Background, outline = Palette.Outline, outlineVariant = Palette.Outline, error = Palette.Error,
) else lightColorScheme(
    primary = Palette.Accent, onPrimary = Palette.OnAccent, secondary = Palette.Keyframe, onSecondary = Color.White,
    background = Palette.Background, onBackground = Palette.Text, surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.Muted, surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh, surfaceContainerHighest = Palette.SurfaceHigh, surfaceContainerLow = Palette.Surface,
    surfaceContainerLowest = Palette.Background, outline = Palette.Outline, outlineVariant = Palette.Outline, error = Palette.Error,
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
