package app.fovea.camera.state

enum class CaptureMode(val label: String) { PRO("PRO"), VIDEO("VIDEO"), PHOTO("PHOTO") }
enum class FlashMode(val label: String) { OFF("OFF"), AUTO("AUTO"), ON("ON") }
enum class AspectOption(val label: String, val ratio: Float) {
    R43("3:4", 3f / 4f), R169("9:16", 9f / 16f)
}
enum class TimerOption(val label: String, val seconds: Int) {
    OFF("OFF", 0), S3("3 s", 3), S10("10 s", 10)
}
enum class GuideType(val label: String, val blurb: String) {
    NONE("None", "No overlay"),
    THIRDS("Rule of thirds", "Two vertical and two horizontal lines"),
    GOLDEN_GRID("Golden grid", "Lines at 38.2% and 61.8%"),
    SPIRAL("Golden spiral", "Logarithmic spiral toward a focal point"),
    DIAGONAL("Diagonals", "Corner-to-corner lines"),
    TRIANGLE("Triangles", "Diagonal plus two perpendiculars"),
    CENTER("Center cross", "Crosshair at the frame center"),
    SYMMETRY("Symmetry", "Vertical mirror axis"),
    HORIZON("Horizon", "Level reference line"),
    SAFE("Safe area", "Inset frame for cropping margins")
}
enum class PresetCategory(val label: String) {
    CAMERA("Camera"), LOOK("Look"), GUIDE("Guide"), COMBINED("Combined")
}
enum class UiFlag { HISTOGRAM, HAPTICS }
enum class AwbOption(val label: String) {
    AUTO("Auto"), DAYLIGHT("Daylight"), CLOUDY("Cloudy"), TUNGSTEN("Tungsten"), FLUORESCENT("Fluor.")
}
enum class ManualField { NONE, FOCUS, ISO, SHUTTER, WB, EV }

data class Preset(
    val id: Int,
    val name: String,
    val description: String,
    val category: PresetCategory,
    val favorite: Boolean = false
)

/** Settings that survive app restarts (SharedPreferences). */
data class PersistedSettings(
    val guide: GuideType = GuideType.THIRDS,
    val aspect: AspectOption = AspectOption.R43,
    val histogram: Boolean = false,
    val haptics: Boolean = true,
    val shutterSound: Boolean = true,
    val volumeShutter: Boolean = false,
    val look: Look = Look.OFF
)

data class CameraUiState(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val flash: FlashMode = FlashMode.OFF,
    val aspect: AspectOption = AspectOption.R43,
    val timer: TimerOption = TimerOption.OFF,
    val guide: GuideType = GuideType.THIRDS,
    val ev: Float = 0f,
    val frontCamera: Boolean = false,
    val recording: Boolean = false,
    // Pro controls. They are only sent to the camera in PRO mode, and only if the device supports them.
    val manualField: ManualField = ManualField.NONE,
    val exposureManual: Boolean = false,
    val iso: Int = 400,
    val shutterNs: Long = 16_666_667L,
    val focusAuto: Boolean = true,
    val focus: Float = 0f,
    val awb: AwbOption = AwbOption.AUTO,
    val shutterSound: Boolean = true,
    val volumeShutter: Boolean = false,
    val look: Look = Look.OFF,
    val flags: Set<UiFlag> = setOf(UiFlag.HAPTICS),
    val presets: List<Preset> = emptyList(),
    val query: String = "",
    val nextPresetId: Int = 1,
    val message: String? = null
)

fun CameraUiState.effectiveAspect(): AspectOption = if (mode == CaptureMode.VIDEO) AspectOption.R169 else aspect

fun CameraUiState.toPersisted() = PersistedSettings(
    guide, aspect, UiFlag.HISTOGRAM in flags, UiFlag.HAPTICS in flags, shutterSound, volumeShutter, look
)

fun CameraUiState.visiblePresets(): List<Preset> {
    val q = query.trim()
    return presets
        .filter { q.isEmpty() || it.name.contains(q, true) || it.description.contains(q, true) }
        .sortedByDescending { it.favorite }
}
