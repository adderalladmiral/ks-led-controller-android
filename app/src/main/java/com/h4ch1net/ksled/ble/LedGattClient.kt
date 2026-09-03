package com.h4ch1net.ksled.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.h4ch1net.ksled.model.DeviceProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Ports write_command() from led_control.py/led_menu.py, including its
 * fallback chain:
 *   1. write without response (preferred by KS devices)
 *   2. write with response
 *   3. alternate write characteristic (AFD3 <-> FFF3 swap), no response then with response
 *
 * Also ports send_command()'s "is_color" path from led_menu.py, which keeps
 * one GATT connection open to send an ON command followed by the color
 * command with a short settle delay, matching the original app's behavior
 * of always waking the light before pushing a color.
 */
class LedGattClient(private val context: Context) {

    sealed class Result {
        data class Success(val log: List<String>) : Result()
        data class Failure(val message: String, val log: List<String>) : Result()
    }

    private val log = mutableListOf<String>()
    private fun logLine(s: String) { log.add(s) }

    @SuppressLint("MissingPermission")
    suspend fun writeSequence(
        device: BluetoothDevice,
        profile: DeviceProfile,
        payloads: List<ByteArray>,
        interPayloadDelayMs: Long = 500L
    ): Result {
        log.clear()
        val connected = CompletableDeferred<Boolean>()
        val servicesReady = CompletableDeferred<Boolean>()
        var gatt: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    logLine("Connected to ${device.address}")
                    if (!connected.isCompleted) connected.complete(true)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    logLine("Disconnected")
                    if (!connected.isCompleted) connected.complete(false)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (!servicesReady.isCompleted) servicesReady.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        gatt = device.connectGatt(context, false, callback)

        val didConnect = withTimeoutOrNull(10_000) { connected.await() } ?: false
        if (!didConnect) {
            logLine("Failed to connect (timeout)")
            gatt.close()
            return Result.Failure("Failed to connect to device", log.toList())
        }

        // Small settle delay after connecting, mirroring asyncio.sleep(0.3) in Python
        delay(300)

        val discovered = withTimeoutOrNull(8_000) { servicesReady.await() } ?: false
        if (!discovered) {
            logLine("Service discovery failed/timed out")
            safeDisconnect(gatt)
            return Result.Failure("Service discovery failed", log.toList())
        }

        try {
            for (payload in payloads) {
                val ok = writeWithFallback(gatt, profile, payload)
                if (!ok) {
                    safeDisconnect(gatt)
                    return Result.Failure("All write attempts failed", log.toList())
                }
                // Give device time to process command before the next write / disconnect,
                // mirroring asyncio.sleep(0.2) / asyncio.sleep(0.5) between ON and color in Python
                delay(interPayloadDelayMs)
            }
        } finally {
            safeDisconnect(gatt)
        }

        return Result.Success(log.toList())
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeWithFallback(
        gatt: BluetoothGatt,
        profile: DeviceProfile,
        payload: ByteArray
    ): Boolean {
        val service = gatt.getService(profile.serviceUuid)
        val primaryChar = service?.getCharacteristic(profile.writeUuid)

        if (primaryChar != null) {
            if (writeChar(gatt, primaryChar, payload, noResponse = true)) {
                logLine("Wrote to ${profile.writeUuid} (no response)")
                return true
            }
            if (writeChar(gatt, primaryChar, payload, noResponse = false)) {
                logLine("Wrote to ${profile.writeUuid} (with response)")
                return true
            }
        } else {
            logLine("Primary characteristic ${profile.writeUuid} not found on device")
        }

        // Alternate characteristic fallback (AFD3 <-> FFF3), same as Python's alt_char_short logic
        val altShort = DeviceProfile.alternateWriteShort(profile.writeShort)
        if (altShort != null) {
            val altUuid = DeviceProfile.shortToUuid(altShort)
            val altChar = findCharacteristicAnyService(gatt, altUuid)
            if (altChar != null) {
                if (writeChar(gatt, altChar, payload, noResponse = true)) {
                    logLine("Wrote to alternate $altUuid (no response)")
                    return true
                }
                if (writeChar(gatt, altChar, payload, noResponse = false)) {
                    logLine("Wrote to alternate $altUuid (with response)")
                    return true
                }
            }
        }

        logLine("Write failed on all attempted characteristics")
        return false
    }

    private fun findCharacteristicAnyService(
        gatt: BluetoothGatt,
        charUuid: UUID
    ): BluetoothGattCharacteristic? {
        for (service: BluetoothGattService in gatt.services) {
            val c = service.getCharacteristic(charUuid)
            if (c != null) return c
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeChar(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        noResponse: Boolean
    ): Boolean {
        return try {
            characteristic.writeType = if (noResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            characteristic.value = payload
            @Suppress("DEPRECATION")
            val queued = gatt.writeCharacteristic(characteristic)
            if (queued) delay(150)
            queued
        } catch (e: Exception) {
            logLine("Write exception: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDisconnect(gatt: BluetoothGatt) {
        try {
            gatt.disconnect()
            gatt.close()
        } catch (_: Exception) {
            // Ignore disconnect errors, mirroring the Python `except Exception: pass`
        }
    }
}
