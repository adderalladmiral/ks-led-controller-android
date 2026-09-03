package com.h4ch1net.ksled.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.h4ch1net.ksled.R
import com.h4ch1net.ksled.ble.BleScanner
import com.h4ch1net.ksled.ble.LedGattClient
import com.h4ch1net.ksled.ble.PermissionHelper
import com.h4ch1net.ksled.ble.ScannedDevice
import com.h4ch1net.ksled.data.NicknameStore
import com.h4ch1net.ksled.databinding.ActivityMainBinding
import com.h4ch1net.ksled.model.LedCommands
import kotlinx.coroutines.launch

/**
 * Ports the device-selection portion of led_menu.py's main() loop, plus the
 * --all-ks03 batch behavior from led_control.py, as a scan/list screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DeviceListAdapter
    private lateinit var nicknameStore: NicknameStore
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BleScanner? = null
    private val foundDevices = linkedMapOf<String, ScannedDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startScan()
        } else {
            Toast.makeText(this, R.string.scan_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nicknameStore = NicknameStore(this)
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        adapter = DeviceListAdapter(nicknameStore) { device ->
            scanner?.stop()
            val intent = Intent(this, DeviceControlActivity::class.java)
            intent.putExtra(DeviceControlActivity.EXTRA_ADDRESS, device.address)
            intent.putExtra(DeviceControlActivity.EXTRA_NAME, device.name)
            intent.putExtra(DeviceControlActivity.EXTRA_PREFIX, device.profile.prefix)
            startActivity(intent)
        }
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = adapter

        binding.scanButton.setOnClickListener { requestPermissionsAndScan() }
        binding.allOnButton.setOnClickListener { sendToAllKs03(true) }
        binding.allOffButton.setOnClickListener { sendToAllKs03(false) }

        requestPermissionsAndScan()
    }

    private fun requestPermissionsAndScan() {
        if (PermissionHelper.hasAll(this)) {
            startScan()
        } else {
            permissionLauncher.launch(PermissionHelper.requiredPermissions())
        }
    }

    private fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            binding.statusText.text = getString(R.string.scan_bt_off)
            return
        }
        foundDevices.clear()
        this.adapter.submit(emptyList())
        binding.emptyText.visibility = android.view.View.GONE

        scanner = BleScanner(adapter)
        scanner?.start(timeoutMs = 8000L, listener = object : BleScanner.Listener {
            override fun onDeviceFound(device: ScannedDevice) {
                foundDevices[device.address] = device
                runOnUiThread {
                    this@MainActivity.adapter.submit(foundDevices.values.toList())
                    binding.emptyText.visibility = android.view.View.GONE
                }
            }

            override fun onScanStarted() {
                runOnUiThread {
                    binding.scanProgress.visibility = android.view.View.VISIBLE
                    binding.statusText.text = getString(R.string.scan_scanning)
                    binding.scanButton.isEnabled = false
                }
            }

            override fun onScanStopped() {
                runOnUiThread {
                    binding.scanProgress.visibility = android.view.View.GONE
                    binding.scanButton.isEnabled = true
                    binding.statusText.text = ""
                    if (foundDevices.isEmpty()) {
                        binding.emptyText.visibility = android.view.View.VISIBLE
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    binding.scanProgress.visibility = android.view.View.GONE
                    binding.scanButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Scan failed ($errorCode)", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /** Ports led_control.py's --all-ks03 batch on/off across every KS03-/KS03~ device found. */
    private fun sendToAllKs03(turnOn: Boolean) {
        val targets = foundDevices.values.filter {
            it.profile.prefix == "KS03-" || it.profile.prefix == "KS03~"
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "No KS03 devices found", Toast.LENGTH_SHORT).show()
            return
        }
        val client = LedGattClient(this)
        val payload = LedCommands.onOff(turnOn)
        lifecycleScope.launch {
            var okCount = 0
            for (t in targets) {
                val device = bluetoothAdapter?.getRemoteDevice(t.address) ?: continue
                val result = client.writeSequence(device, t.profile, listOf(payload))
                if (result is LedGattClient.Result.Success) okCount++
            }
            Toast.makeText(
                this@MainActivity,
                "Sent ${if (turnOn) "ON" else "OFF"} to $okCount/${targets.size} KS03 device(s)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner?.stop()
    }
}
