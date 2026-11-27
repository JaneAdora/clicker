package com.janeadora.clicker.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.core.DpadStyle
import com.janeadora.clicker.ui.shortcuts.ShortcutRow
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.LocalClickerColors

/** Back, Home and Menu: three keys on the body's face. Height comes from [modifier]. */
@Composable
internal fun NavRow(p: RemoteParts, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, send, glyph) in NAV_KEYS) {
            RemoteButton(label, { p.press(send) }, Modifier.weight(1f).fillMaxHeight(), p.connected) { Glyph(glyph) }
        }
    }
}

/**
 * The D-pad's compartment: the ring [padTop] under its top edge (in the
 * middle when null), Rewind in the bottom left corner and Fast forward in
 * the bottom right (see [cornerKeys]). With the swipe pad chosen, the pad
 * takes the compartment's width above the two keys (see [swipePadRect]), so
 * no part of the pad lies under a key.
 */
@Composable
internal fun PadBay(p: RemoteParts, modifier: Modifier, dpad: Dp, ok: Dp, padTop: Dp?) {
    val swipe = p.dpad == DpadStyle.Swipe
    Layout(
        content = {
            Bay {}
            if (swipe) SwipePad(p.connected, p.press, shape = UnitShapes.bayKey) else DPad(p.connected, p.press, Modifier, dpad, ok)
            TransportKey(forward = false, p.connected, p.press)
            TransportKey(forward = true, p.connected, p.press)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        // The compartment takes the size it is given; asked how tall it would like to be, the least.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val w = width.toDp().value
        val h = height.toDp().value
        fun fixed(r: DpRect) = Constraints.fixed(r.width.dp.roundToPx().coerceAtLeast(0), r.height.dp.roundToPx().coerceAtLeast(0))
        val (rewindAt, forwardAt) = cornerKeys(w, h)
        val padAt = swipePadRect(w, h)
        val bay = measurables[0].measure(Constraints.fixed(width, height))
        val pad = measurables[1].measure(if (swipe) fixed(padAt) else Constraints())
        val rewind = measurables[2].measure(fixed(rewindAt))
        val forward = measurables[3].measure(fixed(forwardAt))
        layout(width, height) {
            bay.place(0, 0)
            when {
                swipe -> pad.place(padAt.left.dp.roundToPx(), padAt.top.dp.roundToPx())
                padTop != null -> pad.place((width - pad.width) / 2, padTop.roundToPx())
                else -> pad.place((width - pad.width) / 2, (height - pad.height) / 2)
            }
            rewind.place(rewindAt.left.dp.roundToPx(), rewindAt.top.dp.roundToPx())
            forward.place(forwardAt.left.dp.roundToPx(), forwardAt.top.dp.roundToPx())
        }
    }
}

/**
 * The shortcut rack: the tiles in a compartment, scrolling sideways inside
 * it. The inset is the compartment's, outside the scroll, so a tile that is
 * only partly in view is cut short of the hairline and never touches the
 * compartment's corner.
 */
@Composable
internal fun Rack(p: RemoteParts, tile: Dp, modifier: Modifier = Modifier) {
    Bay(modifier) {
        Box(Modifier.padding(BAY_INSET.dp)) {
            ShortcutRow(p.state.shortcuts, p.connected, p.pictures, p.onAction, PaddingValues(0.dp), tile)
        }
    }
}

/**
 * Type (a keyboard) in the left corner, More (three dots) in the right, the
 * nameplate and vent between. Height comes from [modifier]. [cornerEnd] is
 * false where More does not sit in the shell's corner (landscape).
 */
@Composable
internal fun BottomPlate(p: RemoteParts, modifier: Modifier = Modifier, cornerEnd: Boolean = true) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val key = Modifier.width(72.dp).fillMaxHeight()
        RemoteButton("Type", { p.onAction(RemoteAction.OpenTyping) }, key, p.connected, shape = UnitShapes.bottomStart) {
            Glyph(NavGlyph.Keyboard)
        }
        Row(
            // Decoration: TalkBack skips it.
            Modifier.weight(1f).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CLICKER", style = ClickerType.plate, color = LocalClickerColors.current.muted, maxLines = 1, softWrap = false)
            Vent()
        }
        RemoteButton("More", { p.onAction(RemoteAction.OpenMore) }, key, shape = if (cornerEnd) UnitShapes.bottomEnd else UnitShapes.key) {
            Glyph(NavGlyph.More)
        }
    }
}
