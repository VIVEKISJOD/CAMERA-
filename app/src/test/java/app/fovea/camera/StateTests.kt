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
    @Test fun cannotSwitchModeOrCameraWhileRecording() {
        val vm = CameraViewModel(); vm.setMode(CaptureMode.VIDEO); vm.setRecording(true)
        vm.setMode(CaptureMode.PHOTO); vm.switchCamera()
        assertEquals(CaptureMode.VIDEO, vm.state.value.mode)
        assertFalse(vm.state.value.frontCamera)
    }
    @Test fun videoForcesWideAspect() {
        val vm = CameraViewModel(); vm.setMode(CaptureMode.VIDEO)
        assertEquals(AspectOption.R169, vm.state.value.effectiveAspect())
    }
    @Test fun manualExposureTurnsOnAndOff() {
        val vm = CameraViewModel(); vm.setIso(800)
        assertTrue(vm.state.value.exposureManual)
        vm.setExposureAuto()
        assertFalse(vm.state.value.exposureManual)
    }
    @Test fun restoreDefaultsKeepsPresets() {
        val vm = CameraViewModel()
        vm.addPreset("Keep me", "", PresetCategory.LOOK)
        vm.setGuide(GuideType.SPIRAL); vm.setShutterSound(false)
        vm.restoreDefaults()
        assertEquals(GuideType.THIRDS, vm.state.value.guide)
        assertTrue(vm.state.value.shutterSound)
        assertEquals(1, vm.state.value.presets.size)
    }
    @Test fun persistedRoundTrip() {
        val vm = CameraViewModel()
        val p = PersistedSettings(GuideType.SPIRAL, AspectOption.R169, true, false, false, true)
        vm.applyPersisted(p)
        assertEquals(p, vm.state.value.toPersisted())
    }
}

class UpdateParserTest {
    @Test fun parsesBuildTag() = assertEquals(12, app.fovea.camera.update.UpdateParser.buildNumber("build-12"))
    @Test fun rejectsOtherTags() {
        assertNull(app.fovea.camera.update.UpdateParser.buildNumber("v1.2"))
        assertNull(app.fovea.camera.update.UpdateParser.buildNumber("build-x"))
    }
    @Test fun newerComparison() {
        assertTrue(app.fovea.camera.update.UpdateParser.isNewer(5, 4))
        assertFalse(app.fovea.camera.update.UpdateParser.isNewer(4, 4))
        assertFalse(app.fovea.camera.update.UpdateParser.isNewer(3, 4))
    }
}
