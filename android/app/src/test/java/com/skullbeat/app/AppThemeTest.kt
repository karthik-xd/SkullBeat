package com.skullbeat.app

import androidx.compose.ui.graphics.Color
import com.skullbeat.app.ui.theme.AppThemePresets
import com.skullbeat.app.ui.theme.CyanAccent
import com.skullbeat.app.ui.theme.ElectricBlue
import com.skullbeat.app.ui.theme.VioletAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {

    @Test
    fun defaultPresetPreservesClassicColors() {
        val defaultPreset = AppThemePresets.DEFAULT
        assertEquals("default", defaultPreset.key)
        assertEquals(ElectricBlue, defaultPreset.primary)
        assertEquals(CyanAccent, defaultPreset.secondary)
        assertEquals(VioletAccent, defaultPreset.tertiary)
        assertEquals("SkullBeat Classic", defaultPreset.name)
    }

    @Test
    fun allSevenPresetsConfigured() {
        assertEquals(7, AppThemePresets.all.size)
        val keys = AppThemePresets.all.map { it.key }
        assertTrue(keys.contains("default"))
        assertTrue(keys.contains("cyan"))
        assertTrue(keys.contains("blue"))
        assertTrue(keys.contains("green"))
        assertTrue(keys.contains("purple"))
        assertTrue(keys.contains("red"))
        assertTrue(keys.contains("amber"))

        // Ensure all primary colors are unique
        val primaryColors = AppThemePresets.all.map { it.primary }.toSet()
        assertEquals(7, primaryColors.size)
    }

    @Test
    fun fallbackToDefaultOnUnknownOrNull() {
        assertEquals(AppThemePresets.DEFAULT, AppThemePresets.fromKey(null))
        assertEquals(AppThemePresets.DEFAULT, AppThemePresets.fromKey(""))
        assertEquals(AppThemePresets.DEFAULT, AppThemePresets.fromKey("unknown_theme_xyz"))
    }

    @Test
    fun caseInsensitiveLookup() {
        assertEquals(AppThemePresets.CYAN, AppThemePresets.fromKey("cyan"))
        assertEquals(AppThemePresets.CYAN, AppThemePresets.fromKey("CYAN"))
        assertEquals(AppThemePresets.CYAN, AppThemePresets.fromKey("Cyan"))

        assertEquals(AppThemePresets.GREEN, AppThemePresets.fromKey("GREEN"))
        assertEquals(AppThemePresets.BLUE, AppThemePresets.fromKey("Blue"))
        assertEquals(AppThemePresets.PURPLE, AppThemePresets.fromKey("purple"))
        assertEquals(AppThemePresets.RED, AppThemePresets.fromKey("RED"))
        assertEquals(AppThemePresets.AMBER, AppThemePresets.fromKey("Amber"))
    }

    @Test
    fun presetMetadataNotEmpty() {
        AppThemePresets.all.forEach { preset ->
            assertTrue(preset.key.isNotBlank())
            assertTrue(preset.name.isNotBlank())
            assertTrue(preset.description.isNotBlank())
            assertNotNull(preset.primaryGradient)
            assertNotNull(preset.highlightGradient)
        }
    }
}
