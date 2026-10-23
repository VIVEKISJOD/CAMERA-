package app.fovea.camera.settings

import android.content.Context
import app.fovea.camera.state.*

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("fovea_settings", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        runCatching { enumValueOf<E>(p.getString(key, default.name)!!) }.getOrDefault(default)

    fun load(): PersistedSettings {
        val d = PersistedSettings()
        return PersistedSettings(
            guide = enumOf("guide", d.guide),
            aspect = enumOf("aspect", d.aspect),
            histogram = p.getBoolean("histogram", d.histogram),
            haptics = p.getBoolean("haptics", d.haptics),
            shutterSound = p.getBoolean("shutterSound", d.shutterSound),
            volumeShutter = p.getBoolean("volumeShutter", d.volumeShutter),
            look = enumOf("look", d.look),
            startMode = enumOf("startMode", d.startMode),
            startCamera = enumOf("startCamera", d.startCamera),
            lastMode = enumOf("lastMode", d.lastMode),
            lastFront = p.getBoolean("lastFront", d.lastFront),
            jpegQuality = enumOf("jpegQuality", d.jpegQuality),
            fastCapture = p.getBoolean("fastCapture", d.fastCapture),
            videoQuality = enumOf("videoQuality", d.videoQuality),
            recordAudio = p.getBoolean("recordAudio", d.recordAudio),
            guideColor = enumOf("guideColor", d.guideColor),
            guideOpacity = p.getFloat("guideOpacity", d.guideOpacity).coerceIn(0.2f, 1f),
            guideThickness = p.getFloat("guideThickness", d.guideThickness).coerceIn(1f, 4f),
            reduceMotion = p.getBoolean("reduceMotion", d.reduceMotion),
            largeText = p.getBoolean("largeText", d.largeText)
        )
    }

    fun save(s: PersistedSettings) {
        p.edit()
            .putString("guide", s.guide.name)
            .putString("aspect", s.aspect.name)
            .putBoolean("histogram", s.histogram)
            .putBoolean("haptics", s.haptics)
            .putBoolean("shutterSound", s.shutterSound)
            .putBoolean("volumeShutter", s.volumeShutter)
            .putString("look", s.look.name)
            .putString("startMode", s.startMode.name)
            .putString("startCamera", s.startCamera.name)
            .putString("lastMode", s.lastMode.name)
            .putBoolean("lastFront", s.lastFront)
            .putString("jpegQuality", s.jpegQuality.name)
            .putBoolean("fastCapture", s.fastCapture)
            .putString("videoQuality", s.videoQuality.name)
            .putBoolean("recordAudio", s.recordAudio)
            .putString("guideColor", s.guideColor.name)
            .putFloat("guideOpacity", s.guideOpacity)
            .putFloat("guideThickness", s.guideThickness)
            .putBoolean("reduceMotion", s.reduceMotion)
            .putBoolean("largeText", s.largeText)
            .apply()
    }
}
