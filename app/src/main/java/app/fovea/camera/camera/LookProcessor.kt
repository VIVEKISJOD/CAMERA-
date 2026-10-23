package app.fovea.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import app.fovea.camera.state.Look
import app.fovea.camera.state.LookMath
import app.fovea.camera.state.PhotoEffect
import java.io.IOException
import kotlin.math.hypot

/** Saves a color-graded COPY of a photo next to the original. The original is never modified. */
object LookProcessor {
    // Keeps memory use safe on phones. Larger photos are scaled down for the look copy only.
    private const val MAX_PIXELS = 8_000_000L

    fun createLookCopy(
        context: Context, source: Uri, look: Look, watermark: String? = null, effect: PhotoEffect = PhotoEffect.NONE
    ): Uri? {
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
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(if (effect == PhotoEffect.DOCUMENT) documentMatrix() else LookMath.matrix(look))
            )
        }
        canvas.drawBitmap(upright, 0f, 0f, paint)
        upright.recycle()

        if (effect == PhotoEffect.TILT_SHIFT) applyTiltShift(out, canvas)

        if (look.vignette > 0f && effect != PhotoEffect.DOCUMENT) {
            val radius = hypot(w / 2.0, h / 2.0).toFloat()
            val edge = Color.argb((look.vignette * 255).toInt().coerceIn(0, 255), 0, 0, 0)
            val shader = RadialGradient(
                w / 2f, h / 2f, radius, intArrayOf(0, 0, edge), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        }

        if (!watermark.isNullOrBlank()) drawWatermark(canvas, w, h, watermark)

        val suffix = listOfNotNull(
            if (effect != PhotoEffect.NONE) effect.short else null,
            if (look != Look.OFF && effect != PhotoEffect.DOCUMENT) look.short else null,
            if (!watermark.isNullOrBlank()) "WM" else null
        ).joinToString("_")
        val baseName = displayName(context, source)?.substringBeforeLast('.')
            ?: "FOVEA_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${baseName}_$suffix")
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

    /** Grayscale, strong contrast and a brighter background: makes paper look clean. Enhance only, no cropping. */
    private fun documentMatrix(): FloatArray {
        val c = 1.7f
        val offset = (-0.5f * c + 0.5f) * 255f + 28f
        val r = 0.299f * c
        val g = 0.587f * c
        val b = 0.114f * c
        return floatArrayOf(
            r, g, b, 0f, offset,
            r, g, b, 0f, offset,
            r, g, b, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /**
     * Miniature look: the top and bottom of the photo are blurred, the middle band stays sharp.
     * The blur is made by shrinking and enlarging the photo (works on every phone, no special libraries).
     */
    private fun applyTiltShift(img: Bitmap, canvas: Canvas) {
        val w = img.width
        val h = img.height
        val small = Bitmap.createScaledBitmap(img, (w / 12).coerceAtLeast(2), (h / 12).coerceAtLeast(2), true)
        val blurred = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== blurred) small.recycle()
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawBitmap(blurred, 0f, 0f, null)
        blurred.recycle()
        val mask = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(Color.BLACK, Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0f, 0.34f, 0.66f, 1f), Shader.TileMode.CLAMP
            )
        }
        lc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
        canvas.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
    }

    /** Text in the bottom-right corner, white with a soft dark shadow, sized from the photo. */
    private fun drawWatermark(canvas: Canvas, w: Int, h: Int, text: String) {
        val base = minOf(w, h).toFloat()
        val size = base * 0.035f
        val margin = base * 0.03f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            textAlign = Paint.Align.RIGHT
            setShadowLayer(size * 0.15f, 0f, 0f, Color.argb(200, 0, 0, 0))
        }
        canvas.drawText(text, w - margin, h - margin, paint)
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }
}
