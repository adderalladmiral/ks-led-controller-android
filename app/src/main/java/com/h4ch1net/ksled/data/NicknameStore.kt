package com.h4ch1net.ksled.data

import android.content.Context
import org.json.JSONObject

/**
 * Persists device nicknames keyed by BLE MAC address, mirroring
 * load_devices()/save_devices() in led_menu.py (~/.ks_led_devices.json).
 */
class NicknameStore(context: Context) {
    private val prefs = context.getSharedPreferences("ks_led_devices", Context.MODE_PRIVATE)
    private val key = "nicknames_json"

    fun all(): Map<String, String> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun get(address: String): String? = all()[address]

    fun set(address: String, nickname: String) {
        val obj = JSONObject(all())
        obj.put(address, nickname)
        prefs.edit().putString(key, obj.toString()).apply()
    }

    fun clear(address: String) {
        val current = all().toMutableMap()
        current.remove(address)
        val obj = JSONObject()
        current.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    fun displayName(address: String, advertisedName: String): String {
        val nick = get(address)
        return if (!nick.isNullOrEmpty()) "$nick ($advertisedName)" else advertisedName
    }
}
