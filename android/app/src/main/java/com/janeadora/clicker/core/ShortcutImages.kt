package com.janeadora.clicker.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** The long side of a stored shortcut image, in pixels. */
const val ICON_MAX = 512

private const val WEBP_QUALITY = 90
private const val PENDING = ".pending"

/**
 * Shortcut pictures, one small WEBP per slot in [dir] (filesDir/shortcut-icons).
 * Every method touches files: call them off the main thread.
 *
 * The editor stages a chosen picture with [stage]; it only becomes the
 * slot's picture with [commit], and [discard] drops it. Writes go to a temp
 * file in [dir] and are renamed into place, so a failed write never harms
 * the picture already there. Writes, commits, discards and removals run
 * one at a time, so two of them on one slot never share a temp file.
 */
class ShortcutImages(private val dir: File) {
    private val lock = Any()

    fun fileFor(slot: String): File = File(dir, "$slot.webp")

    /** Where the editor's chosen, not yet saved, picture for [slot] lives. */
    fun pendingFor(slot: String): File = File(dir, "$slot$PENDING.webp")

    fun has(slot: String): Boolean = fileFor(slot).isFile

    /**
     * Decodes with downsampling, scales so the long side is at most 512px,
     * writes WEBP q90. Returns false and leaves any existing image untouched
     * on failure.
     */
    fun save(slot: String, open: () -> InputStream): Boolean = synchronized(lock) { write(fileFor(slot), open) }

    /** Like [save], into the pending file. Returns it, or null on failure, which leaves any earlier pending file as it was. */
    fun stage(slot: String, open: () -> InputStream): File? {
        val pending = pendingFor(slot)
        return synchronized(lock) { if (write(pending, open)) pending else null }
    }

    /** Makes the staged picture the slot's picture. False when nothing is staged or the move fails. */
    fun commit(slot: String): Boolean = synchronized(lock) {
        val pending = pendingFor(slot)
        if (!pending.isFile) return false
        val target = fileFor(slot)
        if (!pending.renameTo(target)) return false
        // A fresh time, so the picture's cache key changes even for a quick second edit.
        target.setLastModified(System.currentTimeMillis())
        return true
    }

    fun discard(slot: String) {
        synchronized(lock) { pendingFor(slot).delete() }
    }

    /** Drops every staged picture and stray temp file, such as those left by a killed process. */
    fun discardAll() = synchronized(lock) {
        dir.listFiles()?.filter { it.name.contains(PENDING) || it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun remove(slot: String) {
        synchronized(lock) { fileFor(slot).delete() }
    }

    /** The picture's last change (0 with none), used as an image cache key. */
    fun version(slot: String): Long = fileFor(slot).lastModified()

    // Copies the source in whole first: a stream that fails part way then
    // fails the save, instead of decoding to a half-grey picture.
    private fun write(target: File, open: () -> InputStream): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val source = File(dir, "${target.name}.src.tmp")
        val tmp = File(dir, "${target.name}.tmp")
        var bitmap: Bitmap? = null
        return try {
            open().use { input -> source.outputStream().use { input.copyTo(it) } }
            // A pathological image can still exhaust memory; that fails the save, not the app.
            bitmap = try {
                readScaled(source)
            } catch (e: OutOfMemoryError) {
                null
            } ?: return false
            val ok = tmp.outputStream().use { bitmap.compress(webp(), WEBP_QUALITY, it) }
            ok && tmp.renameTo(target)
        } catch (e: Exception) {
            false
        } finally {
            source.delete()
            tmp.delete()
            bitmap?.recycle()
        }
    }
}

/** [w] x [h] scaled so the long side is at most [max]. Smaller images keep their size. */
fun targetSize(w: Int, h: Int, max: Int = ICON_MAX): Pair<Int, Int> {
    val long = max(w, h)
    if (long <= max) return w to h
    val scale = max.toFloat() / long
    return max((w * scale).roundToInt(), 1) to max((h * scale).roundToInt(), 1)
}

/**
 * The largest power of two that keeps the long side at least [max] after
 * sampling, so the scale down to [max] loses nothing. 1 for small images.
 */
fun sampleSizeFor(w: Int, h: Int, max: Int = ICON_MAX): Int {
    var sample = 1
    while (max(w, h) / (sample * 2) >= max) sample *= 2
    return sample
}

/** The stored picture in [file], or null when it is missing or does not decode. */
fun decodeIcon(file: File): Bitmap? = if (!file.isFile) null else try {
    BitmapFactory.decodeFile(file.absolutePath)
} catch (e: Exception) {
    null
}

// Reads the file three times (bounds, orientation, pixels), so a huge
// photo is never decoded at full size and a portrait photo stands upright.
// An animated image decodes to its first frame.
private fun readScaled(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = try {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
    val sampled = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    return fit(sampled, orientation)
}

// Scales [src] so its long side is at most ICON_MAX and turns it upright.
private fun fit(src: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    val (w, h) = targetSize(src.width, src.height)
    m.postScale(w.toFloat() / src.width, h.toFloat() / src.height)
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(-90f)
    }
    if (m.isIdentity) return src
    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    if (out !== src) src.recycle()
    return out
}

@Suppress("DEPRECATION")
private fun webp(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
