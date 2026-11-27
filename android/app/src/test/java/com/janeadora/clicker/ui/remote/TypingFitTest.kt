package com.janeadora.clicker.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingFitTest {
    @Test fun roomy_screen_keeps_the_full_dpad_over_the_row() =
        assertEquals(TypingFit(240f, stacked = true), typingFit(room = 420f, full = 240f, row = 48f))

    @Test fun shorter_room_shrinks_the_stacked_dpad() =
        assertEquals(TypingFit(200f, stacked = true), typingFit(room = 284f, full = 208f, row = 48f))

    @Test fun below_160_the_buttons_move_beside_the_dpad() =
        assertEquals(TypingFit(208f, stacked = false), typingFit(room = 230f, full = 208f, row = 48f))

    @Test fun beside_the_column_the_dpad_goes_down_to_152() =
        assertEquals(TypingFit(152f, stacked = false), typingFit(room = 160f, full = 208f, row = 48f))

    @Test fun every_dpad_offered_keeps_48dp_arrows_and_a_48dp_ok() {
        for (full in listOf(200f, 208f, 240f)) {
            val share = if (full == 240f) 88f / 240f else 76f / 208f
            for (room in 40..460) {
                val size = typingFit(room.toFloat(), full, 48f).dpad ?: continue
                val (ring, ok) = typingRing(size, share)
                assertEquals(size - DPAD_LIP, ring, 1e-3f)
                assertTrue("room=$room ok $ok", ok >= 48f)
                assertTrue("room=$room arrow ${(ring - ok) / 2f}", (ring - ok) / 2f >= 48f - 1e-3f)
            }
        }
    }

    @Test fun a_full_size_dpad_keeps_its_ok_disc() {
        val (ring, ok) = typingRing(240f + DPAD_LIP, 88f / 240f)
        assertEquals(240f, ring, 1e-3f)
        assertEquals(88f, ok, 1e-3f)
    }

    @Test fun too_short_for_the_button_column_leaves_only_the_row() =
        assertEquals(TypingFit(null, stacked = true), typingFit(room = 150f, full = 208f, row = 48f))

    @Test fun nothing_when_not_even_the_row_fits() =
        assertEquals(TypingFit(null, stacked = true, buttons = false), typingFit(room = 40f, full = 208f, row = 48f))
}
