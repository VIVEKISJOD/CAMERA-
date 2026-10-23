package app.fovea.camera.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.fovea.camera.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val build: Int, val name: String, val notes: String, val url: String, val size: Long) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

object UpdateParser {
    /** Release tags look like "build-12". Returns 12, or null for anything else. */
    fun buildNumber(tag: String): Int? = tag.removePrefix("build-").toIntOrNull()?.takeIf { tag.startsWith("build-") }
    fun isNewer(remoteBuild: Int, installedBuild: Int) = remoteBuild > installedBuild
}

/** Checks GitHub Releases, downloads the APK and hands it to Android's installer. Independent of camera code. */
class UpdateManager(private val context: Context) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    // The work runs in this manager's own scope, so leaving the Settings screen never cancels a check or
    // download and never leaves the state stuck on "Checking" or "Downloading".
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun busy() = _state.value is UpdateState.Checking || _state.value is UpdateState.Downloading
    fun startCheck() { if (!busy()) scope.launch { check() } }
    fun startDownload(a: UpdateState.Available) { if (!busy()) scope.launch { download(a) } }

    suspend fun check() {
        _state.value = UpdateState.Checking
        _state.value = withContext(Dispatchers.IO) {
            try { fetch() } catch (e: Exception) {
                UpdateState.Error("Could not check for updates: ${e.message ?: "no connection"}")
            }
        }
    }

    private fun fetch(): UpdateState {
        val repo = BuildConfig.GITHUB_REPO
        if (repo.isBlank()) return UpdateState.Error("This build was not made by the GitHub workflow, so it doesn't know where to look for updates.")
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Fovea-Updater")
        try {
            if (conn.responseCode == 404) return UpdateState.Error("No release found. The repository must be public and have at least one build.")
            if (conn.responseCode != 200) return UpdateState.Error("GitHub answered with code ${conn.responseCode}.")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = UpdateParser.buildNumber(json.optString("tag_name")) ?: return UpdateState.Error("Latest release has an unexpected version tag.")
            if (!UpdateParser.isNewer(build, BuildConfig.VERSION_CODE)) return UpdateState.UpToDate
            val assets = json.optJSONArray("assets")
            var url: String? = null
            var size = 0L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); size = a.optLong("size"); break }
            }
            if (url == null) return UpdateState.Error("The latest release has no APK file.")
            return UpdateState.Available(build, json.optString("name", "build $build"), json.optString("body"), url, size)
        } finally { conn.disconnect() }
    }

    suspend fun download(a: UpdateState.Available) {
        _state.value = UpdateState.Downloading(0)
        _state.value = withContext(Dispatchers.IO) { downloadWithRetry(a) }
    }

    private fun downloadWithRetry(a: UpdateState.Available): UpdateState {
        var last: IOException? = null
        for (attempt in 1..3) {
            try { return downloadOnce(a) } catch (e: IOException) { last = e }
        }
        return UpdateState.Error("Download failed (${last?.message}). Check your connection and free storage, then try again. Your installed version still works.")
    }

    private fun downloadOnce(a: UpdateState.Available): UpdateState {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "fovea-update.apk")
        if (out.exists()) out.delete()
        val conn = URL(a.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Fovea-Updater")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (a.size > 0) _state.value = UpdateState.Downloading((done * 100 / a.size).toInt().coerceIn(0, 100))
                    }
                }
            }
        } catch (e: IOException) {
            out.delete()
            throw e
        } finally { conn.disconnect() }
        if (a.size > 0 && out.length() != a.size) { out.delete(); throw IOException("incomplete file") }
        val info = context.packageManager.getPackageArchiveInfo(out.absolutePath, 0)
        if (info?.packageName != context.packageName) { out.delete(); return UpdateState.Error("The downloaded file is not a valid Fovea update.") }
        return UpdateState.Ready(out)
    }

    /** Android always asks the user to confirm the install. */
    fun installIntent(file: File): Intent {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
