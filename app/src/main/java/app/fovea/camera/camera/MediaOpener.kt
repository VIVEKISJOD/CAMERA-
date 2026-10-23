package app.fovea.camera.camera

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens a photo or video in the phone's Gallery, or Google Photos if there is no Gallery. */
object MediaOpener {
    // Phone makers' own Gallery apps first, Google Photos last. The first one that exists is used.
    private val APPS = listOf(
        "com.miui.gallery",
        "com.sec.android.gallery3d",
        "com.coloros.gallery3d",
        "com.oplus.gallery",
        "com.oneplus.gallery",
        "com.vivo.gallery",
        "com.huawei.photos",
        "com.motorola.motgallery",
        "com.nothing.gallery",
        "com.google.android.gallery3d",
        "com.android.gallery3d",
        "com.google.android.apps.photos"
    )

    fun open(context: Context, uri: Uri, isVideo: Boolean): Boolean {
        val type = if (isVideo) "video/*" else "image/*"
        fun view() = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (pkg in APPS) {
            try {
                context.startActivity(view().setPackage(pkg))
                return true
            } catch (e: Exception) {
                // That app is not on this phone: try the next one.
            }
        }
        return try {
            context.startActivity(view())
            true
        } catch (e: Exception) {
            false
        }
    }
}
