package com.janeadora.clicker.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Grid pitch of the dot ground, in dp. */
const val DOT_PITCH_DP = 7f

/** Largest dot radius, in dp. */
const val DOT_MAX_RADIUS_DP = 2.2f

// Clear margin around each calm zone, then a soft edge where dots grow back.
private const val CALM_MARGIN_DP = 6f
private const val CALM_FADE_DP = 40f

// Dots gather within this share of the diagonal from the top-right and
// bottom-left corners; the top-right reaches further. The middle and the
// other two corners stay empty. Lower exponents keep the dots fuller for
// longer before they thin out.
private const val REACH_TOP_RIGHT = 0.58f
private const val REACH_BOTTOM_LEFT = 0.53f
private const val FALLOFF_TOP_RIGHT = 1.3f
private const val FALLOFF_BOTTOM_LEFT = 1.35f

// Below this radius (dp) the open field would only show specks, so it is
// left out. The calm fade is applied after this cut, so dots shrink away
// smoothly toward calm zones instead of stopping at a hard edge.
private const val MIN_RADIUS_DP = 0.4f

/**
 * A place the dot ground keeps clear: the shape of a control or a line of
 * text, in px in the dot ground's coordinates. The clearing follows the
 * shape's own edge, so a round button gets a round hole and nothing leaves
 * a straight seam across the field.
 */
sealed interface CalmZone {
    /** Distance in px from ([x], [y]) to the zone's edge; zero or less inside. */
    fun distanceTo(x: Float, y: Float): Float

    data class Circle(val cx: Float, val cy: Float, val radius: Float) : CalmZone {
        override fun distanceTo(x: Float, y: Float): Float = hypot(x - cx, y - cy) - radius
    }

    /** A rectangle with [corner] px rounded corners (a pill when [corner] is half its height). */
    data class Rounded(val rect: Rect, val corner: Float) : CalmZone {
        override fun distanceTo(x: Float, y: Float): Float {
            val c = corner.coerceIn(0f, min(rect.width, rect.height) / 2f)
            val qx = abs(x - rect.center.x) - (rect.width / 2f - c)
            val qy = abs(y - rect.center.y) - (rect.height / 2f - c)
            return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - c
        }
    }
}

/**
 * How much of a ground dot survives at ([x], [y]) next to the [calm] zones:
 * 0 within 6dp of any zone, rising evenly to 1 over the next 40dp, so the
 * dots shrink away gradually instead of stopping on a visible edge. It
 * depends only on the distance to each zone's edge, so the clearing is the
 * same on every side of a shape. [density] is px per dp.
 */
fun calmFactor(x: Float, y: Float, calm: List<CalmZone>, density: Float = 1f): Float {
    val margin = CALM_MARGIN_DP * density
    val fade = CALM_FADE_DP * density
    var f = 1f
    for (z in calm) {
        val d = z.distanceTo(x, y) - margin
        if (d <= 0f) return 0f
        val t = (d / fade).coerceAtMost(1f)
        f = min(f, t)
    }
    return f
}

/**
 * Radius of the ground dot at ([x], [y]) in a [w] by [h] field, all in px.
 * It rises toward the top-right and bottom-left corners, up to 2.2dp, and
 * is cleared softly around the [calm] zones (see [calmFactor]). [density]
 * is px per dp.
 */
fun dotRadius(x: Float, y: Float, w: Float, h: Float, calm: List<CalmZone>, density: Float = 1f): Float {
    if (w <= 0f || h <= 0f) return 0f
    val u = x / w
    val v = y / h
    val toTopRight = hypot(1f - u, v) / sqrt(2f)
    val toBottomLeft = hypot(u, 1f - v) / sqrt(2f)
    val t = max(
        ((REACH_TOP_RIGHT - toTopRight) / REACH_TOP_RIGHT).coerceIn(0f, 1f).pow(FALLOFF_TOP_RIGHT),
        ((REACH_BOTTOM_LEFT - toBottomLeft) / REACH_BOTTOM_LEFT).coerceIn(0f, 1f).pow(FALLOFF_BOTTOM_LEFT),
    )
    val r = DOT_MAX_RADIUS_DP * density * t
    if (r < MIN_RADIUS_DP * density) return 0f
    return r * calmFactor(x, y, calm, density)
}

/** Which of the three ground inks a dot of [radius] px takes: 0 small, 1 mid, 2 large. */
internal fun inkBand(radius: Float, density: Float): Int {
    val share = radius / (DOT_MAX_RADIUS_DP * density)
    return when {
        share < 0.4f -> 0
        share < 0.75f -> 1
        else -> 2
    }
}

/**
 * The quiet halftone ground with plain rectangles kept calm (the debug
 * gallery uses it). Static: the dots are laid out once per size, mode and
 * set of [calm] rects (px, in this composable's own coordinates).
 */
@Composable
fun DotGround(calm: List<Rect>, modifier: Modifier = Modifier) {
    val inks = LocalClickerColors.current.dotGround
    val build = remember(calm, inks) { dotBuilder(calm.map { CalmZone.Rounded(it, 0f) }, inks) }
    Spacer(modifier.drawWithCache(build))
}

/**
 * The dot ground with its [calm] zones read in the draw phase, so a change
 * redraws only this layer, once. Draws nothing while [calm] returns null.
 * At rest nothing runs per frame. While the launch entrance runs (see
 * [LocalEntrance]) the same cached dots are drawn each frame, each grown by
 * how far the front has passed it; when it ends the plain draw is back.
 */
@Composable
fun DotGround(calm: () -> List<CalmZone>?, modifier: Modifier = Modifier) {
    val inks = LocalClickerColors.current.dotGround
    val entrance = LocalEntrance.current
    Spacer(
        modifier.drawWithCache {
            val zones = calm() ?: return@drawWithCache onDrawBehind {}
            dotBuilder(zones, inks, entrance).invoke(this)
        },
    )
}

private fun dotBuilder(calm: List<CalmZone>, inks: List<Color>, entrance: Entrance? = null): CacheDrawScope.() -> DrawResult = {
    val w = size.width
    val h = size.height
    val pitch = DOT_PITCH_DP * density
    val centers = List(inks.size) { ArrayList<Offset>() }
    val radii = List(inks.size) { ArrayList<Float>() }
    var y = pitch / 2f
    while (y < h) {
        var x = pitch / 2f
        while (x < w) {
            val r = dotRadius(x, y, w, h, calm, density)
            if (r > 0f) {
                val band = inkBand(r, density)
                centers[band].add(Offset(x, y))
                radii[band].add(r)
            }
            x += pitch
        }
        y += pitch
    }
    onDrawBehind {
        // Read here, so the entrance redraws this layer and nothing else.
        val front = entrance?.front ?: 1f
        for (band in inks.indices) {
            val c = centers[band]
            val rs = radii[band]
            if (front >= 1f) {
                for (i in c.indices) drawCircle(inks[band], rs[i], c[i])
            } else {
                for (i in c.indices) {
                    val grown = arrived(front, seedDistance(c[i].x, c[i].y, w, h))
                    if (grown > 0f) drawCircle(inks[band], rs[i] * grown, c[i])
                }
            }
        }
    }
}
