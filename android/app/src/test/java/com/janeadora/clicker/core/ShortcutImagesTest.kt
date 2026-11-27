package com.janeadora.clicker.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// SDK 35 is the newest Robolectric 4.18 runs on Java 17 (36 and 37 need Java 21).
// Real graphics, so BitmapFactory and WEBP encoding behave as on a phone.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutImagesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private lateinit var images: ShortcutImages

    @Before fun setUp() {
        dir = File(tmp.root, "shortcut-icons")
        images = ShortcutImages(dir)
    }

    @Test fun target_size_caps_long_side() = assertEquals(512 to 384, targetSize(4000, 3000))

    @Test fun target_size_caps_a_portrait_long_side() = assertEquals(256 to 512, targetSize(1000, 2000))

    @Test fun small_images_are_not_enlarged() = assertEquals(200 to 100, targetSize(200, 100))

    @Test fun sample_size_for_huge_photo() = assertEquals(8, sampleSizeFor(8000, 5000)) // 40 MP

    @Test fun sample_size_keeps_the_long_side_at_least_max() {
        assertEquals(8, sampleSizeFor(6000, 4000))
        assertEquals(2, sampleSizeFor(2000, 1000))
        assertEquals(1, sampleSizeFor(300, 200))
    }

    // Pins the long-side rule: under the old short-side rule this was 1, a full 18 MP decode.
    @Test fun sample_size_for_a_panorama_follows_its_long_side() = assertEquals(32, sampleSizeFor(20000, 900))

    @Test fun concurrent_saves_to_one_slot_end_with_one_valid_image() {
        val a = png(1200, 600)
        val b = png(600, 1200)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = List(8) { i ->
            Thread { results += images.save("3") { (if (i % 2 == 0) a else b).inputStream() } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(List(8) { true }, results.toList())
        assertEquals(listOf("3.webp"), dir.list()!!.toList())
        assertTrue(bounds(images.fileFor("3")) in setOf(512 to 256, 256 to 512))
    }

    @Test fun file_is_named_by_slot() = assertEquals(File(dir, "3.webp"), images.fileFor("3"))

    @Test fun save_writes_scaled_webp() {
        assertTrue(images.save("3") { png(2000, 1000).inputStream() })
        assertTrue(images.has("3"))
        assertEquals(512 to 256, bounds(images.fileFor("3")))
        assertEquals("image/webp", mime(images.fileFor("3")))
    }

    @Test fun exif_rotation_is_applied() {
        // Left half red, right half blue, tagged to show turned 90 degrees clockwise.
        val jpeg = tmp.newFile("turned.jpg")
        jpeg.writeBytes(halves(400, 200, Bitmap.CompressFormat.JPEG))
        ExifInterface(jpeg.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertTrue(images.save("3") { jpeg.inputStream() })
        assertEquals(200 to 400, bounds(images.fileFor("3")))
        // Turned clockwise, the left half of the source is now the top.
        val out = BitmapFactory.decodeFile(images.fileFor("3").absolutePath)
        assertTrue(isRed(out.getPixel(100, 50)))
        assertTrue(isBlue(out.getPixel(100, 350)))
    }

    @Test fun animated_image_stores_its_first_frame() {
        assertTrue(images.save("3") { Base64.getDecoder().decode(TWO_FRAME_GIF).inputStream() })
        val out = BitmapFactory.decodeFile(images.fileFor("3").absolutePath)
        assertEquals(20 to 10, out.width to out.height)
        assertTrue(isRed(out.getPixel(10, 5)))
    }

    @Test fun failed_decode_keeps_existing_image() {
        assertTrue(images.save("3") { png(600, 600).inputStream() })
        val before = images.fileFor("3").readBytes()
        assertFalse(images.save("3") { "not an image".toByteArray().inputStream() })
        assertArrayEquals(before, images.fileFor("3").readBytes())
    }

    @Test fun stream_failing_midway_keeps_existing_image_byte_identical() {
        assertTrue(images.save("3") { png(600, 600).inputStream() })
        val before = images.fileFor("3").readBytes()
        val big = png(2000, 1000)
        assertFalse(images.save("3") { FailingStream(big, failAfter = big.size / 2) })
        assertArrayEquals(before, images.fileFor("3").readBytes())
        assertEquals(listOf("3.webp"), dir.list()!!.toList())
    }

    @Test fun stream_that_throws_returns_false() {
        assertFalse(images.save("3") { throw FileNotFoundException() })
        assertFalse(images.has("3"))
        assertFalse(images.fileFor("3").exists())
    }

    @Test fun remove_deletes_file() {
        assertTrue(images.save("3") { png(100, 100).inputStream() })
        images.remove("3")
        assertFalse(images.has("3"))
    }

    @Test fun version_is_zero_without_an_image_and_set_with_one() {
        assertEquals(0L, images.version("3"))
        assertTrue(images.save("3") { png(100, 100).inputStream() })
        assertTrue(images.version("3") > 0L)
    }

    @Test fun staged_image_is_not_the_tile_image_until_committed() {
        assertTrue(images.save("3") { png(100, 100).inputStream() })
        val before = images.fileFor("3").readBytes()
        val staged = images.stage("3") { png(300, 100).inputStream() }
        assertNotNull(staged)
        assertEquals(300 to 100, bounds(staged!!))
        assertArrayEquals(before, images.fileFor("3").readBytes())
        assertTrue(images.commit("3"))
        assertEquals(300 to 100, bounds(images.fileFor("3")))
        assertFalse(images.pendingFor("3").exists())
    }

    @Test fun discarded_stage_leaves_no_file() {
        images.stage("3") { png(300, 100).inputStream() }
        images.discard("3")
        assertFalse(images.pendingFor("3").exists())
        assertFalse(images.has("3"))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun failed_stage_returns_null_and_leaves_nothing() {
        assertNull(images.stage("3") { "nope".toByteArray().inputStream() })
        assertFalse(images.pendingFor("3").exists())
        assertFalse(images.commit("3"))
    }

    @Test fun failed_stage_keeps_the_earlier_pending_image() {
        images.stage("3") { png(300, 100).inputStream() }
        val before = images.pendingFor("3").readBytes()
        assertNull(images.stage("3") { throw FileNotFoundException() })
        assertArrayEquals(before, images.pendingFor("3").readBytes())
    }

    @Test fun discard_all_clears_left_over_pending_files() {
        images.stage("3") { png(50, 50).inputStream() }
        images.stage("0") { png(50, 50).inputStream() }
        assertTrue(images.save("4") { png(50, 50).inputStream() })
        images.discardAll()
        assertEquals(listOf("4.webp"), dir.list()!!.toList())
    }

    @Test fun decode_returns_the_stored_image_or_null() {
        assertNull(decodeIcon(images.fileFor("3")))
        images.fileFor("3").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(decodeIcon(images.fileFor("3")))
        assertTrue(images.save("3") { png(80, 40).inputStream() })
        assertEquals(80, decodeIcon(images.fileFor("3"))!!.width)
    }

    private fun png(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(0x6F, 0x3A, 0x9A))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun halves(w: Int, h: Int, format: Bitmap.CompressFormat): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = Color.RED })
        canvas.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.BLUE })
        return ByteArrayOutputStream().also { bmp.compress(format, 95, it) }.toByteArray()
    }

    private fun bounds(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    private fun mime(f: File): String? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outMimeType
    }

    private fun isRed(p: Int) = Color.red(p) > 200 && Color.blue(p) < 60
    private fun isBlue(p: Int) = Color.blue(p) > 200 && Color.red(p) < 60

    // Hands out [bytes] and throws once [failAfter] of them have been read.
    private class FailingStream(private val bytes: ByteArray, private val failAfter: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos >= failAfter) throw IOException("gone")
            return bytes[pos++].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= failAfter) throw IOException("gone")
            val n = minOf(len, failAfter - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }

    private companion object {
        // A 20 x 10 GIF: a red frame, then a blue one.
        const val TWO_FRAME_GIF =
            "R0lGODlhFAAKAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAFAAKAAAIGQABCBxIsKDBgwgTKlzIsKHDhxAjSpyoMCAAIfkEAQoAAQAsAAAAABQACgCBAAD/AAAAAAAAAAAACBkAAQgcSLCgwYMIEypcyLChw4cQI0qcqDAgADs="
    }
}
