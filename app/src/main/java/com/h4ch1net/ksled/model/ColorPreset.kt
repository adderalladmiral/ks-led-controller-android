package com.h4ch1net.ksled.model

data class ColorPreset(
    val name: String,
    val r: Int,
    val g: Int,
    val b: Int
) {
    companion object {
        // Ported verbatim from DEFAULT_PRESETS in led_menu.py
        val DEFAULTS: List<ColorPreset> = listOf(
            ColorPreset("Warm White", 255, 147, 41),
            ColorPreset("Cool White", 201, 226, 255),
            ColorPreset("Daylight", 255, 250, 244),
            ColorPreset("Red", 255, 0, 0),
            ColorPreset("Green", 0, 255, 0),
            ColorPreset("Blue", 0, 0, 255),
            ColorPreset("Purple", 128, 0, 128),
            ColorPreset("Cyan", 0, 255, 255),
            ColorPreset("Yellow", 255, 255, 0),
            ColorPreset("Orange", 255, 165, 0)
        )
    }
}
