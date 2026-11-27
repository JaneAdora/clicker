package com.janeadora.clicker.ui.shortcuts

import uniffi.clicker_ffi.ShortcutSlot

/** One tile in the shortcut row. */
sealed interface RowTile {
    /** A saved shortcut. */
    data class Filled(val slot: String, val label: String) : RowTile {
        /** What TalkBack calls the tile; a tile with only a picture is called by its slot. */
        val description: String get() = label.ifEmpty { "Shortcut $slot" }
    }

    /** Adds a shortcut in [slot], the first empty one. */
    data class Add(val slot: String) : RowTile
}

/**
 * The tiles to show for the core's ten [slots]: the filled ones in slot
 * order (1 to 9, then 0), then one Add tile on the first empty slot when
 * there is one.
 */
fun rowTiles(slots: List<ShortcutSlot>): List<RowTile> {
    val sorted = slots.sortedBy(::slotOrder)
    val filled = sorted.filter { it.target.isNotEmpty() }.map { RowTile.Filled(it.slot, it.label) }
    val empty = sorted.firstOrNull { it.target.isEmpty() } ?: return filled
    return filled + RowTile.Add(empty.slot)
}

private fun slotOrder(s: ShortcutSlot): Int = s.slot.toIntOrNull()?.let { if (it == 0) 10 else it } ?: 11

const val LINK_NEEDED = "Add a link or package name."
const val NAME_OR_IMAGE_NEEDED = "Give it a name or an image."
const val CLEAR_BODY = "The button and its image will be removed."
const val LINK_HELP = "A web link such as https://www.netflix.com, or an app package name."

/** The editor's two text fields. */
enum class Field { Label, Link }

/** What is wrong with a shortcut, and under which field to say it. */
data class Problem(val field: Field, val text: String)

/** Null when the shortcut can be saved. An empty label is fine when the tile has a picture. */
fun checkShortcut(label: String, link: String, hasImage: Boolean): Problem? = when {
    link.isBlank() -> Problem(Field.Link, LINK_NEEDED)
    label.isBlank() && !hasImage -> Problem(Field.Label, NAME_OR_IMAGE_NEEDED)
    else -> null
}

/** What Save does to the slot's picture. */
enum class ImageSave { Nothing, Commit, Remove }

/** How the editor has changed the picture so far. */
enum class ImageChange { Keep, Chosen, Removed }

/**
 * The editor's picture, staged until Save. [saved] is whether the slot had
 * a picture when the editor opened. Choosing writes a pending file that the
 * preview shows; nothing reaches the tile until Save.
 */
data class ImageDraft(val saved: Boolean, val change: ImageChange = ImageChange.Keep) {
    /** Whether the preview shows a picture. */
    val shows: Boolean get() = change == ImageChange.Chosen || (change == ImageChange.Keep && saved)

    /** Whether the preview is the pending file rather than the saved one. */
    val pending: Boolean get() = change == ImageChange.Chosen

    val onSave: ImageSave
        get() = when (change) {
            ImageChange.Keep -> ImageSave.Nothing
            ImageChange.Chosen -> ImageSave.Commit
            ImageChange.Removed -> ImageSave.Remove
        }

    fun chosen(): ImageDraft = copy(change = ImageChange.Chosen)

    fun removed(): ImageDraft = copy(change = ImageChange.Removed)

    companion object {
        /** For rememberSaveable: the draft as two flags. */
        fun save(d: ImageDraft): List<Any> = listOf(d.saved, d.change.ordinal)

        fun restore(v: List<Any>): ImageDraft = ImageDraft(v[0] as Boolean, ImageChange.entries[v[1] as Int])
    }
}

/** What a tile shows for its picture. */
sealed interface TilePicture {
    /** No picture, or not known yet: the label. */
    data object None : TilePicture

    /** A picture is on disk but not decoded yet: an empty tile, never the label. */
    data object Loading : TilePicture

    data class Ready(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : TilePicture
}

/** [bitmap] when decoded; otherwise Loading when [onDisk] is true, else None (also while unknown). */
fun tilePicture(bitmap: androidx.compose.ui.graphics.ImageBitmap?, onDisk: Boolean?): TilePicture = when {
    bitmap != null -> TilePicture.Ready(bitmap)
    onDisk == true -> TilePicture.Loading
    else -> TilePicture.None
}

/**
 * A shortcut save in flight. While one is ([saving] holds its slot), Save
 * and Clear shortcut are off, so a double tap saves once. Only the outcome
 * of that save is taken; another arriving after the editor closed is not.
 */
data class SaveGate(val saving: String? = null) {
    val canSave: Boolean get() = saving == null

    fun started(slot: String): SaveGate = SaveGate(slot)

    fun finished(): SaveGate = SaveGate()

    /** Whether an outcome for [slot] belongs to the save in flight in the editor open on [editing]. */
    fun takes(slot: String, editing: String?): Boolean = saving == slot && editing == slot
}
