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
    SPIRAL("Golden spiral", "Golden-ratio spiral fitted to the frame"),
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
enum class StartMode(val label: String) { LAST("Last used"), PHOTO("Photo"), PRO("Pro"), VIDEO("Video") }
enum class StartCamera(val label: String) { LAST("Last used"), REAR("Rear"), FRONT("Front") }
enum class DriveMode(val label: String) { SINGLE("Single"), BURST("Burst"), TIMELAPSE("Time-lapse") }
enum class LapseInterval(val label: String, val seconds: Int) { S2("2 s", 2), S5("5 s", 5), S10("10 s", 10), S30("30 s", 30) }
enum class LapseFrames(val label: String, val count: Int) { F10("10 photos", 10), F30("30 photos", 30), F60("60 photos", 60), F120("120 photos", 120) }
/** Photo effect made on a saved COPY (the original is always kept). */
enum class PhotoEffect(val short: String) { NONE(""), TILT_SHIFT("TILT"), DOCUMENT("DOC") }

/**
 * Special camera modes added in Stage 6. Only one can be active. NONE = the normal camera.
 * video = records video; the rest take photos.
 */
enum class SpecialMode(val label: String, val blurb: String, val video: Boolean = false) {
    NONE("Normal", "Standard camera"),
    HDR("HDR", "Phone's own HDR (if the phone offers it to apps)"),
    NIGHT("Night", "Phone's own night mode (if the phone offers it to apps)"),
    PORTRAIT("Portrait blur", "Phone's own background blur (if the phone offers it to apps)"),
    TILT_SHIFT("Tilt-shift", "Miniature look: top and bottom blurred in a saved copy"),
    DOCUMENTS("Documents", "Clean, high-contrast copy for paper (no auto-crop)"),
    ULTRA_HD("Ultra HD", "Highest photo resolution the camera allows"),
    LONG_EXPOSURE("Long exposure", "Blends many photos for smooth water and light trails. Use a tripod"),
    RAW("RAW / DNG", "Unprocessed DNG file. Preview pauses for a moment"),
    SLOW_MOTION("Slow motion", "High frame rate video (no sound). Preview pauses while recording", video = true),
    DUAL_VIDEO("Dual video", "Front and rear camera record together as two videos", video = true);

    val effect: PhotoEffect get() = when (this) {
        TILT_SHIFT -> PhotoEffect.TILT_SHIFT
        DOCUMENTS -> PhotoEffect.DOCUMENT
        else -> PhotoEffect.NONE
    }
}
enum class LongExposureLength(val label: String, val frames: Int) { F8("8 photos", 8), F16("16 photos", 16), F30("30 photos", 30) }
enum class JpegQuality(val label: String, val value: Int) { MAX("Maximum", 100), HIGH("High", 95), STANDARD("Standard", 85) }
enum class VideoQuality(val label: String) { UHD("4K (if supported)"), FHD("Full HD 1080p"), HD("HD 720p"), SD("SD") }
enum class GuideColor(val label: String, val argb: Long) {
    WHITE("White", 0xFFFFFFFF), LIME("Lime", 0xFFB6F23C), YELLOW("Yellow", 0xFFFFD60A),
    RED("Red", 0xFFFF453A), CYAN("Cyan", 0xFF64D2FF)
}
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
    val highlightGuard: Boolean = true,
    val haptics: Boolean = true,
    val shutterSound: Boolean = true,
    val volumeShutter: Boolean = false,
    val look: Look = Look.OFF,
    val startMode: StartMode = StartMode.LAST,
    val startCamera: StartCamera = StartCamera.LAST,
    val lastMode: CaptureMode = CaptureMode.PHOTO,
    val lastFront: Boolean = false,
    val jpegQuality: JpegQuality = JpegQuality.MAX,
    val fastCapture: Boolean = false,
    val videoQuality: VideoQuality = VideoQuality.FHD,
    val recordAudio: Boolean = true,
    val guideColor: GuideColor = GuideColor.WHITE,
    val guideOpacity: Float = 0.55f,
    val guideThickness: Float = 1f,
    val reduceMotion: Boolean = false,
    val largeText: Boolean = false,
    val watermarkOn: Boolean = false,
    val watermarkText: String = "Fovea"
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
    val startMode: StartMode = StartMode.LAST,
    val startCamera: StartCamera = StartCamera.LAST,
    val jpegQuality: JpegQuality = JpegQuality.MAX,
    val fastCapture: Boolean = false,
    val videoQuality: VideoQuality = VideoQuality.FHD,
    val recordAudio: Boolean = true,
    val guideColor: GuideColor = GuideColor.WHITE,
    val guideOpacity: Float = 0.55f,
    val guideThickness: Float = 1f,
    val reduceMotion: Boolean = false,
    val largeText: Boolean = false,
    val watermarkOn: Boolean = false,
    val watermarkText: String = "Fovea",
    val highlightGuard: Boolean = true,
    // Not saved: the camera always starts in single-photo mode so a burst never starts by surprise.
    val drive: DriveMode = DriveMode.SINGLE,
    val special: SpecialMode = SpecialMode.NONE,
    val longExposure: LongExposureLength = LongExposureLength.F16,
    val lapseInterval: LapseInterval = LapseInterval.S5,
    val lapseFrames: LapseFrames = LapseFrames.F30,
    val flags: Set<UiFlag> = setOf(UiFlag.HAPTICS),
    val presets: List<Preset> = emptyList(),
    val query: String = "",
    val nextPresetId: Int = 1,
    val message: String? = null
)

fun CameraUiState.effectiveAspect(): AspectOption = if (mode == CaptureMode.VIDEO) AspectOption.R169 else aspect

fun CameraUiState.toPersisted() = PersistedSettings(
    guide = guide, aspect = aspect,
    histogram = UiFlag.HISTOGRAM in flags, haptics = UiFlag.HAPTICS in flags,
    shutterSound = shutterSound, volumeShutter = volumeShutter, look = look,
    startMode = startMode, startCamera = startCamera, lastMode = mode, lastFront = frontCamera,
    jpegQuality = jpegQuality, fastCapture = fastCapture, videoQuality = videoQuality, recordAudio = recordAudio,
    guideColor = guideColor, guideOpacity = guideOpacity, guideThickness = guideThickness,
    reduceMotion = reduceMotion, largeText = largeText,
    watermarkOn = watermarkOn, watermarkText = watermarkText, highlightGuard = highlightGuard
)

fun CameraUiState.visiblePresets(): List<Preset> {
    val q = query.trim()
    return presets
        .filter { q.isEmpty() || it.name.contains(q, true) || it.description.contains(q, true) }
        .sortedByDescending { it.favorite }
}
