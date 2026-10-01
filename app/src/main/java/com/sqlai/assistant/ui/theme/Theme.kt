package com.sqlai.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SqlBlue = Color(0xFF1D4ED8)
val SqlCyan = Color(0xFF0EA5E9)
val SqlDark = Color(0xFF0B1220)
val SqlSurface = Color(0xFFF5F7FB)
val SqlSuccess = Color(0xFF16A34A)
val SqlWarn = Color(0xFFF59E0B)
val SqlError = Color(0xFFDC2626)

private val LightScheme = lightColorScheme(
    primary = SqlBlue,
    onPrimary = Color.White,
    secondary = SqlCyan,
    onSecondary = Color.White,
    background = SqlSurface,
    surface = Color.White,
    onBackground = SqlDark,
    onSurface = SqlDark,
    error = SqlError
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0B1220),
    secondary = Color(0xFF38BDF8),
    background = SqlDark,
    surface = Color(0xFF111A2E),
    onBackground = Color(0xFFE5EAF5),
    onSurface = Color(0xFFE5EAF5)
)

private val AppTypography = Typography()

@Composable
fun SqlAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}
