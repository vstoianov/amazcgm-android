package com.vstoianov.amazcgm

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.vstoianov.amazcgm.cgm.CgmType
import com.vstoianov.amazcgm.cgm.CgmVendor
import com.vstoianov.amazcgm.cgm.CodeEntry
import com.vstoianov.amazcgm.cgm.InputMethod
import com.vstoianov.amazcgm.cgm.cgmType
import com.vstoianov.amazcgm.databinding.ActivityMainBinding
import com.vstoianov.amazcgm.libre.FramInfo
import com.vstoianov.amazcgm.libre.Libre2Crypto
import com.vstoianov.amazcgm.libre.Sensor
import com.vstoianov.amazcgm.libre.SensorState
import com.vstoianov.amazcgm.libre.SensorType
import com.vstoianov.amazcgm.ble.ScanBlePeripheral
import com.vstoianov.amazcgm.ble.WatchHandoffService
import com.vstoianov.amazcgm.nfc.LibreNfc
import org.json.JSONObject
import java.io.IOException
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private enum class NfcState { READY, READING, DONE, ERROR, OFF, MISSING }

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private var lastResult: ScanResult? = null

    // NFC callbacks arrive off the UI thread — never read views from there.
    private val enableStreamingWanted = AtomicBoolean(true)
    private val unlockCodeWanted = AtomicLong(DEFAULT_UNLOCK_CODE)
    private val selectedType = AtomicReference(CgmType.DEFAULT)

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // Held in a field: SharedPreferences keeps listeners in a WeakHashMap.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in TRANSFER_KEYS) renderTransferCard()
    }

    // Refreshes the "N min left" countdown while the phone is visible.
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderTransferCard()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private val enableBluetooth = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (ScanBlePeripheral.bluetoothOn(this) && hasStoredScan()) WatchHandoffService.start(this)
        renderTransferCard()
    }
    private var pendingMakeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        selectedType.set(CgmType.of(prefs.getInt(KEY_SENSOR_TYPE, CgmType.DEFAULT.code)) ?: CgmType.DEFAULT)
        setupSensorTypeMenu()

        val storedCode = prefs.getLong(KEY_LAST_CODE, DEFAULT_UNLOCK_CODE)
        binding.unlockCode.setText(storedCode.toString())
        unlockCodeWanted.set(storedCode)
        enableStreamingWanted.set(binding.enableStreaming.isChecked)

        binding.enableStreaming.setOnCheckedChangeListener { _, checked ->
            enableStreamingWanted.set(checked)
            binding.enableStreamingHint.text = getString(
                if (checked) R.string.enable_streaming_hint_on else R.string.enable_streaming_hint_off
            )
        }
        binding.unlockCode.doAfterTextChanged { text ->
            val code = parseUnlockCode(text?.toString())
            unlockCodeWanted.set(code)
            prefs.edit().putLong(KEY_LAST_CODE, code).apply()
        }

        binding.shareJsonButton.setOnClickListener { shareJson() }
        binding.copyJsonButton.setOnClickListener { copyJson() }
        binding.bleVisibleButton.setOnClickListener { makeVisibleClicked() }
        binding.saveCodeButton.setOnClickListener { saveCode() }
        binding.codeInput.doAfterTextChanged { binding.codeLayout.error = null }

        restoreStoredScan()
        applySensorTypeUi()
        if (hasStoredScan()) WatchHandoffService.start(this)
        requestRuntimePermissions()
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        renderTransferCard()
        ticker.postDelayed(tick, TICK_MS)
    }

    override fun onStop() {
        ticker.removeCallbacks(tick)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.appBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.scroll.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun missingBluetoothPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRuntimePermissions() {
        val needed = missingBluetoothPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (missingBluetoothPermissions().isEmpty()) {
            if (hasStoredScan()) WatchHandoffService.start(this)
            if (pendingMakeVisible) makeVisibleClicked()
        } else if (pendingMakeVisible) {
            Toast.makeText(this, R.string.ble_permission_denied, Toast.LENGTH_LONG).show()
        }
        pendingMakeVisible = false
        renderTransferCard()
    }

    /** "Make visible to watch": permissions, then Bluetooth on, then a fresh window. */
    private fun makeVisibleClicked() {
        if (!hasStoredScan()) return
        val missing = missingBluetoothPermissions()
        if (missing.isNotEmpty()) {
            pendingMakeVisible = true
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        if (!ScanBlePeripheral.bluetoothOn(this)) {
            try {
                enableBluetooth.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
            }
            return
        }
        WatchHandoffService.start(this)
    }

    override fun onResume() {
        super.onResume()
        updateNfcReader()
    }

    /** Reader mode is only on while an NFC sensor type is selected. */
    private fun updateNfcReader() {
        val adapter = nfcAdapter
        if (selectedType.get().input != InputMethod.NFC) {
            adapter?.disableReaderMode(this)
            return
        }
        if (adapter == null) {
            setNfcState(NfcState.MISSING)
            return
        }
        if (!adapter.isEnabled) {
            setNfcState(NfcState.OFF)
            return
        }
        if (lastResult == null) setNfcState(NfcState.READY)
        val options = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000) }
        adapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            options
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    /** Called on a binder thread while the tag is in range. */
    override fun onTagDiscovered(tag: Tag) {
        val enableStreaming = enableStreamingWanted.get()
        val unlockCode = unlockCodeWanted.get()
        val type = selectedType.get()
        if (type.input != InputMethod.NFC) return
        runOnUiThread { setNfcState(NfcState.READING) }
        try {
            val result = scan(tag, type, enableStreaming, unlockCode)
            vibrate(longArrayOf(0, 60, 40, 60))
            runOnUiThread { show(result) }
        } catch (e: TypeMismatch) {
            vibrate(longArrayOf(0, 400))
            runOnUiThread { showTypeMismatch(e) }
        } catch (e: Throwable) {
            vibrate(longArrayOf(0, 400))
            runOnUiThread { setNfcState(NfcState.ERROR, e.message ?: e.javaClass.simpleName) }
        }
    }

    /** The scanned sensor is not the selected type. Thrown before anything is written to it. */
    private class TypeMismatch(val detected: CgmType?, val sensor: Sensor, val selected: CgmType) :
        Exception("sensor type mismatch")

    private fun scan(tag: Tag, type: CgmType, enableStreaming: Boolean, unlockCode: Long): ScanResult {
        val nfcv = NfcV.get(tag) ?: throw IOException(getString(R.string.not_nfcv))
        nfcv.connect()
        nfcv.use {
            val uid = tag.id
            val patchInfo = LibreNfc.readPatchInfo(nfcv)
            val sensor = Sensor(uid, patchInfo)
            val detected = sensor.cgmType()
            if (detected != type) throw TypeMismatch(detected, sensor, type)
            val notes = mutableListOf<String>()

            if (!sensor.supportsOpenStreaming) {
                notes += if (sensor.securityGeneration >= 2) {
                    getString(R.string.note_gen2)
                } else {
                    getString(R.string.note_unsupported_type, sensor.type.label)
                }
            }

            // BLE key first — FRAM dump is slower and the tag can walk out of range.
            var address: ByteArray? = null
            var enabledNow = false
            if (enableStreaming && sensor.supportsOpenStreaming) {
                address = LibreNfc.enableStreaming(nfcv, uid, patchInfo, unlockCode)
                enabledNow = true
                prefs.edit()
                    .putLong(KEY_LAST_CODE, unlockCode)
                    .putLong(codeKey(sensor.serial), unlockCode)
                    .putInt(countKey(sensor.serial), 0)
                    .putString(macKey(sensor.serial), address.hex())
                    .apply()
            }

            var framInfo: FramInfo? = null
            var framError: String? = null
            try {
                val raw = LibreNfc.readFram(nfcv)
                val plain = if (sensor.supportsOpenStreaming) {
                    Libre2Crypto.decryptFRAM(uid, patchInfo, raw)
                } else {
                    raw
                }
                framInfo = FramInfo.parse(plain)
                if (!framInfo.crcOk && sensor.type == SensorType.LIBRE2) {
                    notes += getString(R.string.note_crc_failed)
                }
            } catch (e: Throwable) {
                framError = e.message ?: e.javaClass.simpleName
            }

            // Fall back to what a previous scan of this sensor stored.
            val storedCode = prefs.getLong(codeKey(sensor.serial), unlockCode)
            if (address == null) {
                address = prefs.getString(macKey(sensor.serial), null)?.let { hexToBytes(it) }
            }
            val storedCount = prefs.getInt(countKey(sensor.serial), 0)

            if (!enabledNow && storedCode != unlockCode) {
                notes += getString(R.string.note_code_not_written, unlockCode, storedCode)
            }
            if (address == null && sensor.supportsOpenStreaming) {
                notes += getString(R.string.no_ble_address)
            }

            return ScanResult(
                type = type,
                time = Date(),
                sensor = sensor,
                fram = framInfo,
                framError = framError,
                bleAddress = address,
                // JSON must match the key the sensor actually has. A typed-but-not-
                // written code would make the watch compute unlock payloads the
                // sensor will reject.
                unlockCode = if (enabledNow) unlockCode else storedCode,
                nextUnlockCount = storedCount + 1,
                streamingWasEnabled = enabledNow,
                notes = notes
            )
        }
    }

    private fun show(result: ScanResult) {
        lastResult = result
        setNfcState(NfcState.DONE)
        // The field is the code in the JSON, not whatever was typed and not written.
        binding.unlockCode.setText(result.unlockCode.toString())
        unlockCodeWanted.set(result.unlockCode)

        renderSensor(result.sensor, result.fram)
        showGlucose(result.fram)
        val notes = result.notes.toMutableList()
        result.framError?.let { notes += getString(R.string.fram_error, it) }
        renderNotes(notes)

        persistScanJson(result)
        WatchHandoffService.start(this)
        renderTransferCard()
    }

    /** After a relaunch, show the last scan, which Share/Copy and Bluetooth still hand out. */
    private fun restoreStoredScan() {
        val json = prefs.getString(KEY_SCAN_JSON, null)?.takeIf { it.isNotBlank() } ?: return
        try {
            val data = JSONObject(json)
            val type = storedType(data)
            if (type.input == InputMethod.CODE) {
                if (type == selectedType.get()) {
                    binding.codeInput.setText(data.optString("code"))
                    binding.serialInput.setText(data.optString("serial"))
                }
                return
            }
            if (type != selectedType.get()) return
            val uid = hexToBytes(data.getString("uid"))
            val patch = hexToBytes(data.getString("patchInfo"))
            if (uid.size != 8 || patch.size != 6) return
            renderSensor(Sensor(uid, patch), null)
        } catch (_: Exception) {
        }
    }

    private fun renderSensor(sensor: Sensor, fram: FramInfo?) {
        binding.serial.text = sensor.serial.ifBlank { sensor.uid.hex() }
        binding.patchLabel.visibility = View.VISIBLE
        binding.patchInfo.visibility = View.VISIBLE
        binding.patchInfo.text = sensor.patchInfo.hex().chunked(4).joinToString(" ")

        val chips = binding.sensorChips
        chips.removeAllViews()
        val typeLabel = if (sensor.isPlus) getString(R.string.chip_plus, sensor.type.label) else sensor.type.label
        addChip(typeLabel)
        addChip(sensor.region.label)
        fram?.let { info ->
            if (info.state != SensorState.UNKNOWN) addChip(info.state.label, info.state == SensorState.ACTIVE)
            if (info.ageMinutes > 0) {
                addChip(getString(R.string.chip_age, info.ageMinutes / 1440, (info.ageMinutes % 1440) / 60))
            }
            if (info.maxLifeMinutes > 0) addChip(getString(R.string.chip_life, info.maxLifeMinutes / 1440))
        }
        chips.visibility = View.VISIBLE
    }

    private fun addChip(text: String, highlight: Boolean = false) {
        val chip = Chip(this).apply {
            this.text = text
            isClickable = false
            isCheckable = false
            if (highlight) {
                chipBackgroundColor = ColorStateList.valueOf(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)
                )
            }
        }
        binding.sensorChips.addView(chip)
    }

    private fun renderNotes(notes: List<String>) {
        if (notes.isEmpty()) {
            binding.notes.visibility = View.GONE
            return
        }
        binding.notes.visibility = View.VISIBLE
        binding.notes.text = notes.joinToString("\n\n")
    }

    private fun showGlucose(fram: FramInfo?) {
        val mgdl = fram?.currentMgdl
        if (mgdl == null) {
            binding.glucose.visibility = View.GONE
            binding.glucoseNote.visibility = View.GONE
            return
        }
        binding.glucose.visibility = View.VISIBLE
        binding.glucoseNote.visibility = View.VISIBLE
        binding.glucose.text = getString(R.string.glucose_reading, mgdl / 18.0, mgdl)
        val day1 = fram.ageMinutes < DAY1_MINUTES
        binding.glucoseNote.text = if (day1) {
            getString(R.string.glucose_factory) + " · " + getString(R.string.glucose_day1)
        } else {
            getString(R.string.glucose_factory)
        }
    }

    private fun setNfcState(state: NfcState, detail: String? = null) {
        val (title, hint, @DrawableRes icon) = when (state) {
            NfcState.READY -> Triple(R.string.nfc_ready, getString(R.string.nfc_ready_hint), R.drawable.ic_nfc)
            NfcState.READING -> Triple(R.string.nfc_reading, getString(R.string.nfc_reading_hint), R.drawable.ic_nfc)
            NfcState.DONE -> Triple(R.string.nfc_done, getString(R.string.nfc_done_hint), R.drawable.ic_check)
            NfcState.ERROR -> Triple(R.string.nfc_error, detail ?: "", R.drawable.ic_error)
            NfcState.OFF -> Triple(R.string.nfc_off, getString(R.string.nfc_off_hint), R.drawable.ic_error)
            NfcState.MISSING -> Triple(R.string.nfc_missing, getString(R.string.nfc_missing_hint), R.drawable.ic_error)
        }
        val error = state == NfcState.ERROR || state == NfcState.OFF || state == NfcState.MISSING
        fun color(attr: Int) = MaterialColors.getColor(binding.nfcCard, attr)
        val attrs = com.google.android.material.R.attr.colorErrorContainer to
            com.google.android.material.R.attr.colorPrimaryContainer
        binding.nfcCard.setCardBackgroundColor(color(if (error) attrs.first else attrs.second))
        val onColor = color(
            if (error) {
                com.google.android.material.R.attr.colorOnErrorContainer
            } else {
                com.google.android.material.R.attr.colorOnPrimaryContainer
            }
        )
        binding.nfcTitle.setTextColor(onColor)
        binding.nfcSubtitle.setTextColor(onColor)
        binding.nfcTitle.setText(title)
        binding.nfcSubtitle.text = hint
        binding.nfcIcon.setImageResource(icon)
        binding.nfcProgress.visibility = if (state == NfcState.READING) View.VISIBLE else View.GONE
    }

    private fun renderTransferCard() {
        if (!::binding.isInitialized) return
        val hasScan = hasStoredScan()
        binding.shareJsonButton.isEnabled = hasScan
        binding.copyJsonButton.isEnabled = hasScan
        renderBluetooth(hasScan)
        renderStoredSummary()

        val served = prefs.getLong(KEY_LAST_SERVED, 0L)
        binding.lastFetched.text = if (served > 0) {
            val date = Date(served)
            val day = if (android.text.format.DateUtils.isToday(served)) "" else DateFormat.getDateFormat(this).format(date) + " "
            getString(R.string.last_fetched, day + DateFormat.getTimeFormat(this).format(date))
        } else {
            getString(R.string.never_fetched)
        }
    }

    private fun renderBluetooth(hasScan: Boolean) {
        val until = prefs.getLong(KEY_BLE_UNTIL, 0L)
        val stateName = prefs.getString(KEY_BLE_STATE, null)
        val state = ScanBlePeripheral.State.entries.firstOrNull { it.name == stateName }
            ?: ScanBlePeripheral.State.OFF
        val leftMs = until - System.currentTimeMillis()
        val running = WatchHandoffService.running
        val visible = running && state == ScanBlePeripheral.State.VISIBLE && leftMs > 0
        val missing = missingBluetoothPermissions().isNotEmpty()
        val btOn = ScanBlePeripheral.bluetoothOn(this)
        binding.bleStatus.text = when {
            !hasScan -> getString(R.string.no_scan_yet)
            missing -> getString(R.string.ble_no_permission)
            !btOn -> getString(R.string.ble_off)
            visible && leftMs >= 60_000L -> getString(R.string.ble_visible, (leftMs / 60_000L).toInt())
            visible -> getString(R.string.ble_visible_short)
            running && state == ScanBlePeripheral.State.STARTING ->
                getString(R.string.ble_starting)
            state == ScanBlePeripheral.State.UNSUPPORTED -> getString(R.string.ble_unsupported)
            state == ScanBlePeripheral.State.ERROR ->
                getString(R.string.ble_error_code, prefs.getInt(KEY_BLE_ERROR, 0))
            else -> getString(R.string.ble_not_visible)
        }
        val dotAttr = if (visible) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOutline
        }
        binding.bleDot.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.getColor(binding.bleDot, dotAttr))
        binding.bleVisibleButton.isEnabled = hasScan && state != ScanBlePeripheral.State.UNSUPPORTED
    }

    private fun hasStoredScan() = !prefs.getString(KEY_SCAN_JSON, null).isNullOrBlank()

    private fun storedJson(): String? =
        lastResult?.toJson()?.toString() ?: prefs.getString(KEY_SCAN_JSON, null)?.takeIf { it.isNotBlank() }

    private fun persistScanJson(result: ScanResult) {
        prefs.edit().putString(KEY_SCAN_JSON, result.toJson().toString()).apply()
    }

    private fun parseUnlockCode(text: String?): Long {
        val value = text?.trim()?.toLongOrNull()
        return if (value != null && value in 1..0xFFFFFFFFL) value else DEFAULT_UNLOCK_CODE
    }

    private fun copyJson() {
        val json = storedJson() ?: return
        val pretty = try { JSONObject(json).toString(2) } catch (_: Exception) { json }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(storedLabel(json), pretty))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareJson() {
        val json = storedJson() ?: return
        val data = try { JSONObject(json) } catch (_: Exception) { null }
        val serial = data?.optString("serial").orEmpty()
        val body = data?.toString(2) ?: json
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${storedLabel(json)} $serial".trim())
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun setupSensorTypeMenu() {
        val types = CgmType.entries
        val labels = types.map { typeMenuLabel(it) }
        binding.sensorTypeMenu.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.sensorTypeMenu.setText(typeMenuLabel(selectedType.get()), false)
        binding.sensorTypeMenu.setOnItemClickListener { _, _, position, _ -> selectType(types[position]) }
    }

    private fun typeMenuLabel(type: CgmType): String = type.label

    private fun selectType(type: CgmType) {
        if (type == selectedType.get()) return
        selectedType.set(type)
        prefs.edit().putInt(KEY_SENSOR_TYPE, type.code).apply()
        binding.sensorTypeMenu.setText(typeMenuLabel(type), false)
        binding.codeInput.setText("")
        binding.serialInput.setText("")
        binding.codeLayout.error = null
        restoreStoredScan()
        applySensorTypeUi()
        updateNfcReader()
        renderTransferCard()
    }

    /** Shows the NFC cards or the code card for the selected type. */
    private fun applySensorTypeUi() {
        val type = selectedType.get()
        val nfc = type.input == InputMethod.NFC
        binding.nfcCard.visibility = if (nfc) View.VISIBLE else View.GONE
        binding.sensorCard.visibility = if (nfc) View.VISIBLE else View.GONE
        binding.optionsCard.visibility = if (nfc) View.VISIBLE else View.GONE
        binding.codeCard.visibility = if (nfc) View.GONE else View.VISIBLE
        binding.sensorTypeLayout.helperText =
            if (type.amazcgmSupported) null else getString(R.string.sensor_type_unsupported)
        binding.codeHint.text = getString(
            if (type.vendor == CgmVendor.DEXCOM) R.string.code_hint_dexcom else R.string.code_hint_sibionics
        )
    }

    private fun saveCode() {
        val type = selectedType.get()
        if (type.input != InputMethod.CODE) return
        val code = type.normalizeCode(binding.codeInput.text?.toString())
        if (code == null) {
            binding.codeLayout.error = getString(R.string.code_invalid, type.label)
            return
        }
        val serial = binding.serialInput.text?.toString()?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val entry = CodeEntry(type, code, serial)
        binding.codeInput.setText(code)
        lastResult = null
        prefs.edit().putString(KEY_SCAN_JSON, entry.toJson().toString()).apply()
        Toast.makeText(this, R.string.code_saved, Toast.LENGTH_SHORT).show()
        WatchHandoffService.start(this)
        renderTransferCard()
    }

    private fun showTypeMismatch(e: TypeMismatch) {
        val scanned = e.detected?.label ?: e.sensor.type.label
        val message = if (e.detected == null) {
            getString(R.string.sensor_type_unknown_scan, scanned)
        } else {
            getString(R.string.sensor_type_mismatch, scanned, e.selected.label)
        }
        setNfcState(NfcState.ERROR, message)
        val detected = e.detected ?: return
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.sensor_type_switch) { selectType(detected) }
            .show()
    }

    /** Type of a stored payload. Payloads without `sensorType` predate it and are Libre 2 EU. */
    private fun storedType(data: JSONObject): CgmType =
        data.optString("sensorType").toIntOrNull()?.let { CgmType.of(it) } ?: CgmType.DEFAULT

    private fun storedLabel(json: String): String =
        try { storedType(JSONObject(json)).label } catch (_: Exception) { CgmType.DEFAULT.label }

    private fun renderStoredSummary() {
        val json = prefs.getString(KEY_SCAN_JSON, null)?.takeIf { it.isNotBlank() }
        val data = json?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        if (data == null) {
            binding.storedSummary.visibility = View.GONE
            return
        }
        val type = storedType(data)
        val id = data.optString("serial").ifBlank { data.optString("code") }
        binding.storedSummary.text = getString(R.string.stored_summary, type.label, id)
        binding.storedSummary.visibility = View.VISIBLE
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun codeKey(serial: String) = "code_${serial.lowercase(Locale.US)}"
    private fun countKey(serial: String) = "count_${serial.lowercase(Locale.US)}"
    private fun macKey(serial: String) = "mac_${serial.lowercase(Locale.US)}"

    companion object {
        const val PREFS = "amazcgm"
        const val KEY_SCAN_JSON = "scan_json"
        const val KEY_LAST_SERVED = "last_served_at"
        /** Touched by the service on start and stop so the activity re-renders. */
        const val KEY_HANDOFF_STATE = "handoff_state_at"
        const val KEY_BLE_STATE = "ble_state"
        const val KEY_BLE_UNTIL = "ble_visible_until"
        const val KEY_BLE_ERROR = "ble_error_code"
        private val TRANSFER_KEYS = setOf(
            KEY_LAST_SERVED, KEY_SCAN_JSON, KEY_HANDOFF_STATE, KEY_BLE_STATE, KEY_BLE_UNTIL, KEY_BLE_ERROR
        )
        private const val TICK_MS = 15_000L
        private const val KEY_LAST_CODE = "last_unlock_code"
        private const val KEY_SENSOR_TYPE = "sensor_type"
        private const val DEFAULT_UNLOCK_CODE = 42L
        private const val DAY1_MINUTES = 24 * 60
        private const val REQ_PERMS = 1
    }
}
