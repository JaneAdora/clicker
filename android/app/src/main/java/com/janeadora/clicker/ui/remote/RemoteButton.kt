package com.janeadora.clicker.ui.remote

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.janeadora.clicker.ui.theme.ANEK_CAP_LIFT_EM
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.LocalClickerColors

/** Opacity of key buttons while no TV is connected. */
const val INACTIVE_ALPHA = 0.4f

/** What TalkBack adds to a key while no TV is connected. */
const val NOT_CONNECTED = "Not connected"

/**
 * Makes an area a remote key: one touch target that TalkBack reads as
 * [label], plus "Not connected" while [enabled] is false. The key is sent on
 * press down, with a haptic tap once [onPress] says it went out; with no
 * TV, or when the key was refused, it buzzes and says why instead.
 * Keys with [repeat] repeat while held. Every key on the remote uses it, so
 * press behaviour lives in one place (see [detectKeyPresses]). [interaction]
 * hears each press after the key has gone, for the press visual. [feel]
 * picks the haptic of an accepted press.
 */
@Composable
fun Modifier.remoteKey(
    label: String,
    enabled: Boolean = true,
    repeat: Boolean = false,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    feel: KeyFeel = KeyFeel.Nav,
    onPress: () -> Boolean,
): Modifier {
    val presser = rememberKeyPresser(enabled)
    val send by rememberUpdatedState(onPress)
    return this
        .pointerInput(presser, repeat, feel) {
            detectKeyPresses(interaction) {
                KeyHold(repeat, { presser.press(feel, send) }, { presser.tick(send) }, presser::release)
            }
        }
        // TalkBack sends exactly one key, never a repeat.
        .keyNode(label, enabled) { presser.press(feel, send) }
}

/**
 * The accessibility side of a key alone: a button named [label] that runs
 * [onPress], silent content, and "Not connected" while [enabled] is false.
 * The D-pad uses it for areas whose touches its ring handles. [onPress]
 * should go through a [KeyPresser] so TalkBack gets the same feedback.
 */
fun Modifier.keyNode(label: String, enabled: Boolean, onPress: () -> Unit): Modifier =
    clearAndSetSemantics {
        contentDescription = label
        role = Role.Button
        if (!enabled) stateDescription = NOT_CONNECTED
        onClick { onPress(); true }
    }

/**
 * A remote key: a raised face in [shape] with a hairline and a thicker
 * bottom edge, which sits down 2dp while pressed (see [raisedKey]); accent
 * filled when [filled]. Size comes from [modifier] and includes the edge.
 * With [enabled] false it shows at 40%, and a press sends nothing but
 * buzzes and says why (see [remoteKey]); the key then only gives a little,
 * like a key that is locked. [inBay] is for a key seated in a compartment,
 * whose edge takes the stronger line. [feel] picks the haptic, and Power
 * also stays down a moment longer.
 */
@Composable
fun RemoteButton(
    label: String,
    onPress: () -> Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    repeat: Boolean = false,
    filled: Boolean = false,
    shape: Shape = UnitShapes.key,
    inBay: Boolean = false,
    feel: KeyFeel = KeyFeel.Nav,
    content: @Composable () -> Unit,
) {
    val c = LocalClickerColors.current
    val interaction = remember { MutableInteractionSource() }
    val line = when {
        filled -> c.accentPressed
        inBay -> c.bayKeyLine
        else -> c.outlineStrong
    }
    Box(
        modifier
            .alpha(if (enabled) 1f else INACTIVE_ALPHA)
            .remoteKey(label, enabled, repeat, interaction, feel, onPress)
            .raisedKey(
                interaction, shape, if (filled) c.accent else c.key, line,
                minDownMs = if (feel == KeyFeel.Power) POWER_DOWN_MS else KEY_DOWN_MS,
                live = enabled,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The label is the spoken name; remoteKey keeps the content silent.
        CompositionLocalProvider(LocalContentColor provides if (filled) c.onAccent else c.heading) { content() }
    }
}

/** A label on a key: small mono capitals, as on the unit's nameplate. The key itself carries the spoken name. */
@Composable
fun KeyLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Text(text.uppercase(), modifier, style = ClickerType.plate, color = color, maxLines = 1, softWrap = false)
}

/**
 * Anek Gurmukhi label, nudged down so its capitals sit at the optical centre
 * of a button (see [ANEK_CAP_LIFT_EM]).
 */
@Composable
fun ButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ClickerType.button,
    color: Color = LocalContentColor.current,
) {
    Text(
        text,
        modifier.padding(top = capLift(style)),
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        textAlign = TextAlign.Center,
    )
}

/** Top padding that centres Anek capitals optically in a [style] line. */
@Composable
internal fun capLift(style: TextStyle): Dp = with(LocalDensity.current) { style.fontSize.toDp() } * (2 * ANEK_CAP_LIFT_EM)
