package com.janeadora.clicker.ui.remote

import android.os.Build
import android.view.HapticFeedbackConstants
import uniffi.clicker_ffi.Key

/** How a key should feel under the finger when its press is accepted. */
enum class KeyFeel {
    /** Arrows, Back, Home, Menu, transport, shortcuts and the More keys: a light tap. */
    Nav,

    /** OK: a confirm. */
    Confirm,

    /** Power: the most deliberate press on the remote. */
    Power,

    /** Volume up and down: one light step. */
    Step,
}

/** The feel of [key] when pressed. */
fun feelFor(key: Key): KeyFeel = when (key) {
    Key.SELECT -> KeyFeel.Confirm
    Key.POWER -> KeyFeel.Power
    Key.VOL_UP, Key.VOL_DOWN -> KeyFeel.Step
    else -> KeyFeel.Nav
}

/** Every moment the app answers with a haptic. */
enum class Haptic {
    Nav, Confirm, Power, Step,

    /** One repeat of a held key. */
    RepeatTick,

    /** A raised key let go after a deliberate hold. */
    Release,

    /** A press that found no TV, or that the TV link refused. */
    Reject,

    /** A pairing code the TV accepted. */
    Paired,
}

/** The haptic for an accepted press with [feel]. */
fun pressHaptic(feel: KeyFeel): Haptic = when (feel) {
    KeyFeel.Nav -> Haptic.Nav
    KeyFeel.Confirm -> Haptic.Confirm
    KeyFeel.Power -> Haptic.Power
    KeyFeel.Step -> Haptic.Step
}

/**
 * The platform constant for [haptic] on API level [sdk], or null where that
 * level has nothing fitting and the moment stays silent. Each constant is
 * picked for what the platform says it is for:
 *
 * - Nav: KEYBOARD_TAP, a press on a soft key.
 * - Confirm and Paired: CONFIRM from API 30, the end of an interaction that
 *   worked; VIRTUAL_KEY before.
 * - Power: LONG_PRESS, the platform's heaviest click, from API 30. Before
 *   that LONG_PRESS is the reject, so Power takes VIRTUAL_KEY.
 * - Step: SEGMENT_TICK from API 34, one step between discrete choices;
 *   CLOCK_TICK before.
 * - RepeatTick: SEGMENT_FREQUENT_TICK from API 34, steps in quick succession
 *   and the softest tick there is; CLOCK_TICK before.
 * - Release: KEYBOARD_RELEASE from API 27; nothing on API 26.
 * - Reject: REJECT from API 30; LONG_PRESS before.
 */
fun hapticConstant(haptic: Haptic, sdk: Int = Build.VERSION.SDK_INT): Int? = when (haptic) {
    Haptic.Nav -> HapticFeedbackConstants.KEYBOARD_TAP
    Haptic.Confirm, Haptic.Paired -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    Haptic.Power -> if (sdk >= 30) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
    Haptic.Step -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    Haptic.RepeatTick -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    Haptic.Release -> if (sdk >= 27) HapticFeedbackConstants.KEYBOARD_RELEASE else null
    Haptic.Reject -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
}

/** A key must be held at least this long for letting go to be felt. */
const val RELEASE_HAPTIC_AFTER_MS = 180L

/**
 * Whether letting go of a key is felt. Only after a deliberate hold of
 * [RELEASE_HAPTIC_AFTER_MS]: on a quick tap the release would run into the
 * press. Never after a key [repeated], where one more tick would feel like
 * one more step that was not sent.
 */
fun releaseHapticDue(heldMs: Long, repeated: Boolean): Boolean = !repeated && heldMs >= RELEASE_HAPTIC_AFTER_MS

/**
 * True when the link went from pairing, with a code sent, to being on its
 * way or up: the TV accepted the code the user typed.
 */
fun pairingSucceeded(wasPairing: Boolean, codeSent: Boolean, nowLinking: Boolean): Boolean = wasPairing && codeSent && nowLinking
