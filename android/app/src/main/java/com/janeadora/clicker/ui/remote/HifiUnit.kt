package com.janeadora.clicker.ui.remote

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.LocalClickerColors
import com.janeadora.clicker.ui.theme.reduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shapes of the remote body. Corners are concentric: what sits
 * against a curve takes that curve's radius minus the gap between them, and
 * never less than a key's own 10dp.
 */
internal object UnitShapes {
    private val r = SHELL_RADIUS.dp
    private val big = SHELL_BIG_RADIUS.dp
    private val seat = (SHELL_RADIUS - SHELL_PAD).dp
    private val bigSeat = (SHELL_BIG_RADIUS - SHELL_PAD).dp
    private val keyR = 12.dp

    /** The body: one bigger round corner at the top right, where Power sits. */
    val shell = RoundedCornerShape(topStart = r, topEnd = big, bottomEnd = r, bottomStart = r)

    /** A compartment seated in the shell's padding. */
    val bay = RoundedCornerShape(seat)

    /** A key on the face. */
    val key = RoundedCornerShape(keyR)

    /** A key seated in a bay, [BAY_INSET] from its edge. */
    val bayKey = RoundedCornerShape((SHELL_RADIUS - SHELL_PAD - BAY_INSET).dp)

    /** Power, in the shell's big corner. */
    val power = RoundedCornerShape(topStart = keyR, topEnd = bigSeat, bottomEnd = keyR, bottomStart = keyR)

    /** Type and More, in the shell's bottom corners. */
    val bottomStart = RoundedCornerShape(topStart = keyR, topEnd = keyR, bottomEnd = keyR, bottomStart = seat)
    val bottomEnd = RoundedCornerShape(topStart = keyR, topEnd = keyR, bottomEnd = seat, bottomStart = keyR)
}

/** How far a raised key stands above its base, and so how far it travels when pressed. */
val KEY_LIP: Dp = 2.dp

/**
 * The remote body: one shell with a hairline border and, for depth, the
 * same shape again behind it, offset down and to the right. No shadow.
 * [fill] makes the face take all the height it is given.
 */
@Composable
internal fun Shell(modifier: Modifier = Modifier, fill: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalClickerColors.current
    val shape = UnitShapes.shell
    val depth = SHELL_DEPTH.dp
    Box(
        modifier.drawBehind {
            val d = depth.toPx()
            val back = shape.createOutline(Size(size.width - d, size.height - d), layoutDirection, this)
            translate(d, d) {
                drawOutline(back, c.shellBack)
                drawOutline(back, c.shellBackLine, style = Stroke(ClickerShapes.border.toPx()))
            }
        },
    ) {
        Column(
            (if (fill) Modifier.fillMaxSize() else Modifier)
                .padding(end = depth, bottom = depth)
                .clip(shape)
                .background(c.shell)
                .border(ClickerShapes.border, c.outlineStrong, shape)
                .padding(SHELL_PAD.dp),
            content = content,
        )
    }
}

/** A recessed compartment in the shell: the ground tone inside a hairline. */
@Composable
internal fun Bay(modifier: Modifier = Modifier, shape: Shape = UnitShapes.bay, content: @Composable BoxScope.() -> Unit) {
    val c = LocalClickerColors.current
    Box(modifier.clip(shape).background(c.bay)) {
        content()
        // Over the content, so a tile cut by the edge does not break the hairline.
        Box(Modifier.matchParentSize().border(ClickerShapes.border, c.outline, shape))
    }
}

/**
 * Draws this element as a raised key: a [fill] face with a hairline [line]
 * in [shape], standing [lip] above a base in the same line color. While
 * [interaction] reports a press the face sits down on its base. The element's
 * bounds include the lip, so the touch target does not move.
 */
@Composable
internal fun Modifier.raisedKey(
    interaction: InteractionSource,
    shape: Shape,
    fill: Color,
    line: Color,
    lip: Dp = KEY_LIP,
): Modifier {
    val context = LocalContext.current
    // Read at each release, so a change to the phone's setting takes effect at once.
    val travel = remember(shape, line, lip, context) { KeyTravel(shape, line, lip) { reduceMotion(context) } }
    return this
        .indication(interaction, travel)
        .padding(bottom = lip)
        .clip(shape)
        .background(fill)
        .border(ClickerShapes.border, line, shape)
}

/** The least time a key shows down, measured from the press, so a quick tap is still seen. */
const val KEY_DOWN_MS = 90L

/** How long a released key takes to come back up. */
const val KEY_UP_MS = 80

/**
 * How long a key let go [heldMs] after its press must still stay down: the
 * rest of [KEY_DOWN_MS] for a quick tap, nothing for a longer hold.
 */
fun keyDownRemainder(heldMs: Long): Long = (KEY_DOWN_MS - heldMs).coerceIn(0L, KEY_DOWN_MS)

// The press visual for raised keys. It only draws: the key has been sent
// before the press reaches it. Down is immediate. Up waits only until the
// key has shown down for KEY_DOWN_MS since the press, then eases, or is
// immediate with reduce motion on.
private class KeyTravel(val shape: Shape, val base: Color, val lip: Dp, val instant: () -> Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = KeyTravelNode(interactionSource, this)
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class KeyTravelNode(private val source: InteractionSource, private val style: KeyTravel) : Modifier.Node(), DrawModifierNode {
    private val down = Animatable(0f)
    private var pressedAt = 0L
    private var outline: Outline? = null
    private var outlineFor = Size.Unspecified

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collectLatest { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        pressedAt = SystemClock.uptimeMillis()
                        down.snapTo(1f)
                        invalidateDraw()
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        delay(keyDownRemainder(SystemClock.uptimeMillis() - pressedAt))
                        if (style.instant()) down.snapTo(0f) else down.animateTo(0f, tween(KEY_UP_MS)) { invalidateDraw() }
                        invalidateDraw()
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val lip = style.lip.toPx()
        val face = Size(size.width, size.height - lip)
        val base = outline?.takeIf { outlineFor == face }
            ?: style.shape.createOutline(face, layoutDirection, this).also { outline = it; outlineFor = face }
        translate(top = lip) { drawOutline(base, style.base) }
        translate(top = lip * down.value) { this@draw.drawContent() }
    }
}

/** The vent: a short strip of small dots, two rows deep. */
@Composable
internal fun Vent(modifier: Modifier = Modifier, columns: Int = 8) {
    val color = LocalClickerColors.current.outlineStrong
    Canvas(modifier.size(width = (columns * VENT_PITCH).dp, height = (2 * VENT_PITCH).dp)) {
        val pitch = VENT_PITCH.dp.toPx()
        for (row in 0 until 2) for (col in 0 until columns) {
            drawCircle(color, 1.3.dp.toPx(), Offset(pitch * (col + 0.5f), pitch * (row + 0.5f)))
        }
    }
}

private const val VENT_PITCH = 6f
