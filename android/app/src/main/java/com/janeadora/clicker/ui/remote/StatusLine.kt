package com.janeadora.clicker.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.core.Link
import com.janeadora.clicker.core.Volume
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.LocalClickerColors
import com.janeadora.clicker.ui.theme.Settle
import com.janeadora.clicker.ui.theme.linkPulse
import com.janeadora.clicker.ui.theme.reduceMotion
import uniffi.clicker_ffi.Key

/**
 * The status words for [link]. [tvName] is the current TV, or empty
 * when there is none; [anySaved] is whether any TV is saved, so an idle
 * link with TVs saved but none current reads "Not connected", not "Add a TV".
 */
fun statusText(link: Link, tvName: String, anySaved: Boolean = tvName.isNotEmpty()): String = when (link) {
    Link.Connected -> tvName
    Link.Connecting -> "Connecting…"
    Link.Unreachable -> "TV not reachable"
    Link.NoWifi -> "Wi-Fi is off"
    Link.Pairing -> "Pairing…"
    Link.Idle -> if (!anySaved) "Add a TV" else "Not connected"
}

/** What TalkBack reads for the status row, for example "Living Room, connected". */
internal fun statusDescription(link: Link, tvName: String, anySaved: Boolean = tvName.isNotEmpty()): String = when {
    link == Link.Connected -> "$tvName, connected"
    tvName.isEmpty() -> statusText(link, tvName, anySaved)
    else -> "$tvName, ${statusText(link, tvName, anySaved)}"
}

/**
 * The words on the body's top row when the display carries the status: the
 * current TV; a prompt to pick one when TVs are saved but none is current;
 * "Add a TV" when none is saved, where the row is the one call to action.
 */
fun plateName(tvName: String, anySaved: Boolean = tvName.isNotEmpty()): String = when {
    tvName.isNotEmpty() -> tvName
    anySaved -> "Choose a TV"
    else -> "Add a TV"
}

/** The key beside the status: what it says is what it will do. All three ask the app to try again. */
enum class PlateKey(val label: String) { Retry("Retry"), Allow("Allow"), Settings("Settings") }

/**
 * Which key goes with the status, if any. Missing local network access
 * comes first, since nothing works without it: Allow, or Settings once
 * Android will no longer ask ([opensSettings]). Otherwise Retry when the TV
 * is not reachable.
 */
fun plateKey(link: Link, permissionMissing: Boolean, opensSettings: Boolean = false): PlateKey? = when {
    permissionMissing -> if (opensSettings) PlateKey.Settings else PlateKey.Allow
    link == Link.Unreachable -> PlateKey.Retry
    else -> null
}

/**
 * One short emphasis per change of [pulse], from 0 up to 1 and back, ending
 * still (none with reduce motion on). It marks a press made with no TV.
 */
@Composable
fun rememberStatusPulse(pulse: Int): () -> Float {
    val emphasis = remember { Animatable(0f) }
    val context = LocalContext.current
    LaunchedEffect(pulse) {
        if (pulse == 0 || reduceMotion(context)) return@LaunchedEffect
        emphasis.snapTo(0f)
        emphasis.animateTo(1f, tween(PULSE_UP_MS))
        emphasis.animateTo(0f, tween(PULSE_DOWN_MS, easing = Settle))
    }
    return { emphasis.value }
}

/**
 * What the top of the body needs to know. [notice] is set while local
 * network access is missing. [powerOn] is the display's power-on after a
 * connection (see [rememberPowerOn]).
 */
class PlateState(
    val link: Link,
    val tvName: String,
    val volume: Volume?,
    val anySaved: Boolean,
    val notice: String?,
    val noticeOpensSettings: Boolean,
    val onAction: (RemoteAction) -> Boolean,
    val powerOn: () -> Float = { 1f },
) {
    val key: PlateKey? get() = plateKey(link, notice != null, noticeOpensSettings)
}

/**
 * The body's top row: the link light, the TV's name and a caret, which
 * together open the TV switcher, and Power in the shell's big corner.
 * [height] is the height of the row and of Power. With [words] (while
 * typing, where there is no room for the display) the row itself says the
 * status, as "TV not reachable" over the TV's name, takes the [emphasis],
 * and holds the status key.
 */
@Composable
fun NameRow(s: PlateState, modifier: Modifier = Modifier, height: Dp = ROW_HEIGHT.dp, words: Boolean = false, emphasis: () -> Float = { 0f }) {
    val c = LocalClickerColors.current
    val connected = s.link == Link.Connected
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .graphicsLayer {
                        val scale = if (words) 1f + PULSE_SCALE * emphasis() else 1f
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .heightIn(min = height)
                    .clip(UnitShapes.key)
                    .clickable { s.onAction(RemoteAction.OpenDevices) }
                    .clearAndSetSemantics {
                        contentDescription = statusDescription(s.link, s.tvName, s.anySaved)
                        role = Role.Button
                        onClick("Choose TV") { s.onAction(RemoteAction.OpenDevices); true }
                    }
                    .padding(start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinkLight(s.link)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    if (words && !connected && s.tvName.isNotEmpty()) {
                        Text(s.tvName, style = ClickerType.label, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (words) statusText(s.link, s.tvName, s.anySaved) else plateName(s.tvName, s.anySaved),
                        // Optical centring against the light, as for button labels.
                        Modifier.padding(top = capLift(ClickerType.title)),
                        style = ClickerType.title,
                        color = c.heading,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Caret(Modifier.size(14.dp))
            }
        }
        if (words) s.key?.let { StatusKey(it, s.onAction, Modifier.height(height)) }
        RemoteButton(
            label = "Power",
            onPress = { s.onAction(RemoteAction.Press(Key.POWER)) },
            modifier = Modifier.size(height),
            enabled = connected,
            shape = UnitShapes.power,
            feel = KeyFeel.Power,
        ) { PowerGlyph(Modifier.size(20.dp)) }
    }
}

/**
 * The display's row: the night display and, when there is something to do
 * about what it says, the key for it at its right (Retry, Allow or
 * Settings). The row is as tall as a key in every state, so nothing under
 * it moves when the link changes.
 */
@Composable
fun DisplayRow(s: PlateState, modifier: Modifier = Modifier, emphasis: () -> Float = { 0f }) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NightDisplay(s.link, s.volume, s.tvName, s.anySaved, Modifier.weight(1f).fillMaxHeight(), s.notice, emphasis, s.powerOn)
        s.key?.let { StatusKey(it, s.onAction, Modifier.fillMaxHeight()) }
    }
}

// Retry, Allow or Settings. A key like the others, but it acts on release: nothing goes to the TV.
@Composable
private fun StatusKey(key: PlateKey, onAction: (RemoteAction) -> Boolean, modifier: Modifier) {
    val c = LocalClickerColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.widthIn(min = 72.dp)
            .clickable(interaction, indication = null, role = Role.Button) { onAction(RemoteAction.Retry) }
            // Spoken as written, not as the capitals on the key.
            .clearAndSetSemantics {
                contentDescription = key.label
                role = Role.Button
                onClick { onAction(RemoteAction.Retry); true }
            }
            .raisedKey(interaction, UnitShapes.key, c.key, c.outlineStrong)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        KeyLabel(key.label, color = c.heading)
    }
}

private const val PULSE_UP_MS = 90
private const val PULSE_DOWN_MS = 160
internal const val PULSE_SCALE = 0.06f

/**
 * The link light: lit in the accent when connected, lilac while on the way,
 * a hollow ring otherwise. While on the way it breathes, slowly, and only
 * then: the moment the link is up or gone the light is lit or hollow and
 * still. With reduce motion it does not breathe.
 */
@Composable
private fun LinkLight(link: Link) {
    val c = LocalClickerColors.current
    val m = Modifier.size(10.dp).clip(ClickerShapes.control)
    when (link) {
        Link.Connected -> Box(m.background(c.accent))
        Link.Connecting, Link.Pairing -> {
            val context = LocalContext.current
            val glow = remember { mutableFloatStateOf(1f) }
            // Runs only while this branch is on screen, so it stops with the state.
            LaunchedEffect(Unit) {
                if (reduceMotion(context)) return@LaunchedEffect
                val start = withFrameNanos { it }
                while (true) glow.floatValue = linkPulse((withFrameNanos { it } - start) / 1_000_000)
            }
            Box(
                Modifier.size(10.dp)
                    .graphicsLayer {
                        val g = glow.floatValue
                        alpha = LINK_DIM + (1f - LINK_DIM) * g
                        val s = LINK_SMALL + (1f - LINK_SMALL) * g
                        scaleX = s
                        scaleY = s
                    }
                    .clip(ClickerShapes.control)
                    .background(c.accentPressed),
            )
        }
        else -> Box(m.border(1.5.dp, c.outlineStrong, ClickerShapes.control))
    }
}

// The light at the low point of its breath: how faint, and how small.
private const val LINK_DIM = 0.35f
private const val LINK_SMALL = 0.7f

@Composable
private fun Caret(modifier: Modifier) {
    val color = LocalClickerColors.current.muted
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.15f, h * 0.35f)
            lineTo(w * 0.5f, h * 0.7f)
            lineTo(w * 0.85f, h * 0.35f)
        }
        drawPath(p, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// The ring is centred on the disc and the bar rises from its centre, so
// the glyph's ink sits at the disc's centre (measured on the emulator: ink
// mass 0.4px low, ink box 1px high, at 2.625px per dp). The ring used to
// sit a half stroke low, which read as the glyph sinking.
@Composable
private fun PowerGlyph(modifier: Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val r = size.minDimension / 2f - stroke
        val cy = center.y
        drawArc(
            color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(center.x - r, cy - r),
            size = Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(center.x, cy - r - stroke / 2f), Offset(center.x, cy), stroke, StrokeCap.Round)
    }
}
