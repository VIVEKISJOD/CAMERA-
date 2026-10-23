package app.fovea.camera.camera

import app.fovea.camera.state.AspectOption
import app.fovea.camera.state.AwbOption
import app.fovea.camera.state.FlashMode
import app.fovea.camera.state.SpecialMode
import app.fovea.camera.state.VideoQuality

/** What the active camera really reports. Nothing here is assumed. */
data class DeviceCaps(
    val ready: Boolean = false,
    val hasFlash: Boolean = false,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val evMin: Float = 0f,
    val evMax: Float = 0f,
    val evStep: Float = 0f,
    val manualSensor: Boolean = false,
    val isoMin: Int = 0,
    val isoMax: Int = 0,
    val shutterMinNs: Long = 0L,
    val shutterMaxNs: Long = 0L,
    val manualFocus: Boolean = false,
    val minFocusDiopters: Float = 0f,
    val awbModes: Set<AwbOption> = setOf(AwbOption.AUTO),
    val rawCapable: Boolean = false,
    val hardwareLevel: String = "Unknown",
    val hasFront: Boolean = false,
    val hasBack: Boolean = false,
    // Stage 6: what this camera can really do for the special modes.
    val cameraId: String = "",
    val sensorOrientation: Int = 90,
    val highSpeed: Boolean = false,
    val extHdr: Boolean = false,
    val extNight: Boolean = false,
    val extBokeh: Boolean = false,
    val dualCapable: Boolean = false
) {
    val evSupported: Boolean get() = evStep > 0f && evMax > evMin
    val isoSupported: Boolean get() = manualSensor && isoMax > isoMin && shutterMaxNs > shutterMinNs
}

data class BindConfig(
    val front: Boolean,
    val video: Boolean,
    val aspect: AspectOption,
    val flash: FlashMode,
    val histogram: Boolean,
    val jpegQuality: Int = 100,
    val fastCapture: Boolean = false,
    val videoQuality: VideoQuality = VideoQuality.FHD,
    val special: SpecialMode = SpecialMode.NONE
)

/** True when the active camera can do this special mode. Nothing is assumed: it comes from the camera. */
fun SpecialMode.availableOn(c: DeviceCaps): Boolean = when (this) {
    SpecialMode.NONE, SpecialMode.TILT_SHIFT, SpecialMode.DOCUMENTS,
    SpecialMode.ULTRA_HD, SpecialMode.LONG_EXPOSURE -> true
    SpecialMode.HDR -> c.extHdr
    SpecialMode.NIGHT -> c.extNight
    SpecialMode.PORTRAIT -> c.extBokeh
    SpecialMode.RAW -> c.rawCapable
    SpecialMode.SLOW_MOTION -> c.highSpeed
    SpecialMode.DUAL_VIDEO -> c.dualCapable
}

data class ManualSettings(
    val exposureManual: Boolean,
    val iso: Int,
    val shutterNs: Long,
    val focusManual: Boolean,
    val focusDiopters: Float,
    val awb: AwbOption
)
