package app.fovea.camera.settings

import android.content.Context
import app.fovea.camera.state.AspectOption
import app.fovea.camera.state.GuideType
import app.fovea.camera.state.Look
import app.fovea.camera.state.PersistedSettings

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("fovea_settings", Context.MODE_PRIVATE)

    fun load(): PersistedSettings {
        val d = PersistedSettings()
        return PersistedSettings(
            guide = runCatching { GuideType.valueOf(p.getString("guide", d.guide.name)!!) }.getOrDefault(d.guide),
            aspect = runCatching { AspectOption.valueOf(p.getString("aspect", d.aspect.name)!!) }.getOrDefault(d.aspect),
            histogram = p.getBoolean("histogram", d.histogram),
            haptics = p.getBoolean("haptics", d.haptics),
            shutterSound = p.getBoolean("shutterSound", d.shutterSound),
            volumeShutter = p.getBoolean("volumeShutter", d.volumeShutter),
            look = runCatching { Look.valueOf(p.getString("look", d.look.name)!!) }.getOrDefault(d.look)
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
            .apply()
    }
}
