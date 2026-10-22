package app.fovea.camera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.fovea.camera.ui.theme.Fovea

/**
 * Integration boundary between the UI and the camera engine.
 * Stage 2 adds a CameraX-backed host; the rest of the UI does not change.
 */
interface CameraPreviewHost {
    @Composable
    fun Preview(modifier: Modifier)
}

/** Stage 1 stand-in. Clearly labelled; it never pretends to be a live feed. */
object PlaceholderPreviewHost : CameraPreviewHost {
    @Composable
    override fun Preview(modifier: Modifier) {
        Box(modifier.background(Fovea.Graphite), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PREVIEW PLACEHOLDER", color = Fovea.Muted, style = MaterialTheme.typography.labelSmall)
                Text("No camera feed in Stage 1", color = Fovea.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}
