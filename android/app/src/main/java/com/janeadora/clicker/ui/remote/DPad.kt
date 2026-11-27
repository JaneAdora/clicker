package com.janeadora.clicker.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.Direction
import com.janeadora.clicker.ui.theme.DottedChevron
import com.janeadora.clicker.ui.theme.LocalClickerColors
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

/**
 * The D-pad: a raised ring holding four single dotted-chevron arrows around
 * an accent-filled OK disc. Pressed, the ring sits down on its base and a
 * ring of dots marks where the finger landed, so the chosen arrow shows. The whole ring is one touch target that picks the
 * key by where the finger lands, so no part of it is dead. TalkBack still
 * sees five controls: Up, Down, Left, Right and Select. The key is picked
 * and sent where the finger lands; dragging never changes it, and the
 * arrows repeat while held until release or until the finger leaves the
 * ring. With [enabled] false the block shows at 40% and a press sends
 * nothing but buzzes and says why.
 */
@Composable
fun DPad(
    enabled: Boolean,
    onPress: (Key) -> Boolean,
    modifier: Modifier = Modifier,
    size: Dp = DPAD_SIZE,
    okSize: Dp = 88.dp,
) {
    val c = LocalClickerColors.current
    val press by rememberUpdatedState(onPress)
    val presser = rememberKeyPresser(enabled)
    val interaction = remember { MutableInteractionSource() }
    val select: (Key) -> Unit = { key -> presser.press { press(key) } }
    Box(
        modifier
            // The ring stands on its base, so the block is that much taller than wide.
            .size(width = size, height = size + DPAD_LIP.dp)
            .alpha(if (enabled) 1f else INACTIVE_ALPHA)
            .raisedKey(interaction, ClickerShapes.control, c.key, c.outlineStrong, lip = DPAD_LIP.dp)
            .indication(interaction, rememberPressDots(c.accent, c.onAccent, okSize / 2))
            .pointerInput(size, okSize, presser) {
                val ring = size.toPx()
                val ok = okSize.toPx()
                detectKeyPresses(interaction, stillOn = { dpadKeyAt(it.x, it.y, ring, ok) != null }) { at ->
                    dpadKeyAt(at.x, at.y, ring, ok)?.let { key ->
                        val send = { press(key) }
                        KeyHold(repeat = key != Key.SELECT, { presser.press(send) }, { presser.tick(send) })
                    }
                }
            },
    ) {
        Arrow(Direction.Up, "Up", Key.UP, Alignment.TopCenter, size, okSize, enabled, select)
        Arrow(Direction.Down, "Down", Key.DOWN, Alignment.BottomCenter, size, okSize, enabled, select)
        Arrow(Direction.Left, "Left", Key.LEFT, Alignment.CenterStart, size, okSize, enabled, select)
        Arrow(Direction.Right, "Right", Key.RIGHT, Alignment.CenterEnd, size, okSize, enabled, select)
        Box(
            Modifier.size(okSize).align(Alignment.Center)
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
    onPress: (Key) -> Unit,
) {
    val band = (size - okSize) / 2
    val vertical = direction == Direction.Up || direction == Direction.Down
    Box(
        Modifier
            .align(alignment)
            .size(width = if (vertical) okSize else band, height = if (vertical) band else okSize)
            .keyNode(label, enabled) { onPress(key) },
        contentAlignment = Alignment.Center,
    ) {
        DottedChevron(direction, LocalClickerColors.current.accent, Modifier.size(34.dp))
    }
}
