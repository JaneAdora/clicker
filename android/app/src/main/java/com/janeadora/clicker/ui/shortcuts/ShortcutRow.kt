package com.janeadora.clicker.ui.shortcuts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.ui.remote.ButtonLabel
import com.janeadora.clicker.ui.remote.INACTIVE_ALPHA
import com.janeadora.clicker.ui.remote.LocalKeyFeedback
import com.janeadora.clicker.ui.remote.NOT_CONNECTED
import com.janeadora.clicker.ui.remote.RemoteAction
import com.janeadora.clicker.ui.remote.rememberKeyPresser
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.LocalClickerColors
import uniffi.clicker_ffi.ShortcutSlot

private const val EDIT_LABEL = "Edit shortcut"

/**
 * The shortcut row: saved shortcuts as [tile]-square tiles with 10dp
 * corners in slot order, then an Add tile when a slot is free (see
 * [rowTiles]). A tile shows its picture from [pictures] when it has one,
 * an empty tile while a known picture loads, else its label. Tap launches with the keys' feedback; long-press edits, as
 * does Add. Scrolls sideways when the tiles overflow, inside the rack
 * compartment that holds it, with [padding] around the tiles. Must sit
 * inside the remote's key feedback provider.
 */
@Composable
fun ShortcutRow(
    shortcuts: List<ShortcutSlot>,
    connected: Boolean,
    pictures: (String) -> TilePicture,
    onAction: (RemoteAction) -> Boolean,
    padding: PaddingValues,
    tile: Dp,
) {
    val tiles = rowTiles(shortcuts)
    if (tiles.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(padding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (t in tiles) when (t) {
            is RowTile.Filled -> key(t.slot) { ShortcutTile(t, pictures(t.slot), connected, onAction, tile) }
            is RowTile.Add -> key("add") { AddTile(tile) { onAction(RemoteAction.EditShortcut(t.slot, tile)) } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(t: RowTile.Filled, picture: TilePicture, connected: Boolean, onAction: (RemoteAction) -> Boolean, tile: Dp) {
    val c = LocalClickerColors.current
    val haptics = LocalKeyFeedback.current.haptics
    // Tap launches on release (long-press edits), with the keys' haptics.
    val presser = rememberKeyPresser(connected)
    val launch = { presser.press { onAction(RemoteAction.Launch(t.slot)) } }
    val edit = { onAction(RemoteAction.EditShortcut(t.slot, tile)) }
    val size = if (picture != TilePicture.None) Modifier.size(tile) else Modifier.sizeIn(minWidth = tile, minHeight = tile)
    Box(
        size.alpha(if (connected) 1f else INACTIVE_ALPHA)
            .clip(TILE_SHAPE)
            .background(c.key)
            // Drawn over the content, so the hairline also rims a picture.
            .border(ClickerShapes.border, c.outlineStrong, TILE_SHAPE)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = EDIT_LABEL,
                hapticFeedbackEnabled = haptics,
                onLongClick = { edit() },
                onClick = { launch() },
            )
            .clearAndSetSemantics {
                contentDescription = t.description
                role = Role.Button
                if (!connected) stateDescription = NOT_CONNECTED
                onClick { launch(); true }
                onLongClick(EDIT_LABEL) { edit(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (picture) {
            is TilePicture.Ready ->
                Image(picture.bitmap, contentDescription = null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            TilePicture.Loading -> Unit
            TilePicture.None -> ButtonLabel(t.label.ifEmpty { t.slot }, Modifier.padding(horizontal = 10.dp), style = ClickerType.label, color = c.heading)
        }
    }
}

// Quieter than a shortcut: a hairline and a plus, no fill. Not dimmed
// without a TV, since adding a shortcut does not need one.
@Composable
private fun AddTile(tile: Dp, onClick: () -> Unit) {
    val c = LocalClickerColors.current
    Box(
        Modifier.size(tile)
            .clip(TILE_SHAPE)
            .border(ClickerShapes.border, c.outlineStrong, TILE_SHAPE)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "Add shortcut"
                role = Role.Button
                onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = 1.5.dp.toPx()
            val mid = size.width / 2f
            drawLine(c.muted, Offset(mid, 0f), Offset(mid, size.height), w, StrokeCap.Round)
            drawLine(c.muted, Offset(0f, mid), Offset(size.width, mid), w, StrokeCap.Round)
        }
    }
}

// Concentric with the rack compartment: its 18dp corner less the 8dp inset.
internal val TILE_SHAPE = RoundedCornerShape(10.dp)
