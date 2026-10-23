package app.fovea.camera.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI state holder. The camera itself lives in CameraEngine; presets stay in memory until Stage 5. */
class CameraViewModel : ViewModel() {
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private fun change(block: CameraUiState.() -> CameraUiState) = _state.update { it.block() }

    fun setMode(m: CaptureMode) = change { if (recording) this else copy(mode = m, manualField = ManualField.NONE) }
    fun cycleFlash() = change { copy(flash = FlashMode.values()[(flash.ordinal + 1) % FlashMode.values().size]) }
    fun cycleAspect() = change { copy(aspect = AspectOption.values()[(aspect.ordinal + 1) % AspectOption.values().size]) }
    fun cycleTimer() = change { copy(timer = TimerOption.values()[(timer.ordinal + 1) % TimerOption.values().size]) }
    fun setGuide(g: GuideType) = change { copy(guide = g) }
    fun setEv(v: Float) = change { copy(ev = v) }
    fun switchCamera() = change { if (recording) this else copy(frontCamera = !frontCamera) }
    fun setRecording(r: Boolean) = change { copy(recording = r) }
    fun selectField(f: ManualField) = change { copy(manualField = if (manualField == f) ManualField.NONE else f) }
    fun setIso(v: Int) = change { copy(iso = v, exposureManual = true) }
    fun setShutterNs(v: Long) = change { copy(shutterNs = v, exposureManual = true) }
    fun setExposureAuto() = change { copy(exposureManual = false) }
    fun setFocus(v: Float) = change { copy(focus = v.coerceIn(0f, 1f), focusAuto = false) }
    fun setFocusAuto() = change { copy(focusAuto = true) }
    fun setAwb(a: AwbOption) = change { copy(awb = a) }
    fun toggleFlag(f: UiFlag) = change { copy(flags = if (f in flags) flags - f else flags + f) }
    fun setShutterSound(v: Boolean) = change { copy(shutterSound = v) }
    fun setVolumeShutter(v: Boolean) = change { copy(volumeShutter = v) }
    fun setLook(l: Look) = change { copy(look = l) }
    fun setQuery(q: String) = change { copy(query = q) }
    fun postMessage(text: String) = change { copy(message = text) }
    fun clearMessage() = change { copy(message = null) }

    /** applyStart = true only at app launch: it also picks the starting mode and camera. */
    fun applyPersisted(p: PersistedSettings, applyStart: Boolean = false) = change {
        val newMode = if (!applyStart) mode else when (p.startMode) {
            StartMode.LAST -> p.lastMode
            StartMode.PHOTO -> CaptureMode.PHOTO
            StartMode.PRO -> CaptureMode.PRO
            StartMode.VIDEO -> CaptureMode.VIDEO
        }
        val newFront = if (!applyStart) frontCamera else when (p.startCamera) {
            StartCamera.LAST -> p.lastFront
            StartCamera.REAR -> false
            StartCamera.FRONT -> true
        }
        copy(
            mode = newMode, frontCamera = newFront,
            guide = p.guide, aspect = p.aspect,
            flags = buildSet { if (p.histogram) add(UiFlag.HISTOGRAM); if (p.haptics) add(UiFlag.HAPTICS) },
            shutterSound = p.shutterSound, volumeShutter = p.volumeShutter, look = p.look,
            startMode = p.startMode, startCamera = p.startCamera,
            jpegQuality = p.jpegQuality, fastCapture = p.fastCapture,
            videoQuality = p.videoQuality, recordAudio = p.recordAudio,
            guideColor = p.guideColor, guideOpacity = p.guideOpacity, guideThickness = p.guideThickness,
            reduceMotion = p.reduceMotion, largeText = p.largeText
        )
    }

    /** Resets saved preferences only. Presets, photos and the current mode/camera are never touched. */
    fun restoreDefaults() = applyPersisted(PersistedSettings(), applyStart = false)

    private inline fun <reified E : Enum<E>> next(e: E): E {
        val all = enumValues<E>()
        return all[(e.ordinal + 1) % all.size]
    }
    fun cycleStartMode() = change { copy(startMode = next(startMode)) }
    fun cycleStartCamera() = change { copy(startCamera = next(startCamera)) }
    fun cycleJpegQuality() = change { copy(jpegQuality = next(jpegQuality)) }
    fun cycleVideoQuality() = change { copy(videoQuality = next(videoQuality)) }
    fun setFastCapture(v: Boolean) = change { copy(fastCapture = v) }
    fun setRecordAudio(v: Boolean) = change { copy(recordAudio = v) }
    fun setGuideColor(c: GuideColor) = change { copy(guideColor = c) }
    fun setGuideOpacity(v: Float) = change { copy(guideOpacity = v.coerceIn(0.2f, 1f)) }
    fun setGuideThickness(v: Float) = change { copy(guideThickness = v.coerceIn(1f, 4f)) }
    fun setReduceMotion(v: Boolean) = change { copy(reduceMotion = v) }
    fun setLargeText(v: Boolean) = change { copy(largeText = v) }

    fun addPreset(name: String, description: String, category: PresetCategory): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        change {
            copy(
                presets = presets + Preset(nextPresetId, n, description.trim(), category),
                nextPresetId = nextPresetId + 1
            )
        }
        return true
    }

    fun toggleFavorite(id: Int) = change {
        copy(presets = presets.map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
    }

    fun deletePreset(id: Int) = change { copy(presets = presets.filterNot { it.id == id }) }
}
