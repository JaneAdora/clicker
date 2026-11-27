package com.janeadora.clicker.ui.shortcuts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.janeadora.clicker.core.ShortcutImages
import com.janeadora.clicker.core.decodeIcon
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The ten slots, in row order. */
private val SLOTS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

/** What a piece of picture work in [ShortcutStore] came to. */
sealed interface PictureOutcome {
    val slot: String

    /** A picked picture was copied in ([ok]) or could not be read. */
    data class Staged(override val slot: String, val ok: Boolean) : PictureOutcome

    /** Save finished; [ok] is false when the picture could not be put in place. */
    data class Saved(override val slot: String, val ok: Boolean) : PictureOutcome
}

/**
 * The shortcut pictures for the whole process, so they outlive a rotation:
 * which slots have one, the decoded bitmaps, and the file work. [main]
 * must run on the main dispatcher; file work runs on IO and finishes even
 * if the screen goes away, and its result lands in [outcome] for whichever
 * screen is showing then.
 */
class ShortcutStore(val images: ShortcutImages, private val main: CoroutineScope) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shown = mutableStateMapOf<String, ImageBitmap>()
    private val cache = HashMap<String, Pair<Long, ImageBitmap>>()
    private val loading = Mutex()

    /** Slots with a picture on disk; null until first looked up. */
    var withPicture by mutableStateOf<Set<String>?>(null)
        private set

    /** The latest finished piece of work, until [consume] clears it. */
    var outcome by mutableStateOf<PictureOutcome?>(null)
        private set

    /** The decoded picture for [slot], or null when it has none or it has not loaded yet. */
    operator fun get(slot: String): ImageBitmap? = shown[slot]

    /** Whether [slot] has a picture on disk, as far as is known. */
    fun has(slot: String): Boolean = withPicture?.contains(slot) == true

    fun consume(o: PictureOutcome) {
        if (outcome == o) outcome = null
    }

    /**
     * Looks up which slots have a picture, publishes that first so a tile
     * never shows its label while its picture loads, then decodes what
     * changed. Cached by slot and file version.
     */
    fun refresh() {
        main.launch {
            loading.withLock {
                val versions = withContext(Dispatchers.IO) { SLOTS.associateWith { images.version(it) } }
                withPicture = versions.filterValues { it > 0L }.keys
                val found = withContext(Dispatchers.IO) {
                    versions.mapValues { (slot, version) ->
                        if (version == 0L) return@mapValues null
                        cache[slot]?.takeIf { it.first == version }?.second
                            ?: decodeIcon(images.fileFor(slot))?.asImageBitmap()?.also { cache[slot] = version to it }
                    }
                }
                for ((slot, bitmap) in found) if (bitmap == null) shown.remove(slot) else shown[slot] = bitmap
                // A file that does not decode reads as no picture: the tile shows its label.
                withPicture = found.filterValues { it != null }.keys
            }
        }
    }

    /** Copies a picked picture into [slot]'s pending file. Reports [PictureOutcome.Staged]. */
    fun stage(slot: String, open: () -> InputStream) {
        io.launch {
            val ok = images.stage(slot, open) != null
            withContext(Dispatchers.Main) { outcome = PictureOutcome.Staged(slot, ok) }
        }
    }

    /** Applies the editor's picture change on Save, then reports [PictureOutcome.Saved]. */
    fun save(slot: String, change: ImageSave) {
        io.launch {
            val ok = when (change) {
                ImageSave.Commit -> images.commit(slot)
                ImageSave.Remove -> { images.remove(slot); true }
                ImageSave.Nothing -> true
            }
            images.discard(slot)
            withContext(Dispatchers.Main) {
                refresh()
                outcome = PictureOutcome.Saved(slot, ok)
            }
        }
    }

    /** Drops [slot]'s picture and anything staged for it. */
    fun clear(slot: String) = files { remove(slot); discard(slot) }

    /** Drops anything staged for [slot]. */
    fun discard(slot: String) = files { discard(slot) }

    private var swept = false

    /** Once per process: drops staged pictures a killed process left behind. */
    fun sweepOnce() {
        if (swept) return
        swept = true
        files { discardAll() }
    }

    private fun files(work: ShortcutImages.() -> Unit) {
        io.launch {
            images.work()
            withContext(Dispatchers.Main) { refresh() }
        }
    }
}
