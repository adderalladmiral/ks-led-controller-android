package com.h4ch1net.ksled.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import com.h4ch1net.ksled.model.DeviceProfile

/**
 * Ports scan_devices() / find_device_by_prefix() / find_all_ks03() from
 * led_menu.py and led_control.py. The Python tool used BleakScanner.discover()
 * with a timeout; here we use the platform BLE scanner with an equivalent
 * timed window, filtering to known KS name prefixes as we go.
 */
class BleScanner(private val adapter: BluetoothAdapter) {

    interface Listener {
        fun onDeviceFound(device: ScannedDevice)
        fun onScanStarted()
        fun onScanStopped()
        fun onScanFailed(errorCode: Int)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private val seenAddresses = mutableSetOf<String>()
    private var listener: Listener? = null

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val profile = DeviceProfile.matchByName(name) ?: return
            val address = result.device.address
            if (seenAddresses.add(address)) {
                listener?.onDeviceFound(
                    ScannedDevice(address, name, profile, result.rssi)
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onScanFailed(errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(timeoutMs: Long = 8000L, listener: Listener) {
        this.listener = listener
        seenAddresses.clear()
        val bleScanner = adapter.bluetoothLeScanner ?: run {
            listener.onScanFailed(-1)
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        listener.onScanStarted()
        bleScanner.startScan(null, settings, callback)
        handler.postDelayed({ stop() }, timeoutMs)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        try {
            adapter.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
            // adapter may already be off
        }
        listener?.onScanStopped()
    }

    fun isScanning() = scanning
}
