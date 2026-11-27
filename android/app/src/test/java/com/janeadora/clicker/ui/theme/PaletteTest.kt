package com.janeadora.clicker.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    @Test fun text_contrast_is_AA_in_both_modes() {
        for (c in listOf(DarkColors, LightColors)) {
            assertTrue(contrast(c.onSurface, c.surface) >= 4.5)
            assertTrue(contrast(c.heading, c.surface) >= 4.5)
            assertTrue(contrast(c.muted, c.surface) >= 4.5)
            assertTrue(contrast(c.onAccent, c.accent) >= 4.5)
            assertTrue(contrast(c.onSurface, c.surfaceContainer) >= 4.5)
            assertTrue(contrast(c.outlineStrong, c.surface) >= 3.0)
        }
    }

    @Test fun the_remote_body_keeps_contrast_in_both_modes() {
        for (c in listOf(DarkColors, LightColors)) {
            // Key icons and the TV name sit on the key and shell tones.
            assertTrue(contrast(c.heading, c.key) >= 4.5)
            assertTrue(contrast(c.heading, c.shell) >= 4.5)
            assertTrue(contrast(c.muted, c.shell) >= 4.5)
            assertTrue(contrast(c.muted, c.bay) >= 4.5)
            // The key's hairline and bottom edge: 3:1 against the shell, and in a
            // compartment against the key's own face or the compartment around it.
            assertTrue(contrast(c.outlineStrong, c.shell) >= 3.0)
            assertTrue(maxOf(contrast(c.outlineStrong, c.key), contrast(c.outlineStrong, c.bay)) >= 3.0)
            // The arrows and the OK disc on the D-pad ring.
            assertTrue(contrast(c.accent, c.key) >= 4.5)
        }
    }

    @Test fun keys_are_the_lightest_step_and_compartments_the_darkest_in_both_modes() {
        fun lightness(c: Color) = contrast(c, Color.Black)
        for (c in listOf(DarkColors, LightColors)) {
            assertTrue(lightness(c.key) > lightness(c.shell))
            assertTrue(lightness(c.shell) > lightness(c.bay))
        }
    }

    @Test fun the_night_display_is_the_same_dark_panel_in_both_modes() {
        assertEquals(DarkColors.night, LightColors.night)
        assertEquals(DarkColors.onNight, LightColors.onNight)
        assertEquals(DarkColors.nightMuted, LightColors.nightMuted)
        assertEquals(DarkColors.nightAccent, LightColors.nightAccent)
        val c = LightColors
        assertTrue(contrast(c.onNight, c.night) >= 7.0)
        assertTrue(contrast(c.nightMuted, c.night) >= 7.0)
        assertTrue(contrast(c.nightAccent, c.night) >= 7.0)
    }

    @Test fun contrast_matches_wcag_reference_values() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrast(Color.White, Color.White), 0.0001)
        // Order does not matter.
        assertEquals(contrast(Color.Black, Color.White), contrast(Color.White, Color.Black), 0.0)
        // #767676 on white is the well known 4.54:1 grey.
        assertEquals(4.54, contrast(Color(0xFF767676), Color.White), 0.01)
    }

    @Test fun each_mode_has_three_dot_inks_of_each_kind() {
        for (c in listOf(DarkColors, LightColors)) {
            assertEquals(3, c.dotGround.size)
            assertEquals(3, c.dotMark.size)
        }
    }
}
