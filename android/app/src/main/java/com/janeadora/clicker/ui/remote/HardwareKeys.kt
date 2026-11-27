package com.janeadora.clicker.ui.remote

import android.view.KeyEvent
import com.janeadora.clicker.core.Settings
import uniffi.clicker_ffi.Key

/** What a physical key does on the remote. */
sealed interface HardwareAction {
    data class Press(val key: Key) : HardwareAction
    data class Launch(val slot: String) : HardwareAction
}

/**
 * The remote action for a physical key, or null to leave the key to the
 * system. The phone's volume keys follow [Settings.volumeKeys]; a keyboard
 * uses the TUI's bindings.
 */
fun mapHardwareKey(keyCode: Int, settings: Settings): HardwareAction? {
    if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
        return HardwareAction.Launch((keyCode - KeyEvent.KEYCODE_0).toString())
    }
    val key = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> if (settings.volumeKeys) Key.VOL_UP else null
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (settings.volumeKeys) Key.VOL_DOWN else null
        KeyEvent.KEYCODE_DPAD_UP -> Key.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.SELECT
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> Key.BACK
        KeyEvent.KEYCODE_H -> Key.HOME
        KeyEvent.KEYCODE_P -> Key.POWER
        KeyEvent.KEYCODE_S -> Key.SETTINGS
        KeyEvent.KEYCODE_O -> Key.MENU
        KeyEvent.KEYCODE_I -> Key.INPUT
        KeyEvent.KEYCODE_V -> Key.ASSIST
        KeyEvent.KEYCODE_M -> Key.MUTE
        KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> Key.VOL_UP
        KeyEvent.KEYCODE_MINUS -> Key.VOL_DOWN
        KeyEvent.KEYCODE_PAGE_UP -> Key.CHANNEL_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> Key.CHANNEL_DOWN
        KeyEvent.KEYCODE_SPACE -> Key.PLAY_PAUSE
        KeyEvent.KEYCODE_X -> Key.STOP
        KeyEvent.KEYCODE_COMMA -> Key.REWIND
        KeyEvent.KEYCODE_PERIOD -> Key.FAST_FORWARD
        KeyEvent.KEYCODE_SEMICOLON -> Key.PREV
        KeyEvent.KEYCODE_APOSTROPHE -> Key.NEXT
        else -> null
    }
    return key?.let { HardwareAction.Press(it) }
}

/** True for the keys a held physical key may repeat: arrows and volume. Others act once per press. */
fun repeatsWhenHeld(action: HardwareAction): Boolean =
    action is HardwareAction.Press && action.key in HELD_REPEAT

private val HELD_REPEAT = setOf(Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.VOL_UP, Key.VOL_DOWN)

private val PHONE_VOLUME = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)

// Keys that activate whatever control has keyboard focus.
private val ACTIVATE = setOf(
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_SPACE,
    KeyEvent.KEYCODE_DPAD_CENTER,
)

/**
 * Turns physical key events into remote actions. A key taken on its first
 * down keeps every later event (repeats and the up) away from the system,
 * so it never also moves focus or changes the phone's volume. While
 * [typing] (a text field in the app has focus) every key but the phone's
 * volume keys is left to the field, and Enter, Space and centre are left
 * to a focused on-screen control
 * ([controlFocused]) such as Retry or a shortcut tile. [perform] gets
 * `first = false` for the repeats of a held key.
 */
class HardwareKeyRouter(
    private val settings: () -> Settings,
    private val typing: () -> Boolean,
    private val controlFocused: () -> Boolean,
    private val perform: (action: HardwareAction, first: Boolean) -> Unit,
) {
    private val taken = mutableSetOf<Int>()

    /**
     * Returns true when the event is consumed. [chord] is true while Ctrl,
     * Alt or Meta is held; those keys belong to the system (Ctrl+P is not Power).
     */
    fun down(keyCode: Int, repeatCount: Int, chord: Boolean = false): Boolean {
        if (repeatCount > 0) {
            if (keyCode !in taken) return false
            val action = mapHardwareKey(keyCode, settings()) ?: return true
            if (repeatsWhenHeld(action)) perform(action, false)
            return true
        }
        taken.remove(keyCode)
        if (chord) return false
        // The phone's volume keys type nothing, so they still reach the TV.
        if (typing() && keyCode !in PHONE_VOLUME) return false
        if (keyCode in ACTIVATE && controlFocused()) return false
        val action = mapHardwareKey(keyCode, settings()) ?: return false
        taken += keyCode
        perform(action, true)
        return true
    }

    /** Returns true when the matching down was consumed. */
    fun up(keyCode: Int): Boolean = taken.remove(keyCode)
}
