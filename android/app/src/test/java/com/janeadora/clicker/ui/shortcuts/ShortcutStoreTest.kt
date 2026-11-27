package com.janeadora.clicker.ui.shortcuts

import android.graphics.Bitmap
import android.os.Looper
import com.janeadora.clicker.core.ShortcutImages
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// SDK 35 for the same reason as ShortcutImagesTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var images: ShortcutImages
    private lateinit var store: ShortcutStore

    @Before fun setUp() {
        images = ShortcutImages(File(tmp.root, "shortcut-icons"))
        store = ShortcutStore(images, CoroutineScope(Dispatchers.Main))
    }

    // Runs the main looper until the store reports, as the screen would see it.
    private fun awaitOutcome(): PictureOutcome {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            store.outcome?.let { return it }
            Thread.sleep(10)
        }
        error("no outcome")
    }

    private fun png(): ByteArray {
        val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun a_save_whose_picture_cannot_be_put_in_place_says_so() {
        // Commit with nothing staged: the move fails.
        store.save("3", ImageSave.Commit)
        assertEquals(PictureOutcome.Saved("3", ok = false), awaitOutcome())
    }

    @Test fun staged_then_saved_lands_the_picture_and_reports_it() {
        store.stage("3") { png().inputStream() }
        val staged = awaitOutcome()
        assertEquals(PictureOutcome.Staged("3", ok = true), staged)
        store.consume(staged)
        store.save("3", ImageSave.Commit)
        assertEquals(PictureOutcome.Saved("3", ok = true), awaitOutcome())
        assertTrue(images.has("3"))
        assertFalse(images.pendingFor("3").exists())
    }

    @Test fun an_unreadable_pick_reports_failure() {
        store.stage("3") { "nope".byteInputStream() }
        assertEquals(PictureOutcome.Staged("3", ok = false), awaitOutcome())
    }
}
