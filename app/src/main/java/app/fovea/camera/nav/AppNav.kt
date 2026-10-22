package app.fovea.camera.nav

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.fovea.camera.state.CameraViewModel
import app.fovea.camera.ui.camera.CameraScreen
import app.fovea.camera.ui.screens.*

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
fun AppNav(vm: CameraViewModel = viewModel()) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(
        navController = nav, startDestination = Routes.CAMERA,
        enterTransition = { fadeIn(tween(180)) }, exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(180)) }, popExitTransition = { fadeOut(tween(120)) }
    ) {
        composable(Routes.CAMERA) {
            CameraScreen(
                vm,
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onGallery = { nav.navigate(Routes.GALLERY) },
                onPresets = { nav.navigate(Routes.PRESETS) },
                onGuides = { nav.navigate(Routes.GUIDES) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm, back, { nav.navigate(Routes.GUIDES) }, { nav.navigate(Routes.CAPS) }, { nav.navigate(Routes.ABOUT) }, { nav.navigate(Routes.STATES) })
        }
        composable(Routes.GUIDES) { GuidesScreen(vm, back) }
        composable(Routes.PRESETS) { PresetLibraryScreen(vm, back, { nav.navigate(Routes.PRESET_CREATE) }, { nav.navigate(Routes.PRESET_CODE) }) }
        composable(Routes.PRESET_CREATE) { PresetCreateScreen(vm, back) }
        composable(Routes.PRESET_CODE) { PresetCodeScreen(back) }
        composable(Routes.GALLERY) { GalleryScreen(back) { nav.navigate(Routes.REVIEW) } }
        composable(Routes.REVIEW) { ReviewScreen(back) }
        composable(Routes.CAPS) { CapabilitiesScreen(back) }
        composable(Routes.ABOUT) { AboutScreen(back) }
        composable(Routes.STATES) { StatesPreviewScreen(back) }
    }
}
