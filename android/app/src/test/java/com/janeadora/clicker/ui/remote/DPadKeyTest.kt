package com.janeadora.clicker.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.clicker_ffi.Key

class DPadKeyTest {
    // A 240 ring with an 88 OK disc, centre at (120, 120).
    private fun at(x: Float, y: Float) = dpadKeyAt(x, y, 240f, 88f)

    @Test fun centre_right_is_right() = assertEquals(Key.RIGHT, at(220f, 120f))
    @Test fun centre_left_is_left() = assertEquals(Key.LEFT, at(20f, 120f))
    @Test fun straight_up_is_up() = assertEquals(Key.UP, at(120f, 20f))
    @Test fun straight_down_is_down() = assertEquals(Key.DOWN, at(120f, 220f))

    @Test fun exactly_45_degrees_goes_to_up_or_down() {
        assertEquals(Key.UP, at(120f + 50f, 120f - 50f))
        assertEquals(Key.DOWN, at(120f - 50f, 120f + 50f))
    }

    @Test fun near_diagonal_is_not_dead() {
        assertEquals(Key.RIGHT, at(120f + 51f, 120f - 50f))
        assertEquals(Key.UP, at(120f + 50f, 120f - 51f))
    }

    @Test fun inside_ok_radius_is_select() {
        assertEquals(Key.SELECT, at(120f, 120f))
        assertEquals(Key.SELECT, at(120f + 43f, 120f))
    }

    @Test fun outside_the_ring_is_null() {
        assertNull(at(5f, 5f))
        assertNull(at(240f, 240f))
    }
}
