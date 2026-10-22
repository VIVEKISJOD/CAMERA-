package app.fovea.camera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import app.fovea.camera.state.*
import app.fovea.camera.ui.camera.GuideOverlay
import app.fovea.camera.ui.components.*
import app.fovea.camera.ui.theme.Fovea

@Composable
fun SettingsScreen(vm: CameraViewModel, onBack: () -> Unit, onGuides: () -> Unit, onCaps: () -> Unit, onAbout: () -> Unit, onStates: () -> Unit) {
    val s by vm.state.collectAsState()
    ScreenScaffold("Settings", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Viewfinder")
            SettingRow("Composition guides", s.guide.label, onClick = onGuides)
            ToggleRow("Histogram overlay", "Preference stored in this session only", UiFlag.HISTOGRAM in s.flags) { vm.toggleFlag(UiFlag.HISTOGRAM) }
            ToggleRow("Level overlay", "Preference stored in this session only", UiFlag.LEVEL in s.flags) { vm.toggleFlag(UiFlag.LEVEL) }
            SectionLabel("Feedback")
            ToggleRow("Haptics", "Preference stored in this session only", UiFlag.HAPTICS in s.flags) { vm.toggleFlag(UiFlag.HAPTICS) }
            SectionLabel("Privacy")
            SettingRow("Location tagging", "Off. Settings are persisted and applied from Stage 3.") { UiOnlyTag("OFF") }
            SectionLabel("Device")
            SettingRow("Device capabilities", "What this phone's camera supports", onClick = onCaps)
            SettingRow("UI states preview", "Loading, empty, permission and error layouts", onClick = onStates)
            SettingRow("About", "Version and project info", onClick = onAbout)
        }
    }
}

@Composable
fun GuidesScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    ScreenScaffold("Composition guides", onBack) {
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
fun GalleryScreen(onBack: () -> Unit, onOpen: () -> Unit) {
    var sample by remember { mutableStateOf(false) }
    ScreenScaffold("Recent captures", onBack, actions = {
        FoveaChip(if (sample) "Hide sample" else "Show layout", { sample = !sample })
    }) {
        if (!sample) {
            StateView(Icons.Filled.Home, "No captures yet", "Photos and videos you take will appear here. Capture arrives in Stage 2.")
        } else {
            Box(Modifier.padding(horizontal = Fovea.ScreenPadding)) { UiOnlyTag("SAMPLE LAYOUT — NOT REAL PHOTOS") }
            LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(Fovea.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items((1..12).toList()) { n ->
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Fovea.Surface2).clickable(onClick = onOpen),
                        contentAlignment = Alignment.Center
                    ) { Text("$n", color = Fovea.Muted) }
                }
            }
        }
    }
}

@Composable
fun ReviewScreen(onBack: () -> Unit) {
    ScreenScaffold("Review", onBack) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(Fovea.ScreenPadding).clip(RoundedCornerShape(10.dp)).background(Fovea.Graphite), contentAlignment = Alignment.Center) {
            Text("Photo appears here", color = Fovea.Muted)
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            FoveaChip("Share", {}, enabled = false)
            FoveaChip("Info", {}, enabled = false)
            FoveaChip("Delete", {}, enabled = false)
        }
    }
}

@Composable
fun CapabilitiesScreen(onBack: () -> Unit) {
    val rows = listOf("Lenses", "ISO range", "Shutter range", "Manual focus", "White balance", "RAW / DNG", "Video stabilization", "Flash / torch")
    ScreenScaffold("Device capabilities", onBack) {
        Text(
            "Capabilities are read from the real camera in Stage 2. Nothing below is claimed as supported.",
            color = Fovea.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(Fovea.ScreenPadding)
        )
        LazyColumn { items(rows) { SettingRow(it) { Text("Not checked yet", color = Fovea.Muted, style = MaterialTheme.typography.bodySmall) } } }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    ScreenScaffold("About", onBack) {
        SettingRow("Fovea", "A camera-first Android app")
        SettingRow("Version", "0.1.0 (Stage 1: UI foundation)")
        SettingRow("Privacy", "No accounts, analytics or internet required")
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
