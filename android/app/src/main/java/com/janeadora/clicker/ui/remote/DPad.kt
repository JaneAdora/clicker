package com.janeadora.clicker.ui.remote

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.Direction
import com.janeadora.clicker.ui.theme.DottedChevron
import com.janeadora.clicker.ui.theme.LocalClickerColors
import com.janeadora.clicker.ui.theme.Settle
import com.janeadora.clicker.ui.theme.reduceMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import uniffi.clicker_ffi.Key
import kotlin.math.abs
import kotlin.math.hypot

/** Diameter of the full-size D-pad ring. */
val DPAD_SIZE: Dp = 240.dp

/**
 * The key for a touch at ([x], [y]) on a D-pad ring [size] across, with an
 * OK disc [okSize] across at its centre; all in the same unit, measured
 * from the ring's top-left. Inside the OK disc is Select; the rest of the
 * ring is cut into four 90 degree wedges. A touch exactly on a diagonal
 * goes to Up or Down. Outside the ring is null.
 */
fun dpadKeyAt(x: Float, y: Float, size: Float, okSize: Float): Key? {
    val r = size / 2f
    val dx = x - r
    val dy = y - r
    val d = hypot(dx, dy)
    return when {
        d > r -> null
        d <= okSize / 2f -> Key.SELECT
        abs(dx) > abs(dy) -> if (dx > 0f) Key.RIGHT else Key.LEFT
        else -> if (dy < 0f) Key.UP else Key.DOWN
    }
}

/** How long a lit arrow takes to settle, and how long the ring of dots around OK lasts. */
const val ARROW_SETTLE_MS = 220
const val CONFIRM_MS = 260

/** How strongly one repeat of a held arrow lights it, against 1 for the press. */
const val TICK_SWELL = 0.55f

/**
 * The size of a dot in the ring that leaves the OK disc, as a share of full
 * size, [progress] (0 to 1) through its run: it grows fast, then shrinks
 * away, and is gone at both ends.
 */
fun confirmDot(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (p < CONFIRM_PEAK) p / CONFIRM_PEAK else (1f - p) / (1f - CONFIRM_PEAK)
}

private const val CONFIRM_PEAK = 0.25f
private const val OK_SINK = 0.05f
private const val CONFIRM_DOTS = 16

// What an accepted press looks like on the D-pad, beyond the ring's travel.
// An arrow lights its chevron, which settles; each repeat of a held arrow
// lights it again, less. OK sinks the disc and sends one ring of dots out
// from its rim. All of it starts after the key has been sent, and with
// reduce motion only the sunk disc shows.
private class DPadFeel(private val scope: CoroutineScope, private val instant: () -> Boolean) {
    val ok = Animatable(0f)
    val confirm = Animatable(1f)
    val swell = listOf(Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT).associateWith { Animatable(0f) }
    private var pressedAt = 0L

    fun pressed(key: Key) {
        pressedAt = SystemClock.uptimeMillis()
        scope.launch {
            if (key == Key.SELECT) {
                ok.snapTo(1f)
                if (!instant()) {
                    confirm.snapTo(0f)
                    confirm.animateTo(1f, tween(CONFIRM_MS, easing = LinearOutSlowInEasing))
                }
            } else {
                light(key, 1f)
            }
        }
    }

    fun ticked(key: Key) {
        scope.launch { light(key, TICK_SWELL) }
    }

    private suspend fun light(key: Key, to: Float) {
        val arrow = swell[key] ?: return
        if (instant()) return
        arrow.snapTo(to)
        arrow.animateTo(0f, tween(ARROW_SETTLE_MS, easing = Settle))
    }

    // The disc comes up with the key's own timing.
    suspend fun letGo() {
        delay(keyDownRemainder(SystemClock.uptimeMillis() - pressedAt))
        if (instant()) ok.snapTo(0f) else ok.animateTo(0f, tween(KEY_UP_MS, easing = Settle))
    }
}

/**
 * The D-pad: a raised ring holding four single dotted-chevron arrows around
 * an accent-filled OK disc. Pressed, the ring sits down on its base. An
 * arrow also lights its chevron, which settles, and a ring of dots marks
 * where the finger landed; each repeat of a held arrow lights the chevron
 * again. OK sinks its disc and sends one ring of dots out from
 * its rim. The whole ring is one touch target that picks the
 * key by where the finger lands, so no part of it is dead. TalkBack still
 * sees five controls: Up, Down, Left, Right and Select. The key is picked
 * and sent where the finger lands; dragging never changes it, and the
 * arrows repeat while held until release or until the finger leaves the
 * ring. With [enabled] false the block shows at 40% and a press sends
 * nothing but buzzes and says why; the ring then only gives a little and
 * nothing lights. [inBay] is for the ring seated in a compartment.
 */
@Composable
fun DPad(
    enabled: Boolean,
    onPress: (Key) -> Boolean,
    modifier: Modifier = Modifier,
    size: Dp = DPAD_SIZE,
    okSize: Dp = 88.dp,
    inBay: Boolean = false,
) {
    val c = LocalClickerColors.current
    val press by rememberUpdatedState(onPress)
    val presser = rememberKeyPresser(enabled)
    val interaction = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feel = remember(scope, context) { DPadFeel(scope) { reduceMotion(context) } }
    LaunchedEffect(interaction, feel) {
        interaction.interactions.collectLatest { if (it is PressInteraction.Release || it is PressInteraction.Cancel) feel.letGo() }
    }
    val select: (Key) -> Unit = { key -> presser.press(feelFor(key)) { press(key) } }
    val line = if (inBay) c.bayKeyLine else c.outlineStrong
    Box(
        modifier
            // The ring stands on its base, so the block is that much taller than wide.
            .size(width = size, height = size + DPAD_LIP.dp)
            .alpha(if (enabled) 1f else INACTIVE_ALPHA)
            .raisedKey(interaction, ClickerShapes.control, c.key, line, lip = DPAD_LIP.dp, live = enabled)
            .indication(interaction, rememberPressDots(c.accent, okSize / 2, live = enabled))
            .drawWithContent {
                drawContent()
                val p = feel.confirm.value
                if (p < 1f) {
                    val ring = okSize.toPx() / 2f + (6.dp + 10.dp * p).toPx()
                    val dot = 2.4.dp.toPx() * confirmDot(p)
                    for (k in 0 until CONFIRM_DOTS) {
                        val a = 2 * PI * k / CONFIRM_DOTS
                        drawCircle(c.accent, dot, Offset(center.x + ring * cos(a).toFloat(), center.y + ring * sin(a).toFloat()))
                    }
                }
            }
            .pointerInput(size, okSize, presser) {
                val ring = size.toPx()
                val ok = okSize.toPx()
                detectKeyPresses(interaction, stillOn = { dpadKeyAt(it.x, it.y, ring, ok) != null }) { at ->
                    dpadKeyAt(at.x, at.y, ring, ok)?.let { key ->
                        val send = { press(key) }
                        KeyHold(
                            repeat = key != Key.SELECT,
                            press = { presser.press(feelFor(key), send).also { sent -> if (sent) feel.pressed(key) } },
                            tick = { presser.tick(send).also { sent -> if (sent) feel.ticked(key) } },
                            release = presser::release,
                        )
                    }
                }
            },
    ) {
        Arrow(Direction.Up, "Up", Key.UP, Alignment.TopCenter, size, okSize, enabled, feel, select)
        Arrow(Direction.Down, "Down", Key.DOWN, Alignment.BottomCenter, size, okSize, enabled, feel, select)
        Arrow(Direction.Left, "Left", Key.LEFT, Alignment.CenterStart, size, okSize, enabled, feel, select)
        Arrow(Direction.Right, "Right", Key.RIGHT, Alignment.CenterEnd, size, okSize, enabled, feel, select)
        Box(
            Modifier.size(okSize).align(Alignment.Center)
                .graphicsLayer {
                    val s = 1f - OK_SINK * feel.ok.value
                    scaleX = s
                    scaleY = s
                }
                .clip(ClickerShapes.control)
                .background(c.accent)
                .keyNode("Select", enabled) { select(Key.SELECT) },
            contentAlignment = Alignment.Center,
        ) {
            ButtonLabel("OK", style = ClickerType.button, color = c.onAccent)
        }
    }
}

// One arrow: its chevron, centred in the ring band, and its own
// accessibility node. Touches are handled by the ring around it.
@Composable
private fun BoxScope.Arrow(
    direction: Direction,
    label: String,
    key: Key,
    alignment: Alignment,
    size: Dp,
    okSize: Dp,
    enabled: Boolean,
    feel: DPadFeel,
    onPress: (Key) -> Unit,
) {
    val c = LocalClickerColors.current
    val band = (size - okSize) / 2
    val vertical = direction == Direction.Up || direction == Direction.Down
    val lit = feel.swell.getValue(key)
    Box(
        Modifier
            .align(alignment)
            .size(width = if (vertical) okSize else band, height = if (vertical) band else okSize)
            .keyNode(label, enabled) { onPress(key) },
        contentAlignment = Alignment.Center,
    ) {
        DottedChevron(direction, c.accent, Modifier.size(34.dp), swellColor = c.dotMark[2]) { lit.value }
    }
}
