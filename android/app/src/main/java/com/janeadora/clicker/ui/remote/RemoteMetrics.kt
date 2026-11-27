package com.janeadora.clicker.ui.remote

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The rows of the portrait remote body, top to bottom. The
 * sizes in [RemoteMetrics] and the screen's column both walk this list, so
 * the height the metrics count is the height that is laid out.
 */
enum class BodyRow { Name, Display, Nav, Pad, Volume, Rack, Plate }

/**
 * Sizes for the portrait remote body, in dp. [key] is the height
 * of each row of keys; [name], [display] and [rack] are the heights of
 * those rows. [bayPad] is the room above the D-pad inside its compartment;
 * below it there is [bayDrop] more, which keeps Rewind and Fast forward
 * clear of the ring. [dpadTop] is where the ring starts and [shellTop]
 * where the body starts, both measured from the top of the screen. [fits]
 * is false when even the smallest set is taller than the screen, and the
 * screen scrolls.
 *
 * None of it depends on the link or on a notice: the body does not move
 * when the TV drops or comes back.
 */
data class RemoteMetrics(
    val dpad: Float,
    val ok: Float,
    val tile: Float,
    val gap: Float,
    val bayPad: Float,
    val bayDrop: Float,
    val key: Float,
    val name: Float,
    val display: Float,
    val rack: Float,
    val shellTop: Float = 0f,
    val fits: Boolean = true,
) {
    val compact: Boolean get() = dpad < REGULAR_DPAD

    /** Height of the D-pad compartment. */
    val bay: Float get() = bayPad + dpad + DPAD_LIP + bayPad + bayDrop

    /** Height of [row]. */
    fun height(row: BodyRow): Float = when (row) {
        BodyRow.Name -> name
        BodyRow.Display -> display
        BodyRow.Nav, BodyRow.Volume, BodyRow.Plate -> key
        BodyRow.Pad -> bay
        BodyRow.Rack -> rack
    }

    /** Space above [row]: none over the first, a small one between the name and the display, [gap] elsewhere. */
    fun gapBefore(row: BodyRow): Float = when (row) {
        BodyRow.Name -> 0f
        BodyRow.Display -> PLATE_GAP
        else -> gap
    }

    /** Height of the body's face: its rows, their gaps and the shell's padding. The offset shell is not counted. */
    val bodyHeight: Float get() = 2 * SHELL_PAD + BodyRow.entries.sumOf { (gapBefore(it) + height(it)).toDouble() }.toFloat()

    /** Where [row] starts, measured from the top of the screen. */
    fun top(row: BodyRow): Float {
        var y = shellTop + SHELL_PAD
        for (r in BodyRow.entries) {
            y += gapBefore(r)
            if (r == row) return y
            y += height(r)
        }
        return y
    }

    val dpadTop: Float get() = top(BodyRow.Pad) + bayPad
    val dpadCentre: Float get() = dpadTop + dpad / 2f
}

/** Space between the body (with its offset shell) and the bottom system bar. */
const val BOTTOM_PAD = 8f

/** Space between the top system bar and the body, at the least. */
const val SHELL_TOP = 8f

/** Space between the body and the screen's sides. */
const val SHELL_SIDE = 12f

/** How far the offset shell behind the body shows, right and below. */
const val SHELL_DEPTH = 6f

/** Padding inside the shell, and so the gap between its edge and a compartment. */
const val SHELL_PAD = 10f

/** The shell's corner, and its one bigger corner at the top right. */
const val SHELL_RADIUS = 28f
const val SHELL_BIG_RADIUS = 44f

/** The body never grows wider than this. */
const val SHELL_MAX_WIDTH = 480f

/** Height of a row of keys at the least, bottom edge included; also the size of Rewind and Fast forward. */
const val ROW_HEIGHT = 48f

/** Space between the TV name's row and the night display. */
const val PLATE_GAP = 6f

/** Space between a compartment's edge and a key or tile inside it. */
const val BAY_INSET = 8f

/** How far the D-pad ring stands above its base. */
const val DPAD_LIP = 3f

/** Screens at least this tall get the full-size D-pad when it fits. */
const val REGULAR_MIN_HEIGHT = 840f

/** Rewind and Fast forward stay at least this clear of the ring, and get [WANTED_CLEARANCE] when there is room. */
const val MIN_CLEARANCE = 8f
const val WANTED_CLEARANCE = 16f

private const val REGULAR_DPAD = 240f
private const val COMPACT_DPAD = 208f
private const val SMALLEST_DPAD = 200f
private const val MIN_GAP = 8f
private const val MIN_BAY_PAD = 10f

/** A rectangle in dp, measured from a compartment's top left. */
data class DpRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun intersects(o: DpRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** Where Rewind (left) and Fast forward (right) sit in a compartment [bayWidth] by [bayHeight]: its bottom corners. */
fun cornerKeys(bayWidth: Float, bayHeight: Float): Pair<DpRect, DpRect> {
    val top = bayHeight - BAY_INSET - ROW_HEIGHT
    return DpRect(BAY_INSET, top, BAY_INSET + ROW_HEIGHT, bayHeight - BAY_INSET) to
        DpRect(bayWidth - BAY_INSET - ROW_HEIGHT, top, bayWidth - BAY_INSET, bayHeight - BAY_INSET)
}

/**
 * The swipe pad's place in that compartment: all of its width, from the
 * top down to just above the two corner keys. The keys sit under the pad,
 * never on it, so a swipe that starts low cannot press one.
 */
fun swipePadRect(bayWidth: Float, bayHeight: Float): DpRect =
    DpRect(BAY_INSET, BAY_INSET, bayWidth - BAY_INSET, bayHeight - 2 * BAY_INSET - ROW_HEIGHT)

/**
 * The clear space between the D-pad ring and the Rewind or Fast forward key
 * in a bottom corner of its compartment, [bayWidth] wide, with the ring
 * [dpad] across centred in it and [below] dp of room under the ring's base.
 * Measured to the corner of the key's square, so a rounded key has a little
 * more.
 */
fun cornerKeyClearance(bayWidth: Float, dpad: Float, below: Float): Float {
    val reach = BAY_INSET + ROW_HEIGHT
    val dx = max(bayWidth / 2f - reach, 0f)
    val dy = max(dpad / 2f + DPAD_LIP + below - reach, 0f)
    return hypot(dx, dy) - dpad / 2f
}

/** Width of the D-pad compartment on a screen [widthDp] wide. */
fun bayWidth(widthDp: Float): Float =
    min(widthDp - 2 * SHELL_SIDE, SHELL_MAX_WIDTH) - SHELL_DEPTH - 2 * SHELL_PAD

// The least extra room under a ring, on top of [pad], that keeps the corner keys [clear] dp from it.
private fun dropFor(widthDp: Float, dpad: Float, pad: Float, clear: Float): Float {
    val reach = BAY_INSET + ROW_HEIGHT
    val dx = max(bayWidth(widthDp) / 2f - reach, 0f)
    val want = dpad / 2f + clear
    val dy = if (dx >= want) 0f else sqrt(want * want - dx * dx)
    return max(0f, ceil(dy + reach - dpad / 2f - DPAD_LIP - pad))
}

/**
 * Picks the portrait sizes for a screen [heightDp] by [widthDp] (the whole
 * window) with system bars of [topInsetDp] and [bottomInsetDp], at
 * [fontScale].
 *
 * The body sits on the bottom bar and holds the rows of [BodyRow]. Regular
 * (screens 840dp and taller): a 240dp D-pad and 72dp tiles. Compact: 208dp
 * and 64dp. When compact does not fit, the rack's tiles shrink to 56 and
 * then 48dp, and only then the D-pad, to 200dp. The bottom plate stays
 * 48dp, the smallest a touch target may be.
 *
 * Spare height makes the body taller, so on most phones it fills the
 * screen: first the room under the D-pad, until Rewind and Fast forward
 * are 16dp clear of the ring; then the gaps (8 to 12dp), the room around
 * the D-pad (to 24dp), the key rows and the display (48 to 56dp), the gaps
 * again (to 16dp) and the room around the D-pad again (to 40dp). Anything
 * still left stays above the body, so the keys keep low for one-handed
 * reach.
 */
fun remoteMetrics(
    heightDp: Float,
    fontScale: Float,
    bottomInsetDp: Float = 24f,
    topInsetDp: Float = 24f,
    widthDp: Float = 411f,
): RemoteMetrics {
    val floorY = heightDp - bottomInsetDp - BOTTOM_PAD - SHELL_DEPTH
    val room = floorY - topInsetDp - SHELL_TOP

    // Row heights from the type sizes in Type.kt: the TV name is a 28sp line
    // plus its cap lift (0.38 x 22sp), the readout an 18sp line, a tile
    // label an 18sp line plus 0.38 x 14sp.
    fun build(dpad: Float, ok: Float, tile: Float, gap: Float, pad: Float, drop: Float, key: Float, fits: Boolean = true): RemoteMetrics {
        val m = RemoteMetrics(
            dpad, ok, tile, gap, pad, drop, key,
            name = max(key, 36.4f * fontScale),
            display = max(key, 18f * fontScale + 12f),
            rack = max(tile, 23.3f * fontScale) + 2 * BAY_INSET,
            fits = fits,
        )
        return m.copy(shellTop = floorY - m.bodyHeight)
    }

    val sets = buildList {
        if (heightDp >= REGULAR_MIN_HEIGHT) add(Triple(REGULAR_DPAD, 88f, 72f))
        add(Triple(COMPACT_DPAD, 76f, 64f))
        add(Triple(COMPACT_DPAD, 76f, 56f))
        add(Triple(COMPACT_DPAD, 76f, 48f))
        add(Triple(SMALLEST_DPAD, 73f, 48f))
    }
    for ((dpad, ok, tile) in sets) {
        val least = dropFor(widthDp, dpad, MIN_BAY_PAD, MIN_CLEARANCE)
        var spare = room - build(dpad, ok, tile, MIN_GAP, MIN_BAY_PAD, least, ROW_HEIGHT).bodyHeight
        if (spare < 0f) continue
        // Takes up to [most] dp for each of [count] things out of the spare height.
        fun take(most: Float, count: Int): Float {
            val each = floor(spare / count).coerceIn(0f, max(most, 0f))
            spare -= each * count
            return each
        }
        val drop = least + take(dropFor(widthDp, dpad, MIN_BAY_PAD, WANTED_CLEARANCE) - least, 1)
        var gap = MIN_GAP + take(4f, GAPS)
        var pad = MIN_BAY_PAD + take(24f - MIN_BAY_PAD, 2)
        val key = ROW_HEIGHT + take(8f, KEY_ROWS)
        gap += take(4f, GAPS)
        pad += take(40f - pad, 2)
        return build(dpad, ok, tile, gap, pad, drop, key)
    }
    val (dpad, ok, tile) = sets.last()
    return build(dpad, ok, tile, MIN_GAP, MIN_BAY_PAD, dropFor(widthDp, dpad, MIN_BAY_PAD, MIN_CLEARANCE), ROW_HEIGHT, fits = false)
}

// Gaps between the body's rows, under the display.
private val GAPS = BodyRow.entries.size - 2

// Rows as tall as a key: the TV name's, the display, Back, Home and Menu, volume, and the bottom plate.
private val KEY_ROWS = BodyRow.entries.size - 2
