package com.janeadora.clicker.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.Volume
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.LocalClickerColors
import com.janeadora.clicker.ui.theme.reduceMotion
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

/** How long the readout takes to come up when a TV connects. */
const val POWER_ON_MS = 340

/** How long a meter segment takes to settle after a volume step. */
const val METER_SETTLE_MS = 220

/**
 * Whether the readout's power-on plays: only when it goes live (connected,
 * with a volume to show) on a screen that was up and not live before.
 * [wasLive] is null for a screen that has just opened, which never plays
 * it, so a rotation or a return to the app shows the reading at once.
 */
fun powerOnPlays(wasLive: Boolean?, live: Boolean): Boolean = wasLive == false && live

/** What a [target] count (lit segments, or the figure) shows [progress] of the way through the power-on: 0 at the start, [target] at the end. */
fun sweepCount(target: Int, progress: Float): Int = (target * progress.coerceIn(0f, 1f)).roundToInt()

/**
 * The readout's power-on, once per connection: how much of the reading
 * shows, from 0 to 1, and 1 at rest. [live] is whether there is a live
 * reading to show. With reduce motion it is always 1.
 */
@Composable
internal fun rememberPowerOn(live: Boolean): () -> Float {
    val context = LocalContext.current
    val progress = remember { Animatable(1f) }
    var wasLive by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Decided while composing, so the first live frame already shows the start of the sweep.
    val starting = powerOnPlays(wasLive, live) && !reduceMotion(context)
    LaunchedEffect(live) {
        if (starting) {
            progress.snapTo(0f)
            wasLive = live
            progress.animateTo(1f, tween(POWER_ON_MS, easing = LinearOutSlowInEasing))
        } else {
            wasLive = live
            progress.snapTo(1f)
        }
    }
    return { if (starting) 0f else progress.value }
}

/** Segments in the volume meter, and their pitch. */
const val METER_SEGMENTS = 18
private const val METER_PITCH = 9f
private const val METER_BAR = 5f

/**
 * The night display: a panel that stays dark in both modes, set in mono,
 * with a rim that sets it apart from the compartments. Connected, it shows
 * the volume as a meter with the figure in the accent; otherwise the status
 * words. [notice] is the sentence TalkBack reads while local network access
 * is missing. [emphasis] (0 to 1) swells the words and lights them in the
 * accent for a moment after a press that found no TV. [powerOn] (0 to 1, 1 at rest) is how much of a
 * live reading shows while it comes up after a connection: the meter fills
 * from the left and the figure counts up with it. A volume step lights its
 * segment at once, a touch hot, and it settles; a segment that goes out
 * fades, as on a deck's meter.
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
    powerOn: () -> Float = { 1f },
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
        val ink = when {
            r.muted -> c.nightAccent
            live -> c.nightMuted
            else -> c.onNight
        }
        Words(
            r.label,
            // Status words take the row and may shorten; a live reading leaves room for the meter and figure.
            (if (live) Modifier else Modifier.weight(1f)).graphicsLayer {
                val s = 1f + PULSE_SCALE * emphasis()
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
            ink,
            c.nightAccent,
            emphasis,
        )
        if (r.level != null) {
            Meter(r.level, r.muted, powerOn, Modifier.padding(start = 12.dp).size(width = ((METER_SEGMENTS - 1) * METER_PITCH + METER_BAR).dp, height = 10.dp))
        }
        if (r.figure.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            Figure(r.figure, c.nightAccent, powerOn)
        }
    }
}

// The display's figure. During the power-on it counts up with the meter.
// [powerOn] is read here and nowhere else in the display's composition, so
// only this text recomposes while it counts.
@Composable
private fun Figure(figure: String, ink: Color, powerOn: () -> Float) {
    val coming = powerOn()
    val level = figure.toIntOrNull()
    Text(
        if (coming < 1f && level != null) sweepCount(level, coming).toString() else figure,
        style = ClickerType.readout,
        color = ink,
        maxLines = 1,
    )
}

// The display's words. After a press that found no TV they also light in
// the accent for that moment; only this text recomposes while they do.
@Composable
private fun Words(text: String, modifier: Modifier, ink: Color, lit: Color, emphasis: () -> Float) {
    val e = emphasis()
    Text(
        text,
        modifier,
        style = ClickerType.readout,
        color = if (e > 0f) lerp(ink, lit, e) else ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val DISPLAY_SHAPE = RoundedCornerShape(10.dp)

// The deck's peak meter: a row of bars lit in the accent from the left up to the level. All dark when muted.
// When the level changes, the bars that came on start a touch hot and settle, and the bars that went out fade.
@Composable
private fun Meter(level: Float, muted: Boolean, powerOn: () -> Float, modifier: Modifier) {
    val c = LocalClickerColors.current
    val lit = if (muted) 0 else litSegments(level, METER_SEGMENTS)
    val still = reduceMotion(LocalContext.current)
    val glow = remember { Animatable(0f) }
    var last by remember { mutableIntStateOf(lit) }
    var from by remember { mutableIntStateOf(lit) }
    LaunchedEffect(lit) {
        if (lit == last) return@LaunchedEffect
        if (still) {
            last = lit
            from = lit
            return@LaunchedEffect
        }
        glow.snapTo(1f)
        from = last
        last = lit
        glow.animateTo(0f, tween(METER_SETTLE_MS, easing = LinearEasing))
    }
    Canvas(modifier) {
        val bar = METER_BAR.dp.toPx()
        val pitch = METER_PITCH.dp.toPx()
        val round = CornerRadius(1.dp.toPx())
        val shown = sweepCount(lit, powerOn())
        // A change the effect has not picked up yet draws as its first moment, so no frame shows it settled.
        val fresh = lit != last && !still
        val g = if (fresh) 1f else glow.value
        val before = if (fresh) last else from
        for (i in 0 until METER_SEGMENTS) {
            val color = when {
                g > 0f && i in before until lit -> lerp(c.nightAccent, c.onNight, METER_HOT * g)
                g > 0f && i in lit until before -> lerp(c.nightLine, c.nightAccent, g)
                i < shown -> c.nightAccent
                else -> c.nightLine
            }
            drawRoundRect(color, Offset(i * pitch, 0f), Size(bar, size.height), round)
        }
    }
}

// How far a bar that has just come on leans toward the display's white.
private const val METER_HOT = 0.7f
