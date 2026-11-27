package com.janeadora.clicker.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTravelTest {
    @Test fun a_quick_tap_stays_down_for_the_rest_of_the_minimum() {
        assertEquals(KEY_DOWN_MS, keyDownRemainder(0))
        assertEquals(KEY_DOWN_MS - 20, keyDownRemainder(20))
    }

    @Test fun a_long_hold_comes_up_at_once() {
        assertEquals(0L, keyDownRemainder(KEY_DOWN_MS))
        assertEquals(0L, keyDownRemainder(1500))
    }

    @Test fun a_clock_that_went_backwards_never_holds_longer_than_the_minimum() =
        assertEquals(KEY_DOWN_MS, keyDownRemainder(-40))

    @Test fun power_stays_down_longer_than_the_other_keys() {
        assertTrue(POWER_DOWN_MS > KEY_DOWN_MS)
        assertEquals(POWER_DOWN_MS, keyDownRemainder(0, POWER_DOWN_MS))
        assertEquals(POWER_DOWN_MS - KEY_DOWN_MS, keyDownRemainder(KEY_DOWN_MS, POWER_DOWN_MS))
        assertEquals(0L, keyDownRemainder(POWER_DOWN_MS, POWER_DOWN_MS))
    }

    @Test fun a_live_key_goes_all_the_way_down_and_a_locked_one_only_gives() {
        assertEquals(1f, keyTravelDepth(live = true), 0f)
        assertEquals(LOCKED_TRAVEL, keyTravelDepth(live = false), 0f)
        assertTrue(LOCKED_TRAVEL > 0f && LOCKED_TRAVEL < 0.5f)
    }

    @Test fun the_return_is_brisk() = assertTrue(KEY_UP_MS <= 100)
}
