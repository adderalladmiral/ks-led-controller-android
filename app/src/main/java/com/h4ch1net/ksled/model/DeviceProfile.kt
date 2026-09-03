package com.h4ch1net.ksled.model

import java.util.UUID

/**
 * Direct port of DEVICE_UUIDS / DEVICE_MAPPINGS from led_control.py and led_menu.py.
 * Short UUIDs are expanded using the same 16-bit BLE base UUID template the
 * original Python tool used: 0000XXXX-0000-1000-8000-00805f9b34fb
 */
enum class LampFamily { FLOOR, CEILING }

data class DeviceProfile(
    val prefix: String,
    val serviceShort: String,
    val writeShort: String,
    val family: LampFamily
) {
    val serviceUuid: UUID get() = shortToUuid(serviceShort)
    val writeUuid: UUID get() = shortToUuid(writeShort)

    companion object {
        private const val UUID_TEMPLATE = "0000%s-0000-1000-8000-00805f9b34fb"

        fun shortToUuid(short: String): UUID =
            UUID.fromString(String.format(UUID_TEMPLATE, short))

        // Ported verbatim from DEVICE_UUIDS in led_control.py (union of both scripts' mappings)
        val PROFILES: List<DeviceProfile> = listOf(
            DeviceProfile("KS03-", "FFF0", "FFF3", LampFamily.CEILING),
            DeviceProfile("KS04-", "FFF0", "FFF3", LampFamily.CEILING),
            DeviceProfile("KS03~", "AFD0", "AFD1", LampFamily.FLOOR),
            DeviceProfile("KS01-", "AE00", "AE01", LampFamily.CEILING),
            DeviceProfile("KS02-", "AE00", "AE01", LampFamily.CEILING),
            DeviceProfile("KS04~", "AE00", "AE10", LampFamily.FLOOR),
            DeviceProfile("KS05-", "AE00", "AE02", LampFamily.CEILING),
            DeviceProfile("KS07-", "AE00", "AE10", LampFamily.CEILING),
            DeviceProfile("KS08-", "AE00", "AE10", LampFamily.CEILING),
            DeviceProfile("KS09-", "AE00", "AE10", LampFamily.CEILING),
            DeviceProfile("KS10-", "AE00", "AE10", LampFamily.CEILING),
            DeviceProfile("KS11-", "AE00", "AE10", LampFamily.CEILING),
            DeviceProfile("KS12-", "AE00", "AE10", LampFamily.CEILING),
            DeviceProfile("KS13-", "AE00", "AE10", LampFamily.CEILING),
            DeviceProfile("KS15~", "AFD0", "AFD3", LampFamily.FLOOR)
        )

        /** Alternate write characteristic fallback, mirroring the AFD3<->FFF3 swap in led_control.py */
        fun alternateWriteShort(writeShort: String): String? = when (writeShort.uppercase()) {
            "AFD3" -> "FFF3"
            "FFF3" -> "AFD3"
            else -> null
        }

        /** Longest-prefix match against a scanned device's advertised name. Case-insensitive. */
        fun matchByName(name: String?): DeviceProfile? {
            if (name.isNullOrEmpty()) return null
            val upper = name.uppercase()
            return PROFILES
                .filter { upper.startsWith(it.prefix.uppercase()) }
                .maxByOrNull { it.prefix.length }
        }

        fun byPrefix(prefix: String): DeviceProfile? = PROFILES.find { it.prefix == prefix }
    }
}
