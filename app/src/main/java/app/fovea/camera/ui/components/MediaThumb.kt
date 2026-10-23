package app.fovea.camera.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.fovea.camera.camera.MediaStoreHelper
import app.fovea.camera.ui.theme.Fovea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads a real thumbnail of a saved photo or video from MediaStore. */
@Composable
fun MediaThumb(uri: Uri, size: Int, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) { bmp = withContext(Dispatchers.IO) { MediaStoreHelper.thumb(ctx, uri, size) } }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = scale)
    else Box(modifier.background(Fovea.Surface2))
}
