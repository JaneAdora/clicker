package com.janeadora.clicker.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.clicker_ffi.Key

class SwipeRecognizerTest {
    @Test fun long_right_drag_emits_two_rights() = assertEquals(listOf(Key.RIGHT, Key.RIGHT), SwipeRecognizer(48f).drag(100f, 5f))

    @Test fun diagonal_uses_dominant_axis() = assertEquals(listOf(Key.UP), SwipeRecognizer(48f).drag(20f, -60f))

    @Test fun small_moves_accumulate() {
        val r = SwipeRecognizer(48f)
        assertTrue(r.drag(30f, 0f).isEmpty())
        assertEquals(listOf(Key.RIGHT), r.drag(30f, 0f))
    }

    @Test fun tap_is_select() = assertEquals(Key.SELECT, SwipeRecognizer(48f).end(moved = false))

    @Test fun a_swipe_does_not_end_in_select() {
        val r = SwipeRecognizer(48f)
        r.drag(0f, 60f)
        assertNull(r.end(moved = true))
    }

    @Test fun drift_on_the_other_axis_is_dropped_after_a_step() {
        val r = SwipeRecognizer(48f)
        assertEquals(listOf(Key.UP), r.drag(20f, -60f))
        // The 20px of sideways drift went with the Up step.
        assertTrue(r.drag(30f, 0f).isEmpty())
    }

    @Test fun left_and_down() {
        val r = SwipeRecognizer(48f)
        assertEquals(listOf(Key.LEFT), r.drag(-50f, 0f))
        assertEquals(listOf(Key.DOWN), r.drag(0f, 50f))
    }

    @Test fun a_new_gesture_starts_clean() {
        val r = SwipeRecognizer(48f)
        r.drag(40f, 0f)
        r.end(moved = true)
        assertTrue(r.drag(10f, 0f).isEmpty())
    }

    @Test fun a_clean_tap_is_select() = assertEquals(Key.SELECT, tapOutcome(moved = false, upConsumed = false))

    // A system cancel arrives as an up that is already consumed.
    @Test fun a_cancelled_touch_sends_nothing() = assertNull(tapOutcome(moved = false, upConsumed = true))

    @Test fun a_swipe_ends_with_nothing() {
        assertNull(tapOutcome(moved = true, upConsumed = false))
        assertNull(tapOutcome(moved = true, upConsumed = true))
    }
}
