package com.scanly.ui.theme

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

private val Green = Color(0xFF1E6F5C)
private val GreenLight = Color(0xFFA7E8BD)

private val LightColors = lightColorScheme(primary = Green, secondary = GreenLight)
private val DarkColors = darkColorScheme(primary = GreenLight, secondary = Green)

/**
 * Material You theme. Uses dynamic color on Android 12+ (addresses the "free apps look
 * basic" complaint) and falls back to a branded green scheme below that.
 */
@Composable
fun ScanlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = ScanlyTypography, content = content)
}
