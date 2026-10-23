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
    fun setQuery(q: String) = change { copy(query = q) }
    fun postMessage(text: String) = change { copy(message = text) }
    fun clearMessage() = change { copy(message = null) }

    fun applyPersisted(p: PersistedSettings) = change {
        copy(
            guide = p.guide, aspect = p.aspect,
            flags = buildSet { if (p.histogram) add(UiFlag.HISTOGRAM); if (p.haptics) add(UiFlag.HAPTICS) },
            shutterSound = p.shutterSound, volumeShutter = p.volumeShutter
        )
    }

    /** Resets saved preferences only. Presets and photos are never touched. */
    fun restoreDefaults() = applyPersisted(PersistedSettings())

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
