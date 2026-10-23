package app.fovea.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.Camera
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import app.fovea.camera.state.PhotoEffect
import app.fovea.camera.state.SpecialMode
import app.fovea.camera.state.VideoQuality
import android.os.Environment
import android.os.StatFs
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    private val lookExecutor = Executors.newSingleThreadExecutor()
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val extras = Camera2Extras(context)
    private var extensions: ExtensionsManager? = null
    private var dualBack: VideoCapture<Recorder>? = null
    private var dualFront: VideoCapture<Recorder>? = null
    private val dualRecordings = mutableListOf<Recording>()

    val caps = MutableStateFlow(DeviceCaps())
    val zoomRatio = MutableStateFlow(1f)
    val histogram = MutableStateFlow<IntArray?>(null)
    val bindCount = MutableStateFlow(0)
    val lastSaved = MutableStateFlow<Uri?>(null)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val volumeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Bumped when the screen must bind the camera again (after RAW or slow motion used the camera directly). */
    val rebind = MutableStateFlow(0)
    /** Emitted when a special mode could not start, so the screen goes back to the normal camera. */
    val specialFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val slowMoActive: Boolean get() = extras.slowActive

    // Exposure the owner asked for (EV slider) plus a small automatic reduction when highlights burn out.
    private var userEv = 0f
    private var guardEv = 0f
    private var guardLastChangeAt = 0L
    private var guardCalmCount = 0

    @Volatile var cameraScreenVisible = false

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { c ->
        addListener({
            try { c.resume(get()) } catch (e: Exception) { c.resumeWithException(e) }
        }, mainExecutor)
    }

    private suspend fun extensionsManager(p: ProcessCameraProvider): ExtensionsManager? {
        extensions?.let { return it }
        return try {
            ExtensionsManager.getInstanceAsync(context, p).await().also { extensions = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    /** Highest photo resolution CameraX can give. The high-resolution switch is looked up at run time, so an older CameraX cannot break the build. */
    private fun ultraSelector(base: AspectRatioStrategy): ResolutionSelector {
        val b = ResolutionSelector.Builder()
            .setAspectRatioStrategy(base)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        try {
            val method = ResolutionSelector.Builder::class.java.getMethod("setAllowedResolutionMode", Int::class.javaPrimitiveType)
            val flag = ResolutionSelector::class.java.getField("PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE").getInt(null)
            method.invoke(b, flag)
        } catch (e: Throwable) {
            // This CameraX build has no high-resolution mode: the highest normal resolution is used.
        }
        return b.build()
    }

    /** Must be called from the main thread (LaunchedEffect does this). */
    suspend fun bind(owner: LifecycleOwner, view: PreviewView, cfg: BindConfig) {
        try {
            val p = provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }
            recording?.stop()
            recording = null
            stopDual()
            p.unbindAll()

            val front = if (cfg.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val special = cfg.special
            // Slow motion shows a live preview only. The recording itself is made by Camera2Extras.
            val previewOnly = special == SpecialMode.SLOW_MOTION
            val strategy = if (cfg.aspect == AspectOption.R169 || cfg.video || previewOnly)
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val selector = ResolutionSelector.Builder().setAspectRatioStrategy(strategy).build()
            // Photos always ask for the biggest picture the phone can give in this shape (never a smaller default).
            val photoSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(strategy)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()

            val preview = Preview.Builder().setResolutionSelector(selector).build()
            preview.setSurfaceProvider(view.surfaceProvider)
            val useCases = mutableListOf<UseCase>(preview)

            val em = extensionsManager(p)
            var extMode = ExtensionMode.NONE

            if (previewOnly) {
                videoCapture = null
                imageCapture = null
                histogram.value = null
            } else if (cfg.video) {
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
                extMode = when (special) {
                    SpecialMode.HDR -> ExtensionMode.HDR
                    SpecialMode.NIGHT -> ExtensionMode.NIGHT
                    SpecialMode.PORTRAIT -> ExtensionMode.BOKEH
                    else -> ExtensionMode.NONE
                }
                if (extMode != ExtensionMode.NONE && (em == null || !em.isExtensionAvailable(front, extMode))) {
                    events.tryEmit("${special.label} is not offered to apps by this phone's camera.")
                    specialFailed.tryEmit(Unit)
                    extMode = ExtensionMode.NONE
                }
                val ic = ImageCapture.Builder()
                    .setResolutionSelector(if (special == SpecialMode.ULTRA_HD) ultraSelector(strategy) else photoSelector)
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
                // The live histogram is read from the preview picture (see sampleHistogram), NOT from an extra camera
                // stream: an extra stream made some phones shrink the saved photo.
                if (!cfg.histogram || extMode != ExtensionMode.NONE) histogram.value = null
            }

            val extSelector = if (extMode != ExtensionMode.NONE && em != null)
                em.getExtensionEnabledCameraSelector(front, extMode) else front
            val cam = try {
                p.bindToLifecycle(owner, extSelector, *useCases.toTypedArray())
            } catch (e: Exception) {
                if (extMode == ExtensionMode.NONE) throw e
                events.tryEmit("This phone could not start ${special.label}, so the normal camera is used.")
                specialFailed.tryEmit(Unit)
                p.unbindAll()
                p.bindToLifecycle(owner, front, *useCases.toTypedArray())
            }
            camera = cam
            zoomRatio.value = 1f
            guardEv = 0f
            guardCalmCount = 0
            val base = try { readCaps(cam, p) } catch (e: Exception) { caps.value }
            var hdr = false
            var night = false
            var bokeh = false
            if (em != null) {
                try {
                    hdr = em.isExtensionAvailable(front, ExtensionMode.HDR)
                    night = em.isExtensionAvailable(front, ExtensionMode.NIGHT)
                    bokeh = em.isExtensionAvailable(front, ExtensionMode.BOKEH)
                } catch (e: Exception) { }
            }
            val dual = Build.VERSION.SDK_INT >= 30 && try {
                p.availableConcurrentCameraInfos.isNotEmpty()
            } catch (e: Throwable) { false }
            caps.value = base.copy(extHdr = hdr, extNight = night, extBokeh = bokeh, dualCapable = dual)
            bindCount.value = bindCount.value + 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            events.tryEmit("Camera could not start: ${e.message ?: "unknown error"}")
        }
    }

    /** Dual video: front and rear camera together, each with its own preview and its own video file. Returns false if not possible. */
    @SuppressLint("NewApi")
    suspend fun bindDual(owner: LifecycleOwner, backView: PreviewView, frontView: PreviewView): Boolean {
        if (Build.VERSION.SDK_INT < 30) {
            events.tryEmit("Dual video needs Android 11 or newer.")
            return false
        }
        try {
            val p = provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }
            recording?.stop()
            recording = null
            stopDual()
            p.unbindAll()
            if (p.availableConcurrentCameraInfos.isEmpty()) {
                events.tryEmit("This phone cannot use the front and rear camera together.")
                return false
            }
            fun group(view: PreviewView): Pair<UseCaseGroup, VideoCapture<Recorder>> {
                val pv = Preview.Builder().build()
                pv.setSurfaceProvider(view.surfaceProvider)
                val rec = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    ).build()
                val vc = VideoCapture.withOutput(rec)
                return Pair(UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc).build(), vc)
            }
            val back = group(backView)
            val frontG = group(frontView)
            p.bindToLifecycle(
                listOf(
                    ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA, back.first, owner),
                    ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA, frontG.first, owner)
                )
            )
            dualBack = back.second
            dualFront = frontG.second
            camera = null
            imageCapture = null
            videoCapture = null
            histogram.value = null
            bindCount.value = bindCount.value + 1
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            events.tryEmit("Dual video could not start: ${e.message ?: e.javaClass.simpleName}")
            return false
        }
    }

    private fun stopDual() {
        try { dualRecordings.forEach { it.stop() } } catch (e: Exception) { }
        dualRecordings.clear()
        dualBack = null
        dualFront = null
    }

    fun toggleDualRecording(withAudio: Boolean, onState: (Boolean) -> Unit) {
        val back = dualBack
        val front = dualFront
        if (back == null || front == null) { events.tryEmit("Dual video is not ready yet."); return }
        if (dualRecordings.isNotEmpty()) { dualRecordings.toList().forEach { it.stop() }; return }
        if (freeBytes() < MIN_FREE_BYTES * 6) { events.tryEmit("Storage is almost full. Free some space to record video."); return }
        val name = stamp()
        var remaining = 2
        fun start(vc: VideoCapture<Recorder>, suffix: String, audio: Boolean): Recording {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "FOVEA_${name}_$suffix")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Fovea")
            }
            val out = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values).build()
            var pending = vc.output.prepareRecording(context, out)
            if (audio) pending = pending.withAudioEnabled()
            return pending.start(mainExecutor) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> {
                        if (suffix == "BACK") onState(true)
                    }
                    is VideoRecordEvent.Finalize -> {
                        remaining--
                        val kept = !ev.hasError() || ev.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
                            ev.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ||
                            ev.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                        if (kept) lastSaved.value = ev.outputResults.outputUri
                        else events.tryEmit("The $suffix video could not be saved (error ${ev.error}).")
                        if (remaining <= 0) {
                            dualRecordings.clear()
                            onState(false)
                            events.tryEmit("Dual video saved as two files (BACK and FRONT).")
                        }
                    }
                    else -> Unit
                }
            }
        }
        dualRecordings += start(back, "BACK", withAudio)
        dualRecordings += start(front, "FRONT", false)
    }

    /** Called when the camera screen leaves the screen: stops any recording and releases the camera. */
    fun unbind() {
        try {
            recording?.stop()
            recording = null
            stopDual()
            extras.stopSlowMo()
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
            hasBack = hasBack,
            cameraId = c2.cameraId,
            sensorOrientation = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            highSpeed = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.highSpeedVideoSizes?.isNotEmpty() == true
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
        // Focus and brightness follow the tapped spot; white balance is left alone so colors do not jump.
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val future = camera?.cameraControl?.startFocusAndMetering(action) ?: return
        future.addListener({
            try {
                if (!future.get().isFocusSuccessful) {
                    events.tryEmit("Could not focus there. Step back a little, or tap a spot with more detail.")
                }
            } catch (e: Exception) {
                // Focus was cancelled by a newer tap or by closing the camera: nothing to tell the owner.
            }
        }, mainExecutor)
    }

    fun setEv(stops: Float) {
        userEv = stops
        applyEv()
    }

    private fun applyEv() {
        val c = caps.value
        if (!c.evSupported) return
        val lo = (c.evMin / c.evStep).roundToInt()
        val hi = (c.evMax / c.evStep).roundToInt()
        camera?.cameraControl?.setExposureCompensationIndex(((userEv + guardEv) / c.evStep).roundToInt().coerceIn(lo, hi))
    }

    /** Turns the automatic highlight reduction off and gives the exposure back to the EV slider. */
    fun resetHighlightGuard() {
        if (guardEv != 0f) {
            guardEv = 0f
            guardCalmCount = 0
            applyEv()
        }
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

    fun takePhoto(playSound: Boolean, look: Look, watermark: String?, effect: PhotoEffect, announce: Boolean = true, onDone: (Boolean) -> Unit) {
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
                if (src == null) return
                val needsCopy = look != Look.OFF || watermark != null || effect != PhotoEffect.NONE
                val what = listOfNotNull(
                    when (effect) {
                        PhotoEffect.TILT_SHIFT -> "Tilt-shift"
                        PhotoEffect.DOCUMENT -> "Document"
                        PhotoEffect.NONE -> null
                    },
                    if (look != Look.OFF && effect != PhotoEffect.DOCUMENT) look.label else null,
                    if (watermark != null) "watermark" else null
                ).joinToString(" + ")
                try {
                    lookExecutor.execute {
                        try {
                            if (!needsCopy) {
                                if (announce) photoSizeText(src)?.let { events.tryEmit("Saved $it") }
                                return@execute
                            }
                            val copy = LookProcessor.createLookCopy(context, src, look, watermark, effect)
                            if (copy == null) {
                                events.tryEmit("$what could not be applied. Original is saved.")
                            } else if (watermark != null) {
                                // Watermark ON: only ONE photo is kept (the one with the watermark).
                                MediaStoreHelper.delete(context, src)
                                lastSaved.value = copy
                                events.tryEmit("Saved with $what" + (photoSizeText(copy)?.let { " ($it)" } ?: ""))
                            } else {
                                lastSaved.value = copy
                                events.tryEmit("Saved original + $what copy")
                            }
                        } catch (t: Throwable) {
                            events.tryEmit("$what failed (${t.javaClass.simpleName}). Original is saved.")
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    // App is closing; the original photo is already saved.
                }
            }
            override fun onError(e: ImageCaptureException) {
                events.tryEmit("Photo failed: ${e.message}")
                onDone(false)
            }
        })
    }

    /** "12.2 MP (4000x3000)" for a saved photo, or null if it cannot be read. */
    private fun photoSizeText(uri: Uri): String? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth > 0 && o.outHeight > 0) {
            "%.1f MP (%dx%d)".format(o.outWidth.toLong() * o.outHeight / 1_000_000.0, o.outWidth, o.outHeight)
        } else null
    } catch (e: Exception) { null }

    /** Takes one photo and waits until it is saved. Returns false if it failed (message already posted). */
    suspend fun takePhotoAwait(playSound: Boolean, look: Look, watermark: String?, effect: PhotoEffect = PhotoEffect.NONE, announce: Boolean = false): Boolean =
        suspendCancellableCoroutine { cont ->
            takePhoto(playSound, look, watermark, effect, announce) { ok -> if (cont.isActive) cont.resume(ok) }
        }

    private suspend fun captureFrame(): RawFrame? = suspendCancellableCoroutine { c ->
        val ic = imageCapture
        if (ic == null) {
            c.resume(null)
            return@suspendCancellableCoroutine
        }
        ic.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    if (c.isActive) c.resume(RawFrame(bytes, image.imageInfo.rotationDegrees))
                } catch (e: Exception) {
                    if (c.isActive) c.resume(null)
                } finally {
                    image.close()
                }
            }
            override fun onError(exception: ImageCaptureException) {
                if (c.isActive) c.resume(null)
            }
        })
    }

    /** Long exposure: takes [frames] photos one after another and blends them into one smooth picture. */
    suspend fun takeLongExposure(frames: Int, playSound: Boolean, onProgress: (Int) -> Unit): Uri? {
        if (imageCapture == null) { events.tryEmit("Camera is not ready yet."); return null }
        if (freeBytes() < MIN_FREE_BYTES) { events.tryEmit("Storage is almost full. Free some space to take photos."); return null }
        if (playSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        val stack = LongExposureStack()
        try {
            var used = 0
            var tries = 0
            while (used < frames && tries < frames + 4) {
                tries++
                val f = captureFrame() ?: continue
                val ok = withContext(Dispatchers.Default) {
                    try { stack.add(f) } catch (e: OutOfMemoryError) { false } catch (e: Exception) { false }
                }
                if (ok) { used++; onProgress(used) }
            }
            if (stack.frames < 2) {
                events.tryEmit("Long exposure needs at least 2 photos. Nothing was saved.")
                return null
            }
            val bmp = withContext(Dispatchers.Default) {
                try { stack.result() } catch (e: OutOfMemoryError) { null }
            }
            if (bmp == null) { events.tryEmit("Long exposure ran out of memory. Nothing was saved."); return null }
            val uri = withContext(Dispatchers.IO) {
                try { LongExposureStack.save(context, bmp, "FOVEA_${stamp()}_LONGEXP") } finally { bmp.recycle() }
            }
            if (uri != null) {
                lastSaved.value = uri
                events.tryEmit("Long exposure saved (${stack.frames} photos blended)")
            } else events.tryEmit("Long exposure could not be saved.")
            return uri
        } finally {
            stack.clear()
        }
    }

    /** RAW (DNG): CameraX is released for a moment, the photo is taken directly, then the camera screen binds again. */
    suspend fun captureRaw(playSound: Boolean): Uri? {
        val c = caps.value
        if (c.cameraId.isBlank() || !c.rawCapable) { events.tryEmit("This camera cannot take RAW photos."); return null }
        if (freeBytes() < MIN_FREE_BYTES * 2) { events.tryEmit("Storage is almost full. Free some space to take photos."); return null }
        unbind()
        delay(300)
        if (playSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        try {
            val uri = extras.captureDng(c.cameraId, c.sensorOrientation)
            lastSaved.value = uri
            events.tryEmit("Saved RAW (DNG) photo")
            return uri
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            events.tryEmit("RAW photo failed: ${e.message ?: e.javaClass.simpleName}")
            return null
        } finally {
            rebind.value = rebind.value + 1
        }
    }

    /** Slow motion: CameraX is released, high-speed recording starts directly on the camera. Returns true if recording. */
    suspend fun startSlowMo(): Boolean {
        val c = caps.value
        if (c.cameraId.isBlank() || !c.highSpeed) { events.tryEmit("This camera does not offer high-speed video to apps."); return false }
        if (freeBytes() < MIN_FREE_BYTES * 8) { events.tryEmit("Storage is almost full. Free some space to record video."); return false }
        unbind()
        delay(300)
        try {
            val fps = extras.startSlowMo(c.cameraId, c.sensorOrientation)
            events.tryEmit("Slow motion recording at $fps fps (no sound). The preview is paused.")
            return true
        } catch (e: CancellationException) {
            rebind.value = rebind.value + 1
            throw e
        } catch (e: Exception) {
            events.tryEmit("Slow motion failed: ${e.message ?: e.javaClass.simpleName}")
            rebind.value = rebind.value + 1
            return false
        }
    }

    fun stopSlowMo() {
        val uri = extras.stopSlowMo()
        if (uri != null) {
            lastSaved.value = uri
            events.tryEmit("Slow motion saved. It is a high-frame-rate video: use your Gallery's slow-motion or speed tool to see it slowed down.")
        } else events.tryEmit("Slow motion video could not be saved.")
        rebind.value = rebind.value + 1
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

    /**
     * Reads the preview picture a few times per second (main thread). Feeds the live histogram and, if [guard] is on,
     * the highlight protection: when many pixels are burnt out (windows, lights) the exposure is lowered by up to
     * 1.5 stops in small steps, and given back slowly when the scene calms down.
     */
    fun sampleHistogram(view: PreviewView, wantHistogram: Boolean, guard: Boolean) {
        val grabbed: Bitmap? = try { view.bitmap } catch (e: Throwable) { null }
        val bmp = grabbed ?: return
        try {
            val small = Bitmap.createScaledBitmap(bmp, 64, 64, true)
            val px = IntArray(64 * 64)
            small.getPixels(px, 0, 64, 0, 0, 64, 64)
            val bins = IntArray(64)
            var burnt = 0
            for (c in px) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = (r * 299 + g * 587 + b * 114) / 1000
                bins[y shr 2]++
                if (y >= 245) burnt++
            }
            if (small !== bmp) small.recycle()
            if (wantHistogram) histogram.value = bins
            if (guard) {
                val now = android.os.SystemClock.elapsedRealtime()
                val share = burnt / (64f * 64f)
                if (now - guardLastChangeAt >= 700) {
                    if (share > 0.06f && guardEv > -1.5f) {
                        guardEv = (guardEv - 0.33f).coerceAtLeast(-1.5f)
                        guardCalmCount = 0
                        guardLastChangeAt = now
                        applyEv()
                    } else if (share < 0.01f && guardEv < 0f) {
                        guardCalmCount++
                        if (guardCalmCount >= 4) {
                            guardEv = (guardEv + 0.33f).coerceAtMost(0f)
                            guardCalmCount = 0
                            guardLastChangeAt = now
                            applyEv()
                        }
                    } else guardCalmCount = 0
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    fun release() {
        recording?.stop()
        stopDual()
        extras.release()
        provider?.unbindAll()
        sound.release()
        lookExecutor.shutdown()
    }
}
