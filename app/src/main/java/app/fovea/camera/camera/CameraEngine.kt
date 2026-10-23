package app.fovea.camera.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.fovea.camera.state.AspectOption
import app.fovea.camera.state.AwbOption
import app.fovea.camera.state.FlashMode
import app.fovea.camera.state.Look
import app.fovea.camera.state.VideoQuality
import android.os.Environment
import android.os.StatFs
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * The only class that talks to the camera. The UI reads its StateFlows and calls its functions.
 * Photo/video are saved with MediaStore (no storage permission needed).
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(private val context: Context) {
    private val MIN_FREE_BYTES = 50L * 1024 * 1024
    private val VIDEO_RESERVE_BYTES = 30L * 1024 * 1024
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val lookExecutor = Executors.newSingleThreadExecutor()
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lastHistogramAt = 0L

    val caps = MutableStateFlow(DeviceCaps())
    val zoomRatio = MutableStateFlow(1f)
    val histogram = MutableStateFlow<IntArray?>(null)
    val bindCount = MutableStateFlow(0)
    val lastSaved = MutableStateFlow<Uri?>(null)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val volumeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile var cameraScreenVisible = false

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { c ->
        addListener({
            try { c.resume(get()) } catch (e: Exception) { c.resumeWithException(e) }
        }, mainExecutor)
    }

    /** Must be called from the main thread (LaunchedEffect does this). */
    suspend fun bind(owner: LifecycleOwner, view: PreviewView, cfg: BindConfig) {
        try {
            val p = provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }
            recording?.stop()
            recording = null
            p.unbindAll()

            val front = if (cfg.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val strategy = if (cfg.aspect == AspectOption.R169 || cfg.video)
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val selector = ResolutionSelector.Builder().setAspectRatioStrategy(strategy).build()

            val preview = Preview.Builder().setResolutionSelector(selector).build()
            preview.setSurfaceProvider(view.surfaceProvider)
            val useCases = mutableListOf<UseCase>(preview)

            if (cfg.video) {
                val wanted = when (cfg.videoQuality) {
                    VideoQuality.UHD -> Quality.UHD
                    VideoQuality.FHD -> Quality.FHD
                    VideoQuality.HD -> Quality.HD
                    VideoQuality.SD -> Quality.SD
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(wanted, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    ).build()
                val vc = VideoCapture.withOutput(recorder)
                videoCapture = vc
                imageCapture = null
                useCases += vc
            } else {
                val ic = ImageCapture.Builder()
                    .setResolutionSelector(selector)
                    .setCaptureMode(
                        if (cfg.fastCapture) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    )
                    .setJpegQuality(cfg.jpegQuality)
                    .setFlashMode(flashConst(cfg.flash))
                    .build()
                imageCapture = ic
                videoCapture = null
                useCases += ic
                if (cfg.histogram) {
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { img -> analyze(img) }
                    useCases += analysis
                } else histogram.value = null
            }

            val cam = p.bindToLifecycle(owner, front, *useCases.toTypedArray())
            camera = cam
            zoomRatio.value = 1f
            caps.value = readCaps(cam, p)
            bindCount.value = bindCount.value + 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            events.tryEmit("Camera could not start: ${e.message ?: "unknown error"}")
        }
    }

    /** Called when the camera screen leaves the screen: stops any recording and releases the camera. */
    fun unbind() {
        try {
            recording?.stop()
            recording = null
            provider?.unbindAll()
        } catch (e: Exception) {
            // Releasing must never crash the app.
        }
        camera = null
        imageCapture = null
        videoCapture = null
        histogram.value = null
    }

    private fun readCaps(cam: Camera, p: ProcessCameraProvider): DeviceCaps {
        val info = cam.cameraInfo
        val c2 = Camera2CameraInfo.from(info)
        val zs = info.zoomState.value
        val ev = info.exposureState
        val step = try { ev.exposureCompensationStep.toFloat() } catch (e: Exception) { 0f }
        val range = ev.exposureCompensationRange
        val iso = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val awbRaw = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        val capList = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val level = c2.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

        val awb = mutableSetOf(AwbOption.AUTO)
        awbRaw?.forEach { m ->
            when (m) {
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> awb += AwbOption.DAYLIGHT
                CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> awb += AwbOption.CLOUDY
                CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> awb += AwbOption.TUNGSTEN
                CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> awb += AwbOption.FLUORESCENT
            }
        }
        val manualSensor = capList?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val raw = capList?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        val levelName = when (level) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
            else -> "Unknown"
        }
        val hasFront = try { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) } catch (e: Exception) { false }
        val hasBack = try { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) } catch (e: Exception) { false }

        return DeviceCaps(
            ready = true,
            hasFlash = info.hasFlashUnit(),
            minZoom = zs?.minZoomRatio ?: 1f,
            maxZoom = zs?.maxZoomRatio ?: 1f,
            evMin = range.lower * step,
            evMax = range.upper * step,
            evStep = step,
            manualSensor = manualSensor,
            isoMin = iso?.lower ?: 0,
            isoMax = iso?.upper ?: 0,
            shutterMinNs = exp?.lower ?: 0L,
            shutterMaxNs = exp?.upper ?: 0L,
            manualFocus = minFocus > 0f,
            minFocusDiopters = minFocus,
            awbModes = awb,
            rawCapable = raw,
            hardwareLevel = levelName,
            hasFront = hasFront,
            hasBack = hasBack
        )
    }

    private fun flashConst(f: FlashMode) = when (f) {
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
    }

    fun setFlash(f: FlashMode, video: Boolean) {
        imageCapture?.flashMode = flashConst(f)
        // In video mode "ON" means torch.
        camera?.cameraControl?.enableTorch(video && f == FlashMode.ON)
    }

    fun setZoom(ratio: Float) {
        val c = caps.value
        val z = ratio.coerceIn(c.minZoom, c.maxZoom)
        camera?.cameraControl?.setZoomRatio(z)
        zoomRatio.value = z
    }

    fun focusAt(view: PreviewView, x: Float, y: Float) {
        val point = view.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    fun setEv(stops: Float) {
        val c = caps.value
        if (!c.evSupported) return
        val lo = (c.evMin / c.evStep).roundToInt()
        val hi = (c.evMax / c.evStep).roundToInt()
        camera?.cameraControl?.setExposureCompensationIndex((stops / c.evStep).roundToInt().coerceIn(lo, hi))
    }

    fun applyManual(m: ManualSettings) {
        val control = camera?.cameraControl?.let { Camera2CameraControl.from(it) } ?: return
        val c = caps.value
        val b = CaptureRequestOptions.Builder()
        if (m.exposureManual && c.isoSupported) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, m.iso.coerceIn(c.isoMin, c.isoMax))
            b.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME, m.shutterNs.coerceIn(c.shutterMinNs, c.shutterMaxNs)
            )
        }
        if (m.focusManual && c.manualFocus) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, m.focusDiopters.coerceIn(0f, c.minFocusDiopters))
        }
        awbConst(m.awb)?.let { mode ->
            if (m.awb in c.awbModes) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, mode)
        }
        control.setCaptureRequestOptions(b.build())
    }

    fun clearManual() {
        val control = camera?.cameraControl?.let { Camera2CameraControl.from(it) } ?: return
        control.clearCaptureRequestOptions()
    }

    private fun awbConst(a: AwbOption): Int? = when (a) {
        AwbOption.AUTO -> null
        AwbOption.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        AwbOption.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        AwbOption.TUNGSTEN -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        AwbOption.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    }

    private fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    } catch (e: Exception) { Long.MAX_VALUE }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun takePhoto(playSound: Boolean, look: Look, watermark: String?, onDone: (Boolean) -> Unit) {
        val ic = imageCapture
        if (ic == null) { events.tryEmit("Camera is not ready yet."); onDone(false); return }
        if (freeBytes() < MIN_FREE_BYTES) { events.tryEmit("Storage is almost full. Free some space to take photos."); onDone(false); return }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FOVEA_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Fovea")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        if (playSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        ic.takePicture(opts, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                val src = r.savedUri
                lastSaved.value = src
                onDone(true)
                if ((look != Look.OFF || watermark != null) && src != null) {
                    val what = when {
                        look != Look.OFF && watermark != null -> "${look.label} + watermark"
                        look != Look.OFF -> look.label
                        else -> "Watermark"
                    }
                    try {
                        lookExecutor.execute {
                            try {
                                val copy = LookProcessor.createLookCopy(context, src, look, watermark)
                                if (copy != null) {
                                    lastSaved.value = copy
                                    events.tryEmit("Saved original + $what copy")
                                } else events.tryEmit("$what could not be applied. Original is saved.")
                            } catch (t: Throwable) {
                                events.tryEmit("$what failed (${t.javaClass.simpleName}). Original is saved.")
                            }
                        }
                    } catch (e: RejectedExecutionException) {
                        // App is closing; the original photo is already saved.
                    }
                }
            }
            override fun onError(e: ImageCaptureException) {
                events.tryEmit("Photo failed: ${e.message}")
                onDone(false)
            }
        })
    }

    /** Takes one photo and waits until it is saved. Returns false if it failed (message already posted). */
    suspend fun takePhotoAwait(playSound: Boolean, look: Look, watermark: String?): Boolean =
        suspendCancellableCoroutine { cont ->
            takePhoto(playSound, look, watermark) { ok -> if (cont.isActive) cont.resume(ok) }
        }

    fun toggleRecording(withAudio: Boolean, onState: (Boolean) -> Unit) {
        val vc = videoCapture
        if (vc == null) { events.tryEmit("Video is not ready yet."); return }
        val current = recording
        if (current != null) { current.stop(); return }
        if (freeBytes() < MIN_FREE_BYTES * 4) { events.tryEmit("Storage is almost full. Free some space to record video."); return }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "FOVEA_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Fovea")
        }
        val outBuilder = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
        // Stop cleanly before the storage is completely full, so the video is finalized and playable.
        val free = freeBytes()
        if (free != Long.MAX_VALUE) outBuilder.setFileSizeLimit((free - VIDEO_RESERVE_BYTES).coerceAtLeast(MIN_FREE_BYTES))
        val out = outBuilder.build()
        var pending = vc.output.prepareRecording(context, out)
        if (withAudio) pending = pending.withAudioEnabled()
        recording = pending.start(mainExecutor) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> onState(true)
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    onState(false)
                    if (!ev.hasError()) {
                        lastSaved.value = ev.outputResults.outputUri
                    } else {
                        // These stops are not failures of the file: what was recorded so far is kept.
                        val kept = when (ev.error) {
                            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
                            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
                            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> true
                            else -> false
                        }
                        if (kept) lastSaved.value = ev.outputResults.outputUri
                        events.tryEmit(videoErrorText(ev.error))
                    }
                }
                else -> Unit
            }
        }
    }

    private fun videoErrorText(code: Int): String = when (code) {
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ->
            "Recording stopped because free space is running low. Check Recent captures for the video."
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
            "Recording stopped because storage is full. Check Recent captures for the video."
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
            "Recording was interrupted (camera closed). Check Recent captures for the video."
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "Recording was too short, so nothing was saved."
        else -> "Video could not be saved (error $code)."
    }

    private fun analyze(img: androidx.camera.core.ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastHistogramAt >= 200) {
                lastHistogramAt = now
                val plane = img.planes[0]
                val buf = plane.buffer
                val rs = plane.rowStride
                val ps = plane.pixelStride
                val bins = IntArray(64)
                var y = 0
                while (y < img.height) {
                    var x = 0
                    while (x < img.width) {
                        val i = y * rs + x * ps
                        if (i < buf.limit()) bins[(buf.get(i).toInt() and 0xFF) shr 2]++
                        x += 8
                    }
                    y += 8
                }
                histogram.value = bins
            }
        } finally {
            img.close()
        }
    }

    fun release() {
        recording?.stop()
        provider?.unbindAll()
        sound.release()
        analysisExecutor.shutdown()
        lookExecutor.shutdown()
    }
}
