package com.janeadora.clicker.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import uniffi.clicker_ffi.Key

/**
 * Rewind or Fast forward ([forward]): a small key for a bottom corner of
 * the D-pad's compartment. Size and place come from [modifier]. Play/pause is not on the main screen; it is in
 * the More sheet.
 */
@Composable
fun TransportKey(forward: Boolean, enabled: Boolean, onPress: (Key) -> Boolean, modifier: Modifier = Modifier) {
    val label = if (forward) "Fast forward" else "Rewind"
    val key = if (forward) Key.FAST_FORWARD else Key.REWIND
    RemoteButton(label, { onPress(key) }, modifier, enabled, shape = UnitShapes.bayKey, inBay = true) {
        Glyph { c -> twin(c, forward) }
    }
}

/** A 22dp glyph drawn in the button's content color. */
@Composable
internal fun Glyph(draw: DrawScope.(Color) -> Unit) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) { draw(color) }
}

// Fills a triangle with softened corners: a fill plus a thin round-joined stroke.
private fun DrawScope.triangle(color: Color, a: Offset, b: Offset, tip: Offset) {
    val p = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(tip.x, tip.y)
        close()
    }
    drawPath(p, color, style = Fill)
    drawPath(p, color, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
}

// Two triangles pointing the same way: rewind or fast-forward.
private fun DrawScope.twin(color: Color, forward: Boolean) {
    val w = size.width
    val h = size.height
    for (start in listOf(0.08f, 0.5f)) {
        val x0 = w * start
        val x1 = x0 + w * 0.42f
        if (forward) {
            triangle(color, Offset(x0, h * 0.2f), Offset(x0, h * 0.8f), Offset(x1, h * 0.5f))
        } else {
            triangle(color, Offset(x1, h * 0.2f), Offset(x1, h * 0.8f), Offset(x0, h * 0.5f))
        }
    }
}
