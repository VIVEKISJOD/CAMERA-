package app.fovea.camera

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.fovea.camera.camera.CameraEngine
import app.fovea.camera.nav.AppNav
import app.fovea.camera.settings.SettingsStore
import app.fovea.camera.ui.theme.FoveaTheme

class MainActivity : ComponentActivity() {
    private lateinit var engine: CameraEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = CameraEngine(applicationContext)
        enableEdgeToEdge()
        setContent { FoveaTheme { AppNav(engine) } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isVolume = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (isVolume && engine.cameraScreenVisible && SettingsStore(this).load().volumeShutter) {
            if (event.repeatCount == 0) engine.volumeEvents.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}
