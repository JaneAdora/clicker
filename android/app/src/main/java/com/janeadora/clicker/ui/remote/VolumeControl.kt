package com.janeadora.clicker.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.core.Volume
import uniffi.clicker_ffi.Key

/**
 * Volume as one row across the body, on its face like Back, Home and Menu:
 * Volume down, Mute, Volume up. Down and up repeat while held. The level
 * itself reads on the night display; [volume] only tells the Mute key which
 * speaker to draw. Height comes from [modifier].
 */
@Composable
fun VolumeControl(volume: Volume?, enabled: Boolean, onPress: (Key) -> Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val k = Modifier.weight(1f).fillMaxHeight()
        RemoteButton("Volume down", { onPress(Key.VOL_DOWN) }, k, enabled, repeat = true, feel = KeyFeel.Step) {
            Glyph { color -> sign(color, plus = false) }
        }
        RemoteButton("Mute", { onPress(Key.MUTE) }, k, enabled) {
            Glyph { color -> speaker(color, muted = volume?.muted == true) }
        }
        RemoteButton("Volume up", { onPress(Key.VOL_UP) }, k, enabled, repeat = true, feel = KeyFeel.Step) {
            Glyph { color -> sign(color, plus = true) }
        }
    }
}

// A minus, or a plus: drawn, so it sits at the key's exact centre.
private fun DrawScope.sign(color: Color, plus: Boolean) {
    val w = size.width
    val h = size.height
    val stroke = 2.4.dp.toPx()
    drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), stroke, StrokeCap.Round)
    if (plus) drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), stroke, StrokeCap.Round)
}

// A speaker cone with sound waves, or a cross when muted.
private fun DrawScope.speaker(color: Color, muted: Boolean) {
    val w = size.width
    val h = size.height
    val cone = Path().apply {
        moveTo(w * 0.08f, h * 0.36f)
        lineTo(w * 0.28f, h * 0.36f)
        lineTo(w * 0.52f, h * 0.14f)
        lineTo(w * 0.52f, h * 0.86f)
        lineTo(w * 0.28f, h * 0.64f)
        lineTo(w * 0.08f, h * 0.64f)
        close()
    }
    drawPath(cone, color, style = Fill)
    drawPath(cone, color, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
    val stroke = 2.dp.toPx()
    if (muted) {
        drawLine(color, Offset(w * 0.66f, h * 0.36f), Offset(w * 0.94f, h * 0.64f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.94f, h * 0.36f), Offset(w * 0.66f, h * 0.64f), stroke, StrokeCap.Round)
    } else {
        for (r in listOf(0.2f, 0.36f)) {
            drawArc(
                color, startAngle = -50f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(w * 0.52f - w * r, h * 0.5f - h * r),
                size = Size(2 * w * r, 2 * h * r),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}
