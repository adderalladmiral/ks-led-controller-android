package com.h4ch1net.ksled.data

import android.content.Context
import com.h4ch1net.ksled.model.ColorPreset
import org.json.JSONObject

/**
 * Persists user color presets, mirroring load_presets()/save_presets() in
 * led_menu.py which used a ~/.ks_led_presets.json file. Here we use a
 * SharedPreferences-backed JSON blob since Android apps don't have a
 * writable home directory in the same sense.
 */
class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("ks_led_presets", Context.MODE_PRIVATE)
    private val key = "presets_json"

    fun load(): List<ColorPreset> {
        val raw = prefs.getString(key, null) ?: return ColorPreset.DEFAULTS
        return try {
            val obj = JSONObject(raw)
            val list = mutableListOf<ColorPreset>()
            for (name in obj.keys()) {
                val rgb = obj.getJSONObject(name)
                list.add(ColorPreset(name, rgb.getInt("r"), rgb.getInt("g"), rgb.getInt("b")))
            }
            if (list.isEmpty()) ColorPreset.DEFAULTS else list
        } catch (e: Exception) {
            ColorPreset.DEFAULTS
        }
    }

    fun save(presets: List<ColorPreset>) {
        val obj = JSONObject()
        for (p in presets) {
            val rgb = JSONObject()
            rgb.put("r", p.r)
            rgb.put("g", p.g)
            rgb.put("b", p.b)
            obj.put(p.name, rgb)
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    fun add(preset: ColorPreset) {
        val current = load().filterNot { it.name == preset.name }
        save(current + preset)
    }

    fun delete(name: String) {
        save(load().filterNot { it.name == name })
    }

    fun resetToDefaults() {
        save(ColorPreset.DEFAULTS)
    }
}
