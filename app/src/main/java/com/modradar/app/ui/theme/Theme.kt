package com.modradar.app.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Accent = Color(0xFF8C8CFF)

private val FallbackScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF14143A),
    primaryContainer = Color(0xFF2B2B5C),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondaryContainer = Color(0xFF24243A),
    onSecondaryContainer = Color(0xFFD8D8F0),
    background = Color.Black,
    onBackground = Color(0xFFEDEDF2),
    surface = Color.Black,
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF1C1C24),
    onSurfaceVariant = Color(0xFFA9A9B8),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0C0C10),
    surfaceContainer = Color(0xFF121218),
    surfaceContainerHigh = Color(0xFF1A1A22),
    surfaceContainerHighest = Color(0xFF22222C),
    outline = Color(0xFF3A3A46),
    outlineVariant = Color(0xFF2A2A33),
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4F4FD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF14143A),
    secondaryContainer = Color(0xFFE4E4F4),
    onSecondaryContainer = Color(0xFF1C1C30),
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF16161C),
    surface = Color(0xFFF7F7FA),
    onSurface = Color(0xFF16161C),
    surfaceVariant = Color(0xFFE6E6EE),
    onSurfaceVariant = Color(0xFF55556A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F1F6),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDEDF3),
    surfaceContainerHighest = Color(0xFFE6E6EE),
    outline = Color(0xFF8A8A9C),
    outlineVariant = Color(0xFFDADAE4),
    error = Color(0xFFD93A3A),
)

/** 主题模式：跟随系统 / 浅色 / 深色（纯黑）。 */
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/** 主题偏好：内存态驱动重组，SharedPreferences 持久化。 */
object ThemePrefs {
    private const val PREF = "ui"
    private const val KEY = "theme"

    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    fun load(ctx: android.content.Context) {
        val v = ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).getString(KEY, null)
        mode = ThemeMode.entries.firstOrNull { it.name == v } ?: ThemeMode.SYSTEM
    }

    fun set(ctx: android.content.Context, m: ThemeMode) {
        mode = m
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
    }
}

/** 当前是否深色（供系统栏样式等使用）。 */
@Composable
fun isDarkTheme(): Boolean = when (ThemePrefs.mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** 风险分级色：低绿 / 中黄 / 较高橙 / 高红。 */
object RiskColors {
    val Low = Color(0xFF4CC38A)
    val Medium = Color(0xFFF2B33D)
    val High = Color(0xFFFF8A3D)
    val Critical = Color(0xFFFF5A5F)
    val Neutral = Color(0xFF8E8EA0)

    fun ofLevel(level: String?): Color = when (level) {
        "LOW" -> Low
        "MEDIUM" -> Medium
        "HIGH" -> High
        "CRITICAL" -> Critical
        else -> Neutral
    }

    fun ofScore(score: Int?): Color = when {
        score == null -> Neutral
        score >= 80 -> Low
        score >= 60 -> Medium
        score >= 40 -> High
        else -> Critical
    }

    fun ofSeverity(sev: String): Color = when (sev) {
        "HIGH" -> Critical
        "MEDIUM" -> Medium
        else -> Neutral
    }
}

fun levelLabel(level: String?): String = when (level) {
    "LOW" -> "低风险"
    "MEDIUM" -> "中风险"
    "HIGH" -> "较高风险"
    "CRITICAL" -> "高风险"
    else -> "未评分"
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 深色为纯黑；Android 12+ 可动态取色，但深色背景与表面始终保持纯黑。 */
@Composable
fun ModRadarTheme(dark: Boolean = isDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val useDynamic = dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dark && useDynamic -> dynamicDarkColorScheme(LocalContext.current).copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
        )
        dark -> FallbackScheme
        useDynamic -> dynamicLightColorScheme(LocalContext.current)
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
