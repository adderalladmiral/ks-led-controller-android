package com.h4ch1net.ksled.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.h4ch1net.ksled.R
import com.h4ch1net.ksled.ble.LedGattClient
import com.h4ch1net.ksled.databinding.ActivityStressTestBinding
import com.h4ch1net.ksled.model.DeviceProfile
import com.h4ch1net.ksled.model.LedCommands
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Isolated write-rate stress test screen for a single already-selected device.
 *
 * Ports the ramping-rate stress test from the KS03Old app's StressTestActivity
 * onto this app's LedGattClient/Session API: opens one persistent GATT
 * connection (LedGattClient.openSession), then streams alternating red/blue
 * color frames through Session.send() at a target rate that increases every
 * rampIntervalMillis, until either the connection drops or writes start
 * failing outright — whichever happens first is reported as the ceiling.
 *
 * This only measures what LedGattClient's write path (and the underlying
 * BLE stack) will accept — WRITE_TYPE_NO_RESPONSE has no acknowledgement
 * from the peripheral, so a successful send() does not guarantee the light
 * actually rendered that frame.
 */
class StressTestActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_PREFIX = "extra_prefix"

        private const val RAMP_INTERVAL_MS = 2000L
        // Same rejection-ceiling heuristic as the KS03Old version: once we've
        // seen more than 20 rejects and rejects exceed 1/4 of accepts, call it.
        private const val MIN_REJECTS_TO_STOP = 20
    }

    private lateinit var binding: ActivityStressTestBinding
    private lateinit var address: String
    private lateinit var advertisedName: String
    private lateinit var profile: DeviceProfile

    private var gattClient: LedGattClient? = null
    private var testJob: Job? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStressTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        address = intent.getStringExtra(EXTRA_ADDRESS) ?: run { finish(); return }
        advertisedName = intent.getStringExtra(EXTRA_NAME) ?: address
        val prefix = intent.getStringExtra(EXTRA_PREFIX) ?: ""
        profile = DeviceProfile.byPrefix(prefix) ?: DeviceProfile.matchByName(advertisedName) ?: run {
            finish(); return
        }

        binding.stressDeviceTitle.text = advertisedName
        binding.stressConnState.text = getString(R.string.stress_not_connected)

        binding.stressSeekStartRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                binding.stressLblStartRate.text = "Start rate: ${5 + value} writes/sec"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.stressSeekRampStep.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                binding.stressLblRampStep.text = "Ramp step: +${1 + value} writes/sec every 2s"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.stressBtnStartStop.setOnClickListener {
            if (running) stopTest("Stopped by user.") else startTest()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) stopTest("Screen closed mid-test.")
    }

    private fun startTest() {
        running = true
        binding.stressTxtResult.text = ""
        binding.stressBtnStartStop.text = getString(R.string.stress_stop)
        binding.stressSeekStartRate.isEnabled = false
        binding.stressSeekRampStep.isEnabled = false

        val startRate = 5 + binding.stressSeekStartRate.progress
        val rampStep = 1 + binding.stressSeekRampStep.progress

        testJob = lifecycleScope.launch {
            binding.stressConnState.text = getString(R.string.stress_connecting)

            val client = LedGattClient(this@StressTestActivity)
            gattClient = client

            when (val openResult = client.openSession(bluetoothDevice(), profile)) {
                is LedGattClient.Result.Failure -> {
                    binding.stressConnState.text =
                        "${getString(R.string.stress_connect_failed)}: ${openResult.message}"
                    appendLog(openResult.message)
                    openResult.log.forEach(::appendLog)
                    stopTest(null)
                    return@launch
                }
                is LedGattClient.Result.Success -> openResult.log.forEach(::appendLog)
            }

            val session = client.activeSession()
            if (session == null) {
                binding.stressConnState.text = getString(R.string.stress_connect_failed)
                stopTest(null)
                return@launch
            }

            binding.stressConnState.text = "Connected: $advertisedName — ready to test"

            runRampingTest(session, startRate, rampStep)
        }
    }

    private suspend fun runRampingTest(session: LedGattClient.Session, startRate: Int, rampStep: Int) {
        var currentRateHz = startRate
        var sentCount = 0L
        var acceptedCount = 0L
        var rejectedCount = 0L
        var toggle = false
        var lastRampAt = System.currentTimeMillis()

        binding.stressTxtCurrentRate.text = "Current target rate: $currentRateHz writes/sec"

        while (isActive) {
            toggle = !toggle
            val payload = if (toggle)
                LedCommands.color(100, 0, 0, profile.family)
            else
                LedCommands.color(0, 0, 100, profile.family)

            sentCount++
            val accepted = try {
                session.send(payload)
            } catch (e: Exception) {
                appendLog("Send exception: ${e.message}")
                false
            }
            if (accepted) acceptedCount++ else rejectedCount++

            updateStats(sentCount, acceptedCount, rejectedCount)

            if (!accepted && rejectedCount == 1L) {
                // First-ever rejection: give it a moment then check again, same
                // spirit as the KS03Old version detecting a dropped connection
                // via its own onDisconnected callback.
                delay(200)
            }

            if (rejectedCount > MIN_REJECTS_TO_STOP && rejectedCount > acceptedCount / 4) {
                stopTest(
                    "BLE stack started rejecting writes at ~$currentRateHz writes/sec " +
                        "(accepted=$acceptedCount, rejected=$rejectedCount)."
                )
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastRampAt >= RAMP_INTERVAL_MS) {
                currentRateHz += rampStep
                lastRampAt = now
                binding.stressTxtCurrentRate.text = "Current target rate: $currentRateHz writes/sec"
            }

            val intervalMillis = (1000.0 / currentRateHz).toLong().coerceAtLeast(1)
            delay(intervalMillis)
        }
    }

    private fun updateStats(sent: Long, accepted: Long, rejected: Long) {
        binding.stressTxtStats.text = "Sent: $sent   Accepted: $accepted   Rejected: $rejected"
    }

    private fun stopTest(resultMessage: String?) {
        running = false
        testJob?.cancel()
        testJob = null
        gattClient?.closeSession()
        gattClient = null

        binding.stressBtnStartStop.text = getString(R.string.stress_start)
        binding.stressSeekStartRate.isEnabled = true
        binding.stressSeekRampStep.isEnabled = true
        if (resultMessage != null) {
            binding.stressTxtResult.text = resultMessage
        }
        binding.stressConnState.text = getString(R.string.stress_not_connected)
    }

    private fun bluetoothDevice() =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter.getRemoteDevice(address)

    private fun appendLog(line: String) {
        val existing = binding.stressTxtLog.text?.toString().orEmpty()
        binding.stressTxtLog.text = (existing + "\n" + line).trim().takeLast(2000)
    }
}
