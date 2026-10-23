package app.fovea.camera.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.fovea.camera.BuildConfig
import app.fovea.camera.camera.CameraEngine
import app.fovea.camera.camera.MediaItem
import app.fovea.camera.camera.MediaStoreHelper
import app.fovea.camera.state.*
import app.fovea.camera.ui.camera.GuideOverlay
import app.fovea.camera.ui.components.*
import app.fovea.camera.ui.theme.Fovea
import app.fovea.camera.update.UpdateManager
import app.fovea.camera.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: CameraViewModel, updates: UpdateManager, onBack: () -> Unit,
    onGuides: () -> Unit, onCaps: () -> Unit, onAbout: () -> Unit, onStates: () -> Unit
) {
    val s by vm.state.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    ScreenScaffold("Settings", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Viewfinder")
            SettingRow("Gridlines", s.guide.label, onClick = onGuides)
            ToggleRow("Histogram", "Live brightness graph (photo and pro modes)", UiFlag.HISTOGRAM in s.flags) { vm.toggleFlag(UiFlag.HISTOGRAM) }
            SectionLabel("Capture")
            ToggleRow("Shutter sound", "Plays a click when a photo is taken", s.shutterSound) { vm.setShutterSound(it) }
            ToggleRow("Volume buttons take photos", "Works while the camera screen is open", s.volumeShutter) { vm.setVolumeShutter(it) }
            ToggleRow("Haptics", "Vibrate on shutter press", UiFlag.HAPTICS in s.flags) { vm.toggleFlag(UiFlag.HAPTICS) }
            SectionLabel("Privacy")
            SettingRow("Location tagging", "Off. Fovea does not read your location.")
            SectionLabel("Updates")
            UpdateSection(updates)
            SectionLabel("Device")
            SettingRow("Device capabilities", "What this phone's camera reports", onClick = onCaps)
            SettingRow("UI states preview", "Loading, empty, permission and error layouts", onClick = onStates)
            SettingRow("Restore default settings", "Photos and presets are not deleted", onClick = { confirmReset = true })
            SettingRow("About", "Version and project info", onClick = onAbout)
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Restore default settings?") },
            text = { Text("Your photos, videos and saved presets will not be deleted.") },
            confirmButton = { TextButton({ vm.restoreDefaults(); confirmReset = false }) { Text("Restore") } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } },
            containerColor = Fovea.Graphite
        )
    }
}

@Composable
private fun UpdateSection(updates: UpdateManager) {
    val st by updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val installed = "Installed: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
    when (val u = st) {
        UpdateState.Idle -> SettingRow("Check for updates", installed, onClick = { scope.launch { updates.check() } })
        UpdateState.Checking -> SettingRow("Checking…", installed)
        UpdateState.UpToDate -> SettingRow("You're up to date", "$installed. Tap to check again.", onClick = { scope.launch { updates.check() } })
        is UpdateState.Available -> SettingRow(
            "Update available: ${u.name}", u.notes.take(160).ifBlank { "Tap to download." },
            onClick = { scope.launch { updates.download(u) } }
        ) { Text("Download", color = Fovea.Accent) }
        is UpdateState.Downloading -> SettingRow("Downloading… ${u.percent}%", "Your current version keeps working.")
        is UpdateState.Ready -> SettingRow(
            "Ready to install", "Android will ask you to confirm. If it opens a settings page, allow installs from Fovea, then come back and tap again.",
            onClick = { ctx.startActivity(updates.installIntent(u.file)) }
        ) { Text("Install", color = Fovea.Accent) }
        is UpdateState.Error -> SettingRow("Update problem", u.message, onClick = { scope.launch { updates.check() } }) {
            Text("Retry", color = Fovea.Accent)
        }
    }
}

@Composable
fun GuidesScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    ScreenScaffold("Gridlines", onBack) {
        Box(
            Modifier.padding(Fovea.ScreenPadding).fillMaxWidth().aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(10.dp)).background(Fovea.Graphite)
        ) { GuideOverlay(s.guide, Modifier.matchParentSize()) }
        LazyColumn {
            items(GuideType.values().toList()) { g ->
                SettingRow(g.label, g.blurb, onClick = { vm.setGuide(g) }) {
                    if (g == s.guide) Icon(Icons.Filled.Check, "Selected", tint = Fovea.Accent)
                }
            }
        }
    }
}

@Composable
fun PresetLibraryScreen(vm: CameraViewModel, onBack: () -> Unit, onCreate: () -> Unit, onEnterCode: () -> Unit) {
    val s by vm.state.collectAsState()
    var confirmDelete by remember { mutableStateOf<Preset?>(null) }
    ScreenScaffold("Presets", onBack) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            FoveaChip("Create", onCreate, selected = true)
            FoveaChip("Enter code", onEnterCode)
        }
        OutlinedTextField(
            value = s.query, onValueChange = { vm.setQuery(it) }, singleLine = true,
            placeholder = { Text("Search presets") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Fovea.ScreenPadding)
        )
        val list = s.visiblePresets()
        if (list.isEmpty()) {
            StateView(
                Icons.Filled.Star,
                if (s.presets.isEmpty()) "No presets yet" else "No matches",
                if (s.presets.isEmpty()) "Create a preset to save it here. Presets are kept in memory until Stage 5." else "Try a different search.",
                actionLabel = if (s.presets.isEmpty()) "Create preset" else null,
                onAction = if (s.presets.isEmpty()) onCreate else null
            )
        } else {
            LazyColumn {
                items(list, key = { it.id }) { p ->
                    SettingRow(p.name, "${p.category.label}${if (p.description.isNotEmpty()) " · " + p.description else ""}") {
                        IconButton(onClick = { vm.toggleFavorite(p.id) }, modifier = Modifier.size(Fovea.TouchTarget)) {
                            Icon(Icons.Filled.Star, if (p.favorite) "Unfavorite" else "Favorite", tint = if (p.favorite) Fovea.Accent else Fovea.Muted)
                        }
                        IconButton(onClick = { confirmDelete = p }, modifier = Modifier.size(Fovea.TouchTarget)) {
                            Icon(Icons.Filled.Delete, "Delete ${p.name}", tint = Fovea.Muted)
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete preset?") },
            text = { Text("\"${p.name}\" will be removed.") },
            confirmButton = { TextButton({ vm.deletePreset(p.id); confirmDelete = null }) { Text("Delete", color = Fovea.Danger) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
            containerColor = Fovea.Graphite
        )
    }
}

@Composable
fun PresetCreateScreen(vm: CameraViewModel, onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(PresetCategory.CAMERA) }
    ScreenScaffold("Create preset", onBack) {
        Column(Modifier.padding(Fovea.ScreenPadding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
            Text("CATEGORY", color = Fovea.Muted, style = MaterialTheme.typography.labelSmall)
            Row { PresetCategory.values().forEach { c -> FoveaChip(c.label, { cat = c }, selected = c == cat) } }
            Text(
                "This saves the name and category only. Capturing the current camera settings and generating a code arrive in Stage 5.",
                color = Fovea.Muted, style = MaterialTheme.typography.bodySmall
            )
            FoveaChip("Save locally", { if (vm.addPreset(name, desc, cat)) onBack() }, selected = true, enabled = name.isNotBlank())
        }
    }
}

@Composable
fun PresetCodeScreen(onBack: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val check = PresetCodeFormat.check(code)
    val (msg, ok) = when (check) {
        CodeCheck.EMPTY -> "Enter the 8-character code you received." to false
        CodeCheck.TOO_SHORT -> "Too short. Codes have ${PresetCodeFormat.LENGTH} characters." to false
        CodeCheck.TOO_LONG -> "Too long. Codes have ${PresetCodeFormat.LENGTH} characters." to false
        CodeCheck.BAD_CHARACTERS -> "Only letters A–Z and digits 0–9 are allowed." to false
        CodeCheck.WELL_FORMED -> "Format looks valid. Decoding and preview arrive in Stage 5." to true
    }
    ScreenScaffold("Enter preset code", onBack) {
        Column(Modifier.padding(Fovea.ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                code, { code = it.take(16) }, label = { Text("Preset code") }, singleLine = true,
                isError = check == CodeCheck.BAD_CHARACTERS || check == CodeCheck.TOO_LONG, modifier = Modifier.fillMaxWidth()
            )
            Text(msg, color = if (ok) Fovea.Accent else Fovea.Muted, style = MaterialTheme.typography.bodyMedium)
            FoveaChip("Preview settings", {}, selected = true, enabled = false)
            UiOnlyTag("FORMAT CHECK ONLY")
        }
    }
}

@Composable
fun GalleryScreen(engine: CameraEngine, onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val ctx = LocalContext.current
    val saved by engine.lastSaved.collectAsState()
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    LaunchedEffect(saved) { items = withContext(Dispatchers.IO) { MediaStoreHelper.recent(ctx) } }
    ScreenScaffold("Recent captures", onBack) {
        val list = items
        when {
            list == null -> LoadingView("Loading captures")
            list.isEmpty() -> StateView(Icons.Filled.Home, "No captures yet", "Photos and videos you take with Fovea appear here.")
            else -> LazyVerticalGrid(
                GridCells.Fixed(3), contentPadding = PaddingValues(Fovea.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(list, key = { it.uri.toString() }) { m ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable { onOpen(m) }) {
                        MediaThumb(m.uri, 320, Modifier.matchParentSize())
                        if (m.isVideo) Text("VIDEO", color = Fovea.Text, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ReviewScreen(item: MediaItem?, engine: CameraEngine, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    if (item == null) {
        ScreenScaffold("Review", onBack) { StateView(Icons.Filled.Warning, "Nothing selected", "Go back and pick a capture.") }
        return
    }
    ScreenScaffold("Review", onBack) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(Fovea.ScreenPadding).clip(RoundedCornerShape(10.dp)).background(Fovea.Graphite)) {
            MediaThumb(item.uri, 1440, Modifier.matchParentSize(), ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            FoveaChip(if (item.isVideo) "Play" else "Open", {
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            })
            FoveaChip("Share", {
                val send = Intent(Intent.ACTION_SEND).setType(if (item.isVideo) "video/mp4" else "image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM, item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, "Share"))
            })
            FoveaChip("Delete", { confirmDelete = true })
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this capture?") },
            text = { Text("It will be removed from your phone.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    if (MediaStoreHelper.delete(ctx, item.uri)) { engine.lastSaved.value = null; onBack() }
                }) { Text("Delete", color = Fovea.Danger) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
            containerColor = Fovea.Graphite
        )
    }
}

@Composable
fun CapabilitiesScreen(engine: CameraEngine, onBack: () -> Unit) {
    val c by engine.caps.collectAsState()
    fun yn(b: Boolean) = if (b) "Supported" else "Not supported"
    ScreenScaffold("Device capabilities", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                if (c.ready) "Read from the camera that is currently active. Switch cameras on the main screen to see the other one."
                else "Open the camera screen first so Fovea can read the camera.",
                color = Fovea.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(Fovea.ScreenPadding)
            )
            if (c.ready) {
                CapRow("Camera hardware level", c.hardwareLevel)
                CapRow("Rear / front camera", "${yn(c.hasBack)} / ${yn(c.hasFront)}")
                CapRow("Flash", yn(c.hasFlash))
                CapRow("Zoom range", "%.1fx – %.1fx".format(c.minZoom, c.maxZoom))
                CapRow("Exposure compensation", if (c.evSupported) "%+.1f to %+.1f EV".format(c.evMin, c.evMax) else "Not supported")
                CapRow("Manual ISO and shutter", if (c.isoSupported) "ISO ${c.isoMin}–${c.isoMax}" else "Not supported")
                CapRow("Manual focus", if (c.manualFocus) "Supported" else "Not supported")
                CapRow("White balance presets", c.awbModes.joinToString { it.label })
                CapRow("RAW (hardware)", if (c.rawCapable) "Hardware supports it. DNG capture is not built yet." else "Not supported")
            }
        }
    }
}

@Composable
private fun CapRow(title: String, value: String) {
    SettingRow(title) { Text(value, color = Fovea.Muted, style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    ScreenScaffold("About", onBack) {
        SettingRow("Fovea", "A camera-first Android app")
        SettingRow("Version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        SettingRow("Privacy", "No accounts or analytics. Internet is used only to check for updates.")
    }
}

@Composable
fun StatesPreviewScreen(onBack: () -> Unit) {
    ScreenScaffold("UI states preview", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LoadingView("Loading")
            HorizontalDivider(color = Fovea.Line)
            StateView(Icons.Filled.Home, "Empty", "Nothing here yet.")
            HorizontalDivider(color = Fovea.Line)
            StateView(Icons.Filled.Lock, "Camera permission needed", "Allow camera access to use the viewfinder.", actionLabel = "Open settings", onAction = {})
            HorizontalDivider(color = Fovea.Line)
            StateView(Icons.Filled.Warning, "Something went wrong", "The camera could not start. Try again.", actionLabel = "Retry", onAction = {})
        }
    }
}
