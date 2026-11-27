package com.janeadora.clicker.ui.shortcuts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.ui.devices.ClickerDialog
import com.janeadora.clicker.ui.devices.DialogButton
import com.janeadora.clicker.ui.devices.DialogText
import com.janeadora.clicker.ui.devices.DialogTextField
import com.janeadora.clicker.ui.devices.FieldMessage
import com.janeadora.clicker.ui.remote.ButtonLabel
import com.janeadora.clicker.ui.remote.SHEET_SHAPE
import com.janeadora.clicker.ui.remote.SheetBarsFollowTheme
import com.janeadora.clicker.ui.theme.ClickerShapes
import com.janeadora.clicker.ui.theme.ClickerType
import com.janeadora.clicker.ui.theme.DarkColors
import com.janeadora.clicker.ui.theme.LocalClickerColors

/** What the editor opens with. [filled] is false for an empty slot. */
data class ShortcutDraft(val slot: String, val label: String, val target: String, val filled: Boolean)

/**
 * The shortcut editor, a bottom sheet: label, link or package name, and
 * the picture. [image] and [preview] are the staged picture (see
 * [ImageDraft]); [imageError] says a chosen picture could not be used.
 * [onSave] gets the trimmed label and link and returns a problem to show,
 * or null once saved. [onClear] runs after the one confirmation and
 * returns an error to show, or null. While [saving] (a save in flight)
 * Save and Clear shortcut are off. Never reads the key feedback: the
 * sheet is not a remote key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShortcutEditor(
    draft: ShortcutDraft,
    image: ImageDraft,
    preview: ImageBitmap?,
    imageError: String?,
    tile: Dp,
    saving: Boolean,
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSave: (label: String, link: String) -> Problem?,
    onClear: () -> String?,
    onDismiss: () -> Unit,
) {
    val c = LocalClickerColors.current
    var label by rememberSaveable(draft.slot) { mutableStateOf(draft.label) }
    var link by rememberSaveable(draft.slot) { mutableStateOf(draft.target) }
    var problem by rememberSaveable(draft.slot, stateSaver = ProblemSaver) { mutableStateOf<Problem?>(null) }
    var confirmClear by rememberSaveable(draft.slot) { mutableStateOf(false) }
    var clearError by rememberSaveable(draft.slot) { mutableStateOf<String?>(null) }
    val save = { problem = onSave(label.trim(), link.trim()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SHEET_SHAPE,
        containerColor = c.surfaceContainer,
        contentColor = c.onSurface,
        tonalElevation = 0.dp,
        scrimColor = c.surface.copy(alpha = 0.7f),
        // No handle: the sheet drags by its body and closes from the scrim or Back.
        dragHandle = null,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = c != DarkColors,
            isAppearanceLightNavigationBars = c != DarkColors,
        ),
    ) {
        SheetBarsFollowTheme(light = c != DarkColors)
        // The sheet's own window, so Next moves focus within the sheet.
        val focus = LocalFocusManager.current
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (draft.filled) "Edit shortcut" else "New shortcut", style = ClickerType.title, color = c.heading)
            Row(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Preview(if (image.shows) preview else null, image.shows, label.trim().ifEmpty { draft.slot }, tile)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton("Choose image", onChooseImage)
                    if (image.shows) DialogButton("Remove image", onRemoveImage)
                }
            }
            if (imageError != null) FieldMessage(imageError)
            FieldLabel("Label")
            DialogTextField(
                initial = draft.label,
                onChange = { label = it; problem = null },
                description = "Label",
                errorText = problem?.takeIf { it.field == Field.Label }?.text,
                keyboardType = KeyboardType.Text,
                onDone = { focus.moveFocus(FocusDirection.Down) },
                capitalization = KeyboardCapitalization.Words,
                autoFocus = false,
                imeAction = ImeAction.Next,
            )
            // Only when there is something to say, so no gap sits under the label.
            problem?.takeIf { it.field == Field.Label }?.let { FieldMessage(it.text) }
            FieldLabel("Link or package name")
            DialogTextField(
                initial = draft.target,
                onChange = { link = it; problem = null },
                description = "Link or package name",
                errorText = problem?.takeIf { it.field == Field.Link }?.text,
                keyboardType = KeyboardType.Uri,
                onDone = save,
                autoFocus = false,
            )
            val linkProblem = problem?.takeIf { it.field == Field.Link }?.text
            FieldMessage(linkProblem ?: LINK_HELP, muted = linkProblem == null)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (draft.filled) DialogButton("Clear shortcut", { clearError = null; confirmClear = true }, enabled = !saving)
                Spacer(Modifier.weight(1f))
                DialogButton("Cancel", onDismiss)
                DialogButton("Save", save, filled = true, enabled = !saving)
            }
        }
    }
    if (confirmClear) {
        ClickerDialog(
            title = "Clear ${draft.label.ifEmpty { "shortcut ${draft.slot}" }}?",
            onDismiss = { confirmClear = false },
            buttons = {
                DialogButton("Cancel", { confirmClear = false })
                DialogButton("Clear", { clearError = onClear() }, filled = true, enabled = !saving)
            },
        ) {
            DialogText(CLEAR_BODY)
            if (clearError != null) FieldMessage(clearError)
        }
    }
}

// The picture as the tile will show it: centre-cropped in the tile's
// shape, or the label on the tile tone when there is none.
@Composable
private fun Preview(bitmap: ImageBitmap?, hasImage: Boolean, label: String, tile: Dp) {
    val c = LocalClickerColors.current
    Box(
        Modifier.size(tile)
            .clearAndSetSemantics { contentDescription = if (hasImage) "Shortcut image" else "No image" }
            .clip(TILE_SHAPE)
            .background(c.surface)
            .border(ClickerShapes.border, c.outlineStrong, TILE_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else if (!hasImage) {
            ButtonLabel(label, Modifier.padding(horizontal = 6.dp), style = ClickerType.label, color = c.heading)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, Modifier.padding(top = 4.dp), style = ClickerType.label, color = LocalClickerColors.current.heading)
}

private val ProblemSaver = Saver<Problem?, List<String>>(
    save = { p -> p?.let { listOf(it.field.name, it.text) } ?: emptyList() },
    restore = { v -> if (v.size == 2) Problem(Field.valueOf(v[0]), v[1]) else null },
)
