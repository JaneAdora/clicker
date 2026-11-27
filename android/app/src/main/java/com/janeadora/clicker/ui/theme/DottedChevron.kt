package com.janeadora.clicker.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

enum class Direction { Up, Down, Left, Right }

// One chevron pointing right, as dot centres in a unit square: two arms of
// three dots sharing the tip. The double form adds a second chevron
// CHEVRON_GAP to the right.
private val CHEVRON = listOf(
    Offset(0.20f, 0.20f), Offset(0.35f, 0.35f), Offset(0.50f, 0.50f),
    Offset(0.35f, 0.65f), Offset(0.20f, 0.80f),
)
private const val CHEVRON_GAP = 0.30f
private const val DOT_RADIUS = 0.065f

// The single chevron: longer arms of four dots each, so it reads at a
// glance on its own. Its dots' centre of mass sits at the middle of the box,
// which centres it optically rather than by its outline.
private val SINGLE = run {
    val tip = Offset(0.68f, 0.50f)
    val ends = listOf(Offset(0.32f, 0.14f), Offset(0.32f, 0.86f))
    val dots = listOf(tip) + ends.flatMap { end -> (1..3).map { i -> tip + (end - tip) * (i / 3f) } }
    val mass = dots.fold(Offset.Zero) { a, b -> a + b } / dots.size.toFloat()
    dots.map { it + Offset(0.5f - mass.x, 0f) }
}
private const val SINGLE_DOT_RADIUS = 0.07f

/**
 * The brand's dotted chevron: a single chevron by default, or the double
 * chevron with [double]. Decorative: the button around it carries the label.
 */
@Composable
fun DottedChevron(direction: Direction, color: Color, modifier: Modifier = Modifier, double: Boolean = false) {
    val degrees = when (direction) {
        Direction.Right -> 0f
        Direction.Down -> 90f
        Direction.Left -> 180f
        Direction.Up -> 270f
    }
    Canvas(modifier.size(24.dp)) {
        val s = size.minDimension
        val left = (size.width - s) / 2f
        val top = (size.height - s) / 2f
        rotate(degrees) {
            if (double) {
                for (shift in listOf(0f, CHEVRON_GAP)) {
                    for (p in CHEVRON) {
                        drawCircle(color, DOT_RADIUS * s, Offset(left + (p.x + shift) * s, top + p.y * s))
                    }
                }
            } else {
                for (p in SINGLE) drawCircle(color, SINGLE_DOT_RADIUS * s, Offset(left + p.x * s, top + p.y * s))
            }
        }
    }
}
