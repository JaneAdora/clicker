package com.janeadora.clicker.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatPressTest {
    @Test fun tap_fires_once() = assertEquals(listOf(0L), repeatSchedule(120))

    @Test fun hold_repeats_after_400ms_every_110ms() = assertEquals(listOf(0L, 400L, 510L, 620L), repeatSchedule(650))

    @Test fun release_exactly_at_a_repeat_does_not_fire_it() = assertEquals(listOf(0L), repeatSchedule(REPEAT_DELAY_MS))

    @Test fun press_times_follow_the_constants() {
        assertEquals(0L, pressAt(0))
        assertEquals(REPEAT_DELAY_MS, pressAt(1))
        assertEquals(REPEAT_DELAY_MS + 2 * REPEAT_INTERVAL_MS, pressAt(3))
    }

    @Test fun next_tick_from_the_press_is_the_first_repeat() = assertEquals(400L, nextTick(0))

    @Test fun next_tick_after_a_repeat_is_one_interval_on() {
        assertEquals(510L, nextTick(400))
        assertEquals(510L, nextTick(405))
    }

    @Test fun a_stall_drops_missed_repeats() = assertEquals(950L, nextTick(850))

    @Test fun next_tick_follows_the_schedule() {
        assertEquals(REPEAT_DELAY_MS, nextTick(0))
        assertEquals(REPEAT_DELAY_MS + REPEAT_INTERVAL_MS, nextTick(REPEAT_DELAY_MS))
        for (elapsed in 0L..2_000L) {
            assertEquals(repeatSchedule(elapsed + 1_000).first { it > elapsed }, nextTick(elapsed))
        }
    }
}

class PressResultTest {
    @Test fun a_sent_key_is_sent() = assertEquals(PressResult.Sent, pressResult(enabled = true) { true })

    @Test fun a_key_the_core_refused_is_refused() = assertEquals(PressResult.Refused, pressResult(enabled = true) { false })

    @Test fun with_no_tv_nothing_is_tried() {
        var tried = false
        assertEquals(PressResult.NotConnected, pressResult(enabled = false) { tried = true; true })
        assertEquals(false, tried)
    }

    @Test fun only_a_sent_key_gets_the_accepted_feel() {
        assertEquals(true, PressResult.Sent.accepted)
        assertEquals(false, PressResult.Refused.accepted)
        assertEquals(false, PressResult.NotConnected.accepted)
    }
}
