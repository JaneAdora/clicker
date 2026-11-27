package com.janeadora.clicker.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.Volume
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.LocalClickerColors
import kotlin.math.roundToInt

/**
 * What the night display reads. Connected: [label] "VOL" with the level as
 * the [figure], or "MUTED" alone; [level] is the share of full volume, for
 * the meter. Not connected: the status words as the [label], with no figure
 * and no meter.
 */
data class Readout(val label: String, val figure: String = "", val level: Float? = null, val muted: Boolean = false) {
    /** The whole line, as on the display: "VOL 24", "MUTED", "TV NOT REACHABLE". */
    val text: String get() = if (figure.isEmpty()) label else "$label $figure"
}

/**
 * The night display's reading for [link] and the TV's [volume] (null until
 * the TV reports it). With [permissionMissing] it says so whatever the link,
 * since nothing can connect until that is put right. With no TV saved it
 * reads "NO TV"; the top row carries the prompt to add one.
 */
fun nightReadout(
    link: Link,
    volume: Volume?,
    tvName: String,
    anySaved: Boolean = tvName.isNotEmpty(),
    permissionMissing: Boolean = false,
): Readout = when {
    permissionMissing -> Readout("LOCAL NETWORK OFF")
    link == Link.Idle && !anySaved -> Readout("NO TV")
    link != Link.Connected -> Readout(statusText(link, tvName, anySaved).uppercase())
    volume == null -> Readout("VOL", "--")
    else -> {
        val level = if (volume.max > 0) (volume.level.toFloat() / volume.max).coerceIn(0f, 1f) else 0f
        if (volume.muted) Readout("MUTED", level = level, muted = true) else Readout("VOL", volume.level.toString(), level)
    }
}

/** What TalkBack reads for the display while connected; the status row speaks for the other states. */
internal fun readoutDescription(volume: Volume?): String = when {
    volume == null -> "Volume not known yet"
    volume.muted -> "Muted, volume ${volume.level} of ${volume.max}"
    else -> "Volume ${volume.level} of ${volume.max}"
}

/** How many of [segments] meter segments are lit at [level] (0 to 1). Any sound at all lights one. */
fun litSegments(level: Float, segments: Int): Int =
    if (level <= 0f) 0 else (level * segments).roundToInt().coerceIn(1, segments)

/** Segments in the volume meter, and their pitch. */
const val METER_SEGMENTS = 18
private const val METER_PITCH = 9f
private const val METER_BAR = 5f

/**
 * The night display: a panel that stays dark in both modes, set in mono,
 * with a rim that sets it apart from the compartments. Connected, it shows
 * the volume as a meter with the figure in the accent; otherwise the status
 * words. [notice] is the sentence TalkBack reads while local network access
 * is missing. [emphasis] (0 to 1) swells the words for a moment after a
 * press that found no TV.
 */
@Composable
internal fun NightDisplay(
    link: Link,
    volume: Volume?,
    tvName: String,
    anySaved: Boolean,
    modifier: Modifier = Modifier,
    notice: String? = null,
    emphasis: () -> Float = { 0f },
) {
    val c = LocalClickerColors.current
    val r = nightReadout(link, volume, tvName, anySaved, permissionMissing = notice != null)
    val live = r.level != null || r.figure.isNotEmpty()
    val spoken = notice ?: if (live) readoutDescription(volume) else null
    Row(
        modifier
            .clip(DISPLAY_SHAPE)
            .background(c.night)
            .border(ClickerShapes.border, c.outlineStrong, DISPLAY_SHAPE)
            // Otherwise the status row already speaks the link state.
            .clearAndSetSemantics { if (spoken != null) contentDescription = spoken }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            r.label,
            // Status words take the row and may shorten; a live reading leaves room for the meter and figure.
            (if (live) Modifier else Modifier.weight(1f)).graphicsLayer {
                val s = 1f + PULSE_SCALE * emphasis()
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
            style = ClickerType.readout,
            color = when {
                r.muted -> c.nightAccent
                live -> c.nightMuted
                else -> c.onNight
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (r.level != null) {
            Meter(r.level, r.muted, Modifier.padding(start = 12.dp).size(width = ((METER_SEGMENTS - 1) * METER_PITCH + METER_BAR).dp, height = 10.dp))
        }
        if (r.figure.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            Text(r.figure, style = ClickerType.readout, color = c.nightAccent, maxLines = 1)
        }
    }
}

private val DISPLAY_SHAPE = RoundedCornerShape(10.dp)

// The deck's peak meter: a row of bars lit in the accent from the left up to the level. All dark when muted.
@Composable
private fun Meter(level: Float, muted: Boolean, modifier: Modifier) {
    val c = LocalClickerColors.current
    Canvas(modifier) {
        val bar = METER_BAR.dp.toPx()
        val pitch = METER_PITCH.dp.toPx()
        val lit = if (muted) 0 else litSegments(level, METER_SEGMENTS)
        val round = CornerRadius(1.dp.toPx())
        for (i in 0 until METER_SEGMENTS) {
            drawRoundRect(if (i < lit) c.nightAccent else c.nightLine, Offset(i * pitch, 0f), Size(bar, size.height), round)
        }
    }
}
