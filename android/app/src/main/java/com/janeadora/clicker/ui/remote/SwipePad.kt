package com.janeadora.clicker.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
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
import com.janeadora.clicker.ui.theme.Settle
import com.janeadora.clicker.ui.theme.reduceMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
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

/** How small the ring under the finger starts again at each step, as a share of full size. */
const val STEP_RING = 0.5f

// What the swipe pad shows while it is touched. A ring of dots grows under
// the finger and goes with it. Each step that is sent starts the ring again
// from half size and lights the edge arrow for that direction, which
// settles. A tap that sends OK lets one ring of dots go from where the
// finger was. With reduce motion the ring shows whole and nothing else moves.
private class SwipeMarks(private val scope: CoroutineScope, private val instant: () -> Boolean) {
    var at by mutableStateOf(Offset.Unspecified)
    val ring = Animatable(0f)
    val confirm = Animatable(1f)
    var confirmAt = Offset.Zero
    val lit = listOf(Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT).associateWith { Animatable(0f) }

    fun down(p: Offset) {
        at = p
        scope.launch {
            if (instant()) {
                ring.snapTo(1f)
            } else {
                ring.snapTo(0f)
                ring.animateTo(1f, tween(PRESS_GROW_MS, easing = LinearOutSlowInEasing))
            }
        }
    }

    fun stepped(key: Key) {
        if (instant()) return
        scope.launch {
            ring.snapTo(STEP_RING)
            ring.animateTo(1f, tween(PRESS_GROW_MS, easing = LinearOutSlowInEasing))
        }
        val arrow = lit[key] ?: return
        scope.launch {
            arrow.snapTo(1f)
            arrow.animateTo(0f, tween(ARROW_SETTLE_MS, easing = Settle))
        }
    }

    fun up(sentOk: Boolean) {
        val from = at
        scope.launch {
            ring.snapTo(0f)
            if (sentOk && !instant() && from.isSpecified) {
                confirmAt = from
                confirm.snapTo(0f)
                confirm.animateTo(1f, tween(CONFIRM_MS, easing = LinearOutSlowInEasing))
            }
        }
    }

    fun draw(scope: DrawScope, color: Color) = with(scope) {
        val reach = min(size.minDimension * 0.32f, 26.dp.toPx())
        val g = ring.value
        if (g > 0f && at.isSpecified) dots(color, at, reach * g, 2.2.dp.toPx() * (0.4f + 0.6f * g), 10)
        val p = confirm.value
        if (p < 1f) dots(color, confirmAt, reach + (6.dp + 10.dp * p).toPx(), 2.4.dp.toPx() * confirmDot(p), 16)
    }

    private fun DrawScope.dots(color: Color, centre: Offset, radius: Float, dot: Float, count: Int) {
        for (k in 0 until count) {
            val a = 2 * PI * k / count
            drawCircle(color, dot, Offset(centre.x + radius * cos(a).toFloat(), centre.y + radius * sin(a).toFloat()))
        }
    }
}

/**
 * The swipe pad that can stand in for the D-pad: a flat surface in the
 * key tone, in [shape], where a swipe moves and a tap is OK. Its size comes
 * from [modifier]; on the remote body it sits in the D-pad's compartment
 * ([inBay]), above Rewind and Fast forward. Each
 * step is sent as it happens. A ring of dots goes with the finger; each
 * step sent starts it again and lights the arrow for that direction, and a
 * tap that sends OK lets one ring of dots go, as OK does on the D-pad. With
 * no TV nothing lights. TalkBack gets Select on double tap and Up,
 * Down, Left, Right and Select as actions.
 */
@Composable
fun SwipePad(enabled: Boolean, onPress: (Key) -> Boolean, modifier: Modifier = Modifier, shape: Shape = UnitShapes.key, inBay: Boolean = false) {
    val c = LocalClickerColors.current
    val press by rememberUpdatedState(onPress)
    val live by rememberUpdatedState(enabled)
    val presser = rememberKeyPresser(enabled)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val marks = remember(scope, context) { SwipeMarks(scope) { reduceMotion(context) } }
    val select: (Key) -> Unit = { key -> presser.press(feelFor(key)) { press(key) } }
    Box(
        modifier
            .clip(shape)
            .background(c.key)
            .border(ClickerShapes.border, if (inBay) c.bayKeyLine else c.outlineStrong, shape)
            .drawWithContent {
                drawContent()
                marks.draw(this, c.accent)
            }
            .pointerInput(presser) {
                val recognizer = SwipeRecognizer(SWIPE_STEP.toPx())
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    recognizer.end(moved = true)
                    if (live) marks.down(down.position)
                    var travel = Offset.Zero
                    var refused = false
                    // One buzz per swipe when no TV is connected, not one per step.
                    // The marks follow the key: it has gone before anything lights.
                    fun step(key: Key): Boolean {
                        if (refused) return false
                        val sent = presser.press(feelFor(key)) { press(key) }
                        if (sent) marks.stepped(key) else refused = true
                        return sent
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
                        recognizer.drag(delta.x, delta.y).forEach { step(it) }
                        if (live) marks.at = change.position
                        change.consume()
                    }
                    recognizer.end(moved = true)
                    val sentOk = tapOutcome(moved = travel.getDistance() > slop, upConsumed = cancelled)?.let { step(it) } == true
                    marks.up(sentOk)
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
        // Four quiet arrows at the edges say which way a swipe goes; each lights when its step is sent.
        val hint = Modifier.padding(14.dp).size(22.dp).alpha(if (enabled) 1f else INACTIVE_ALPHA)
        val ink = c.outlineStrong
        DottedChevron(Direction.Up, ink, hint.align(Alignment.TopCenter), swellColor = c.accent) { marks.lit.getValue(Key.UP).value }
        DottedChevron(Direction.Down, ink, hint.align(Alignment.BottomCenter), swellColor = c.accent) { marks.lit.getValue(Key.DOWN).value }
        DottedChevron(Direction.Left, ink, hint.align(Alignment.CenterStart), swellColor = c.accent) { marks.lit.getValue(Key.LEFT).value }
        DottedChevron(Direction.Right, ink, hint.align(Alignment.CenterEnd), swellColor = c.accent) { marks.lit.getValue(Key.RIGHT).value }
        Text(
            "SWIPE TO MOVE\nTAP FOR OK",
            Modifier.padding(16.dp).alpha(if (enabled) 1f else INACTIVE_ALPHA),
            style = ClickerType.plate.copy(lineHeight = 22.sp),
            color = c.muted,
            textAlign = TextAlign.Center,
        )
    }
}
