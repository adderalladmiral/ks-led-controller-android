package com.h4ch1net.ksled.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.h4ch1net.ksled.R
import com.h4ch1net.ksled.ble.LedGattClient
import com.h4ch1net.ksled.data.NicknameStore
import com.h4ch1net.ksled.data.PresetStore
import com.h4ch1net.ksled.databinding.ActivityDeviceControlBinding
import com.h4ch1net.ksled.databinding.DialogCustomColorBinding
import com.h4ch1net.ksled.databinding.DialogManagePresetsBinding
import com.h4ch1net.ksled.model.ColorPreset
import com.h4ch1net.ksled.model.DeviceProfile
import com.h4ch1net.ksled.model.LampFamily
import com.h4ch1net.ksled.model.LedCommands
import kotlinx.coroutines.launch

/**
 * Ports the interactive menu loop from led_menu.py (print_menu / color_preset_menu /
 * custom_color_menu / brightness_menu / manage_presets_menu / set_device_nickname)
 * as an Android control screen for a single already-selected device.
 */
class DeviceControlActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_PREFIX = "extra_prefix"
    }

    private lateinit var binding: ActivityDeviceControlBinding
    private lateinit var presetStore: PresetStore
    private lateinit var nicknameStore: NicknameStore
    private lateinit var presetAdapter: PresetGridAdapter
    private lateinit var profile: DeviceProfile
    private lateinit var address: String
    private lateinit var advertisedName: String
    private var currentRgb = Triple(255, 147, 41) // defaults to Warm White like Python's initial state
    private var currentBrightness = 255

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        address = intent.getStringExtra(EXTRA_ADDRESS) ?: run { finish(); return }
        advertisedName = intent.getStringExtra(EXTRA_NAME) ?: address
        val prefix = intent.getStringExtra(EXTRA_PREFIX) ?: ""
        profile = DeviceProfile.byPrefix(prefix) ?: DeviceProfile.matchByName(advertisedName) ?: run {
            Toast.makeText(this, "Unknown device type", Toast.LENGTH_LONG).show()
            finish(); return
        }

        presetStore = PresetStore(this)
        nicknameStore = NicknameStore(this)

        renderTitle()
        binding.colorPreview.setBackgroundColor(Color.rgb(currentRgb.first, currentRgb.second, currentRgb.third))

        presetAdapter = PresetGridAdapter { preset -> sendColorPreset(preset) }
        binding.presetGrid.layoutManager = GridLayoutManager(this, 4)
        binding.presetGrid.adapter = presetAdapter
        refreshPresets()

        binding.onButton.setOnClickListener { sendOnOff(true) }
        binding.offButton.setOnClickListener { sendOnOff(false) }
        binding.customColorButton.setOnClickListener { showCustomColorDialog() }
        binding.managePresetsButton.setOnClickListener { showManagePresetsDialog() }
        binding.nicknameButton.setOnClickListener { showNicknameDialog() }

        // Brightness only applies to floor lamps, matching the Python check
        // "device_type == floor" in brightness_menu().
        val brightnessSupported = profile.family == LampFamily.FLOOR
        binding.brightnessSeekBar.isEnabled = brightnessSupported
        binding.brightness25.isEnabled = brightnessSupported
        binding.brightness50.isEnabled = brightnessSupported
        binding.brightness75.isEnabled = brightnessSupported
        binding.brightness100.isEnabled = brightnessSupported
        if (!brightnessSupported) {
            appendLog(getString(R.string.control_unsupported_brightness))
        }

        binding.brightnessSeekBar.progress = currentBrightness
        binding.brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (brightnessSupported) sendBrightness(seekBar?.progress ?: return)
            }
        })
        binding.brightness25.setOnClickListener { sendBrightness(64) }
        binding.brightness50.setOnClickListener { sendBrightness(128) }
        binding.brightness75.setOnClickListener { sendBrightness(192) }
        binding.brightness100.setOnClickListener { sendBrightness(255) }
    }

    private fun renderTitle() {
        binding.deviceTitle.text = nicknameStore.displayName(address, advertisedName)
        val familyLabel = if (profile.family == LampFamily.FLOOR) "Floor lamp" else "Ceiling light"
        binding.deviceSubtitle.text = "$address · ${profile.prefix} · $familyLabel"
    }

    private fun refreshPresets() {
        presetAdapter.submit(presetStore.load())
    }

    private fun gattClient() = LedGattClient(this)

    private fun bluetoothDevice() =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter.getRemoteDevice(address)

    private fun appendLog(line: String) {
        val existing = binding.logText.text?.toString().orEmpty()
        binding.logText.text = (existing + "\n" + line).trim().takeLast(2000)
    }

    /** Ports build_on_off_cmd() + write_command() for a plain ON/OFF toggle. */
    private fun sendOnOff(turnOn: Boolean) {
        val payload = LedCommands.onOff(turnOn)
        appendLog(getString(R.string.control_connecting))
        lifecycleScope.launch {
            val result = gattClient().writeSequence(bluetoothDevice(), profile, listOf(payload))
            handleResult(result, if (turnOn) "ON" else "OFF")
        }
    }

    /**
     * Ports send_command(..., is_color=True) from led_menu.py: sends ON first,
     * then the color payload on the same connection, matching the original
     * app's habit of waking the light before applying a color.
     */
    private fun sendColorPreset(preset: ColorPreset) {
        currentRgb = Triple(preset.r, preset.g, preset.b)
        binding.colorPreview.setBackgroundColor(Color.rgb(preset.r, preset.g, preset.b))
        sendColorSequence(preset.r, preset.g, preset.b, preset.name)
    }

    private fun sendColorSequence(r: Int, g: Int, b: Int, label: String) {
        val onPayload = LedCommands.onOff(true)
        val colorPayload = LedCommands.color(r, g, b, profile.family, currentBrightness)
        appendLog("${getString(R.string.control_sending)} $label")
        lifecycleScope.launch {
            val result = gattClient().writeSequence(
                bluetoothDevice(), profile, listOf(onPayload, colorPayload)
            )
            handleResult(result, label)
        }
    }

    /** Ports brightness_menu()'s floor-lamp white-mode command. */
    private fun sendBrightness(value: Int) {
        currentBrightness = value
        val payload = LedCommands.brightness(value)
        appendLog("${getString(R.string.control_sending)} brightness $value")
        lifecycleScope.launch {
            val result = gattClient().writeSequence(bluetoothDevice(), profile, listOf(payload))
            handleResult(result, "brightness $value")
        }
    }

    private fun handleResult(result: LedGattClient.Result, label: String) {
        when (result) {
            is LedGattClient.Result.Success -> {
                result.log.forEach { appendLog(it) }
                appendLog("${getString(R.string.control_sent)}: $label")
            }
            is LedGattClient.Result.Failure -> {
                result.log.forEach { appendLog(it) }
                appendLog("${getString(R.string.control_failed)}: ${result.message}")
                Toast.makeText(this, "${getString(R.string.control_failed)}: ${result.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Ports custom_color_menu(): live RGB sliders + preview + optional "save as preset". */
    private fun showCustomColorDialog() {
        val dialogBinding = DialogCustomColorBinding.inflate(LayoutInflater.from(this))
        dialogBinding.redSeek.progress = currentRgb.first
        dialogBinding.greenSeek.progress = currentRgb.second
        dialogBinding.blueSeek.progress = currentRgb.third
        dialogBinding.presetNameInput.visibility = android.view.View.GONE

        fun updatePreview() {
            val r = dialogBinding.redSeek.progress
            val g = dialogBinding.greenSeek.progress
            val b = dialogBinding.blueSeek.progress
            dialogBinding.dialogPreview.setBackgroundColor(Color.rgb(r, g, b))
            dialogBinding.rgbValueText.text = "R:$r  G:$g  B:$b"
        }
        updatePreview()

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updatePreview()
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        dialogBinding.redSeek.setOnSeekBarChangeListener(seekListener)
        dialogBinding.greenSeek.setOnSeekBarChangeListener(seekListener)
        dialogBinding.blueSeek.setOnSeekBarChangeListener(seekListener)

        AlertDialog.Builder(this)
            .setTitle(R.string.control_custom)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null) // overridden below to control dismiss timing
            .setNegativeButton(R.string.cancel, null)
            .create()
            .apply {
                setOnShowListener { dialog ->
                    getButton(AlertDialog.BUTTON_POSITIVE).text = getString(R.string.control_on)
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val r = dialogBinding.redSeek.progress
                        val g = dialogBinding.greenSeek.progress
                        val b = dialogBinding.blueSeek.progress
                        currentRgb = Triple(r, g, b)
                        binding.colorPreview.setBackgroundColor(Color.rgb(r, g, b))
                        sendColorSequence(r, g, b, "custom color")
                        promptSaveAsPreset(r, g, b)
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    /** Ports the "Save as preset?" follow-up prompt in custom_color_menu(). */
    private fun promptSaveAsPreset(r: Int, g: Int, b: Int) {
        val input = TextInputEditText(this)
        input.hint = getString(R.string.preset_name_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.save_as_preset)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    presetStore.add(ColorPreset(name, r, g, b))
                    refreshPresets()
                    Toast.makeText(this, "Saved '$name'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Ports manage_presets_menu(): add / delete / reset to defaults. */
    private fun showManagePresetsDialog() {
        val dialogBinding = DialogManagePresetsBinding.inflate(LayoutInflater.from(this))
        val listAdapter = ManagePresetAdapter(presetStore.load().toMutableList()) { toDelete ->
            presetStore.delete(toDelete.name)
            refreshPresets()
        }
        dialogBinding.managePresetList.layoutManager = LinearLayoutManager(this)
        dialogBinding.managePresetList.adapter = listAdapter

        dialogBinding.resetDefaultsButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.reset_defaults)
                .setPositiveButton(R.string.reset_defaults) { _, _ ->
                    presetStore.resetToDefaults()
                    refreshPresets()
                    listAdapter.replace(presetStore.load())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.control_manage_presets)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                showAddPresetDialog { listAdapter.replace(presetStore.load()) }
            }
        }
        dialog.show()
    }

    /** Ports the "Add new preset" flow in manage_presets_menu(). */
    private fun showAddPresetDialog(onAdded: () -> Unit) {
        val dialogBinding = DialogCustomColorBinding.inflate(LayoutInflater.from(this))
        dialogBinding.presetNameInput.visibility = android.view.View.VISIBLE

        fun updatePreview() {
            val r = dialogBinding.redSeek.progress
            val g = dialogBinding.greenSeek.progress
            val b = dialogBinding.blueSeek.progress
            dialogBinding.dialogPreview.setBackgroundColor(Color.rgb(r, g, b))
            dialogBinding.rgbValueText.text = "R:$r  G:$g  B:$b"
        }
        updatePreview()
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updatePreview()
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        dialogBinding.redSeek.setOnSeekBarChangeListener(seekListener)
        dialogBinding.greenSeek.setOnSeekBarChangeListener(seekListener)
        dialogBinding.blueSeek.setOnSeekBarChangeListener(seekListener)

        AlertDialog.Builder(this)
            .setTitle(R.string.add)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.presetNameInput.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    presetStore.add(
                        ColorPreset(
                            name,
                            dialogBinding.redSeek.progress,
                            dialogBinding.greenSeek.progress,
                            dialogBinding.blueSeek.progress
                        )
                    )
                    refreshPresets()
                    onAdded()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Ports set_device_nickname(). */
    private fun showNicknameDialog() {
        val input = TextInputEditText(this)
        input.hint = getString(R.string.nickname_hint)
        input.setText(nicknameStore.get(address) ?: "")
        AlertDialog.Builder(this)
            .setTitle(R.string.nickname_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    nicknameStore.set(address, text)
                } else {
                    nicknameStore.clear(address)
                }
                renderTitle()
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                nicknameStore.clear(address)
                renderTitle()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
