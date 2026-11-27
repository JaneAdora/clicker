package com.janeadora.clicker.ui.remote

import android.view.KeyEvent
import com.janeadora.clicker.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.clicker_ffi.Key

class HardwareKeysTest {
    @Test fun volume_keys_map_when_enabled() =
        assertEquals(HardwareAction.Press(Key.VOL_UP), mapHardwareKey(KeyEvent.KEYCODE_VOLUME_UP, Settings()))

    @Test fun volume_keys_pass_through_when_disabled() =
        assertNull(mapHardwareKey(KeyEvent.KEYCODE_VOLUME_UP, Settings(volumeKeys = false)))

    @Test fun tui_bindings_carry_over() {
        val s = Settings()
        assertEquals(HardwareAction.Press(Key.BACK), mapHardwareKey(KeyEvent.KEYCODE_ESCAPE, s))
        assertEquals(HardwareAction.Press(Key.HOME), mapHardwareKey(KeyEvent.KEYCODE_H, s))
        assertEquals(HardwareAction.Press(Key.PLAY_PAUSE), mapHardwareKey(KeyEvent.KEYCODE_SPACE, s))
        assertEquals(HardwareAction.Press(Key.VOL_DOWN), mapHardwareKey(KeyEvent.KEYCODE_MINUS, s))
        assertEquals(HardwareAction.Launch("3"), mapHardwareKey(KeyEvent.KEYCODE_3, s))
        assertEquals(HardwareAction.Launch("0"), mapHardwareKey(KeyEvent.KEYCODE_0, s))
        assertEquals(HardwareAction.Press(Key.SELECT), mapHardwareKey(KeyEvent.KEYCODE_NUMPAD_ENTER, s))
        assertEquals(HardwareAction.Press(Key.UP), mapHardwareKey(KeyEvent.KEYCODE_DPAD_UP, s))
        assertEquals(HardwareAction.Press(Key.CHANNEL_DOWN), mapHardwareKey(KeyEvent.KEYCODE_PAGE_DOWN, s))
        assertEquals(HardwareAction.Press(Key.NEXT), mapHardwareKey(KeyEvent.KEYCODE_APOSTROPHE, s))
    }

    @Test fun keyboard_volume_keys_work_with_phone_volume_keys_off() =
        assertEquals(HardwareAction.Press(Key.VOL_UP), mapHardwareKey(KeyEvent.KEYCODE_EQUALS, Settings(volumeKeys = false)))

    @Test fun unknown_key_is_null() = assertNull(mapHardwareKey(KeyEvent.KEYCODE_F5, Settings()))

    @Test fun only_arrows_and_volume_repeat_when_held() {
        assertTrue(repeatsWhenHeld(HardwareAction.Press(Key.LEFT)))
        assertTrue(repeatsWhenHeld(HardwareAction.Press(Key.VOL_DOWN)))
        assertFalse(repeatsWhenHeld(HardwareAction.Press(Key.SELECT)))
        assertFalse(repeatsWhenHeld(HardwareAction.Press(Key.POWER)))
        assertFalse(repeatsWhenHeld(HardwareAction.Launch("1")))
    }

    private class Harness(
        var settings: Settings = Settings(),
        var typing: Boolean = false,
        var controlFocused: Boolean = false,
    ) {
        val done = mutableListOf<HardwareAction>()
        val firsts = mutableListOf<Boolean>()
        val router = HardwareKeyRouter({ settings }, { typing }, { controlFocused }) { action, first ->
            done += action
            firsts += first
        }
    }

    @Test fun held_power_acts_once_but_every_event_is_consumed() {
        val h = Harness()
        assertTrue(h.router.down(KeyEvent.KEYCODE_P, 0))
        assertTrue(h.router.down(KeyEvent.KEYCODE_P, 1))
        assertTrue(h.router.down(KeyEvent.KEYCODE_P, 2))
        assertTrue(h.router.up(KeyEvent.KEYCODE_P))
        assertEquals(listOf<HardwareAction>(HardwareAction.Press(Key.POWER)), h.done)
    }

    @Test fun held_arrow_repeats() {
        val h = Harness()
        h.router.down(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        h.router.down(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        assertEquals(2, h.done.size)
    }

    @Test fun up_is_consumed_only_for_keys_taken_on_down() {
        val h = Harness(settings = Settings(volumeKeys = false))
        assertFalse(h.router.down(KeyEvent.KEYCODE_VOLUME_UP, 0))
        assertFalse(h.router.up(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(h.router.up(KeyEvent.KEYCODE_H))
        assertTrue(h.done.isEmpty())
    }

    @Test fun keys_pass_through_while_typing() {
        val h = Harness(typing = true)
        assertFalse(h.router.down(KeyEvent.KEYCODE_H, 0))
        assertFalse(h.router.up(KeyEvent.KEYCODE_H))
        assertTrue(h.done.isEmpty())
    }

    @Test fun digits_and_enter_type_into_the_field_while_typing() {
        val h = Harness(typing = true)
        for (code in listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL)) {
            assertFalse(h.router.down(code, 0))
            assertFalse(h.router.up(code))
        }
        assertTrue(h.done.isEmpty())
    }

    @Test fun phone_volume_keys_still_reach_the_tv_while_typing() {
        val h = Harness(typing = true)
        assertTrue(h.router.down(KeyEvent.KEYCODE_VOLUME_UP, 0))
        assertTrue(h.router.down(KeyEvent.KEYCODE_VOLUME_UP, 1))
        assertTrue(h.router.up(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(h.router.down(KeyEvent.KEYCODE_VOLUME_DOWN, 0))
        assertEquals(
            listOf<HardwareAction>(HardwareAction.Press(Key.VOL_UP), HardwareAction.Press(Key.VOL_UP), HardwareAction.Press(Key.VOL_DOWN)),
            h.done,
        )
    }

    @Test fun phone_volume_keys_stay_with_the_phone_while_typing_when_the_setting_is_off() {
        val h = Harness(settings = Settings(volumeKeys = false), typing = true)
        assertFalse(h.router.down(KeyEvent.KEYCODE_VOLUME_UP, 0))
        assertTrue(h.done.isEmpty())
    }

    @Test fun auto_repeat_without_a_taken_down_passes_through() {
        // A key first pressed while typing, still held after the field lost focus.
        val h = Harness()
        assertFalse(h.router.down(KeyEvent.KEYCODE_DPAD_UP, 3))
        assertFalse(h.router.up(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(h.done.isEmpty())
    }

    @Test fun repeats_are_marked_so_feedback_comes_once_per_press() {
        val h = Harness()
        h.router.down(KeyEvent.KEYCODE_VOLUME_UP, 0)
        h.router.down(KeyEvent.KEYCODE_VOLUME_UP, 1)
        h.router.down(KeyEvent.KEYCODE_VOLUME_UP, 2)
        assertEquals(listOf(true, false, false), h.firsts)
    }

    @Test fun activation_keys_reach_a_focused_control() {
        val h = Harness(controlFocused = true)
        for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER)) {
            assertFalse(h.router.down(code, 0))
            assertFalse(h.router.up(code))
        }
        assertTrue(h.done.isEmpty())
    }

    @Test fun other_keys_still_go_to_the_tv_while_a_control_has_focus() {
        val h = Harness(controlFocused = true)
        assertTrue(h.router.down(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertTrue(h.router.down(KeyEvent.KEYCODE_H, 0))
        assertEquals(listOf<HardwareAction>(HardwareAction.Press(Key.LEFT), HardwareAction.Press(Key.HOME)), h.done)
    }

    @Test fun chords_pass_through() {
        val h = Harness()
        assertFalse(h.router.down(KeyEvent.KEYCODE_P, 0, chord = true))
        assertFalse(h.router.up(KeyEvent.KEYCODE_P))
        assertTrue(h.done.isEmpty())
    }
}
