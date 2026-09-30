package com.minehost.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = MineGreenDark,
    onPrimary = Color.White,
    primaryContainer = MineGreenSoft,
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4E6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8DA),
    onSecondaryContainer = Color(0xFF0A1F16),
    tertiary = Color(0xFF8A5B2A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB9),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF7FBF7),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF7FBF7),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDCE5DE),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFC0C9C1),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = MineGreen,
    onPrimary = Color(0xFF003825),
    primaryContainer = MineGreenContainer,
    onPrimaryContainer = MineGreenSoft,
    secondary = Color(0xFFB4CCBF),
    onSecondary = Color(0xFF1F3529),
    secondaryContainer = Color(0xFF354B40),
    onSecondaryContainer = Color(0xFFD0E8DA),
    tertiary = MineAmber,
    onTertiary = Color(0xFF492900),
    tertiaryContainer = Color(0xFF693F00),
    onTertiaryContainer = Color(0xFFFFDDB9),
    background = MineInk,
    onBackground = MineText,
    surface = MineInk,
    onSurface = MineText,
    surfaceVariant = MineSurfaceHigh,
    onSurfaceVariant = MineTextMuted,
    outline = Color(0xFF89938D),
    outlineVariant = MineOutline,
    error = MineError,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val MineTypography = Typography(
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 25.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 23.sp
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

@Composable
fun MineHostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION") // still the only opt-out below API 35
                window.isStatusBarContrastEnforced = false
                @Suppress("DEPRECATION")
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = MineTypography,
        content = content
    )
}
