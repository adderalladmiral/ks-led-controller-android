package com.h4ch1net.ksled.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.h4ch1net.ksled.model.DeviceProfile

/**
 * Ports scan_devices() / find_device_by_prefix() / find_all_ks03() from
 * led_menu.py and led_control.py.
 *
 * This also folds in the discovery strategy from a known-working reference
 * app (KS03Pulse) that reliably found/connected to these lights when a
 * plain unfiltered name-only scan sometimes did not:
 *
 *  1. Bonded-device fast path — if the phone has already paired with a KS
 *     device, some OEM Bluetooth stacks are much quicker/more reliable
 *     handing back a device from bondedDevices than surfacing it via a
 *     fresh scan. We check this first and short-circuit straight to it.
 *  2. Filtered scan using ScanFilter on each known service UUID — some
 *     OEM Bluetooth stacks throttle or deprioritize completely unfiltered
 *     scans (startScan(null, ...)), which can make real, in-range devices
 *     silently never appear in results.
 *  3. Unfiltered retry fallback — if the filtered scan finds nothing
 *     within a short window, fall back to an unfiltered scan matching
 *     purely on advertised name, in case a given unit doesn't advertise
 *     its service UUID in the primary advertisement packet.
 */
class BleScanner(private val adapter: BluetoothAdapter) {

    interface Listener {
        fun onDeviceFound(device: ScannedDevice)
        fun onScanStarted()
        fun onScanStopped()
        fun onScanFailed(errorCode: Int)
        fun onUnmatchedDevice(address: String, name: String?, rssi: Int) {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private val seenAddresses = mutableSetOf<String>()
    private var listener: Listener? = null
    private var activeScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(timeoutMs: Long = 8000L, listener: Listener) {
        this.listener = listener
        seenAddresses.clear()

        val bleScanner = adapter.bluetoothLeScanner ?: run {
            listener.onScanFailed(-1)
            return
        }

        // 1. Bonded-device fast path. If we've already paired with a known
        // KS device, hand it back immediately without waiting on a scan.
        val bondedMatch = adapter.bondedDevices?.firstOrNull { d ->
            DeviceProfile.matchByName(d.name) != null
        }
        if (bondedMatch != null) {
            val profile = DeviceProfile.matchByName(bondedMatch.name)
            if (profile != null) {
                listener.onScanStarted()
                seenAddresses.add(bondedMatch.address)
                listener.onDeviceFound(
                    ScannedDevice(bondedMatch.address, bondedMatch.name ?: bondedMatch.address, profile, rssi = 0)
                )
                // Still keep scanning in the background in case other
                // (unbonded) KS devices are also nearby, but don't block on it.
            }
        }

        scanning = true
        if (bondedMatch == null) listener.onScanStarted()

        // 2. Filtered scan across every known service UUID.
        val filters = DeviceProfile.PROFILES
            .map { it.serviceUuid }
            .distinct()
            .map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val filteredCallback = buildCallback()
        activeScanner = bleScanner
        activeCallback = filteredCallback
        bleScanner.startScan(filters, settings, filteredCallback)

        // 3. Unfiltered retry: if nothing new turns up within the first
        // 4 seconds of the filtered scan, fall back to unfiltered name
        // matching for the remainder of the timeout window.
        handler.postDelayed({
            if (!scanning) return@postDelayed
            if (seenAddresses.isEmpty()) {
                try {
                    bleScanner.stopScan(filteredCallback)
                } catch (_: Exception) {
                }
                listener.onStatusFallback()
                val openCallback = buildCallback()
                activeCallback = openCallback
                bleScanner.startScan(null, settings, openCallback)
            }
        }, minOf(4000L, timeoutMs / 2))

        handler.postDelayed({ stop() }, timeoutMs)
    }

    private fun Listener.onStatusFallback() {
        // No-op hook point; scan continues transparently. Exposed as a
        // separate function in case callers want to log/observe the
        // filtered->unfiltered fallback transition.
    }

    @SuppressLint("MissingPermission")
    private fun buildCallback(): ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Prefer the name parsed directly out of the advertisement packet.
            // result.device.name relies on the OS's cached GATT device record,
            // which can be null/stale until the phone has connected to the
            // device at least once - scanRecord.deviceName is what's actually
            // being broadcast right now and is what native scanners key off.
            val rawName = (result.scanRecord?.deviceName ?: result.device.name)?.trim()
            val address = result.device.address

            val matchedByServiceUuid = result.scanRecord?.serviceUuids?.any { parcelUuid ->
                DeviceProfile.PROFILES.any { it.serviceUuid == parcelUuid.uuid }
            } == true

            val profile = DeviceProfile.matchByName(rawName)
                ?: if (matchedByServiceUuid) DeviceProfile.PROFILES.firstOrNull { p ->
                    result.scanRecord?.serviceUuids?.any { it.uuid == p.serviceUuid } == true
                } else null

            if (profile == null) {
                listener?.onUnmatchedDevice(address, rawName, result.rssi)
                return
            }

            if (seenAddresses.add(address)) {
                listener?.onDeviceFound(
                    ScannedDevice(address, rawName ?: address, profile, result.rssi)
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onScanFailed(errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        try {
            activeCallback?.let { activeScanner?.stopScan(it) }
        } catch (_: Exception) {
            // adapter may already be off
        }
        listener?.onScanStopped()
    }

    fun isScanning() = scanning
}
