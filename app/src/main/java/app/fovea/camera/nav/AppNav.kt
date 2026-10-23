package app.fovea.camera.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.fovea.camera.camera.CameraEngine
import app.fovea.camera.camera.MediaItem
import app.fovea.camera.settings.SettingsStore
import app.fovea.camera.state.CameraViewModel
import app.fovea.camera.state.toPersisted
import app.fovea.camera.ui.camera.CameraScreen
import app.fovea.camera.ui.screens.*
import app.fovea.camera.ui.theme.LocalUiScale
import app.fovea.camera.update.UpdateManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

object Routes {
    const val CAMERA = "camera"
    const val SETTINGS = "settings"
    const val GUIDES = "guides"
    const val PRESETS = "presets"
    const val PRESET_CREATE = "preset_create"
    const val PRESET_CODE = "preset_code"
    const val GALLERY = "gallery"
    const val REVIEW = "review"
    const val CAPS = "capabilities"
    const val ABOUT = "about"
    const val STATES = "states"
}

@Composable
fun AppNav(engine: CameraEngine, vm: CameraViewModel = viewModel()) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx) }
    val updates = remember { UpdateManager(ctx.applicationContext) }
    remember { vm.applyPersisted(store.load(), applyStart = true); true }
    val ui by vm.state.collectAsState()
    LaunchedEffect(Unit) {
        vm.state.map { it.toPersisted() }.distinctUntilChanged().drop(1).collect { store.save(it) }
    }

    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    var reviewItem by remember { mutableStateOf<MediaItem?>(null) }

    val inMs = if (ui.reduceMotion) 0 else 180
    val outMs = if (ui.reduceMotion) 0 else 120
    CompositionLocalProvider(LocalUiScale provides (if (ui.largeText) 1.15f else 1f)) {
    NavHost(
        navController = nav, startDestination = Routes.CAMERA,
        enterTransition = { fadeIn(tween(inMs)) }, exitTransition = { fadeOut(tween(outMs)) },
        popEnterTransition = { fadeIn(tween(inMs)) }, popExitTransition = { fadeOut(tween(outMs)) }
    ) {
        composable(Routes.CAMERA) {
            CameraScreen(
                vm, engine,
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onGallery = { nav.navigate(Routes.GALLERY) },
                onPresets = { nav.navigate(Routes.PRESETS) },
                onGuides = { nav.navigate(Routes.GUIDES) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm, updates, back, { nav.navigate(Routes.GUIDES) }, { nav.navigate(Routes.CAPS) }, { nav.navigate(Routes.ABOUT) }, { nav.navigate(Routes.STATES) })
        }
        composable(Routes.GUIDES) { GuidesScreen(vm, back) }
        composable(Routes.PRESETS) { PresetLibraryScreen(vm, back, { nav.navigate(Routes.PRESET_CREATE) }, { nav.navigate(Routes.PRESET_CODE) }) }
        composable(Routes.PRESET_CREATE) { PresetCreateScreen(vm, back) }
        composable(Routes.PRESET_CODE) { PresetCodeScreen(back) }
        composable(Routes.GALLERY) { GalleryScreen(engine, back) { reviewItem = it; nav.navigate(Routes.REVIEW) } }
        composable(Routes.REVIEW) { ReviewScreen(reviewItem, engine, back) }
        composable(Routes.CAPS) { CapabilitiesScreen(engine, back) }
        composable(Routes.ABOUT) { AboutScreen(back) }
        composable(Routes.STATES) { StatesPreviewScreen(back) }
    }
    }
}
