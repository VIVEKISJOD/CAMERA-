package app.fovea.camera.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.fovea.camera.camera.*
import app.fovea.camera.state.*
import app.fovea.camera.ui.components.*
import app.fovea.camera.ui.theme.Fovea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun CameraScreen(
    vm: CameraViewModel,
    engine: CameraEngine,
    onSettings: () -> Unit,
    onGallery: () -> Unit,
    onPresets: () -> Unit,
    onGuides: () -> Unit
) {
    val s by vm.state.collectAsState()
    val caps by engine.caps.collectAsState()
    val zoom by engine.zoomRatio.collectAsState()
    val hist by engine.histogram.collectAsState()
    val bound by engine.bindCount.collectAsState()
    val saved by engine.lastSaved.collectAsState()

    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var camGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var audioGranted by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        camGranted = granted(Manifest.permission.CAMERA)
        audioGranted = granted(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) {
        if (!camGranted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    DisposableEffect(activity) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                camGranted = granted(Manifest.permission.CAMERA)
                audioGranted = granted(Manifest.permission.RECORD_AUDIO)
            }
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var count by remember { mutableIntStateOf(0) }
    var showMore by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<Uri?>(null) }

    fun shutter() {
        val st = vm.state.value
        if (count > 0 || !camGranted) return
        if (UiFlag.HAPTICS in st.flags) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (st.mode == CaptureMode.VIDEO) {
            engine.toggleRecording(audioGranted) { rec -> vm.setRecording(rec) }
        } else {
            scope.launch {
                var i = st.timer.seconds
                while (i > 0) { count = i; delay(1000); i-- }
                count = 0
                engine.takePhoto(vm.state.value.shutterSound) { }
            }
        }
    }

    DisposableEffect(Unit) {
        engine.cameraScreenVisible = true
        onDispose { engine.cameraScreenVisible = false }
    }
    LaunchedEffect(Unit) { engine.events.collect { vm.postMessage(it) } }
    LaunchedEffect(Unit) { engine.volumeEvents.collect { shutter() } }
    LaunchedEffect(s.message) { if (s.message != null) { delay(2600); vm.clearMessage() } }
    LaunchedEffect(saved, camGranted) {
        latest = withContext(Dispatchers.IO) { MediaStoreHelper.recent(ctx, 1).firstOrNull()?.uri }
    }

    val wantHist = UiFlag.HISTOGRAM in s.flags
    val isVideo = s.mode == CaptureMode.VIDEO
    LaunchedEffect(camGranted, s.frontCamera, isVideo, s.aspect, wantHist) {
        if (camGranted) engine.bind(activity, previewView, BindConfig(s.frontCamera, isVideo, s.aspect, s.flash, wantHist))
    }
    LaunchedEffect(s.flash, isVideo, bound) { engine.setFlash(s.flash, isVideo) }
    LaunchedEffect(s.ev, bound) { engine.setEv(s.ev) }
    LaunchedEffect(bound, s.mode, s.exposureManual, s.iso, s.shutterNs, s.focusAuto, s.focus, s.awb) {
        if (s.mode == CaptureMode.PRO) {
            engine.applyManual(
                ManualSettings(
                    s.exposureManual, s.iso, s.shutterNs, !s.focusAuto,
                    s.focus * caps.minFocusDiopters, s.awb
                )
            )
        } else engine.clearManual()
    }

    Column(Modifier.fillMaxSize().background(Fovea.Black).statusBarsPadding().navigationBarsPadding()) {
        TopStrip(s, caps, vm, onGuides, onPresets, onSettings)
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            if (!camGranted) {
                PermissionNeeded(
                    onGrant = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) },
                    onSettings = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                )
            } else {
                Viewfinder(s, vm, engine, previewView, zoom, hist, count)
            }
        }
        if (camGranted) {
            ZoomRow(caps, zoom) { engine.setZoom(it) }
            if (s.mode == CaptureMode.PRO) ProPanel(s, caps, vm)
            else if (caps.evSupported) EvRow(s.ev, caps) { vm.setEv(it) }
        }
        ModeRow(s, vm) { showMore = true }
        CaptureBar(s, latest, camGranted, onShutter = { shutter() }, onGallery = onGallery, onSwitch = { vm.switchCamera() })
    }
    if (showMore) MoreSheet { showMore = false }
}

@Composable
private fun TopStrip(
    s: CameraUiState, caps: DeviceCaps, vm: CameraViewModel,
    onGuides: () -> Unit, onPresets: () -> Unit, onSettings: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        TopToken("FLASH", s.flash.label, s.flash != FlashMode.OFF, enabled = caps.hasFlash && !s.frontCamera) { vm.cycleFlash() }
        TopToken("RATIO", s.effectiveAspect().label, false, enabled = s.mode != CaptureMode.VIDEO) { vm.cycleAspect() }
        TopToken("TIMER", s.timer.label, s.timer != TimerOption.OFF, enabled = s.mode != CaptureMode.VIDEO) { vm.cycleTimer() }
        TopToken("GRID", if (s.guide == GuideType.NONE) "OFF" else "ON", s.guide != GuideType.NONE, onClick = onGuides)
        IconButton(onClick = onPresets, modifier = Modifier.size(Fovea.TouchTarget)) {
            Icon(Icons.Filled.Star, "Presets", tint = Fovea.Text)
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(Fovea.TouchTarget)) {
            Icon(Icons.Filled.Settings, "Settings", tint = Fovea.Text)
        }
    }
}

@Composable
private fun TopToken(title: String, value: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val color = when {
        !enabled -> Fovea.Muted.copy(alpha = 0.5f)
        active -> Fovea.Accent
        else -> Fovea.Text
    }
    Column(
        Modifier.heightIn(min = Fovea.TouchTarget).widthIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = Fovea.Muted, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Viewfinder(
    s: CameraUiState, vm: CameraViewModel, engine: CameraEngine, previewView: PreviewView,
    zoom: Float, hist: IntArray?, count: Int
) {
    var focus by remember { mutableStateOf<Offset?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { if (tick > 0) { delay(1300); focus = null } }

    Box(
        Modifier.aspectRatio(s.effectiveAspect().ratio).clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    focus = pos; tick++
                    engine.focusAt(previewView, pos.x, pos.y)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, z, _ -> if (z != 1f) engine.setZoom(engine.zoomRatio.value * z) }
            }
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize())
        if (s.guide != GuideType.NONE) GuideOverlay(s.guide, Modifier.matchParentSize())
        val ring = Fovea.Accent
        focus?.let { p ->
            Canvas(Modifier.matchParentSize()) {
                drawCircle(ring, radius = 34.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
            }
        }
        if (hist != null && UiFlag.HISTOGRAM in s.flags) {
            Histogram(hist, Modifier.align(Alignment.TopStart).padding(10.dp).size(88.dp, 40.dp))
        }
        Text(
            "${"%.1f".format(zoom)}x", color = Fovea.Text, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                .clip(RoundedCornerShape(6.dp)).background(Fovea.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp)
        )
        if (s.recording) {
            Text("● REC", color = Fovea.Danger, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp))
        }
        if (count > 0) {
            Text("$count", color = Fovea.Text, fontSize = 72.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.Center))
        }
        s.message?.let {
            Text(
                it, color = Fovea.Text, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Fovea.Black.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun Histogram(bins: IntArray, modifier: Modifier) {
    Canvas(modifier.background(Fovea.Black.copy(alpha = 0.45f))) {
        val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val bw = size.width / bins.size
        bins.forEachIndexed { i, v ->
            val h = size.height * (v / max)
            drawRect(Fovea.Text.copy(alpha = 0.85f), Offset(i * bw, size.height - h), Size(bw * 0.8f, h))
        }
    }
}

@Composable
private fun PermissionNeeded(onGrant: () -> Unit, onSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StateView(
            Icons.Filled.Lock, "Camera permission needed",
            "Fovea needs the camera to show the viewfinder. Photos stay on your phone.",
            actionLabel = "Allow camera", onAction = onGrant
        )
        FoveaChip("Open app settings", onSettings)
    }
}

@Composable
private fun ZoomRow(caps: DeviceCaps, zoom: Float, onZoom: (Float) -> Unit) {
    val presets = listOf(0.6f, 1f, 2f, 5f).filter { it >= caps.minZoom - 0.02f && it <= caps.maxZoom + 0.01f }
    if (presets.size < 2) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        presets.forEach { z ->
            FoveaChip(
                if (z < 1f) "%.1f".format(z) else "${z.roundToInt()}x",
                { onZoom(z) }, selected = abs(zoom - z) < 0.05f
            )
        }
    }
}

@Composable
private fun EvRow(ev: Float, caps: DeviceCaps, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("EV", color = Fovea.Muted, fontSize = 11.sp)
        FoveaSlider(ev, caps.evMin..caps.evMax, Modifier.weight(1f).padding(horizontal = 8.dp), onChange)
        Text("%+.1f".format(ev), color = Fovea.Text, fontSize = 12.sp)
    }
}

@Composable
private fun FoveaSlider(v: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier, onChange: (Float) -> Unit) {
    Slider(
        value = v.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, modifier = modifier,
        colors = SliderDefaults.colors(thumbColor = Fovea.Accent, activeTrackColor = Fovea.Accent, inactiveTrackColor = Fovea.Line)
    )
}

private fun shutterLabel(ns: Long): String =
    if (ns >= 500_000_000L) "%.1fs".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"

@Composable
private fun ProPanel(s: CameraUiState, caps: DeviceCaps, vm: CameraViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ProToken("FOCUS", if (!caps.manualFocus) "N/A" else if (s.focusAuto) "AUTO" else "${(s.focus * 100).roundToInt()}%",
                s.manualField == ManualField.FOCUS) { vm.selectField(ManualField.FOCUS) }
            ProToken("ISO", if (!caps.isoSupported) "N/A" else if (s.exposureManual) "${s.iso}" else "AUTO",
                s.manualField == ManualField.ISO) { vm.selectField(ManualField.ISO) }
            ProToken("SHUTTER", if (!caps.isoSupported) "N/A" else if (s.exposureManual) shutterLabel(s.shutterNs) else "AUTO",
                s.manualField == ManualField.SHUTTER) { vm.selectField(ManualField.SHUTTER) }
            ProToken("WB", s.awb.label.uppercase(), s.manualField == ManualField.WB) { vm.selectField(ManualField.WB) }
            ProToken("EV", if (caps.evSupported) "%+.1f".format(s.ev) else "N/A", s.manualField == ManualField.EV) { vm.selectField(ManualField.EV) }
        }
        when (s.manualField) {
            ManualField.FOCUS -> if (!caps.manualFocus) NoteRow("This camera does not support manual focus.") else
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FoveaChip("Auto", { vm.setFocusAuto() }, selected = s.focusAuto)
                    Text("∞", color = Fovea.Muted)
                    FoveaSlider(s.focus, 0f..1f, Modifier.weight(1f).padding(horizontal = 8.dp)) { vm.setFocus(it) }
                    Text("Near", color = Fovea.Muted, fontSize = 11.sp)
                }
            ManualField.ISO -> if (!caps.isoSupported) NoteRow("This camera does not allow manual ISO.") else
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FoveaChip("Auto", { vm.setExposureAuto() }, selected = !s.exposureManual)
                    FoveaSlider(s.iso.toFloat(), caps.isoMin.toFloat()..caps.isoMax.toFloat(), Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        vm.setIso(it.roundToInt())
                    }
                }
            ManualField.SHUTTER -> if (!caps.isoSupported) NoteRow("This camera does not allow manual shutter speed.") else {
                val lo = caps.shutterMinNs.coerceAtLeast(1L)
                val hi = caps.shutterMaxNs.coerceAtMost(1_000_000_000L).coerceAtLeast(lo + 1)
                val t = (ln(s.shutterNs.coerceIn(lo, hi).toDouble() / lo) / ln(hi.toDouble() / lo)).toFloat()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FoveaChip("Auto", { vm.setExposureAuto() }, selected = !s.exposureManual)
                    FoveaSlider(t, 0f..1f, Modifier.weight(1f).padding(horizontal = 8.dp)) { x ->
                        vm.setShutterNs((lo * (hi.toDouble() / lo).pow(x.toDouble())).toLong())
                    }
                }
            }
            ManualField.WB -> Row(Modifier.fillMaxWidth()) {
                AwbOption.values().filter { it in caps.awbModes }.forEach { a ->
                    FoveaChip(a.label, { vm.setAwb(a) }, selected = s.awb == a)
                }
            }
            ManualField.EV -> if (!caps.evSupported) NoteRow("Exposure compensation is not available.") else
                EvRow(s.ev, caps) { vm.setEv(it) }
            ManualField.NONE -> Unit
        }
    }
}

@Composable
private fun NoteRow(text: String) {
    Text(text, color = Fovea.Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
}

@Composable
private fun ProToken(title: String, value: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.heightIn(min = Fovea.TouchTarget).widthIn(min = 56.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = Fovea.Muted, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(value, color = if (selected) Fovea.Accent else Fovea.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModeRow(s: CameraUiState, vm: CameraViewModel, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        ModeToken("PRO", s.mode == CaptureMode.PRO) { vm.setMode(CaptureMode.PRO) }
        ModeToken("VIDEO", s.mode == CaptureMode.VIDEO) { vm.setMode(CaptureMode.VIDEO) }
        ModeToken("PHOTO", s.mode == CaptureMode.PHOTO) { vm.setMode(CaptureMode.PHOTO) }
        ModeToken("PORTRAIT", false, dim = true) { vm.postMessage("Portrait blur is planned for a later stage.") }
        ModeToken("MORE", false, onClick = onMore)
    }
}

@Composable
private fun ModeToken(label: String, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = Fovea.TouchTarget).clickable(onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
            color = when { selected -> Fovea.Accent; dim -> Fovea.Muted.copy(alpha = 0.6f); else -> Fovea.Text }
        )
    }
}

@Composable
private fun CaptureBar(
    s: CameraUiState, latest: Uri?, enabled: Boolean,
    onShutter: () -> Unit, onGallery: () -> Unit, onSwitch: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(Fovea.TouchTarget).clip(RoundedCornerShape(10.dp)).background(Fovea.Surface2)
                .border(1.dp, Fovea.Line, RoundedCornerShape(10.dp)).clickable(onClick = onGallery),
            contentAlignment = Alignment.Center
        ) {
            if (latest != null) MediaThumb(latest, 160, Modifier.matchParentSize())
            else Text("Recent", color = Fovea.Muted, fontSize = 10.sp)
        }
        val inner = if (s.mode == CaptureMode.VIDEO) Fovea.Danger else Fovea.Text
        Box(
            Modifier.size(Fovea.ShutterSize).clip(CircleShape).border(4.dp, if (enabled) Fovea.Text else Fovea.Muted, CircleShape)
                .clickable(enabled = enabled, onClick = onShutter),
            contentAlignment = Alignment.Center
        ) {
            val shape = if (s.recording) RoundedCornerShape(6.dp) else CircleShape
            Box(Modifier.size(if (s.recording) 30.dp else 60.dp).clip(shape).background(inner))
        }
        RoundIconButton(
            Icons.Filled.Refresh, if (s.frontCamera) "Switch to rear camera" else "Switch to front camera", onSwitch
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Fovea.Graphite, contentColor = Fovea.Text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Fovea.ScreenPadding).padding(bottom = 24.dp)) {
            Text("Planned modes and tools", style = MaterialTheme.typography.titleLarge)
            Text(
                "These are not built yet. They will appear here as they become real, and only if your phone supports them.",
                color = Fovea.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)
            )
            listOf(
                "HDR", "Night", "Panorama", "Time-lapse", "Slow motion", "Long exposure", "Documents",
                "Ultra HD", "Dual video", "Timed burst", "Tilt-shift", "Watermark", "RAW / DNG capture"
            ).forEach { name ->
                SettingRow(name) { UiOnlyTag("PLANNED") }
            }
        }
    }
}
