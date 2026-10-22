package app.fovea.camera.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.fovea.camera.state.*
import app.fovea.camera.ui.components.*
import app.fovea.camera.ui.theme.Fovea
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun CameraScreen(
    vm: CameraViewModel,
    onSettings: () -> Unit,
    onGallery: () -> Unit,
    onPresets: () -> Unit,
    onGuides: () -> Unit,
    host: CameraPreviewHost = PlaceholderPreviewHost
) {
    val s by vm.state.collectAsState()
    var overflow by remember { mutableStateOf(false) }
    LaunchedEffect(s.message) {
        if (s.message != null) { delay(2400); vm.clearMessage() }
    }

    Column(Modifier.fillMaxSize().background(Fovea.Black).statusBarsPadding().navigationBarsPadding()) {
        TopStrip(s, vm, overflow) { overflow = !overflow }
        if (overflow) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
                FoveaChip("Guides", onGuides)
                FoveaChip("Presets", onPresets)
                FoveaChip("Settings", onSettings)
            }
        }
        Viewfinder(s, vm, host, Modifier.weight(1f).fillMaxWidth())
        QuickControls(s, vm)
        CaptureBar(s, vm, onGallery)
    }

    if (s.proOpen) ManualSheet(s, vm)
}

@Composable
private fun TopStrip(s: CameraUiState, vm: CameraViewModel, overflowOpen: Boolean, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        FoveaChip(s.flash.label, { vm.cycleFlash() }, selected = s.flash != FlashMode.OFF)
        FoveaChip("HDR", { vm.postMessage("HDR availability is detected in Stage 2.") }, enabled = false)
        FoveaChip(s.aspect.label, { vm.cycleAspect() })
        FoveaChip(s.timer.label, { vm.cycleTimer() }, selected = s.timer != TimerOption.OFF)
        FoveaChip("More", onMore, selected = overflowOpen)
    }
}

@Composable
private fun Viewfinder(s: CameraUiState, vm: CameraViewModel, host: CameraPreviewHost, modifier: Modifier) {
    var focus by remember { mutableStateOf<Offset?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { if (tick > 0) { delay(1300); focus = null } }

    Box(modifier.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.aspectRatio(s.aspect.ratio).clip(RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    detectTapGestures { pos -> focus = pos; tick++ }
                }
        ) {
            host.Preview(Modifier.matchParentSize())
            if (s.guide != GuideType.NONE) GuideOverlay(s.guide, Modifier.matchParentSize())
            val ring = Fovea.Accent
            focus?.let { p ->
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(ring, radius = 34.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
                }
            }
            Text(
                "${"%.1f".format(s.zoom)}x", color = Fovea.Text, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Fovea.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp)
            )
            if (s.ev != 0f) {
                Text(
                    "EV ${"%+.1f".format(s.ev)}", color = Fovea.Accent, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                )
            }
            if (s.recording) {
                Text("REC", color = Fovea.Danger, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            }
            s.message?.let {
                Text(
                    it, color = Fovea.Text, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Fovea.Black.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickControls(s: CameraUiState, vm: CameraViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // Lens availability is unknown until Stage 2, so these are simple zoom presets.
            listOf(1f, 2f, 5f).forEach { z ->
                FoveaChip("${z.roundToInt()}x", { vm.setZoom(z) }, selected = s.zoom == z)
            }
            FoveaChip("Pro", { vm.setProOpen(true) })
            FoveaChip("Photo", { vm.setMode(CaptureMode.PHOTO) }, selected = s.mode == CaptureMode.PHOTO)
            FoveaChip("Video", { vm.setMode(CaptureMode.VIDEO) }, selected = s.mode == CaptureMode.VIDEO)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EV", color = Fovea.Muted, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = s.ev, onValueChange = { vm.setEv(it) }, valueRange = -3f..3f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(thumbColor = Fovea.Accent, activeTrackColor = Fovea.Accent, inactiveTrackColor = Fovea.Line)
            )
            Text("%+.1f".format(s.ev), color = Fovea.Text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CaptureBar(s: CameraUiState, vm: CameraViewModel, onGallery: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(Fovea.TouchTarget).clip(RoundedCornerShape(10.dp)).background(Fovea.Surface2)
                .border(1.dp, Fovea.Line, RoundedCornerShape(10.dp)).clickable(onClick = onGallery),
            contentAlignment = Alignment.Center
        ) { Text("Recent", color = Fovea.Muted, style = MaterialTheme.typography.labelSmall) }

        val inner = if (s.mode == CaptureMode.VIDEO) Fovea.Danger else Fovea.Text
        Box(
            Modifier.size(Fovea.ShutterSize).clip(CircleShape).border(4.dp, Fovea.Text, CircleShape)
                .clickable { vm.onShutter() },
            contentAlignment = Alignment.Center
        ) {
            val innerSize = if (s.recording) 30.dp else 60.dp
            val shape = if (s.recording) RoundedCornerShape(6.dp) else CircleShape
            Box(Modifier.size(innerSize).clip(shape).background(inner))
        }

        RoundIconButton(Icons.Filled.Refresh, if (s.frontCamera) "Switch to rear camera" else "Switch to front camera", { vm.switchCamera() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSheet(s: CameraUiState, vm: CameraViewModel) {
    ModalBottomSheet(
        onDismissRequest = { vm.setProOpen(false) },
        containerColor = Fovea.Graphite, contentColor = Fovea.Text
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Fovea.ScreenPadding).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Manual controls", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                UiOnlyTag("UI ONLY — NOT APPLIED TO CAMERA")
            }
            SheetSlider("ISO", "${s.iso}", s.iso.toFloat(), 50f..3200f) { vm.setIso(it.roundToInt()) }
            SheetSlider("Shutter", "1/${s.shutterDenominator}", s.shutterDenominator.toFloat(), 8f..4000f) { vm.setShutter(it.roundToInt()) }
            SheetSlider("Focus", "%.0f%%".format(s.focus * 100), s.focus, 0f..1f) { vm.setFocus(it) }
            SheetSlider("White balance", "${s.wbKelvin} K", s.wbKelvin.toFloat(), 2500f..8000f) { vm.setWb(it.roundToInt()) }
            ToggleRow("Exposure lock", null, s.aeLock) { vm.setAeLock(it) }
            ToggleRow("Focus lock", null, s.afLock) { vm.setAfLock(it) }
        }
    }
}

@Composable
private fun SheetSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        Row {
            Text(label, color = Fovea.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, color = Fovea.Text, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = v, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Fovea.Accent, activeTrackColor = Fovea.Accent, inactiveTrackColor = Fovea.Line)
        )
    }
}
