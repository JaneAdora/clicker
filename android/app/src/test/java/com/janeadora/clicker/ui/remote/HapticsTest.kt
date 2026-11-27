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

    @Test fun a_quick_tap_has_no_release_haptic() {
        assertFalse(releaseHapticDue(0, repeated = false))
        assertFalse(releaseHapticDue(RELEASE_HAPTIC_AFTER_MS - 1, repeated = false))
    }

    @Test fun a_deliberate_hold_is_felt_on_release() {
        assertTrue(releaseHapticDue(RELEASE_HAPTIC_AFTER_MS, repeated = false))
        assertTrue(releaseHapticDue(2_000, repeated = false))
    }

    @Test fun a_key_that_repeated_is_never_felt_on_release() {
        assertFalse(releaseHapticDue(REPEAT_DELAY_MS + 1, repeated = true))
        assertFalse(releaseHapticDue(5_000, repeated = true))
    }

    @Test fun pairing_is_confirmed_only_when_a_sent_code_is_accepted() {
        assertTrue(pairingSucceeded(wasPairing = true, codeSent = true, nowLinking = true))
        // Cancelled, or the link dropped: not a success.
        assertFalse(pairingSucceeded(wasPairing = true, codeSent = true, nowLinking = false))
        // No code was sent, so the user did not cause this.
        assertFalse(pairingSucceeded(wasPairing = true, codeSent = false, nowLinking = true))
        // An ordinary connect or reconnect is silent.
        assertFalse(pairingSucceeded(wasPairing = false, codeSent = false, nowLinking = true))
    }
}
