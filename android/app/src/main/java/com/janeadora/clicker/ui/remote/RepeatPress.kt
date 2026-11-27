package com.janeadora.clicker.ui.remote

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.util.Log
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.janeadora.clicker.core.LOG_TAG
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A held key first repeats this long after the press. */
const val REPEAT_DELAY_MS = 400L

/** Then it repeats this often until released. */
const val REPEAT_INTERVAL_MS = 110L

/** When the [n]th press of a held key fires, from the moment it went down. The first is n = 0. */
fun pressAt(n: Int): Long = if (n == 0) 0L else REPEAT_DELAY_MS + (n - 1) * REPEAT_INTERVAL_MS

/**
 * Emits press times for a held button: one at 0ms, then after 400ms every
 * 110ms until released. A repeat due at the moment of release does not fire.
 */
fun repeatSchedule(heldMs: Long): List<Long> = buildList {
    add(0L)
    var n = 1
    while (pressAt(n) < heldMs) add(pressAt(n++))
}

/**
 * The scheduled press time that comes next strictly after [elapsedMs] from
 * the press. Slots already passed (after the main thread stalled) are
 * skipped, never sent late.
 */
fun nextTick(elapsedMs: Long): Long =
    if (elapsedMs < REPEAT_DELAY_MS) {
        REPEAT_DELAY_MS
    } else {
        REPEAT_DELAY_MS + ((elapsedMs - REPEAT_DELAY_MS) / REPEAT_INTERVAL_MS + 1) * REPEAT_INTERVAL_MS
    }

/** How presses feel: whether haptics are on, and what to do when a press finds no TV. */
class KeyFeedback(val haptics: Boolean, val onNotConnected: () -> Unit)

/** Must be provided (see [ProvideKeyFeedback]); a key outside a provider fails at once. */
val LocalKeyFeedback = compositionLocalOf<KeyFeedback> { error("LocalKeyFeedback not provided") }

/**
 * Provides key feedback to [content], which should hold the remote and any
 * sheet opened from it, so every key follows the same Haptics setting.
 */
@Composable
fun ProvideKeyFeedback(haptics: Boolean, onNotConnected: () -> Unit, content: @Composable () -> Unit) {
    val notConnected by rememberUpdatedState(onNotConnected)
    val feedback = remember(haptics) { KeyFeedback(haptics) { notConnected() } }
    CompositionLocalProvider(LocalKeyFeedback provides feedback, content = content)
}

/** The reject haptic: REJECT where the phone has it (API 30), LONG_PRESS before. */
val REJECT_HAPTIC = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS

/**
 * Plays haptic [constant] on [view] when [on]. Debug builds log each call
 * (tag Clicker), so the Haptics setting can be checked on an emulator.
 */
fun playHaptic(view: View, constant: Int, on: Boolean) {
    debugLog(view.context, if (on) "haptic $constant" else "haptic $constant skipped, haptics off")
    if (on) view.performHapticFeedback(constant)
}

/** A logcat line (tag Clicker) in debug builds only, for checks on an emulator. */
fun debugLog(context: Context, message: String) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d(LOG_TAG, message)
}

/** How one press went. Only [Sent] feels accepted. */
enum class PressResult(val accepted: Boolean) { Sent(true), Refused(false), NotConnected(false) }

/**
 * Tries one press: nothing while not [enabled] (no TV); otherwise [send],
 * which says whether the key went out. The core can still refuse one (the
 * link dropped this instant, or its queue is full).
 */
fun pressResult(enabled: Boolean, send: () -> Boolean): PressResult = when {
    !enabled -> PressResult.NotConnected
    send() -> PressResult.Sent
    else -> PressResult.Refused
}

/**
 * Sends keys with their feedback. Touch, TalkBack and hold-to-repeat all
 * go through here, so they cannot drift apart.
 */
class KeyPresser internal constructor(
    private val view: View,
    private val feedback: State<KeyFeedback>,
    private val enabled: State<Boolean>,
    private val live: () -> Boolean,
) {
    /**
     * One press. Connected: [send] runs at once and returns whether the key
     * went out; then a tap haptic if it did. Not connected, or refused:
     * a reject haptic plays and the screen says why. Returns true when sent.
     */
    fun press(send: () -> Boolean): Boolean {
        val result = pressResult(enabled.value, send)
        if (result.accepted) {
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        } else {
            haptic(REJECT_HAPTIC)
            feedback.value.onNotConnected()
        }
        return result.accepted
    }

    /**
     * One repeat of a held key. Returns false once the TV is gone, the app
     * is no longer in front, or a repeat was refused; the repeat then stops.
     */
    fun tick(send: () -> Boolean): Boolean {
        if (!enabled.value || !live()) return false
        if (!send()) return false
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        return true
    }

    private fun haptic(constant: Int) = playHaptic(view, constant, feedback.value.haptics)
}

/** A [KeyPresser] for keys that work while [enabled] (a TV is connected). */
@Composable
fun rememberKeyPresser(enabled: Boolean): KeyPresser {
    val view = LocalView.current
    val feedback = rememberUpdatedState(LocalKeyFeedback.current)
    val on = rememberUpdatedState(enabled)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalWindowInfo.current
    return remember(view, lifecycle, window) {
        KeyPresser(view, feedback, on) {
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && window.isWindowFocused
        }
    }
}

/**
 * What a press does, decided where the finger lands. [press] runs on the
 * down and returns true when it sent; [tick] runs for each repeat when
 * [repeat] is set and returns false to stop.
 */
class KeyHold(val repeat: Boolean, val press: () -> Boolean, val tick: () -> Boolean)

/**
 * The one gesture for every remote key. The press fires on the down, in the
 * same input event, before any frame is drawn; [interaction] gets the press
 * afterwards for the visual. A held key repeats on [nextTick]'s clock until
 * release, until the finger leaves the key ([stillOn]), or until something
 * else (the screen's scroll, a system gesture) takes the pointer. A finger
 * that drags never changes or re-fires the key.
 */
suspend fun PointerInputScope.detectKeyPresses(
    interaction: MutableInteractionSource,
    stillOn: (Offset) -> Boolean = { it.x in 0f..size.width.toFloat() && it.y in 0f..size.height.toFloat() },
    holdAt: (Offset) -> KeyHold?,
) = coroutineScope {
    awaitEachGesture {
        val down = awaitFirstDown()
        val hold = holdAt(down.position) ?: return@awaitEachGesture
        down.consume()
        val start = SystemClock.uptimeMillis()
        val sent = hold.press()
        val press = PressInteraction.Press(down.position)
        interaction.tryEmit(press)
        val repeater = if (sent && hold.repeat) {
            launch {
                var due = nextTick(0)
                while (true) {
                    delay((start + due - SystemClock.uptimeMillis()).coerceAtLeast(0L))
                    if (!hold.tick()) break
                    due = nextTick(SystemClock.uptimeMillis() - start)
                }
            }
        } else {
            null
        }
        val released = try {
            awaitUpOrLeave(down.id, stillOn)
        } finally {
            repeater?.cancel()
        }
        interaction.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
    }
}

// True on release; false when the finger leaves or the pointer is taken.
// A system cancel arrives as an up that is already consumed: not a release.
private suspend fun AwaitPointerEventScope.awaitUpOrLeave(id: PointerId, stillOn: (Offset) -> Boolean): Boolean {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed) {
            val released = !change.isConsumed
            change.consume()
            return released
        }
        if (change.isConsumed || !stillOn(change.position)) return false
        // A parent that takes the drag consumes it after us, on the Final pass.
        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.id == id && it.isConsumed }) return false
    }
}
