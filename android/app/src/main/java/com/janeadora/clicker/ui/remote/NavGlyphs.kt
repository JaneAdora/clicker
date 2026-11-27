package com.janeadora.clicker.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Icons for the label-less buttons, drawn to match the transport glyphs. */
enum class NavGlyph { Back, Home, Menu, Keyboard, More, Close, Clear }

/** A 22dp [glyph] in the button's content color. */
@Composable
internal fun Glyph(glyph: NavGlyph) = Glyph { c ->
    when (glyph) {
        NavGlyph.Back -> back(c)
        NavGlyph.Home -> home(c)
        NavGlyph.Menu -> menu(c)
        NavGlyph.Keyboard -> keyboard(c)
        NavGlyph.More -> more(c)
        NavGlyph.Close -> close(c)
        NavGlyph.Clear -> clear(c)
    }
}

// Line weight shared with the speaker's sound waves.
private fun DrawScope.line() = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

// A return arrow: a filled head pointing left on a line that curls back
// down to the right, as on a TV remote.
private fun DrawScope.back(color: Color) {
    val w = size.width
    val h = size.height
    val tail = Path().apply {
        moveTo(w * 0.30f, h * 0.36f)
        lineTo(w * 0.62f, h * 0.36f)
        cubicTo(w * 0.95f, h * 0.36f, w * 0.95f, h * 0.84f, w * 0.62f, h * 0.84f)
        lineTo(w * 0.36f, h * 0.84f)
    }
    drawPath(tail, color, style = line())
    val head = Path().apply {
        moveTo(w * 0.06f, h * 0.36f)
        lineTo(w * 0.36f, h * 0.14f)
        lineTo(w * 0.36f, h * 0.58f)
        close()
    }
    drawPath(head, color, style = Fill)
    drawPath(head, color, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
}

// A house: a filled roof and walls with the door cut out.
private fun DrawScope.home(color: Color) {
    val w = size.width
    val h = size.height
    val house = Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(w * 0.50f, h * 0.08f)
        lineTo(w * 0.94f, h * 0.48f)
        lineTo(w * 0.80f, h * 0.48f)
        lineTo(w * 0.80f, h * 0.90f)
        lineTo(w * 0.20f, h * 0.90f)
        lineTo(w * 0.20f, h * 0.48f)
        lineTo(w * 0.06f, h * 0.48f)
        close()
        // The door, as a hole.
        moveTo(w * 0.41f, h * 0.62f)
        lineTo(w * 0.59f, h * 0.62f)
        lineTo(w * 0.59f, h * 0.90f)
        lineTo(w * 0.41f, h * 0.90f)
        close()
    }
    drawPath(house, color, style = Fill)
}

// Three horizontal lines.
private fun DrawScope.menu(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = 2.4.dp.toPx()
    for (y in listOf(0.24f, 0.5f, 0.76f)) {
        drawLine(color, Offset(w * 0.12f, h * y), Offset(w * 0.88f, h * y), stroke, StrokeCap.Round)
    }
}

// A keyboard: a rounded outline, two rows of keys and a space bar.
private fun DrawScope.keyboard(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = 1.8.dp.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(w * 0.02f + stroke / 2, h * 0.18f),
        size = Size(w * 0.96f - stroke, h * 0.64f),
        cornerRadius = CornerRadius(w * 0.12f),
        style = Stroke(stroke),
    )
    val key = Size(w * 0.10f, h * 0.10f)
    val corner = CornerRadius(w * 0.02f)
    for ((row, y) in listOf(0 to 0.32f, 1 to 0.46f)) {
        val count = if (row == 0) 5 else 4
        val start = if (row == 0) 0.17f else 0.245f
        for (i in 0 until count) {
            drawRoundRect(color, Offset(w * (start + i * 0.15f) - key.width / 2, h * y), key, corner)
        }
    }
    drawRoundRect(color, Offset(w * 0.30f, h * 0.64f), Size(w * 0.40f, h * 0.08f), corner)
}

// Three dots in a row, as the brand draws everything in dots.
private fun DrawScope.more(color: Color) {
    val w = size.width
    val h = size.height
    for (x in listOf(0.18f, 0.5f, 0.82f)) drawCircle(color, w * 0.10f, Offset(w * x, h * 0.5f))
}

// A chevron pointing down, for putting the type bar and keyboard away.
private fun DrawScope.close(color: Color) {
    val w = size.width
    val h = size.height
    val chevron = Path().apply {
        moveTo(w * 0.18f, h * 0.36f)
        lineTo(w * 0.50f, h * 0.66f)
        lineTo(w * 0.82f, h * 0.36f)
    }
    drawPath(chevron, color, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// A cross in a circle, for clearing all the text.
private fun DrawScope.clear(color: Color) {
    val w = size.width
    val h = size.height
    drawCircle(color, w * 0.42f, Offset(w * 0.5f, h * 0.5f), style = line())
    val stroke = 2.dp.toPx()
    drawLine(color, Offset(w * 0.36f, h * 0.36f), Offset(w * 0.64f, h * 0.64f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.36f, h * 0.64f), stroke, StrokeCap.Round)
}
