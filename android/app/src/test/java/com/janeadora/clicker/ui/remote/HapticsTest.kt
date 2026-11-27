package com.janeadora.clicker.ui.remote

import android.view.HapticFeedbackConstants as H
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.clicker_ffi.Key

class HapticsTest {
    // haptic, then the constant on API 26, 27, 29, 30, 33 and 34.
    private val table = listOf(
        Haptic.Nav to listOf(H.KEYBOARD_TAP, H.KEYBOARD_TAP, H.KEYBOARD_TAP, H.KEYBOARD_TAP, H.KEYBOARD_TAP, H.KEYBOARD_TAP),
        Haptic.Confirm to listOf(H.VIRTUAL_KEY, H.VIRTUAL_KEY, H.VIRTUAL_KEY, H.CONFIRM, H.CONFIRM, H.CONFIRM),
        Haptic.Power to listOf(H.VIRTUAL_KEY, H.VIRTUAL_KEY, H.VIRTUAL_KEY, H.LONG_PRESS, H.LONG_PRESS, H.LONG_PRESS),
        Haptic.Step to listOf(H.CLOCK_TICK, H.CLOCK_TICK, H.CLOCK_TICK, H.CLOCK_TICK, H.CLOCK_TICK, H.SEGMENT_TICK),
        Haptic.RepeatTick to listOf(H.CLOCK_TICK, H.CLOCK_TICK, H.CLOCK_TICK, H.CLOCK_TICK, H.CLOCK_TICK, H.SEGMENT_FREQUENT_TICK),
        Haptic.Release to listOf(null, H.KEYBOARD_RELEASE, H.KEYBOARD_RELEASE, H.KEYBOARD_RELEASE, H.KEYBOARD_RELEASE, H.KEYBOARD_RELEASE),
        Haptic.Reject to listOf(H.LONG_PRESS, H.LONG_PRESS, H.LONG_PRESS, H.REJECT, H.REJECT, H.REJECT),
        Haptic.Paired to listOf(H.VIRTUAL_KEY, H.VIRTUAL_KEY, H.VIRTUAL_KEY, H.CONFIRM, H.CONFIRM, H.CONFIRM),
    )
    private val levels = listOf(26, 27, 29, 30, 33, 34)

    @Test fun every_haptic_has_its_constant_at_every_api_level() {
        assertEquals(Haptic.entries.toSet(), table.map { it.first }.toSet())
        for ((haptic, expected) in table) {
            for ((i, sdk) in levels.withIndex()) {
                assertEquals("$haptic on API $sdk", expected[i], hapticConstant(haptic, sdk))
            }
        }
    }

    @Test fun only_the_release_on_api_26_is_silent() {
        for (haptic in Haptic.entries) for (sdk in 26..37) {
            if (haptic == Haptic.Release && sdk == 26) assertNull(hapticConstant(haptic, sdk)) else assertTrue(hapticConstant(haptic, sdk) != null)
        }
    }

    @Test fun a_reject_never_shares_a_constant_with_an_accepted_press() {
        for (sdk in 26..37) {
            val reject = hapticConstant(Haptic.Reject, sdk)
            for (feel in KeyFeel.entries) assertNotEquals("$feel on API $sdk", reject, hapticConstant(pressHaptic(feel), sdk))
            assertNotEquals(reject, hapticConstant(Haptic.RepeatTick, sdk))
        }
    }

    @Test fun on_current_phones_the_five_kinds_of_press_are_five_constants() {
        val now = listOf(Haptic.Nav, Haptic.Confirm, Haptic.Power, Haptic.Step, Haptic.RepeatTick, Haptic.Reject).map { hapticConstant(it, 34) }
        assertEquals(now.size, now.toSet().size)
    }

    @Test fun each_key_has_its_feel() {
        assertEquals(KeyFeel.Confirm, feelFor(Key.SELECT))
        assertEquals(KeyFeel.Power, feelFor(Key.POWER))
        assertEquals(KeyFeel.Step, feelFor(Key.VOL_UP))
        assertEquals(KeyFeel.Step, feelFor(Key.VOL_DOWN))
        for (key in listOf(Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.BACK, Key.HOME, Key.MENU, Key.MUTE, Key.REWIND, Key.FAST_FORWARD, Key.PLAY_PAUSE)) {
            assertEquals(KeyFeel.Nav, feelFor(key))
        }
    }

    @Test fun every_feel_has_a_press_haptic_of_its_own() {
        assertEquals(KeyFeel.entries.size, KeyFeel.entries.map(::pressHaptic).toSet().size)
    }

    // feel, how long it was held, whether it repeated, then whether letting go is felt.
    private val releases = listOf(
        Release(KeyFeel.Nav, 0, repeated = false, felt = false),
        Release(KeyFeel.Nav, RELEASE_HAPTIC_AFTER_MS - 1, repeated = false, felt = false),
        Release(KeyFeel.Nav, RELEASE_HAPTIC_AFTER_MS, repeated = false, felt = true),
        Release(KeyFeel.Nav, 2_000, repeated = false, felt = true),
        Release(KeyFeel.Nav, REPEAT_DELAY_MS + 1, repeated = true, felt = false),
        Release(KeyFeel.Nav, 5_000, repeated = true, felt = false),
        Release(KeyFeel.Confirm, RELEASE_HAPTIC_AFTER_MS - 1, repeated = false, felt = false),
        Release(KeyFeel.Confirm, RELEASE_HAPTIC_AFTER_MS, repeated = false, felt = true),
        Release(KeyFeel.Step, RELEASE_HAPTIC_AFTER_MS, repeated = false, felt = true),
        Release(KeyFeel.Step, 5_000, repeated = true, felt = false),
        Release(KeyFeel.Power, 0, repeated = false, felt = false),
        Release(KeyFeel.Power, RELEASE_HAPTIC_AFTER_MS, repeated = false, felt = false),
        Release(KeyFeel.Power, 2_000, repeated = false, felt = false),
        Release(KeyFeel.Power, 5_000, repeated = true, felt = false),
    )

    private data class Release(val feel: KeyFeel, val heldMs: Long, val repeated: Boolean, val felt: Boolean)

    @Test fun letting_go_is_felt_only_after_a_deliberate_hold_that_did_not_repeat() {
        for (r in releases) assertEquals("$r", r.felt, releaseHapticDue(r.feel, r.heldMs, r.repeated))
    }

    @Test fun letting_go_of_power_is_never_felt() {
        for (held in listOf(0L, RELEASE_HAPTIC_AFTER_MS, 60_000L)) for (repeated in listOf(false, true)) {
            assertFalse(releaseHapticDue(KeyFeel.Power, held, repeated))
        }
        for (feel in KeyFeel.entries - KeyFeel.Power) assertTrue("$feel", releaseHapticDue(feel, RELEASE_HAPTIC_AFTER_MS, repeated = false))
    }

    @Test fun pairing_is_confirmed_only_when_the_accepted_count_rises() {
        assertTrue(pairingAccepted(seen = 0, accepted = 1))
        assertTrue(pairingAccepted(seen = 3, accepted = 4))
        // Nothing accepted since the last look: a connect, a reconnect, another TV picked.
        assertFalse(pairingAccepted(seen = 0, accepted = 0))
        assertFalse(pairingAccepted(seen = 2, accepted = 2))
    }
}
