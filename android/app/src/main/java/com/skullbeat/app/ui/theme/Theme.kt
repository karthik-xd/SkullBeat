package com.skullbeat.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalThemeColors = staticCompositionLocalOf { AppThemePresets.DEFAULT }

object SkullBeatTheme {
    val colors: AppThemePalette
        @Composable
        get() = LocalThemeColors.current
}

@Composable
fun SkullBeatTheme(
    themePreset: AppThemePalette = AppThemePresets.DEFAULT,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = BgDark.toArgb()
                window.navigationBarColor = BgDark.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    val darkColorScheme = darkColorScheme(
        primary = themePreset.primary,
        onPrimary = TextBone,
        secondary = themePreset.secondary,
        onSecondary = BgDark,
        tertiary = themePreset.tertiary,
        background = BgDark,
        onBackground = TextBone,
        surface = SurfaceDark,
        onSurface = TextBone,
        surfaceVariant = RaisedDark,
        onSurfaceVariant = TextMuted,
        error = HeartRed,
        onError = TextBone
    )

    CompositionLocalProvider(
        LocalThemeColors provides themePreset
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme,
            typography = SkullBeatTypography,
            content = content
        )
    }
}
