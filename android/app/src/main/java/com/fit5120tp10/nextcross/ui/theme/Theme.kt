package com.fit5120tp10.nextcross.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFAFCBFF),
    onPrimary = Color(0xFF071A3A),
    secondary = Color(0xFFB7C8E6),
    tertiary = Color(0xFFE8B6CC),
    background = Color(0xFF101827),
    onBackground = Color(0xFFF3F6FC),
    surface = Color(0xFF1B2638),
    onSurface = Color(0xFFF3F6FC),
    onSurfaceVariant = Color(0xFFC3CCDA),
    outline = Color(0xFF9CAABD)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF071A3A),
    onPrimary = Color.White,
    secondary = Color(0xFF1769E0),
    tertiary = Color(0xFF6650A4),
    background = Color(0xFFF4F7FE),
    onBackground = Color(0xFF071A3A),
    surface = Color.White,
    onSurface = Color(0xFF071A3A),
    onSurfaceVariant = Color(0xFF63728B),
    outline = Color(0xFF63728B)
)

@Composable
fun NextCrossTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep the app palette stable across devices while still following light/dark mode.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
