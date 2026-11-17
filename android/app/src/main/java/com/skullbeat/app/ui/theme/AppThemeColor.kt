package com.skullbeat.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class AppThemePalette(
    val key: String,
    val name: String,
    val description: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val primaryGradient: Brush,
    val highlightGradient: Brush
)

object AppThemePresets {
    val DEFAULT = AppThemePalette(
        key = "default",
        name = "SkullBeat Classic",
        description = "Original Electric Blue & Cyan Accent",
        primary = ElectricBlue,
        secondary = CyanAccent,
        tertiary = VioletAccent,
        primaryGradient = PrimaryGradient,
        highlightGradient = HighlightGradient
    )

    val CYAN = AppThemePalette(
        key = "cyan",
        name = "Cyber Cyan",
        description = "Vibrant Neon Aqua & Teal",
        primary = Color(0xFF00E5FF),
        secondary = Color(0xFF00B0FF),
        tertiary = Color(0xFF1DE9B6),
        primaryGradient = Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF1DE9B6))),
        highlightGradient = Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF00B0FF)))
    )

    val BLUE = AppThemePalette(
        key = "blue",
        name = "Sapphire Blue",
        description = "Deep Royal & Electric Blue",
        primary = Color(0xFF2979FF),
        secondary = Color(0xFF448AFF),
        tertiary = Color(0xFF00B0FF),
        primaryGradient = Brush.linearGradient(listOf(Color(0xFF2979FF), Color(0xFF448AFF))),
        highlightGradient = Brush.linearGradient(listOf(Color(0xFF2979FF), Color(0xFF00B0FF)))
    )

    val GREEN = AppThemePalette(
        key = "green",
        name = "Matrix Green",
        description = "Toxic Emerald & Lime Glow",
        primary = Color(0xFF00E676),
        secondary = Color(0xFF69F0AE),
        tertiary = Color(0xFF76FF03),
        primaryGradient = Brush.linearGradient(listOf(Color(0xFF00E676), Color(0xFF76FF03))),
        highlightGradient = Brush.linearGradient(listOf(Color(0xFF00E676), Color(0xFF69F0AE)))
    )

    val PURPLE = AppThemePalette(
        key = "purple",
        name = "Neon Violet",
        description = "Cyberpunk Magenta & Violet",
        primary = Color(0xFFD500F9),
        secondary = Color(0xFFAA00FF),
        tertiary = Color(0xFFE040FB),
        primaryGradient = Brush.linearGradient(listOf(Color(0xFFD500F9), Color(0xFF8B5CFF))),
        highlightGradient = Brush.linearGradient(listOf(Color(0xFFD500F9), Color(0xFFAA00FF)))
    )

    val RED = AppThemePalette(
        key = "red",
        name = "Crimson Skull",
        description = "Blood Red & Ruby Accent",
        primary = Color(0xFFFF1744),
        secondary = Color(0xFFFF5252),
        tertiary = Color(0xFFFF4081),
        primaryGradient = Brush.linearGradient(listOf(Color(0xFFFF1744), Color(0xFFFF5252))),
        highlightGradient = Brush.linearGradient(listOf(Color(0xFFFF1744), Color(0xFFFF4081)))
    )

    val AMBER = AppThemePalette(
        key = "amber",
        name = "Sunset Gold",
        description = "Warm Neon Amber & Gold",
        primary = Color(0xFFFFAB00),
        secondary = Color(0xFFFFD740),
        tertiary = Color(0xFFFF9100),
        primaryGradient = Brush.linearGradient(listOf(Color(0xFFFFAB00), Color(0xFFFFD740))),
        highlightGradient = Brush.linearGradient(listOf(Color(0xFFFFAB00), Color(0xFFFF9100)))
    )

    val all: List<AppThemePalette> = listOf(
        DEFAULT,
        CYAN,
        BLUE,
        GREEN,
        PURPLE,
        RED,
        AMBER
    )

    fun fromKey(key: String?): AppThemePalette {
        if (key.isNullOrBlank()) return DEFAULT
        return all.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}
