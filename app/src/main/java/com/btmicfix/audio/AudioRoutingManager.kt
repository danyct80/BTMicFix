package com.btmicfix.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.btmicfix.BuildConfig
import com.btmicfix.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Core audio routing manager.
 *
 * Primary routing uses AudioManager.setCommunicationDevice() (API 31+).
 * The diagnostic microphone test additionally opens an AudioRecord using
 * VOICE_COMMUNICATION and explicitly requests the Bluetooth SCO input, so we can
 * distinguish "Android says SCO is selected" from "audio is really arriving from SCO".
 */
class AudioRoutingManager(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService<AudioManager>()
            ?: throw IllegalStateException("AudioManager not available")

    private val _routingState = MutableStateFlow<RoutingState>(RoutingState.Idle)
    val routingState: StateFlow<RoutingState> = _routingState.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<BluetoothAudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothAudioDevice>> = _availableDevices.asStateFlow()

    private val _lastMicTestResult = MutableStateFlow<MicTestResult?>(null)
    val lastMicTestResult: StateFlow<MicTestResult?> = _lastMicTestResult.asStateFlow()

    private val _micTestResults = MutableStateFlow<Map<MicTestSource, MicTestResult>>(emptyMap())
    val micTestResults: StateFlow<Map<MicTestSource, MicTestResult>> = _micTestResults.asStateFlow()

    private val _micLiveLevel = MutableStateFlow<MicLiveLevel?>(null)
    val micLiveLevel: StateFlow<MicLiveLevel?> = _micLiveLevel.asStateFlow()

    private var currentRoutedDevice: AudioDeviceInfo? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            Logger.i("Audio devices added: ${addedDevices.map { deviceTypeToString(it.type) }}")
            refreshAvailableDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            Logger.i("Audio devices removed: ${removedDevices.map { deviceTypeToString(it.type) }}")
            currentRoutedDevice?.let { routed ->
                if (removedDevices.any { it.id == routed.id }) {
                    Logger.w("Routed device was removed, clearing routing")
                    clearRouting()
                }
            }
            refreshAvailableDevices()
        }
    }

    sealed class RoutingState {
        data object Idle : RoutingState()
        data class Routing(val deviceName: String) : RoutingState()
        data class Active(val deviceName: String) : RoutingState()
        data class Failed(val reason: String) : RoutingState()
    }

    data class BluetoothAudioDevice(
        val deviceInfo: AudioDeviceInfo,
        val name: String,
        val type: Int,
        val typeLabel: String,
    )

    enum class MicTestSource(val audioSource: Int, val label: String) {
        VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
        VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
        MIC(MediaRecorder.AudioSource.MIC, "MIC"),
    }

    enum class MicTestPhase {
        CALIBRATING,
        SPEAKING,
        FINISHED,
    }

    data class MicLiveLevel(
        val source: MicTestSource,
        val phase: MicTestPhase,
        val rms: Double,
        val peak: Int,
        val dbfs: Double,
        val thresholdRms: Double,
        val thresholdPeak: Int,
        val thresholdDbfs: Double,
        val elapsedMs: Long,
        val durationMs: Long,
    )

    /** Result of one real microphone diagnostic test. */
    data class MicTestResult(
        val source: MicTestSource,
        val verdict: MicTestVerdict,
        val targetDeviceName: String = "Dispositivo Bluetooth",
        val requestedInput: String,
        val actualInput: String,
        val preferredDeviceAccepted: Boolean,
        val communicationDevice: String,
        val peak: Int,
        val rms: Double,
        val samplesRead: Long,
        val durationMs: Long,
        val details: String,
        val baselinePeak: Int = 0,
        val baselineRms: Double = 0.0,
        val thresholdPeak: Int = 0,
        val thresholdRms: Double = 0.0,
        val routeMatchesRequested: Boolean = false,
    ) {
        val rmsDbfs: Double get() = amplitudeToDbfs(rms)
        val thresholdDbfs: Double get() = amplitudeToDbfs(thresholdRms)

        val summary: String
            get() = when (verdict) {
                MicTestVerdict.PASS -> "$targetDeviceName usato realmente come microfono"
                MicTestVerdict.NO_AUDIO -> "$targetDeviceName selezionato, voce sotto soglia"
                MicTestVerdict.WRONG_DEVICE -> "Ingresso reale diverso da $targetDeviceName"
                MicTestVerdict.PERMISSION_REQUIRED -> "Permesso microfono necessario"
                MicTestVerdict.ERROR -> "Test microfono non riuscito"
            }
    }

    enum class MicTestVerdict {
        PASS,
        NO_AUDIO,
        WRONG_DEVICE,
        PERMISSION_REQUIRED,
        ERROR,
    }

    fun startMonitoring() {
        Logger.i("Starting audio device monitoring")
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshAvailableDevices()
    }

    fun stopMonitoring() {
        Logger.i("Stopping audio device monitoring")
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    fun routeToBluetooth(device: AudioDeviceInfo): RoutingState {
        val deviceName = device.productName?.toString() ?: "Dispositivo Bluetooth"
        Logger.i("Attempting to route to: $deviceName (type=${deviceTypeToString(device.type)})")

        _routingState.value = RoutingState.Routing(deviceName)

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val success = audioManager.setCommunicationDevice(device)

            if (success) {
                currentRoutedDevice = device
                val state = RoutingState.Active(deviceName)
                _routingState.value = state
                Logger.i("Routing active: $deviceName")
                return state
            } else {
                val state = RoutingState.Failed("setCommunicationDevice ha restituito false")
                _routingState.value = state
                Logger.e("setCommunicationDevice failed for $deviceName")
                audioManager.mode = AudioManager.MODE_NORMAL
                return state
            }
        } catch (e: Exception) {
            val state = RoutingState.Failed(e.message ?: "Errore sconosciuto")
            _routingState.value = state
            Logger.e("Exception during routing", e)
            audioManager.mode = AudioManager.MODE_NORMAL
            return state
        }
    }

    fun routeToFirstAvailableBluetooth(): RoutingState {
        val btDevice = findFirstBluetoothCommunicationDevice()
        if (btDevice == null) {
            val state = RoutingState.Failed("Nessun dispositivo di comunicazione Bluetooth trovato")
            _routingState.value = state
            Logger.w("No BT communication devices available")
            return state
        }
        return routeToBluetooth(btDevice)
    }

    fun routeToDeviceByAddress(address: String): RoutingState {
        val targetDevice = getAvailableCommunicationDevices().find { deviceInfo ->
            deviceInfo.address == address
        }
        if (targetDevice == null) {
            val state = RoutingState.Failed("Dispositivo associato non trovato tra quelli disponibili")
            _routingState.value = state
            Logger.w("Device with address ${if (BuildConfig.DEBUG) address else "REDACTED"} not found")
            return state
        }
        return routeToBluetooth(targetDevice)
    }

    /**
     * Route only to the device explicitly selected by the user.
     * If a priority device exists but is not connected we DO NOT fall back to another
     * Bluetooth device (for example an Android Auto head unit).
     */
    private fun bluetoothCommunicationDevices(): List<AudioDeviceInfo> =
        getAvailableCommunicationDevices().filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    private fun deviceMatchesPreference(
        device: AudioDeviceInfo?,
        address: String?,
        name: String?,
    ): Boolean {
        if (device == null) return false

        // Prefer the MAC when Android exposes it. Some OEM builds return an empty
        // AudioDeviceInfo.address for SCO devices, so a unique product name is the
        // safe fallback rather than switching to the first Bluetooth device.
        if (!address.isNullOrBlank() && device.address.isNotBlank()) {
            if (device.address.equals(address, ignoreCase = true)) return true
        }

        return !name.isNullOrBlank() &&
            device.productName?.toString()?.equals(name, ignoreCase = true) == true
    }

    private fun findPreferredBluetoothCommunicationDevice(
        address: String?,
        name: String?,
    ): AudioDeviceInfo? {
        val devices = bluetoothCommunicationDevices()

        if (!address.isNullOrBlank()) {
            devices.firstOrNull {
                it.address.isNotBlank() && it.address.equals(address, ignoreCase = true)
            }?.let { return it }
        }

        if (!name.isNullOrBlank()) {
            val byName = devices.filter {
                it.productName?.toString()?.equals(name, ignoreCase = true) == true
            }
            if (byName.size == 1) return byName.first()
        }

        return null
    }

    fun routeToPreferredBluetooth(address: String?, name: String?): RoutingState {
        val hasPreference = !address.isNullOrBlank() || !name.isNullOrBlank()
        val target = findPreferredBluetoothCommunicationDevice(address, name)

        if (target != null) return routeToBluetooth(target)

        if (hasPreference) {
            val state = RoutingState.Failed("Dispositivo prioritario non disponibile")
            _routingState.value = state
            Logger.w("Preferred Bluetooth device is not currently available")
            return state
        }

        return routeToFirstAvailableBluetooth()
    }

    /**
     * Re-assert only when the SYSTEM communication device is not already the preferred
     * one. This matters because the background CompanionDeviceService and the Activity
     * own different AudioRoutingManager instances: currentRoutedDevice is therefore not
     * a reliable source of truth across lifecycles.
     */
    fun reassertPreferredRouting(address: String?, name: String?): Boolean {
        val systemDevice = audioManager.communicationDevice
        if (deviceMatchesPreference(systemDevice, address, name)) {
            currentRoutedDevice = systemDevice
            val deviceName = systemDevice?.productName?.toString() ?: "Dispositivo Bluetooth"
            _routingState.value = RoutingState.Active(deviceName)
            Logger.d("Preferred routing already active on $deviceName; no SCO renegotiation")
            return true
        }

        return routeToPreferredBluetooth(address, name) is RoutingState.Active
    }

    fun isPreferredCommunicationDeviceActive(address: String?, name: String?): Boolean =
        deviceMatchesPreference(audioManager.communicationDevice, address, name)

    fun clearRouting() {
        Logger.i("Clearing audio routing")
        try {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            currentRoutedDevice = null
            _routingState.value = RoutingState.Idle
            Logger.i("Routing cleared, back to system defaults")
        } catch (e: Exception) {
            Logger.e("Error clearing routing", e)
        }
    }

    fun getAvailableCommunicationDevices(): List<AudioDeviceInfo> {
        return audioManager.availableCommunicationDevices
    }

    fun findFirstBluetoothCommunicationDevice(): AudioDeviceInfo? {
        return audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    fun isBluetoothRouted(): Boolean {
        return currentRoutedDevice != null && _routingState.value is RoutingState.Active
    }

    fun reassertCurrentRouting(): Boolean {
        val device = currentRoutedDevice ?: findFirstBluetoothCommunicationDevice() ?: return false
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val success = audioManager.setCommunicationDevice(device)
            if (success) {
                currentRoutedDevice = device
                val deviceName = device.productName?.toString() ?: "Dispositivo Bluetooth"
                _routingState.value = RoutingState.Active(deviceName)
                Logger.d("Routing re-asserted on $deviceName")
            } else {
                Logger.w("Routing re-assertion rejected")
            }
            success
        } catch (e: Exception) {
            Logger.e("Routing re-assertion failed", e)
            false
        }
    }

    fun currentCommunicationDeviceLabel(): String {
        val device = audioManager.communicationDevice
        return if (device == null) {
            "Nessun communication device"
        } else {
            "${device.productName ?: "Dispositivo"} (${deviceTypeToString(device.type)})"
        }
    }

    fun currentBluetoothCommunicationDeviceName(): String? {
        val device = audioManager.communicationDevice ?: return null
        if (!isBluetoothMicType(device.type)) return null
        return device.productName?.toString()?.takeIf { it.isNotBlank() }
    }

    /**
     * Real-world SCO microphone test.
     *
     * It opens AudioRecord with VOICE_COMMUNICATION, explicitly requests the Bluetooth
     * SCO/BLE input that matches the selected communication device, records for a few
     * seconds, and reports AudioRecord.routedDevice plus actual PCM activity.
     *
     * This test is intentionally diagnostic: it does not save or expose recorded audio.
     */
    suspend fun testBluetoothMicrophone(
        source: MicTestSource = MicTestSource.VOICE_COMMUNICATION,
        durationMs: Long = 6_000L,
        preferredAddress: String? = null,
        preferredName: String? = null,
    ): MicTestResult = withContext(Dispatchers.IO) {
        val configuredTargetName = preferredName?.takeIf { it.isNotBlank() } ?: "Dispositivo Bluetooth"

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _micLiveLevel.value = null
            return@withContext publishMicTest(
                MicTestResult(
                    source = source,
                    verdict = MicTestVerdict.PERMISSION_REQUIRED,
                    targetDeviceName = configuredTargetName,
                    requestedInput = "N/D",
                    actualInput = "N/D",
                    preferredDeviceAccepted = false,
                    communicationDevice = currentCommunicationDeviceLabel(),
                    peak = 0,
                    rms = 0.0,
                    samplesRead = 0,
                    durationMs = 0,
                    details = "SOURCE=${source.label} (${source.audioSource})\nPERMISSION_REQUIRED: android.permission.RECORD_AUDIO non concesso",
                )
            )
        }

        // IMPORTANT: the diagnostic test must be passive. It must never renegotiate SCO
        // or switch the global communication device just because TEST was pressed.
        // Read the actual system route and refuse the test if it is not already the
        // selected priority device. The explicit force-routing control remains the
        // only action allowed to change the communication route.
        val communicationDevice = audioManager.communicationDevice
        val hasPreference = !preferredAddress.isNullOrBlank() || !preferredName.isNullOrBlank()

        if (hasPreference && !deviceMatchesPreference(communicationDevice, preferredAddress, preferredName)) {
            _micLiveLevel.value = null
            return@withContext publishMicTest(
                MicTestResult(
                    source = source,
                    verdict = MicTestVerdict.WRONG_DEVICE,
                    targetDeviceName = configuredTargetName,
                    requestedInput = preferredName ?: preferredAddress ?: "Dispositivo prioritario",
                    actualInput = communicationDevice?.let(::deviceLabel) ?: "Nessun communication device",
                    preferredDeviceAccepted = false,
                    communicationDevice = currentCommunicationDeviceLabel(),
                    peak = 0,
                    rms = 0.0,
                    samplesRead = 0,
                    durationMs = 0,
                    details = buildString {
                        appendLine("SOURCE=${source.label} (${source.audioSource})")
                        appendLine("VERDICT=WRONG_DEVICE")
                        appendLine("TEST_PASSIVE: routing non modificato")
                        appendLine("Priorita attesa: ${preferredName ?: preferredAddress}")
                        appendLine("Communication device reale: ${currentCommunicationDeviceLabel()}")
                        append("Premi 'Forza ${configuredTargetName} ora' prima del test se necessario")
                    },
                )
            )
        }

        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val communicationName = communicationDevice?.productName?.toString()
        val communicationAddress = communicationDevice?.address
        val bluetoothInputs = inputDevices.filter { isBluetoothMicType(it.type) }
        val requestedInput = bluetoothInputs.firstOrNull { input ->
            !communicationAddress.isNullOrBlank() && input.address.isNotBlank() &&
                input.address.equals(communicationAddress, ignoreCase = true)
        } ?: bluetoothInputs.firstOrNull { input ->
            communicationName != null &&
                input.productName?.toString()?.equals(communicationName, ignoreCase = true) == true
        }

        if (requestedInput == null) {
            val inputs = inputDevices.joinToString { deviceLabel(it) }
            _micLiveLevel.value = null
            return@withContext publishMicTest(
                MicTestResult(
                    source = source,
                    verdict = MicTestVerdict.WRONG_DEVICE,
                    targetDeviceName = configuredTargetName,
                    requestedInput = "Nessun input BT SCO/BLE disponibile",
                    actualInput = "N/D",
                    preferredDeviceAccepted = false,
                    communicationDevice = currentCommunicationDeviceLabel(),
                    peak = 0,
                    rms = 0.0,
                    samplesRead = 0,
                    durationMs = 0,
                    details = buildString {
                        appendLine("SOURCE=${source.label} (${source.audioSource})")
                        appendLine("VERDICT=WRONG_DEVICE")
                        appendLine("Nessun AudioDeviceInfo di input Bluetooth SCO/BLE trovato")
                        appendLine("Communication device: ${currentCommunicationDeviceLabel()}")
                        appendLine("Input disponibili: $inputs")
                    }.trim(),
                )
            )
        }

        var recorder: AudioRecord? = null
        try {
            val sampleRate = 16_000
            val channelMask = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            val bufferSize = maxOf(minBuffer, sampleRate / 2 * 2, 4096)

            recorder = AudioRecord.Builder()
                .setAudioSource(source.audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord non inizializzato")
            }

            val preferredAccepted = recorder.setPreferredDevice(requestedInput)
            recorder.startRecording()
            Thread.sleep(250)

            val start = SystemClock.elapsedRealtime()
            val calibrationMs = minOf(MIC_CALIBRATION_MS, maxOf(700L, durationMs / 3))
            val pcm = ShortArray(1024)

            var baselinePeak = 0
            var baselineSumSquares = 0.0
            var baselineSamples = 0L

            var speechPeak = 0
            var speechSumSquares = 0.0
            var speechSamples = 0L

            var totalSamples = 0L
            var lastActualDevice: AudioDeviceInfo? = recorder.routedDevice
            var readErrors = 0
            var lastUiUpdate = 0L

            while (SystemClock.elapsedRealtime() - start < durationMs) {
                val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    readErrors++
                    if (readErrors >= 3) break
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - start
                val calibrating = elapsed < calibrationMs
                var blockPeak = 0
                var blockSumSquares = 0.0

                for (i in 0 until read) {
                    val value = kotlin.math.abs(pcm[i].toInt())
                    if (value > blockPeak) blockPeak = value
                    blockSumSquares += value.toDouble() * value.toDouble()
                }

                if (calibrating) {
                    if (blockPeak > baselinePeak) baselinePeak = blockPeak
                    baselineSumSquares += blockSumSquares
                    baselineSamples += read
                } else {
                    if (blockPeak > speechPeak) speechPeak = blockPeak
                    speechSumSquares += blockSumSquares
                    speechSamples += read
                }

                totalSamples += read
                recorder.routedDevice?.let { lastActualDevice = it }

                if (now - lastUiUpdate >= 100L) {
                    val blockRms = sqrt(blockSumSquares / read.toDouble())
                    val baselineRmsNow = if (baselineSamples > 0)
                        sqrt(baselineSumSquares / baselineSamples.toDouble()) else 0.0
                    val thresholdRmsNow = maxOf(MIC_ABSOLUTE_MIN_RMS, baselineRmsNow * MIC_BASELINE_RMS_MULTIPLIER)
                    val thresholdPeakNow = maxOf(MIC_ABSOLUTE_MIN_PEAK, (baselinePeak * MIC_BASELINE_PEAK_MULTIPLIER).toInt())
                    _micLiveLevel.value = MicLiveLevel(
                        source = source,
                        phase = if (calibrating) MicTestPhase.CALIBRATING else MicTestPhase.SPEAKING,
                        rms = blockRms,
                        peak = blockPeak,
                        dbfs = amplitudeToDbfs(blockRms),
                        thresholdRms = thresholdRmsNow,
                        thresholdPeak = thresholdPeakNow,
                        thresholdDbfs = amplitudeToDbfs(thresholdRmsNow),
                        elapsedMs = elapsed,
                        durationMs = durationMs,
                    )
                    lastUiUpdate = now
                }
            }

            val actualInput = lastActualDevice
            val baselineRms = if (baselineSamples > 0) sqrt(baselineSumSquares / baselineSamples.toDouble()) else 0.0
            val speechRms = if (speechSamples > 0) sqrt(speechSumSquares / speechSamples.toDouble()) else 0.0
            val thresholdRms = maxOf(MIC_ABSOLUTE_MIN_RMS, baselineRms * MIC_BASELINE_RMS_MULTIPLIER)
            val thresholdPeak = maxOf(MIC_ABSOLUTE_MIN_PEAK, (baselinePeak * MIC_BASELINE_PEAK_MULTIPLIER).toInt())

            val routeMatchesRequested = actualInput != null &&
                isBluetoothMicType(actualInput.type) &&
                (actualInput.id == requestedInput.id ||
                    actualInput.productName?.toString()?.equals(
                        requestedInput.productName?.toString(), ignoreCase = true
                    ) == true)

            val audioPresent = speechRms >= thresholdRms && speechPeak >= thresholdPeak
            val verdict = when {
                !routeMatchesRequested -> MicTestVerdict.WRONG_DEVICE
                !audioPresent -> MicTestVerdict.NO_AUDIO
                else -> MicTestVerdict.PASS
            }

            val elapsedTotal = SystemClock.elapsedRealtime() - start
            val testedDeviceName = requestedInput.productName?.toString()?.takeIf { it.isNotBlank() }
                ?: configuredTargetName

            val result = MicTestResult(
                source = source,
                verdict = verdict,
                targetDeviceName = testedDeviceName,
                requestedInput = deviceLabel(requestedInput),
                actualInput = actualInput?.let(::deviceLabel) ?: "Nessun routedDevice riportato",
                preferredDeviceAccepted = preferredAccepted,
                communicationDevice = currentCommunicationDeviceLabel(),
                peak = speechPeak,
                rms = speechRms,
                samplesRead = totalSamples,
                durationMs = elapsedTotal,
                baselinePeak = baselinePeak,
                baselineRms = baselineRms,
                thresholdPeak = thresholdPeak,
                thresholdRms = thresholdRms,
                routeMatchesRequested = routeMatchesRequested,
                details = buildString {
                    appendLine("SOURCE=${source.label} (${source.audioSource})")
                    appendLine("VERDICT=${verdict.name}")
                    appendLine("Requested input: ${deviceLabel(requestedInput)}")
                    appendLine("setPreferredDevice accepted: $preferredAccepted")
                    appendLine("Actual routed input: ${actualInput?.let(::deviceLabel) ?: "null"}")
                    appendLine("Route matches requested BT input: $routeMatchesRequested")
                    appendLine("Communication device: ${currentCommunicationDeviceLabel()}")
                    appendLine("Calibration: ${calibrationMs} ms (resta in silenzio)")
                    appendLine("Baseline peak: $baselinePeak / 32767")
                    appendLine("Baseline RMS: ${"%.1f".format(baselineRms)} (${"%.1f".format(amplitudeToDbfs(baselineRms))} dBFS)")
                    appendLine("Voice peak: $speechPeak / 32767")
                    appendLine("Voice RMS: ${"%.1f".format(speechRms)} (${"%.1f".format(amplitudeToDbfs(speechRms))} dBFS)")
                    appendLine("Threshold peak: $thresholdPeak")
                    appendLine("Threshold RMS: ${"%.1f".format(thresholdRms)} (${"%.1f".format(amplitudeToDbfs(thresholdRms))} dBFS)")
                    appendLine("Samples read: $totalSamples")
                    appendLine("Duration: $elapsedTotal ms")
                    appendLine("All BT inputs: ${bluetoothInputs.joinToString { deviceLabel(it) }}")
                    appendLine("All inputs: ${inputDevices.joinToString { deviceLabel(it) }}")
                }.trim(),
            )

            _micLiveLevel.value = MicLiveLevel(
                source = source,
                phase = MicTestPhase.FINISHED,
                rms = speechRms,
                peak = speechPeak,
                dbfs = amplitudeToDbfs(speechRms),
                thresholdRms = thresholdRms,
                thresholdPeak = thresholdPeak,
                thresholdDbfs = amplitudeToDbfs(thresholdRms),
                elapsedMs = elapsedTotal,
                durationMs = durationMs,
            )

            Logger.i("Mic diagnostic result:\n${result.details}")
            publishMicTest(result)
        } catch (t: Throwable) {
            Logger.e("Bluetooth microphone diagnostic failed", t)
            _micLiveLevel.value = null
            publishMicTest(
                MicTestResult(
                    source = source,
                    verdict = MicTestVerdict.ERROR,
                    targetDeviceName = requestedInput.productName?.toString()?.takeIf { it.isNotBlank() }
                        ?: configuredTargetName,
                    requestedInput = deviceLabel(requestedInput),
                    actualInput = recorder?.routedDevice?.let(::deviceLabel) ?: "N/D",
                    preferredDeviceAccepted = false,
                    communicationDevice = currentCommunicationDeviceLabel(),
                    peak = 0,
                    rms = 0.0,
                    samplesRead = 0,
                    durationMs = 0,
                    details = buildString {
                        appendLine("SOURCE=${source.label} (${source.audioSource})")
                        append("ERROR=${t.javaClass.simpleName}: ${t.message ?: "nessun messaggio"}")
                    },
                )
            )
        } finally {
            try { recorder?.stop() } catch (_: Throwable) {}
            try { recorder?.release() } catch (_: Throwable) {}
        }
    }

    private fun publishMicTest(result: MicTestResult): MicTestResult {
        _lastMicTestResult.value = result
        _micTestResults.value = _micTestResults.value.toMutableMap().apply {
            put(result.source, result)
        }
        return result
    }

    private fun refreshAvailableDevices() {
        val commDevices = audioManager.availableCommunicationDevices
        val btDevices = commDevices
            .filter { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            .map { device ->
                BluetoothAudioDevice(
                    deviceInfo = device,
                    name = device.productName?.toString() ?: "Dispositivo BT sconosciuto",
                    type = device.type,
                    typeLabel = deviceTypeToString(device.type),
                )
            }

        _availableDevices.value = btDevices
        Logger.d("Available BT devices: ${btDevices.map { "${it.name} (${it.typeLabel})" }}")
    }

    private fun deviceLabel(device: AudioDeviceInfo): String =
        "${device.productName ?: "Dispositivo"} (${deviceTypeToString(device.type)}, id=${device.id})"

    companion object {
        const val MIC_CALIBRATION_MS = 1_200L
        const val MIC_ABSOLUTE_MIN_RMS = 250.0
        const val MIC_ABSOLUTE_MIN_PEAK = 1_500
        const val MIC_BASELINE_RMS_MULTIPLIER = 2.5
        const val MIC_BASELINE_PEAK_MULTIPLIER = 2.0

        fun amplitudeToDbfs(amplitude: Double): Double {
            if (amplitude <= 0.0) return -96.0
            val normalized = (amplitude / 32767.0).coerceIn(0.000001, 1.0)
            return 20.0 * kotlin.math.log10(normalized)
        }

        fun isBluetoothMicType(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            -> true
            else -> false
        }

        fun deviceTypeToString(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Cuffie BLE"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Altoparlante BLE"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfono integrato"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altoparlante integrato"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Cuffie con filo"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "Dispositivo USB"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "Cuffie USB"
            else -> "Tipo $type"
        }
    }
}
