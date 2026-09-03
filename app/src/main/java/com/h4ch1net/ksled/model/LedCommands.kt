package com.h4ch1net.ksled.model

/**
 * Byte-for-byte port of the command builders in led_control.py / led_menu.py.
 * These hex strings were reverse-engineered by the original project from the
 * decompiled KS Android APK (CmdFloor.getTopOn, ceiling 7E07... frames, etc).
 */
object LedCommands {

    /** ON: 5BF001B5   OFF: 5B0F01B5   (CmdFloor.getTopOn equivalent) */
    fun onOff(isOn: Boolean): ByteArray =
        hexToBytes(if (isOn) "5BF001B5" else "5B0F01B5")

    /**
     * RGB color command.
     * Floor lamps (KS03~ style):   5A0001RRGGBB00BB00A5   (BB = brightness byte)
     * Ceiling lights (KS03- etc.): 7E070503RRGGBB00EF
     */
    fun color(r: Int, g: Int, b: Int, family: LampFamily, brightness: Int = 255): ByteArray {
        val rgbHex = hex2(r) + hex2(g) + hex2(b)
        val cmdStr = if (family == LampFamily.FLOOR) {
            "5A0001$rgbHex" + "00" + hex2(brightness) + "00A5"
        } else {
            "7E070503$rgbHex" + "00EF"
        }
        return hexToBytes(cmdStr)
    }

    /**
     * White-mode brightness-only command, floor lamps only:
     * 5A000200000000BB00A5
     */
    fun brightness(value: Int): ByteArray =
        hexToBytes("5A000200000000" + hex2(value) + "00A5")

    /**
     * Rhythm/mic-reactive mode select command, reverse-engineered from the
     * Keepsmile KS03~ protocol (device spoofing via ESP32):
     *   5A 0A [mic_type] [type] [speed] 01 A5
     * mic_type: 0x0F = phone mic, 0xF0 = device's own built-in mic
     * type: 1-4 (reactive pattern variant)
     * speed: 1 (fastest) - 8 (slowest)
     *
     * Selecting MicSource.PHONE puts the light into "listen for colors pushed
     * by the app" mode; the app is then expected to stream ColorCustom_t
     * (see LedCommands.color()) frames repeatedly, which is what
     * MicAnalyzer + the music-sync loop in DeviceControlActivity do.
     */
    enum class MicSource(val byte: Int) { PHONE(0x0F), DEVICE(0xF0) }

    fun rhythm(source: MicSource, type: Int = 1, speed: Int = 4): ByteArray {
        val cmdStr = "5A0A" +
            hex2(source.byte) +
            hex2(type.coerceIn(1, 4)) +
            hex2(speed.coerceIn(1, 8)) +
            "01A5"
        return hexToBytes(cmdStr)
    }

    private fun hex2(v: Int): String = String.format("%02X", v.coerceIn(0, 255))

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = ((Character.digit(clean[idx], 16) shl 4) +
                    Character.digit(clean[idx + 1], 16)).toByte()
        }
        return out
    }
}
