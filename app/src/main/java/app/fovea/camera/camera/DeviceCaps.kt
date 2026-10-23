package app.fovea.camera.camera

import app.fovea.camera.state.AspectOption
import app.fovea.camera.state.AwbOption
import app.fovea.camera.state.FlashMode

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
    val hasBack: Boolean = false
) {
    val evSupported: Boolean get() = evStep > 0f && evMax > evMin
    val isoSupported: Boolean get() = manualSensor && isoMax > isoMin && shutterMaxNs > shutterMinNs
}

data class BindConfig(
    val front: Boolean,
    val video: Boolean,
    val aspect: AspectOption,
    val flash: FlashMode,
    val histogram: Boolean
)

data class ManualSettings(
    val exposureManual: Boolean,
    val iso: Int,
    val shutterNs: Long,
    val focusManual: Boolean,
    val focusDiopters: Float,
    val awb: AwbOption
)
