package com.janeadora.clicker.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.ui.theme.LocalClickerColors
import com.janeadora.clicker.ui.theme.reduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** How long the press dots take to grow to full size. */
const val PRESS_GROW_MS = 120

/**
 * Press feedback for the D-pad and the swipe pad, where it shows which
 * arrow or spot the finger found (other keys just sit down, see
 * [raisedKey]): a ring of dots grows under the finger over
 * [PRESS_GROW_MS], then holds still until release, when it goes at once.
 * With reduce motion on it appears at full size with no tween. It only
 * draws; the key has already been sent when it starts. Presses that land
 * within [centre] of the middle draw nothing (the D-pad's OK disc answers
 * for itself). With [live] false (no TV) there are no dots at all: a
 * refused press must not look like an accepted one.
 */
@Composable
fun rememberPressDots(
    color: Color = LocalClickerColors.current.accent,
    centre: Dp = 0.dp,
    live: Boolean = true,
): IndicationNodeFactory {
    val context = LocalContext.current
    val liveNow = rememberUpdatedState(live)
    // Read at each press, so a change to the phone's setting takes effect at once.
    return remember(color, centre, context) { PressDots(color, centre, { liveNow.value }) { reduceMotion(context) } }
}

private class PressDots(
    val color: Color,
    val centre: Dp,
    val live: () -> Boolean,
    val instant: () -> Boolean,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressDotsNode(interactionSource, this)
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class PressDotsNode(
    private val source: InteractionSource,
    private val style: PressDots,
) : Modifier.Node(), DrawModifierNode {
    private val grow = Animatable(0f)
    private var at = Offset.Unspecified

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collectLatest { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        at = i.pressPosition
                        if (!style.live()) {
                            grow.snapTo(0f)
                            invalidateDraw()
                        } else if (style.instant()) {
                            grow.snapTo(1f)
                            invalidateDraw()
                        } else {
                            grow.snapTo(0f)
                            grow.animateTo(1f, tween(PRESS_GROW_MS, easing = LinearOutSlowInEasing)) { invalidateDraw() }
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        grow.snapTo(0f)
                        invalidateDraw()
                    }
                }
            }
        }
    }

    // Drawn over the content, so the dots also show on the D-pad's OK disc.
    override fun ContentDrawScope.draw() {
        drawContent()
        val g = grow.value
        if (g > 0f) {
            val p = if (at.isSpecified()) at else center
            if (style.centre > 0.dp && (p - center).getDistance() <= style.centre.toPx()) return
            val color = style.color
            val ring = min(size.minDimension * 0.32f, 26.dp.toPx()) * g
            val dot = 2.2.dp.toPx() * (0.4f + 0.6f * g)
            for (k in 0 until DOTS) {
                val a = 2 * PI * k / DOTS
                drawCircle(color, dot, Offset(p.x + ring * cos(a).toFloat(), p.y + ring * sin(a).toFloat()))
            }
        }
    }

    private fun Offset.isSpecified() = this != Offset.Unspecified

    private companion object {
        const val DOTS = 10
    }
}
