package app.fovea.camera.state

enum class CaptureMode(val label: String) { PHOTO("Photo"), VIDEO("Video") }
enum class FlashMode(val label: String) { OFF("Flash off"), AUTO("Flash auto"), ON("Flash on") }
enum class AspectOption(val label: String, val ratio: Float) {
    R43("4:3", 3f / 4f), R169("16:9", 9f / 16f), R11("1:1", 1f)
}
enum class TimerOption(val label: String, val seconds: Int) {
    OFF("Timer off", 0), S3("3 s", 3), S10("10 s", 10)
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
enum class UiFlag { HISTOGRAM, LEVEL, HAPTICS }

data class Preset(
    val id: Int,
    val name: String,
    val description: String,
    val category: PresetCategory,
    val favorite: Boolean = false
)

data class CameraUiState(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val flash: FlashMode = FlashMode.OFF,
    val aspect: AspectOption = AspectOption.R43,
    val timer: TimerOption = TimerOption.OFF,
    val guide: GuideType = GuideType.THIRDS,
    val zoom: Float = 1f,
    val ev: Float = 0f,
    val frontCamera: Boolean = false,
    val recording: Boolean = false,
    val proOpen: Boolean = false,
    val iso: Int = 100,
    val shutterDenominator: Int = 125,
    val focus: Float = 0.5f,
    val wbKelvin: Int = 5500,
    val aeLock: Boolean = false,
    val afLock: Boolean = false,
    val flags: Set<UiFlag> = emptySet(),
    val presets: List<Preset> = emptyList(),
    val query: String = "",
    val nextPresetId: Int = 1,
    val message: String? = null
)

fun CameraUiState.visiblePresets(): List<Preset> {
    val q = query.trim()
    return presets
        .filter { q.isEmpty() || it.name.contains(q, true) || it.description.contains(q, true) }
        .sortedByDescending { it.favorite }
}
