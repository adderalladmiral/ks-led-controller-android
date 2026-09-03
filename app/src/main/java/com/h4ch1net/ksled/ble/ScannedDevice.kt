package com.h4ch1net.ksled.ble

import com.h4ch1net.ksled.model.DeviceProfile

data class ScannedDevice(
    val address: String,
    val name: String,
    val profile: DeviceProfile,
    val rssi: Int
)
