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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import com.janeadora.clicker.ui.theme.ENTRANCE_BAND
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.Entrance
import com.janeadora.clicker.ui.theme.LocalClickerColors
import com.janeadora.clicker.ui.theme.LocalEntrance
import com.janeadora.clicker.ui.theme.Settle
import com.janeadora.clicker.ui.theme.DOT_PITCH_DP
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
        modifier.printsIn(LocalEntrance.current).drawBehind {
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

/**
 * The body's share of the launch entrance: it prints in through a screen of
 * dots on the dot ground's grid, each dot growing as the entrance's front
 * passes it until the dots close up and the body is whole. Only the
 * drawing is clipped, to those dots, on the screen's own canvas. Layout and
 * touch are final from the first frame, so a key pressed while it prints is
 * sent like any other. When the entrance is over (or there is none) this
 * adds nothing at all: the body is drawn as it always was.
 *
 * Each frame the clip is one disc for everything the front has passed plus
 * the dots still growing in the band behind the front (see [printDots]),
 * put into one path that is kept and rewound, so a frame allocates nothing.
 */
@Composable
private fun Modifier.printsIn(entrance: Entrance?): Modifier {
    if (entrance == null || !entrance.running) return this
    val at = remember { mutableStateOf(Rect.Zero) }
    val clip = remember { Path() }
    return this
        // Where the body sits in the window, and how big the window is, for the shared grid and seed.
        .onGloballyPositioned { at.value = Rect(it.positionInRoot(), it.findRootCoordinates().size.toSize()) }
        .drawWithContent {
            val front = entrance.front
            if (front >= 1f) {
                drawContent()
                return@drawWithContent
            }
            val place = at.value
            val dots = clip.asAndroidPath()
            dots.rewind()
            if (place.width > 0f && place.height > 0f) {
                val grown = printedRadius(front, place.width, place.height)
                // Everything the front has fully passed: one disc around the seed corner.
                if (grown > 0f) dots.addCircle(place.width - place.left, -place.top, grown, android.graphics.Path.Direction.CW)
                printDots(front, place.left, place.top, size.width, size.height, place.width, place.height, DOT_PITCH_DP.dp.toPx()) { x, y, r ->
                    dots.addCircle(x, y, r, android.graphics.Path.Direction.CW)
                }
            }
            clipPath(clip) { this@drawWithContent.drawContent() }
        }
        // The body keeps its recorded drawing while it prints, so each frame only moves the clip.
        .graphicsLayer()
}

// A print dot at full size, as a share of the print pitch: past 0.71 neighbours overlap and the print is solid.
internal const val PRINT_DOT = 0.76f

// The print's pitch in steps of the dot ground's: every other ground dot, so the clip has a quarter of the dots.
internal const val PRINT_STEP = 2

/**
 * How far from the seed corner the body is whole when the front is at
 * [front], in a [w] by [h] window: everything nearer has arrived. Negative
 * while nothing has.
 */
internal fun printedRadius(front: Float, w: Float, h: Float): Float = (front * (1f + ENTRANCE_BAND) - ENTRANCE_BAND) * hypot(w, h)

/**
 * The print dots still growing when the entrance's front is at [front],
 * for a body [width] by [height] whose top left is at ([left], [top]) in a
 * [w] by [h] window. They sit on every [PRINT_STEP]th point of the dot
 * ground's grid ([pitch] apart, from the window's corner) in the ring
 * between [printedRadius] and the front, and nowhere else: each row is
 * walked only between the two columns where that ring crosses it, so the
 * cost follows the ring, not the body. [dot] gets each one's centre, in the
 * body's own coordinates, and its radius.
 */
internal inline fun printDots(
    front: Float,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    w: Float,
    h: Float,
    pitch: Float,
    dot: (x: Float, y: Float, radius: Float) -> Unit,
) {
    val diagonal = hypot(w, h)
    val band = ENTRANCE_BAND * diagonal
    val outer = front * (1f + ENTRANCE_BAND) * diagonal
    if (front >= 1f || band <= 0f || outer <= 0f || pitch <= 0f) return
    val inner = outer - band
    val step = pitch * PRINT_STEP
    val full = step * PRINT_DOT
    // Rows run down from the seed's side, so the first one past the front ends the walk.
    var row = ceil((top - full - pitch / 2f) / step).toInt()
    while (true) {
        val y = pitch / 2f + row * step
        if (y - top > height + full || y >= outer) break
        row++
        if (y <= -outer) continue
        // The ring crosses this row between these distances left of the seed.
        val far = sqrt(outer * outer - y * y)
        val near = if (inner > abs(y)) sqrt(inner * inner - y * y) else 0f
        val from = max(w - far, left - full)
        val to = min(w - near, left + width + full)
        var column = ceil((from - pitch / 2f) / step).toInt()
        while (true) {
            val x = pitch / 2f + column * step
            if (x > to) break
            column++
            val grown = (outer - hypot(w - x, y)) / band
            if (grown > 0f && grown < 1f) dot(x - left, y - top, full * grown)
        }
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
 * [interaction] reports a press the face sits down on its base, for at
 * least [minDownMs]. With [live] false (no TV) the key is locked: it gives
 * only [LOCKED_TRAVEL] of the way. The element's bounds include the lip, so
 * the touch target does not move.
 */
@Composable
internal fun Modifier.raisedKey(
    interaction: InteractionSource,
    shape: Shape,
    fill: Color,
    line: Color,
    lip: Dp = KEY_LIP,
    minDownMs: Long = KEY_DOWN_MS,
    live: Boolean = true,
): Modifier {
    val context = LocalContext.current
    val liveNow = rememberUpdatedState(live)
    // Read at each release, so a change to the phone's setting takes effect at once.
    val travel = remember(shape, line, lip, minDownMs, context) {
        KeyTravel(shape, line, lip, minDownMs, { liveNow.value }) { reduceMotion(context) }
    }
    return this
        .indication(interaction, travel)
        .padding(bottom = lip)
        .clip(shape)
        .background(fill)
        .border(ClickerShapes.border, line, shape)
}

/**
 * The press of a flat tile, to match the raised keys: while [interaction]
 * reports a press the whole tile sits [KEY_LIP] lower, with the keys'
 * timing; [live] false gives the locked key's short travel. Goes before the
 * tile's clip, fill and border, so they move together. It only draws.
 */
@Composable
fun Modifier.tilePress(interaction: InteractionSource, live: Boolean = true): Modifier {
    val context = LocalContext.current
    val liveNow = rememberUpdatedState(live)
    val travel = remember(context) { KeyTravel(null, null, KEY_LIP, KEY_DOWN_MS, { liveNow.value }) { reduceMotion(context) } }
    return this.indication(interaction, travel)
}

/** The least time a key shows down, measured from the press, so a quick tap is still seen. */
const val KEY_DOWN_MS = 90L

/** Power stays down longer: the one key that should feel deliberate. */
const val POWER_DOWN_MS = 170L

/** How long a released key takes to come back up. It leaves fast and lands soft (see [Settle]). */
const val KEY_UP_MS = 90

/** How far a locked key (no TV) gives, as a share of the full travel. */
const val LOCKED_TRAVEL = 0.4f

/**
 * How long a key let go [heldMs] after its press must still stay down: the
 * rest of [minDownMs] for a quick tap, nothing for a longer hold.
 */
fun keyDownRemainder(heldMs: Long, minDownMs: Long = KEY_DOWN_MS): Long = (minDownMs - heldMs).coerceIn(0L, minDownMs)

/** How far down a pressed key sits, from 0 (up) to 1 (on its base): all the way when [live], else [LOCKED_TRAVEL]. */
fun keyTravelDepth(live: Boolean): Float = if (live) 1f else LOCKED_TRAVEL

// The press visual for raised keys. It only draws: the key has been sent
// before the press reaches it. Down is immediate. Up waits only until the
// key has shown down for its minimum since the press, then settles, or is
// immediate with reduce motion on. With no shape there is no base to draw
// and the whole element travels (a flat tile).
private class KeyTravel(
    val shape: Shape?,
    val base: Color?,
    val lip: Dp,
    val minDownMs: Long,
    val live: () -> Boolean,
    val instant: () -> Boolean,
) : IndicationNodeFactory {
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
                        down.snapTo(keyTravelDepth(style.live()))
                        invalidateDraw()
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        delay(keyDownRemainder(SystemClock.uptimeMillis() - pressedAt, style.minDownMs))
                        if (style.instant()) down.snapTo(0f) else down.animateTo(0f, tween(KEY_UP_MS, easing = Settle)) { invalidateDraw() }
                        invalidateDraw()
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val lip = style.lip.toPx()
        val shape = style.shape
        val ink = style.base
        if (shape != null && ink != null) {
            val face = Size(size.width, size.height - lip)
            val base = outline?.takeIf { outlineFor == face }
                ?: shape.createOutline(face, layoutDirection, this).also { outline = it; outlineFor = face }
            translate(top = lip) { drawOutline(base, ink) }
        }
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
