package app.fovea.camera.camera

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

data class MediaItem(val uri: Uri, val isVideo: Boolean, val dateAdded: Long)

/** Reads and deletes only the files Fovea saved (Pictures/Fovea and Movies/Fovea). */
object MediaStoreHelper {
    fun recent(context: Context, limit: Int = 90): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        fun query(base: Uri, video: Boolean, folder: String) {
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
            try {
                context.contentResolver.query(
                    base, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$folder/Fovea%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    var n = 0
                    while (c.moveToNext() && n < limit) {
                        out += MediaItem(ContentUris.withAppendedId(base, c.getLong(idI)), video, c.getLong(dI))
                        n++
                    }
                }
            } catch (e: Exception) { /* leave list as is */ }
        }
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, "Pictures")
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, "Movies")
        return out.sortedByDescending { it.dateAdded }.take(limit)
    }

    fun thumb(context: Context, uri: Uri, size: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(uri, Size(size, size), null)
    } catch (e: Exception) { null }

    fun delete(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) { false }
}
