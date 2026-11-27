package com.janeadora.clicker.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uniffi.clicker_ffi.Key

/**
 * How the D-pad and Back, Home and Menu fit above the type bar. [dpad] is
 * the D-pad size in dp, or null when there is no room for one. [stacked]
 * puts the button row over the D-pad; otherwise the buttons stand in a
 * column beside it. [buttons] is false when not even the row fits.
 */
data class TypingFit(val dpad: Float?, val stacked: Boolean, val buttons: Boolean = true)

/** The smallest D-pad shown with the row over it. Beside the button column it can go to 152dp, arrows still 48dp deep (see [typingRing]). */
private const val MIN_STACKED_DPAD = 160f
private const val TYPING_GAP = 12f
private const val NAV_COLUMN_GAP = 8f
private const val BESIDE_MARGIN = 4f

/**
 * The ring and OK disc, in dp, for a D-pad given [size] dp of room (its
 * base included) above the type bar. The OK disc keeps the full D-pad's
 * share of the ring ([okShare]) but gives way so every arrow is at least
 * 48dp deep, and never goes under 48dp itself. Both hold from 147dp up,
 * and [typingFit] never offers less than 152dp.
 */
fun typingRing(size: Float, okShare: Float): Pair<Float, Float> {
    val ring = size - DPAD_LIP
    val ok = (ring * okShare).coerceAtMost(ring - 2 * ROW_HEIGHT).coerceAtLeast(ROW_HEIGHT)
    return ring to ok
}

/**
 * Fits the D-pad into [room] dp of height (under the status row, above
 * the bar), at most [full] dp across: stacked under the [row]-tall button
 * row when it can be at least 160dp; else beside a column of the three
 * buttons, as tall as the room allows less 4dp above and below; else just the button row, when
 * that fits. Nothing is ever placed where the bar would cover it.
 */
fun typingFit(room: Float, full: Float, row: Float): TypingFit {
    val stacked = room - row - 3 * TYPING_GAP
    if (stacked >= MIN_STACKED_DPAD) return TypingFit(minOf(stacked, full), stacked = true)
    if (room < row) return TypingFit(null, stacked = true, buttons = false)
    if (room < 3 * row + 2 * NAV_COLUMN_GAP) return TypingFit(null, stacked = true)
    return TypingFit(minOf(room - 2 * BESIDE_MARGIN, full), stacked = false)
}

/**
 * The remote while the type bar is open: a short body holding the status
 * row, then Back, Home and Menu over the D-pad in the space above the bar,
 * so the TV's results can be moved through without closing the keyboard
 * (see [typingFit]). [modifier] already keeps clear of the bar and the
 * keyboard.
 */
@Composable
internal fun TypingRemote(p: RemoteParts, modifier: Modifier) {
    val emphasis = rememberStatusPulse(p.pulse)
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Shell(Modifier.widthIn(max = SHELL_MAX_WIDTH.dp).fillMaxHeight().calmShell(), fill = true) {
            NameRow(p.plate, Modifier.fillMaxWidth(), words = true, emphasis = emphasis)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val fit = typingFit(maxHeight.value, p.metrics.dpad, ROW_HEIGHT)
                val gap = TYPING_GAP.dp
                if (fit.stacked) {
                    // Even space above, between and below, so a roomy screen does not bunch them.
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (fit.buttons) NavRow(p, Modifier.height(ROW_HEIGHT.dp))
                        fit.dpad?.let { Pad(p, it) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        fit.dpad?.let { Pad(p, it) }
                        NavColumn(p)
                    }
                }
            }
        }
    }
}

@Composable
private fun Pad(p: RemoteParts, size: Float) {
    val (ring, ok) = typingRing(size, p.metrics.ok / p.metrics.dpad)
    DirectionPad(p.dpad, p.connected, p.press, Modifier, ring.dp, ok.dp)
}

/** Back, Home and Menu stood one above the other, for when the row does not fit under the D-pad. */
@Composable
private fun NavColumn(p: RemoteParts) {
    Column(verticalArrangement = Arrangement.spacedBy(NAV_COLUMN_GAP.dp)) {
        for ((label, key, glyph) in NAV_KEYS) {
            val m = Modifier.width(104.dp).height(ROW_HEIGHT.dp)
            RemoteButton(label, { p.press(key) }, m, p.connected) { Glyph(glyph) }
        }
    }
}

/** The Back, Home and Menu keys, shared with the row in [NavRow]. */
internal val NAV_KEYS = listOf(
    Triple("Back", Key.BACK, NavGlyph.Back),
    Triple("Home", Key.HOME, NavGlyph.Home),
    Triple("Menu", Key.MENU, NavGlyph.Menu),
)
