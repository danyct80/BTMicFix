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

    /** Result of the real microphone diagnostic test. */
    data class MicTestResult(
        val verdict: MicTestVerdict,
        val requestedInput: String,
        val actualInput: String,
        val preferredDeviceAccepted: Boolean,
        val communicationDevice: String,
        val peak: Int,
        val rms: Double,
        val samplesRead: Long,
        val durationMs: Long,
        val details: String,
    ) {
        val summary: String
            get() = when (verdict) {
                MicTestVerdict.PASS -> "Cardo usato realmente come microfono"
                MicTestVerdict.NO_AUDIO -> "Cardo selezionato, ma nessun audio ricevuto"
                MicTestVerdict.WRONG_DEVICE -> "Android sta usando un altro microfono"
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

    /**
     * Real-world SCO microphone test.
     *
     * It opens AudioRecord with VOICE_COMMUNICATION, explicitly requests the Bluetooth
     * SCO/BLE input that matches the selected communication device, records for a few
     * seconds, and reports AudioRecord.routedDevice plus actual PCM activity.
     *
     * This test is intentionally diagnostic: it does not save or expose recorded audio.
     */
    suspend fun testBluetoothMicrophone(durationMs: Long = 6_000L): MicTestResult =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext publishMicTest(
                    MicTestResult(
                        verdict = MicTestVerdict.PERMISSION_REQUIRED,
                        requestedInput = "N/D",
                        actualInput = "N/D",
                        preferredDeviceAccepted = false,
                        communicationDevice = currentCommunicationDeviceLabel(),
                        peak = 0,
                        rms = 0.0,
                        samplesRead = 0,
                        durationMs = 0,
                        details = "PERMISSION_REQUIRED: android.permission.RECORD_AUDIO non concesso",
                    )
                )
            }

            val communicationDevice =
                currentRoutedDevice ?: findFirstBluetoothCommunicationDevice()

            // Establish communication mode first: on some OEM stacks the SCO input only
            // becomes visible after the communication device has been selected.
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationDevice?.let {
                val accepted = audioManager.setCommunicationDevice(it)
                Logger.i("Mic test initial communication device accepted=$accepted")
            }
            Thread.sleep(250)

            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            val communicationName = communicationDevice?.productName?.toString()

            val bluetoothInputs = inputDevices.filter { isBluetoothMicType(it.type) }
            val requestedInput = bluetoothInputs.firstOrNull {
                communicationName != null &&
                    it.productName?.toString()?.equals(communicationName, ignoreCase = true) == true
            } ?: bluetoothInputs.firstOrNull()

            if (requestedInput == null) {
                val inputs = inputDevices.joinToString { deviceLabel(it) }
                return@withContext publishMicTest(
                    MicTestResult(
                        verdict = MicTestVerdict.WRONG_DEVICE,
                        requestedInput = "Nessun input BT SCO/BLE disponibile",
                        actualInput = "N/D",
                        preferredDeviceAccepted = false,
                        communicationDevice = currentCommunicationDeviceLabel(),
                        peak = 0,
                        rms = 0.0,
                        samplesRead = 0,
                        durationMs = 0,
                        details = buildString {
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
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
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

                // Give AudioPolicy a brief moment to settle before inspecting routedDevice.
                Thread.sleep(250)

                val start = SystemClock.elapsedRealtime()
                val pcm = ShortArray(1024)
                var peak = 0
                var sumSquares = 0.0
                var samplesRead = 0L
                var lastActualDevice: AudioDeviceInfo? = recorder.routedDevice
                var readErrors = 0

                while (SystemClock.elapsedRealtime() - start < durationMs) {
                    val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        for (i in 0 until read) {
                            val value = kotlin.math.abs(pcm[i].toInt())
                            if (value > peak) peak = value
                            sumSquares += value.toDouble() * value.toDouble()
                        }
                        samplesRead += read
                        recorder.routedDevice?.let { lastActualDevice = it }
                    } else {
                        readErrors++
                        if (readErrors >= 3) break
                    }
                }

                val actualInput = lastActualDevice
                val routedToBluetooth = actualInput != null && isBluetoothMicType(actualInput.type)
                val rms = if (samplesRead > 0) sqrt(sumSquares / samplesRead.toDouble()) else 0.0
                // Deliberately low threshold: the user should speak clearly during the test.
                val audioPresent = peak >= 200 && rms >= 20.0

                val verdict = when {
                    !routedToBluetooth -> MicTestVerdict.WRONG_DEVICE
                    !audioPresent -> MicTestVerdict.NO_AUDIO
                    else -> MicTestVerdict.PASS
                }

                val result = MicTestResult(
                    verdict = verdict,
                    requestedInput = deviceLabel(requestedInput),
                    actualInput = actualInput?.let(::deviceLabel) ?: "Nessun routedDevice riportato",
                    preferredDeviceAccepted = preferredAccepted,
                    communicationDevice = currentCommunicationDeviceLabel(),
                    peak = peak,
                    rms = rms,
                    samplesRead = samplesRead,
                    durationMs = SystemClock.elapsedRealtime() - start,
                    details = buildString {
                        appendLine("VERDICT=${verdict.name}")
                        appendLine("Requested input: ${deviceLabel(requestedInput)}")
                        appendLine("setPreferredDevice accepted: $preferredAccepted")
                        appendLine("Actual routed input: ${actualInput?.let(::deviceLabel) ?: "null"}")
                        appendLine("Communication device: ${currentCommunicationDeviceLabel()}")
                        appendLine("Peak PCM16: $peak / 32767")
                        appendLine("RMS PCM16: ${"%.1f".format(rms)}")
                        appendLine("Samples read: $samplesRead")
                        appendLine("Duration: ${SystemClock.elapsedRealtime() - start} ms")
                        appendLine("All BT inputs: ${bluetoothInputs.joinToString { deviceLabel(it) }}")
                        appendLine("All inputs: ${inputDevices.joinToString { deviceLabel(it) }}")
                    }.trim(),
                )

                Logger.i("Mic diagnostic result:\n${result.details}")
                publishMicTest(result)
            } catch (t: Throwable) {
                Logger.e("Bluetooth microphone diagnostic failed", t)
                publishMicTest(
                    MicTestResult(
                        verdict = MicTestVerdict.ERROR,
                        requestedInput = deviceLabel(requestedInput),
                        actualInput = recorder?.routedDevice?.let(::deviceLabel) ?: "N/D",
                        preferredDeviceAccepted = false,
                        communicationDevice = currentCommunicationDeviceLabel(),
                        peak = 0,
                        rms = 0.0,
                        samplesRead = 0,
                        durationMs = 0,
                        details = "ERROR=${t.javaClass.simpleName}: ${t.message ?: "nessun messaggio"}",
                    )
                )
            } finally {
                try {
                    recorder?.stop()
                } catch (_: Throwable) {
                }
                try {
                    recorder?.release()
                } catch (_: Throwable) {
                }
            }
        }

    private fun publishMicTest(result: MicTestResult): MicTestResult {
        _lastMicTestResult.value = result
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
