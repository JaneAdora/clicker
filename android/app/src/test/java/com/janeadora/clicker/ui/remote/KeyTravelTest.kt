package com.janeadora.clicker.ui.remote

import org.junit.Assert.assertEquals
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
}
