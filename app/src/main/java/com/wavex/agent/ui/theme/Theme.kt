package com.wavex.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8DBBFF),
    onPrimary = Color(0xFF00315F),
    secondary = Color(0xFFBBC7E2),
    onSecondary = Color(0xFF253044),
    tertiary = Color(0xFFD5B9FF),
    background = Color(0xFF101114),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF17181C),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF252832),
    onSurfaceVariant = Color(0xFFC0C7D5),
    outline = Color(0xFF8A909D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1769C2),
    onPrimary = Color.White,
    secondary = Color(0xFF52627A),
    onSecondary = Color.White,
    tertiary = Color(0xFF68519A),
    background = Color(0xFFF9F9FC),
    onBackground = Color(0xFF1A1B20),
    surface = Color.White,
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE9EDF5),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

@Composable
fun AgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // 这里刻意不做颜色动画：Mihon 的页面切换重点是状态和内容稳定，
    // 低端设备上瞬时换肤比整页颜色动画更跟手。
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
