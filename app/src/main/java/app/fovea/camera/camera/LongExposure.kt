package app.fovea.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException

/** One captured JPEG frame kept in memory, with the rotation the camera asked for. */
class RawFrame(val jpeg: ByteArray, val rotationDegrees: Int)

/**
 * Long exposure by frame stacking: several photos are averaged, so moving water, clouds and light trails
 * turn smooth while still things stay sharp. This is NOT a real long shutter: keep the phone very steady.
 * Frames are shrunk to about 3 megapixels so memory stays safe on every phone.
 */
class LongExposureStack {
    private var width = 0
    private var height = 0
    private var sum: IntArray? = null
    private var pixels: IntArray? = null
    var frames = 0
        private set
    private var rotation = 0

    /** Returns false if this frame could not be used (it is skipped). */
    fun add(frame: RawFrame): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, opts) ?: return false
        try {
            if (sum == null) {
                width = bmp.width
                height = bmp.height
                sum = IntArray(width * height * 3)
                pixels = IntArray(width * height)
                rotation = frame.rotationDegrees
            }
            if (bmp.width != width || bmp.height != height) return false
            val px = pixels ?: return false
            val acc = sum ?: return false
            bmp.getPixels(px, 0, width, 0, 0, width, height)
            var j = 0
            for (i in px.indices) {
                val c = px[i]
                acc[j] += (c shr 16) and 0xFF
                acc[j + 1] += (c shr 8) and 0xFF
                acc[j + 2] += c and 0xFF
                j += 3
            }
            frames++
            return true
        } finally {
            bmp.recycle()
        }
    }

    /** The averaged picture, upright. Null if no frame was added. */
    fun result(): Bitmap? {
        val acc = sum ?: return null
        val px = pixels ?: return null
        if (frames == 0) return null
        var j = 0
        for (i in px.indices) {
            val r = acc[j] / frames
            val g = acc[j + 1] / frames
            val b = acc[j + 2] / frames
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            j += 3
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, width, 0, 0, width, height)
        if (rotation == 0) return out
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(out, 0, 0, width, height, m, true)
        if (rotated !== out) out.recycle()
        return rotated
    }

    fun clear() {
        sum = null
        pixels = null
        frames = 0
    }

    companion object {
        private const val MAX_PIXELS = 3_000_000L

        /** Saves the picture to Pictures/Fovea so it shows in the phone Gallery. Returns null on failure. */
        fun save(context: Context, bmp: Bitmap, name: String): Uri? {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Fovea")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                val ok = cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    ?: throw IOException("could not open output")
                if (!ok) throw IOException("could not write JPEG")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            } catch (e: Throwable) {
                try { cr.delete(uri, null, null) } catch (ignored: Exception) { }
                return null
            }
            return uri
        }
    }
}
