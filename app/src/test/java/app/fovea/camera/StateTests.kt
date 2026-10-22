package app.fovea.camera

import app.fovea.camera.state.*
import org.junit.Assert.*
import org.junit.Test

class PresetCodeFormatTest {
    @Test fun empty() = assertEquals(CodeCheck.EMPTY, PresetCodeFormat.check("  "))
    @Test fun short() = assertEquals(CodeCheck.TOO_SHORT, PresetCodeFormat.check("AB12"))
    @Test fun long() = assertEquals(CodeCheck.TOO_LONG, PresetCodeFormat.check("ABCDEFGHI"))
    @Test fun badChars() = assertEquals(CodeCheck.BAD_CHARACTERS, PresetCodeFormat.check("ABCD-123"))
    @Test fun valid() = assertEquals(CodeCheck.WELL_FORMED, PresetCodeFormat.check("ab12 cd34"))
    @Test fun normalizeUppercases() = assertEquals("AB12CD34", PresetCodeFormat.normalize("ab12 cd34"))
}

class CameraViewModelTest {
    @Test fun flashCyclesThroughAllModes() {
        val vm = CameraViewModel()
        val seen = mutableListOf(vm.state.value.flash)
        repeat(3) { vm.cycleFlash(); seen += vm.state.value.flash }
        assertEquals(listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON, FlashMode.OFF), seen)
    }
    @Test fun evIsClamped() {
        val vm = CameraViewModel(); vm.setEv(9f)
        assertEquals(3f, vm.state.value.ev, 0f)
    }
    @Test fun blankPresetNameRejected() {
        val vm = CameraViewModel()
        assertFalse(vm.addPreset("   ", "", PresetCategory.LOOK))
        assertTrue(vm.state.value.presets.isEmpty())
    }
    @Test fun presetLifecycle() {
        val vm = CameraViewModel()
        assertTrue(vm.addPreset("Dusk", "warm", PresetCategory.LOOK))
        assertTrue(vm.addPreset("Street", "", PresetCategory.CAMERA))
        vm.toggleFavorite(2)
        assertEquals("Street", vm.state.value.visiblePresets().first().name)
        vm.setQuery("dus")
        assertEquals(1, vm.state.value.visiblePresets().size)
        vm.deletePreset(1)
        assertEquals(1, vm.state.value.presets.size)
    }
    @Test fun modeSwitchStopsRecording() {
        val vm = CameraViewModel(); vm.setMode(CaptureMode.VIDEO); vm.onShutter()
        assertTrue(vm.state.value.recording)
        vm.setMode(CaptureMode.PHOTO)
        assertFalse(vm.state.value.recording)
    }
}
