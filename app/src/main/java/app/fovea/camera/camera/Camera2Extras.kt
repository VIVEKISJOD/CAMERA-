package app.fovea.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Features CameraX cannot do: RAW (DNG) capture and high-speed (slow motion) video.
 * They talk to the camera directly (Camera2), so CameraEngine releases CameraX first and rebinds it afterwards.
 * Written without a real phone to test on: every failure is turned into a readable message, never a crash.
 */
class Camera2Extras(private val context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("fovea-camera2").also { it.start() }
    private val handler = Handler(thread.looper)

    private class SlowMo(
        val device: CameraDevice,
        val session: CameraCaptureSession,
        val recorder: MediaRecorder,
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
        val fps: Int
    )

    private var slow: SlowMo? = null
    val slowActive: Boolean get() = slow != null

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    // ---------------------------------------------------------------- helpers

    @SuppressLint("MissingPermission")
    private suspend fun openOnce(id: String): CameraDevice = suspendCancellableCoroutine { c ->
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { if (c.isActive) c.resume(d) else d.close() }
            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (c.isActive) c.resumeWithException(IOException("The camera was disconnected."))
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                if (c.isActive) c.resumeWithException(IOException("The camera is busy or not available (code $error)."))
            }
        }, handler)
    }

    /** CameraX releases the camera a moment after unbind, so opening is retried a few times. */
    private suspend fun open(id: String): CameraDevice {
        var last: Exception? = null
        for (attempt in 0 until 5) {
            try {
                return openOnce(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(350)
            }
        }
        throw last ?: IOException("The camera is busy.")
    }

    private suspend fun createSession(device: CameraDevice, surfaces: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { c ->
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (c.isActive) c.resume(s) else s.close() }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (c.isActive) c.resumeWithException(IOException("The camera could not start in this mode."))
                }
            }, handler)
        }

    private fun exifOrientation(sensorOrientation: Int): Int = when (sensorOrientation) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    // ---------------------------------------------------------------- RAW / DNG

    /** Takes one RAW photo and saves it as a .dng in Pictures/Fovea. Throws IOException with a readable text. */
    suspend fun captureDng(cameraId: String, sensorOrientation: Int): Uri {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IOException("This camera does not report its sizes.")
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IOException("This camera has no RAW output.")
        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
        val texture = SurfaceTexture(0)
        texture.setDefaultBufferSize(640, 480)
        val previewSurface = Surface(texture)
        val imageDef = CompletableDeferred<Image>()
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireNextImage() } catch (e: Exception) { null }
            if (img != null && !imageDef.complete(img)) img.close()
        }, handler)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            device = open(cameraId)
            session = createSession(device, listOf(previewSurface, reader.surface))
            // A short preview run lets exposure and focus settle before the RAW shot.
            val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            preview.addTarget(previewSurface)
            session.setRepeatingRequest(preview.build(), null, handler)
            delay(900)
            val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            still.addTarget(reader.surface)
            val resultDef = CompletableDeferred<TotalCaptureResult>()
            session.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    resultDef.complete(result)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    resultDef.completeExceptionally(IOException("The camera could not take the RAW photo."))
                }
            }, handler)
            val result = withTimeout(10_000) { resultDef.await() }
            val image = withTimeout(10_000) { imageDef.await() }
            try {
                return writeDng(chars, result, image, sensorOrientation)
            } finally {
                image.close()
            }
        } finally {
            try { session?.close() } catch (e: Exception) { }
            try { device?.close() } catch (e: Exception) { }
            try { reader.close() } catch (e: Exception) { }
            try { previewSurface.release() } catch (e: Exception) { }
            try { texture.release() } catch (e: Exception) { }
        }
    }

    private fun writeDng(chars: CameraCharacteristics, result: TotalCaptureResult, image: Image, sensorOrientation: Int): Uri {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FOVEA_${stamp()}.dng")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Fovea")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the RAW file.")
        try {
            val dng = DngCreator(chars, result)
            try {
                dng.setOrientation(exifOrientation(sensorOrientation))
                val stream = cr.openOutputStream(uri) ?: throw IOException("Could not open the RAW file.")
                stream.use { dng.writeImage(it, image) }
            } finally {
                dng.close()
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Throwable) {
            try { cr.delete(uri, null, null) } catch (ignored: Exception) { }
            throw e
        }
        return uri
    }

    // ---------------------------------------------------------------- Slow motion (high-speed video)

    /** Starts high-speed recording. Returns the frame rate used. Throws IOException with a readable text. */
    suspend fun startSlowMo(cameraId: String, sensorOrientation: Int): Int {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IOException("This camera does not report its sizes.")
        var bestSize: Size? = null
        var bestRange: Range<Int>? = null
        val sizes: Array<Size> = map.highSpeedVideoSizes ?: emptyArray()
        for (sz in sizes) {
            if (sz.width > 1920 || sz.height > 1920) continue
            val all = map.getHighSpeedVideoFpsRangesFor(sz)
            val fixed = all.filter { it.lower == it.upper }
            val usable = if (fixed.isEmpty()) all.toList() else fixed
            for (r in usable) {
                val curSize = bestSize
                val curRange = bestRange
                val area = sz.width.toLong() * sz.height
                val better = curSize == null || curRange == null || r.upper > curRange.upper ||
                    (r.upper == curRange.upper && area > curSize.width.toLong() * curSize.height)
                if (better) { bestSize = sz; bestRange = r }
            }
        }
        val size = bestSize
        val range = bestRange
        if (size == null || range == null || range.upper < 60) {
            throw IOException("This camera does not offer high-speed video to apps.")
        }
        val fps = range.upper

        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FOVEA_${stamp()}_SLOWMO")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Fovea")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the video file.")
        val pfd = try { cr.openFileDescriptor(uri, "w") } catch (e: Exception) { null }
        if (pfd == null) {
            try { cr.delete(uri, null, null) } catch (ignored: Exception) { }
            throw IOException("Could not open the video file.")
        }
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(pfd.fileDescriptor)
            recorder.setVideoEncodingBitRate(24_000_000)
            recorder.setVideoFrameRate(fps)
            recorder.setVideoSize(size.width, size.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setOrientationHint(sensorOrientation)
            recorder.prepare()

            device = open(cameraId)
            val dev = device
            val surface = recorder.surface
            val hs: CameraCaptureSession = suspendCancellableCoroutine { c ->
                dev.createConstrainedHighSpeedCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { if (c.isActive) c.resume(s) else s.close() }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (c.isActive) c.resumeWithException(IOException("High-speed video could not start."))
                    }
                }, handler)
            }
            session = hs
            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(surface)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            val burst = (hs as CameraConstrainedHighSpeedCaptureSession).createHighSpeedRequestList(builder.build())
            hs.setRepeatingBurst(burst, null, handler)
            recorder.start()
            slow = SlowMo(dev, hs, recorder, uri, pfd, fps)
            return fps
        } catch (e: Throwable) {
            try { session?.close() } catch (x: Exception) { }
            try { device?.close() } catch (x: Exception) { }
            try { recorder.reset() } catch (x: Exception) { }
            try { recorder.release() } catch (x: Exception) { }
            try { pfd.close() } catch (x: Exception) { }
            try { cr.delete(uri, null, null) } catch (x: Exception) { }
            if (e is IOException || e is CancellationException) throw e
            throw IOException("High-speed video could not start (${e.javaClass.simpleName}).")
        }
    }

    /** Stops slow motion and saves the file. Safe to call when nothing is running. Returns the saved video or null. */
    fun stopSlowMo(): Uri? {
        val s = slow ?: return null
        slow = null
        var ok = true
        try { s.session.stopRepeating() } catch (e: Exception) { }
        try { s.session.abortCaptures() } catch (e: Exception) { }
        try { s.recorder.stop() } catch (e: RuntimeException) { ok = false }
        try { s.recorder.reset() } catch (e: Exception) { }
        try { s.recorder.release() } catch (e: Exception) { }
        try { s.session.close() } catch (e: Exception) { }
        try { s.device.close() } catch (e: Exception) { }
        try { s.pfd.close() } catch (e: Exception) { }
        val cr = context.contentResolver
        return try {
            if (ok) {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                cr.update(s.uri, v, null, null)
                s.uri
            } else {
                cr.delete(s.uri, null, null)
                null
            }
        } catch (e: Exception) { null }
    }

    fun release() {
        stopSlowMo()
        thread.quitSafely()
    }
}
