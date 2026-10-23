package app.fovea.camera

import app.fovea.camera.camera.DeviceCaps
import app.fovea.camera.camera.availableOn
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

class GoldenSpiralTest {
    private fun checkFrame(w: Float, h: Float) {
        val arcs = GoldenSpiral.arcs(w, h)
        assertEquals(10, arcs.size)
        // The curve must be one continuous line: each arc ends where the next one starts.
        for (i in 0 until arcs.size - 1) {
            val end = GoldenSpiral.pointAt(arcs[i], arcs[i].startDeg + 90f)
            val next = GoldenSpiral.pointAt(arcs[i + 1], arcs[i + 1].startDeg)
            assertEquals(end.first, next.first, 0.05f)
            assertEquals(end.second, next.second, 0.05f)
        }
        // The curve must stay inside the frame.
        for (a in arcs) {
            var d = 0f
            while (d <= 90f) {
                val p = GoldenSpiral.pointAt(a, a.startDeg + d)
                assertTrue(p.first >= -0.5f && p.first <= w + 0.5f)
                assertTrue(p.second >= -0.5f && p.second <= h + 0.5f)
                d += 5f
            }
        }
        // Each arc fills a smaller piece than the one before.
        for (i in 0 until arcs.size - 1) {
            assertTrue(arcs[i + 1].rx * arcs[i + 1].ry < arcs[i].rx * arcs[i].ry)
        }
    }
    @Test fun portraitFrame() = checkFrame(1080f, 1440f)
    @Test fun tallFrame() = checkFrame(1080f, 1920f)
    @Test fun landscapeFrame() = checkFrame(1440f, 1080f)
    @Test fun squareFrame() = checkFrame(500f, 500f)
    @Test fun portraitStartsInTopLeftCorner() {
        val a = GoldenSpiral.arcs(1080f, 1440f)[0]
        val p = GoldenSpiral.pointAt(a, a.startDeg)
        assertEquals(0f, p.first, 0.01f)
        assertEquals(0f, p.second, 0.01f)
    }
    @Test fun landscapeStartsInBottomLeftCorner() {
        val a = GoldenSpiral.arcs(1440f, 1080f)[0]
        val p = GoldenSpiral.pointAt(a, a.startDeg)
        assertEquals(0f, p.first, 0.01f)
        assertEquals(1080f, p.second, 0.01f)
    }
}

class Stage5ModesTest {
    @Test fun driveStartsAsSingle() = assertEquals(DriveMode.SINGLE, CameraViewModel().state.value.drive)
    @Test fun driveCyclesAndWraps() {
        val vm = CameraViewModel()
        val seen = mutableListOf(vm.state.value.drive)
        repeat(DriveMode.values().size) { vm.cycleDrive(); seen += vm.state.value.drive }
        assertEquals(listOf(DriveMode.SINGLE, DriveMode.BURST, DriveMode.TIMELAPSE, DriveMode.SINGLE), seen)
    }
    @Test fun lapseSettingsCycleAndWrap() {
        val vm = CameraViewModel()
        repeat(LapseInterval.values().size) { vm.cycleLapseInterval() }
        repeat(LapseFrames.values().size) { vm.cycleLapseFrames() }
        assertEquals(LapseInterval.S5, vm.state.value.lapseInterval)
        assertEquals(LapseFrames.F30, vm.state.value.lapseFrames)
    }
    @Test fun lapseValuesArePositive() {
        assertTrue(LapseInterval.values().all { it.seconds > 0 })
        assertTrue(LapseFrames.values().all { it.count > 0 })
    }
    @Test fun watermarkTextIsTrimmedAndLimited() {
        val vm = CameraViewModel()
        vm.setWatermarkText("  Hello  ")
        assertEquals("Hello", vm.state.value.watermarkText)
        vm.setWatermarkText("x".repeat(80))
        assertEquals(40, vm.state.value.watermarkText.length)
    }
    @Test fun watermarkIsSavedAndRestoredToDefault() {
        val vm = CameraViewModel()
        vm.applyPersisted(PersistedSettings(watermarkOn = true, watermarkText = "Studio"))
        assertEquals(PersistedSettings(watermarkOn = true, watermarkText = "Studio"), vm.state.value.toPersisted())
        vm.restoreDefaults()
        assertFalse(vm.state.value.watermarkOn)
        assertEquals("Fovea", vm.state.value.watermarkText)
    }
    @Test fun driveModeIsNeverSaved() {
        val vm = CameraViewModel(); vm.cycleDrive()
        vm.applyPersisted(vm.state.value.toPersisted())
        // Applying saved settings must not touch the current drive mode, and saving has no drive field.
        assertEquals(DriveMode.BURST, vm.state.value.drive)
        assertEquals(PersistedSettings(), CameraViewModel().state.value.toPersisted())
    }
}

class Stage6ModesTest {
    @Test fun startsNormal() = assertEquals(SpecialMode.NONE, CameraViewModel().state.value.special)

    @Test fun videoSpecialModesSwitchToVideo() {
        val vm = CameraViewModel()
        vm.setSpecial(SpecialMode.SLOW_MOTION)
        assertEquals(CaptureMode.VIDEO, vm.state.value.mode)
        vm.setSpecial(SpecialMode.DUAL_VIDEO)
        assertEquals(CaptureMode.VIDEO, vm.state.value.mode)
    }

    @Test fun photoSpecialLeavesVideoMode() {
        val vm = CameraViewModel(); vm.setMode(CaptureMode.VIDEO)
        vm.setSpecial(SpecialMode.HDR)
        assertEquals(CaptureMode.PHOTO, vm.state.value.mode)
        assertEquals(SpecialMode.HDR, vm.state.value.special)
    }

    @Test fun photoSpecialKeepsProMode() {
        val vm = CameraViewModel(); vm.setMode(CaptureMode.PRO)
        vm.setSpecial(SpecialMode.ULTRA_HD)
        assertEquals(CaptureMode.PRO, vm.state.value.mode)
    }

    @Test fun pickingAModeLeavesSpecial() {
        val vm = CameraViewModel(); vm.setSpecial(SpecialMode.NIGHT)
        vm.setMode(CaptureMode.PHOTO)
        assertEquals(SpecialMode.NONE, vm.state.value.special)
    }

    @Test fun cannotChangeSpecialWhileRecording() {
        val vm = CameraViewModel(); vm.setSpecial(SpecialMode.SLOW_MOTION); vm.setRecording(true)
        vm.setSpecial(SpecialMode.NONE)
        assertEquals(SpecialMode.SLOW_MOTION, vm.state.value.special)
    }

    @Test fun effectsMapToTheRightPhotoEffect() {
        assertEquals(PhotoEffect.TILT_SHIFT, SpecialMode.TILT_SHIFT.effect)
        assertEquals(PhotoEffect.DOCUMENT, SpecialMode.DOCUMENTS.effect)
        assertEquals(PhotoEffect.NONE, SpecialMode.HDR.effect)
    }

    @Test fun onlySlowMotionAndDualAreVideoModes() {
        val video = SpecialMode.values().filter { it.video }.toSet()
        assertEquals(setOf(SpecialMode.SLOW_MOTION, SpecialMode.DUAL_VIDEO), video)
    }

    @Test fun availabilityComesFromTheCamera() {
        val none = DeviceCaps()
        assertFalse(SpecialMode.HDR.availableOn(none))
        assertFalse(SpecialMode.RAW.availableOn(none))
        assertFalse(SpecialMode.SLOW_MOTION.availableOn(none))
        assertFalse(SpecialMode.DUAL_VIDEO.availableOn(none))
        assertTrue(SpecialMode.TILT_SHIFT.availableOn(none))
        val full = none.copy(extHdr = true, extNight = true, extBokeh = true, rawCapable = true, highSpeed = true, dualCapable = true)
        SpecialMode.values().forEach { assertTrue(it.availableOn(full)) }
    }

    @Test fun longExposureLengthCyclesAndWraps() {
        val vm = CameraViewModel()
        val seen = mutableSetOf(vm.state.value.longExposure)
        repeat(LongExposureLength.values().size) { vm.cycleLongExposure(); seen += vm.state.value.longExposure }
        assertEquals(LongExposureLength.values().toSet(), seen)
        assertTrue(LongExposureLength.values().all { it.frames >= 2 })
    }

    @Test fun specialModeIsNeverSaved() {
        val vm = CameraViewModel(); vm.setSpecial(SpecialMode.NIGHT)
        vm.restoreDefaults()
        // Saved settings have no special mode field, so the camera always starts normal.
        assertEquals(PersistedSettings(), vm.state.value.toPersisted().copy(lastMode = CaptureMode.PHOTO, lastFront = false))
    }
}
