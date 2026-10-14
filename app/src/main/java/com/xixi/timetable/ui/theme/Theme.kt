package com.xixi.timetable.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 亮色配色方案。
 * 偏中性的蓝灰主色，长时间看课表不刺眼。
 */
private val KobiLightColorScheme = lightColorScheme(
    primary = Color(0xFF2F5DA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

/**
 * 暗色配色方案。
 */
private val KobiDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF12468B),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFD7BEE4),
    onTertiary = Color(0xFF3B2947),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF1A1C20),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/**
 * 应用主题。
 *
 * @param darkTheme 是否使用暗色配色，默认跟随系统。
 * @param content 主题作用域内的界面内容。
 */
@Composable
fun KobiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) KobiDarkColorScheme else KobiLightColorScheme,
        content = content
    )
}

/**
 * 课程配色表。
 *
 * 颜色均为中等偏深的饱和色，配白色文字在亮色/暗色主题下都有足够对比度。
 */
object CoursePalette {

    /** 12 种课程配色，按顺序循环使用。 */
    val colors: List<Color> = listOf(
        Color(0xFF2F6FED), // 蓝
        Color(0xFF0F9D58), // 绿
        Color(0xFFE8710A), // 橙
        Color(0xFFD93025), // 红
        Color(0xFF7B1FA2), // 紫
        Color(0xFF00838F), // 青
        Color(0xFF5D4037), // 棕
        Color(0xFF455A64), // 蓝灰
        Color(0xFFC2185B), // 玫红
        Color(0xFF827717), // 橄榄
        Color(0xFF3F51B5), // 靛蓝
        Color(0xFF00695C)  // 墨绿
    )

    /**
     * 按 colorIndex 取色，越界与负数都会安全地循环映射。
     */
    fun colorOf(index: Int): Color {
        val size = colors.size
        if (size == 0) return Color(0xFF2F6FED)
        val normalized = ((index % size) + size) % size
        return colors[normalized]
    }
}
