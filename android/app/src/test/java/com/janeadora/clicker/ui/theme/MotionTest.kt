package com.janeadora.clicker.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun the_entrance_starts_at_nothing_and_ends_whole() {
        assertEquals(0f, entranceFront(0), 0f)
        assertEquals(1f, entranceFront(ENTRANCE_MS), 0f)
    }

    @Test fun the_entrance_stays_at_its_end_state_however_late_the_clock() {
        assertEquals(1f, entranceFront(ENTRANCE_MS + 1), 0f)
        assertEquals(1f, entranceFront(60_000), 0f)
        assertEquals(0f, entranceFront(-5), 0f)
    }

    @Test fun the_front_only_moves_forward_and_is_most_of_the_way_by_half_time() {
        var last = 0f
        for (ms in 0..ENTRANCE_MS step 4) {
            val f = entranceFront(ms)
            assertTrue(f >= last)
            last = f
        }
        assertTrue(entranceFront(ENTRANCE_MS / 2) > 0.75f)
    }

    @Test fun the_entrance_is_short() = assertTrue(ENTRANCE_MS in 450..600)

    @Test fun with_the_front_at_its_end_everything_on_screen_has_arrived() {
        for (d in listOf(0f, 0.3f, 0.99f, 1f)) assertEquals(1f, arrived(1f, d), 0f)
    }

    @Test fun before_the_front_arrives_there_is_nothing() {
        assertEquals(0f, arrived(0f, 0.01f), 0f)
        assertEquals(0f, arrived(0.2f, 0.9f), 0f)
    }

    @Test fun things_nearer_the_seed_arrive_first() {
        assertTrue(arrived(0.4f, 0.1f) > arrived(0.4f, 0.45f))
        assertEquals(1f, arrived(0.5f, 0.1f), 0f)
    }

    @Test fun even_the_far_corner_is_whole_just_as_the_front_ends() {
        assertTrue(arrived(0.999f, 1f) > 0.99f)
    }

    @Test fun seed_distance_runs_from_the_top_right_corner_to_the_bottom_left() {
        assertEquals(0f, seedDistance(360f, 0f, 360f, 780f), 0f)
        assertEquals(1f, seedDistance(0f, 780f, 360f, 780f), 0.0001f)
        assertTrue(seedDistance(180f, 390f, 360f, 780f) in 0.49f..0.51f)
        assertEquals(0f, seedDistance(5f, 5f, 0f, 0f), 0f)
    }

    @Test fun the_entrance_plays_once_for_a_fresh_start() {
        val gate = EntranceGate()
        assertTrue(gate.claim(fresh = true))
        assertFalse(gate.claim(fresh = true))
    }

    @Test fun a_rotation_or_a_return_never_plays_the_entrance() {
        val gate = EntranceGate()
        assertTrue(gate.claim(fresh = true))
        // A rotation rebuilds the activity from saved state.
        assertFalse(gate.claim(fresh = false))
        // Leaving with Back and opening the app again, in the same process.
        assertFalse(gate.claim(fresh = true))
    }

    @Test fun an_activity_rebuilt_from_saved_state_uses_up_the_entrance() {
        val gate = EntranceGate()
        assertFalse(gate.claim(fresh = false))
        assertFalse(gate.claim(fresh = true))
    }

    @Test fun the_link_light_breathes_between_dim_and_full_and_starts_full() {
        assertEquals(1f, linkPulse(0), 0.0001f)
        assertEquals(0f, linkPulse(LINK_PULSE_MS / 2), 0.0001f)
        assertEquals(1f, linkPulse(LINK_PULSE_MS), 0.0001f)
        for (ms in 0..3 * LINK_PULSE_MS step 7) assertTrue(linkPulse(ms) in 0f..1f)
    }
}
