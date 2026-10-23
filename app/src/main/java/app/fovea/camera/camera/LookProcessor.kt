package app.fovea.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import app.fovea.camera.state.Look
import app.fovea.camera.state.LookMath
import java.io.IOException
import kotlin.math.hypot

/** Saves a color-graded COPY of a photo next to the original. The original is never modified. */
object LookProcessor {
    // Keeps memory use safe on phones. Larger photos are scaled down for the look copy only.
    private const val MAX_PIXELS = 8_000_000L

    fun createLookCopy(context: Context, source: Uri, look: Look): Uri? {
        val cr = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = cr.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = cr.openInputStream(source)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (degrees != 0f) {
            val m = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else decoded

        val w = upright.width
        val h = upright.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(LookMath.matrix(look)))
        }
        canvas.drawBitmap(upright, 0f, 0f, paint)
        upright.recycle()

        if (look.vignette > 0f) {
            val radius = hypot(w / 2.0, h / 2.0).toFloat()
            val edge = Color.argb((look.vignette * 255).toInt().coerceIn(0, 255), 0, 0, 0)
            val shader = RadialGradient(
                w / 2f, h / 2f, radius, intArrayOf(0, 0, edge), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        }

        val baseName = displayName(context, source)?.substringBeforeLast('.')
            ?: "FOVEA_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${baseName}_${look.short}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Fovea")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { out.recycle(); return null }
        try {
            val written = cr.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ?: throw IOException("could not open output")
            if (!written) throw IOException("could not write JPEG")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Throwable) {
            // Includes out-of-memory: never leave a half-written (pending) file behind.
            try { cr.delete(uri, null, null) } catch (ignored: Exception) { }
            out.recycle()
            return null
        }
        out.recycle()
        return uri
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }
}
