package com.janeadora.clicker.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.janeadora.clicker.core.DpadStyle
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.Direction
import com.janeadora.clicker.ui.theme.DottedChevron
import com.janeadora.clicker.ui.theme.LocalClickerColors
import uniffi.clicker_ffi.Key
import kotlin.math.abs
import kotlin.math.sign

/** Finger travel that makes one step on the swipe pad. */
val SWIPE_STEP: Dp = 48.dp

/**
 * Turns finger travel into D-pad keys: every [stepPx] along the dominant
 * axis is one key. When a step fires, travel on the other axis is dropped,
 * so a slightly crooked swipe does not also step sideways.
 */
class SwipeRecognizer(private val stepPx: Float) {
    private var x = 0f
    private var y = 0f

    fun drag(dx: Float, dy: Float): List<Key> {
        x += dx
        y += dy
        val horizontal = abs(x) >= abs(y)
        val along = if (horizontal) x else y
        val steps = (abs(along) / stepPx).toInt()
        if (steps == 0) return emptyList()
        val key = when {
            horizontal -> if (along > 0f) Key.RIGHT else Key.LEFT
            else -> if (along > 0f) Key.DOWN else Key.UP
        }
        val rest = along - sign(along) * steps * stepPx
        if (horizontal) { x = rest; y = 0f } else { y = rest; x = 0f }
        return List(steps) { key }
    }

    /** Ends a gesture and starts the next one clean. A tap with no movement is Select. */
    fun end(moved: Boolean): Key? {
        x = 0f
        y = 0f
        return if (moved) null else Key.SELECT
    }
}

/** The D-pad, or the swipe pad in a square the same size when [style] asks for it. */
@Composable
fun DirectionPad(
    style: DpadStyle,
    enabled: Boolean,
    onPress: (Key) -> Boolean,
    modifier: Modifier = Modifier,
    size: Dp = DPAD_SIZE,
    okSize: Dp = 88.dp,
) {
    if (style == DpadStyle.Swipe) SwipePad(enabled, onPress, modifier.size(size)) else DPad(enabled, onPress, modifier, size, okSize)
}

/**
 * What the end of a swipe pad touch sends: Select for a clean tap, nothing
 * for a swipe, and nothing for a touch the system cancelled (its up comes
 * already consumed), so a back gesture or a dialog never sends a key.
 */
fun tapOutcome(moved: Boolean, upConsumed: Boolean): Key? = if (moved || upConsumed) null else Key.SELECT

private val SWIPE_ACTIONS = listOf("Up" to Key.UP, "Down" to Key.DOWN, "Left" to Key.LEFT, "Right" to Key.RIGHT, "Select" to Key.SELECT)

/**
 * The swipe pad that can stand in for the D-pad: a flat surface in the
 * key tone, in [shape], where a swipe moves and a tap is OK. Its size comes
 * from [modifier]; on the remote body it sits in the D-pad's compartment,
 * above Rewind and Fast forward. Each
 * step is sent as it happens. TalkBack gets Select on double tap and Up,
 * Down, Left, Right and Select as actions.
 */
@Composable
fun SwipePad(enabled: Boolean, onPress: (Key) -> Boolean, modifier: Modifier = Modifier, shape: Shape = UnitShapes.key) {
    val c = LocalClickerColors.current
    val press by rememberUpdatedState(onPress)
    val presser = rememberKeyPresser(enabled)
    val interaction = remember { MutableInteractionSource() }
    val select: (Key) -> Unit = { key -> presser.press { press(key) } }
    Box(
        modifier
            .clip(shape)
            .background(c.key)
            .border(ClickerShapes.border, c.outlineStrong, shape)
            .indication(interaction, rememberPressDots())
            .pointerInput(presser) {
                val recognizer = SwipeRecognizer(SWIPE_STEP.toPx())
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    recognizer.end(moved = true)
                    val pressed = PressInteraction.Press(down.position)
                    interaction.tryEmit(pressed)
                    var travel = Offset.Zero
                    var refused = false
                    // One buzz per swipe when no TV is connected, not one per step.
                    fun step(key: Key) {
                        if (!refused && !presser.press { press(key) }) refused = true
                    }
                    // Stays true unless a real (unconsumed) up arrives.
                    var cancelled = true
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            cancelled = change.isConsumed
                            change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        travel += delta
                        recognizer.drag(delta.x, delta.y).forEach(::step)
                        change.consume()
                    }
                    recognizer.end(moved = true)
                    tapOutcome(moved = travel.getDistance() > slop, upConsumed = cancelled)?.let(::step)
                    interaction.tryEmit(if (cancelled) PressInteraction.Cancel(pressed) else PressInteraction.Release(pressed))
                }
            }
            .clearAndSetSemantics {
                contentDescription = "Swipe pad"
                if (!enabled) stateDescription = NOT_CONNECTED
                onClick("Select") { select(Key.SELECT); true }
                customActions = SWIPE_ACTIONS.map { (label, key) -> CustomAccessibilityAction(label) { select(key); true } }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Four quiet arrows at the edges say which way a swipe goes.
        val hint = Modifier.padding(14.dp).size(22.dp).alpha(if (enabled) 1f else INACTIVE_ALPHA)
        DottedChevron(Direction.Up, c.outlineStrong, hint.align(Alignment.TopCenter))
        DottedChevron(Direction.Down, c.outlineStrong, hint.align(Alignment.BottomCenter))
        DottedChevron(Direction.Left, c.outlineStrong, hint.align(Alignment.CenterStart))
        DottedChevron(Direction.Right, c.outlineStrong, hint.align(Alignment.CenterEnd))
        Text(
            "SWIPE TO MOVE\nTAP FOR OK",
            Modifier.padding(16.dp).alpha(if (enabled) 1f else INACTIVE_ALPHA),
            style = ClickerType.plate.copy(lineHeight = 22.sp),
            color = c.muted,
            textAlign = TextAlign.Center,
        )
    }
}
