package com.janeadora.clicker.ui.shortcuts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.core.RemoteRepository
import com.janeadora.clicker.core.RemoteUiState
import com.janeadora.clicker.core.decodeIcon
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.clicker_ffi.RemoteException

const val IMAGE_UNREADABLE = "That image could not be opened. Try another."
const val PICTURE_NOT_SAVED = "The picture could not be saved. The shortcut was saved without it."
const val SAVE_FAILED = "The shortcut could not be saved. Try again."
const val CLEAR_FAILED = "The shortcut could not be cleared. Try again."

private val SaveGateSaver = Saver<SaveGate, String>(save = { it.saving.orEmpty() }, restore = { SaveGate(it.ifEmpty { null }) })

private val ImageDraftSaver = Saver<ImageDraft, List<Any>>(save = { ImageDraft.save(it) }, restore = { ImageDraft.restore(it) })

/**
 * The shortcut editor around [content] (the remote). [content] gets each
 * tile's picture by slot and a function that opens the editor on a slot,
 * with the tile size to preview at. Picked images are copied in at once
 * (the picker's grant is short-lived) into a pending file by [store], and
 * only replace the tile's picture on Save; Cancel drops them. File work
 * runs in [store], off the main thread and past a rotation.
 */
@Composable
fun ShortcutsHost(
    state: RemoteUiState,
    repository: RemoteRepository,
    store: ShortcutStore,
    content: @Composable (pictures: (String) -> TilePicture, edit: (String, Dp) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var tile by rememberSaveable { mutableFloatStateOf(72f) }
    var image by rememberSaveable(stateSaver = ImageDraftSaver) { mutableStateOf(ImageDraft(saved = false)) }
    var imageError by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var staged by remember { mutableIntStateOf(0) }
    var gate by rememberSaveable(stateSaver = SaveGateSaver) { mutableStateOf(SaveGate()) }

    // Left-over pending files from a killed process, unless an editor is being restored.
    LaunchedEffect(Unit) { if (editing == null) store.sweepOnce() }
    LaunchedEffect(state.shortcuts) { store.refresh() }

    // Results of picture work, which may finish after a rotation.
    val outcome = store.outcome
    LaunchedEffect(outcome) {
        val o = outcome ?: return@LaunchedEffect
        store.consume(o)
        if (o.slot != editing) {
            if (o is PictureOutcome.Staged && o.ok) store.discard(o.slot)
            return@LaunchedEffect
        }
        when (o) {
            is PictureOutcome.Staged -> if (o.ok) {
                image = image.chosen(); imageError = null; staged++
            } else {
                imageError = IMAGE_UNREADABLE
            }
            is PictureOutcome.Saved -> {
                // Only the save in flight; a second one for a closed editor is dropped.
                if (!gate.takes(o.slot, editing)) return@LaunchedEffect
                gate = gate.finished()
                if (o.ok) {
                    editing = null
                } else {
                    image = ImageDraft(saved = store.has(o.slot))
                    imageError = PICTURE_NOT_SAVED
                }
            }
        }
    }

    val slot = editing
    val saved = slot?.let { store[it] }
    LaunchedEffect(slot, image.shows, image.pending, staged, saved) {
        preview = when {
            slot == null || !image.shows -> null
            image.pending -> withContext(Dispatchers.IO) { decodeIcon(store.images.pendingFor(slot))?.asImageBitmap() }
            else -> saved
        }
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        val target = editing
        // A cancelled pick changes nothing.
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        store.stage(target) { resolver.openInputStream(uri) ?: throw FileNotFoundException() }
    }

    content({ s -> tilePicture(store[s], store.withPicture?.contains(s)) }) { s, size ->
        editing = s
        tile = size.value
        image = ImageDraft(saved = store.has(s))
        imageError = null
        gate = SaveGate()
    }

    val current = state.shortcuts.firstOrNull { it.slot == slot } ?: return
    ShortcutEditor(
        draft = ShortcutDraft(current.slot, current.label, current.target, filled = current.target.isNotEmpty()),
        image = image,
        preview = preview,
        imageError = imageError,
        tile = tile.dp,
        saving = !gate.canSave,
        onChooseImage = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
        onRemoveImage = { image = image.removed(); imageError = null },
        onSave = save@{ label, link ->
            if (!gate.canSave) return@save null
            checkShortcut(label, link, image.shows)?.let { return@save it }
            try {
                repository.setShortcut(current.slot, label, link)
            } catch (e: RemoteException) {
                return@save Problem(Field.Link, if (link.isBlank()) LINK_NEEDED else SAVE_FAILED)
            }
            // The editor closes once the picture is in place (see the outcome above).
            gate = gate.started(current.slot)
            store.save(current.slot, image.onSave)
            null
        },
        onClear = clear@{
            if (!gate.canSave) return@clear null
            try {
                repository.clearShortcut(current.slot)
            } catch (e: RemoteException) {
                return@clear CLEAR_FAILED
            }
            editing = null
            store.clear(current.slot)
            null
        },
        onDismiss = {
            editing = null
            gate = SaveGate()
            store.discard(current.slot)
        },
    )
}
