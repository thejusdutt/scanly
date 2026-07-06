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

// Full tonal schemes seeded from the Scanly green (#1E6F5C). Overriding only
// primary/secondary (as before) let the M3 baseline purple leak into containers,
// surfaces and tertiary roles whenever dynamic color was unavailable or off.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6F5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA7F2DC),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4B635B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE9DE),
    onSecondaryContainer = Color(0xFF072019),
    tertiary = Color(0xFF416276),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC5E7FF),
    onTertiaryContainer = Color(0xFF001E2C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF6),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5FBF6),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C3),
    inverseSurface = Color(0xFF2B322F),
    inverseOnSurface = Color(0xFFECF2ED),
    inversePrimary = Color(0xFF8BD6C0),
    surfaceDim = Color(0xFFD5DBD7),
    surfaceBright = Color(0xFFF5FBF6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F0),
    surfaceContainer = Color(0xFFE9EFEB),
    surfaceContainerHigh = Color(0xFFE3EAE5),
    surfaceContainerHighest = Color(0xFFDEE4DF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD6C0),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005141),
    onPrimaryContainer = Color(0xFFA7F2DC),
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1D352E),
    secondaryContainer = Color(0xFF334B44),
    onSecondaryContainer = Color(0xFFCDE9DE),
    tertiary = Color(0xFFA9CBE2),
    onTertiary = Color(0xFF0E3446),
    tertiaryContainer = Color(0xFF294A5E),
    onTertiaryContainer = Color(0xFFC5E7FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
    inverseSurface = Color(0xFFDEE4DF),
    inverseOnSurface = Color(0xFF2B322F),
    inversePrimary = Color(0xFF1E6F5C),
    surfaceDim = Color(0xFF0F1512),
    surfaceBright = Color(0xFF343B37),
    surfaceContainerLowest = Color(0xFF090F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
)

/**
 * Material You theme. Uses dynamic color on Android 12+ (addresses the "free apps look
 * basic" complaint) and falls back to the branded green scheme below that — or whenever
 * the user turns dynamic color off in Settings → Appearance.
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
