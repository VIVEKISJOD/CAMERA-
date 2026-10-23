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
    @Test fun evIsStored() {
        // The real limits come from the camera; CameraEngine clamps to them.
        val vm = CameraViewModel(); vm.setEv(1.5f)
        assertEquals(1.5f, vm.state.value.ev, 0f)
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
        val p = PersistedSettings(
            guide = GuideType.SPIRAL, aspect = AspectOption.R169, histogram = true, haptics = false,
            shutterSound = false, volumeShutter = true, look = Look.VIVID_POP,
            jpegQuality = JpegQuality.STANDARD, fastCapture = true, videoQuality = VideoQuality.HD,
            recordAudio = false, guideColor = GuideColor.LIME, guideOpacity = 0.8f, guideThickness = 2f,
            reduceMotion = true, largeText = true
        )
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

class LookMathTest {
    @Test fun offIsIdentity() {
        val m = LookMath.matrix(Look.OFF)
        val id = floatArrayOf(1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 0f,0f,0f,1f,0f)
        for (i in 0 until 20) assertEquals(id[i], m[i], 0.001f)
    }
    @Test fun vividIncreasesColorSpread() {
        val before = intArrayOf(140, 110, 90)
        val after = LookMath.apply(before[0], before[1], before[2], Look.VIVID_POP)
        assertTrue((after.max() - after.min()) > (before.max() - before.min()))
    }
    @Test fun softFadeLiftsBlacks() {
        val after = LookMath.apply(0, 0, 0, Look.SOFT_FADE)
        assertTrue(after.all { it > 0 })
    }
    @Test fun outputStaysInRange() {
        for (look in Look.values()) {
            val p = LookMath.apply(255, 255, 255, look)
            assertTrue(p.all { it in 0..255 })
        }
    }
    @Test fun matrixHasTwentyValues() = assertEquals(20, LookMath.matrix(Look.RICH_CONTRAST).size)
}

class Stage3SettingsTest {
    @Test fun startModeOverridesLastMode() {
        val vm = CameraViewModel()
        vm.applyPersisted(PersistedSettings(startMode = StartMode.VIDEO, lastMode = CaptureMode.PHOTO), applyStart = true)
        assertEquals(CaptureMode.VIDEO, vm.state.value.mode)
    }
    @Test fun lastModeIsUsedWhenStartIsLast() {
        val vm = CameraViewModel()
        vm.applyPersisted(PersistedSettings(startMode = StartMode.LAST, lastMode = CaptureMode.PRO, lastFront = true, startCamera = StartCamera.LAST), applyStart = true)
        assertEquals(CaptureMode.PRO, vm.state.value.mode)
        assertTrue(vm.state.value.frontCamera)
    }
    @Test fun restoreDefaultsDoesNotChangeCurrentModeOrPresets() {
        val vm = CameraViewModel()
        vm.setMode(CaptureMode.VIDEO); vm.addPreset("Keep", "", PresetCategory.LOOK)
        vm.cycleJpegQuality(); vm.setLargeText(true)
        vm.restoreDefaults()
        assertEquals(CaptureMode.VIDEO, vm.state.value.mode)
        assertEquals(JpegQuality.MAX, vm.state.value.jpegQuality)
        assertFalse(vm.state.value.largeText)
        assertEquals(1, vm.state.value.presets.size)
    }
    @Test fun cyclesWrapAround() {
        val vm = CameraViewModel()
        repeat(JpegQuality.values().size) { vm.cycleJpegQuality() }
        assertEquals(JpegQuality.MAX, vm.state.value.jpegQuality)
    }
    @Test fun guideStyleIsClamped() {
        val vm = CameraViewModel(); vm.setGuideOpacity(5f); vm.setGuideThickness(0f)
        assertEquals(1f, vm.state.value.guideOpacity, 0f)
        assertEquals(1f, vm.state.value.guideThickness, 0f)
    }
}

class MoreLooksTest {
    @Test fun noirIsGray() {
        val p = LookMath.apply(200, 120, 40, Look.NOIR)
        assertTrue(p[0] == p[1] && p[1] == p[2])
    }
    @Test fun looksKeepOriginalOrder() {
        assertEquals(0, Look.OFF.ordinal); assertEquals(1, Look.VIVID_POP.ordinal); assertEquals(6, Look.NATURAL_PLUS.ordinal)
    }
    @Test fun shortNamesAreUniqueAndShort() {
        val shorts = Look.values().map { it.short }
        assertEquals(shorts.size, shorts.toSet().size)
        assertTrue(shorts.all { it.length <= 7 })
    }
}
