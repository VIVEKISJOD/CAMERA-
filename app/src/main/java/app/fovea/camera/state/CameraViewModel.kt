package app.fovea.camera.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI-only state holder. Stage 2 connects real camera results to this layer. Presets are in memory until Stage 5. */
class CameraViewModel : ViewModel() {
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private fun change(block: CameraUiState.() -> CameraUiState) = _state.update { it.block() }

    fun setMode(m: CaptureMode) = change { copy(mode = m, recording = false) }
    fun cycleFlash() = change { copy(flash = FlashMode.values()[(flash.ordinal + 1) % FlashMode.values().size]) }
    fun cycleAspect() = change { copy(aspect = AspectOption.values()[(aspect.ordinal + 1) % AspectOption.values().size]) }
    fun cycleTimer() = change { copy(timer = TimerOption.values()[(timer.ordinal + 1) % TimerOption.values().size]) }
    fun setGuide(g: GuideType) = change { copy(guide = g) }
    fun setZoom(z: Float) = change { copy(zoom = z) }
    fun setEv(v: Float) = change { copy(ev = v.coerceIn(-3f, 3f)) }
    fun switchCamera() = change { copy(frontCamera = !frontCamera) }
    fun setProOpen(open: Boolean) = change { copy(proOpen = open) }
    fun setIso(v: Int) = change { copy(iso = v) }
    fun setShutter(v: Int) = change { copy(shutterDenominator = v) }
    fun setFocus(v: Float) = change { copy(focus = v.coerceIn(0f, 1f)) }
    fun setWb(v: Int) = change { copy(wbKelvin = v) }
    fun setAeLock(v: Boolean) = change { copy(aeLock = v) }
    fun setAfLock(v: Boolean) = change { copy(afLock = v) }
    fun toggleFlag(f: UiFlag) = change { copy(flags = if (f in flags) flags - f else flags + f) }
    fun setQuery(q: String) = change { copy(query = q) }
    fun postMessage(text: String) = change { copy(message = text) }
    fun clearMessage() = change { copy(message = null) }

    /** Shutter press in Stage 1: no camera exists yet, so only UI state changes. */
    fun onShutter() {
        if (state.value.mode == CaptureMode.VIDEO) {
            change { copy(recording = !recording) }
            postMessage("Recording state is UI only. Real video arrives in Stage 2.")
        } else {
            postMessage("No camera connected yet. Capture arrives in Stage 2.")
        }
    }

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
