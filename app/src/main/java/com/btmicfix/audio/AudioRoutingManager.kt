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
import com.btmicfix.util.Logger
import com.btmicfix.util.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Audio routing and real microphone diagnostics. */
class AudioRoutingManager(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService<AudioManager>()
            ?: throw IllegalStateException("AudioManager unavailable")

    private val preferences = Preferences(context)

    private val _routingState = MutableStateFlow<RoutingState>(RoutingState.Idle)
    val routingState: StateFlow<RoutingState> = _routingState.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<BluetoothAudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothAudioDevice>> = _availableDevices.asStateFlow()

    private val _lastMicTestResult = MutableStateFlow<MicTestResult?>(null)
    val lastMicTestResult: StateFlow<MicTestResult?> = _lastMicTestResult.asStateFlow()

    private val _micTestResults = MutableStateFlow<Map<MicTestSource, MicTestResult>>(emptyMap())
    val micTestResults: StateFlow<Map<MicTestSource, MicTestResult>> = _micTestResults.asStateFlow()

    data class MicTestKey(
        val source: MicTestSource,
        val routeMode: MicRouteMode,
        val scenario: MicTestScenario = MicTestScenario.BASELINE,
    )

    private val _allMicTestResults = MutableStateFlow<Map<MicTestKey, MicTestResult>>(emptyMap())
    val allMicTestResults: StateFlow<Map<MicTestKey, MicTestResult>> = _allMicTestResults.asStateFlow()

    private val _micLiveLevel = MutableStateFlow<MicLiveLevel?>(null)
    val micLiveLevel: StateFlow<MicLiveLevel?> = _micLiveLevel.asStateFlow()

    private var currentRoutedDevice: AudioDeviceInfo? = null
    private var lastObservedPriorityInput: AudioDeviceInfo? = null
    private var monitoring = false

    private val communicationDeviceChangedListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            Logger.i("Communication device changed: ${device?.let(::deviceLabel) ?: "none"}")
            refreshAvailableDevices()
            syncRoutingStateWithSystem()
        }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            Logger.i("Audio devices added: ${addedDevices.map { deviceTypeToString(it.type) }}")
            refreshAvailableDevices()
            syncRoutingStateWithSystem()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            Logger.i("Audio devices removed: ${removedDevices.map { deviceTypeToString(it.type) }}")
            // Never clear from a stale cached id. Android may already have moved to another route.
            refreshAvailableDevices()
            syncRoutingStateWithSystem()
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

    enum class MicRouteMode(val label: String) {
        /** Observe the input Android chooses for the already-active communication route. */
        SYSTEM_DEFAULT("ROUTE_ATTIVA"),
        /** Ask AudioRecord for the safely-identified priority input. */
        TARGET_PREFERRED("TARGET_ESPLICITO"),
    }

    enum class MicTestScenario(val label: String) {
        BASELINE("BASELINE"),
        SHIZUKU_FORCED("SHIZUKU_FORCED"),
    }

    enum class MicTestPhase {
        CALIBRATING,
        SPEAKING,
        FINISHED,
    }

    enum class MicTestVerdict {
        PASS,
        NO_AUDIO,
        WRONG_DEVICE,
        INDETERMINATE,
        PREFERRED_REJECTED,
        TARGET_NOT_CONFIGURED,
        TARGET_NOT_CONNECTED,
        PERMISSION_REQUIRED,
        ERROR,
    }

    data class MicLiveLevel(
        val source: MicTestSource,
        val routeMode: MicRouteMode,
        val phase: MicTestPhase,
        val rms: Double,
        val dbfs: Double,
        val thresholdRms: Double,
        val thresholdDbfs: Double,
    )

    data class MicTestResult(
        val source: MicTestSource,
        val routeMode: MicRouteMode,
        val scenario: MicTestScenario = MicTestScenario.BASELINE,
        val verdict: MicTestVerdict,
        val targetDeviceName: String,
        val requestedInput: String,
        val actualInput: String,
        val preferredDeviceAccepted: Boolean,
        val communicationDevice: String,
        val peak: Int,
        val rms: Double,
        val baselinePeak: Int = 0,
        val baselineRms: Double = 0.0,
        val thresholdPeak: Int = 0,
        val thresholdRms: Double = 0.0,
        val peakDbfs: Double = DBFS_FLOOR,
        val rmsDbfs: Double = DBFS_FLOOR,
        val thresholdDbfs: Double = DBFS_FLOOR,
        val samplesRead: Long,
        val durationMs: Long,
        val details: String,
        val routeMatchesRequested: Boolean = false,
    ) {
        val summary: String
            get() = when (verdict) {
                MicTestVerdict.PASS -> "$targetDeviceName usato realmente come microfono"
                MicTestVerdict.NO_AUDIO -> "$targetDeviceName instradato, voce sotto soglia"
                MicTestVerdict.WRONG_DEVICE -> "Ingresso reale diverso da $targetDeviceName"
                MicTestVerdict.INDETERMINATE -> "Ingresso Bluetooth reale non identificabile con certezza"
                MicTestVerdict.PREFERRED_REJECTED -> "Audio dal target, ma setPreferredDevice rifiutato"
                MicTestVerdict.TARGET_NOT_CONFIGURED -> "Nessun dispositivo prioritario selezionato"
                MicTestVerdict.TARGET_NOT_CONNECTED -> "$targetDeviceName non disponibile"
                MicTestVerdict.PERMISSION_REQUIRED -> "Permesso microfono necessario"
                MicTestVerdict.ERROR -> "Test microfono non riuscito"
            }
    }

    fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        audioManager.addOnCommunicationDeviceChangedListener(
            context.mainExecutor,
            communicationDeviceChangedListener,
        )
        refreshAvailableDevices()
        syncRoutingStateWithSystem()
    }

    fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        try {
            audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceChangedListener)
        } catch (_: Exception) {}
    }

    private fun bluetoothCommunicationDevices(): List<AudioDeviceInfo> =
        audioManager.availableCommunicationDevices.filter(::isBluetoothMicDevice)

    private fun findPreferredBluetoothCommunicationDevice(
        address: String?,
        name: String?,
    ): AudioDeviceInfo? {
        val devices = bluetoothCommunicationDevices()

        if (!address.isNullOrBlank()) {
            devices.firstOrNull { device ->
                device.address.isNotBlank() &&
                    device.address.equals(address, ignoreCase = true)
            }?.let { return it }
        }

        val normalizedName = name?.trim()?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
        if (normalizedName != null) {
            val matches = devices.filter { device ->
                device.productName?.toString()?.trim()
                    ?.equals(normalizedName, ignoreCase = true) == true
            }
            if (matches.size == 1) return matches.first()
            if (matches.size > 1) {
                Logger.w("Preferred Bluetooth name is ambiguous: $normalizedName")
            }
        }
        return null
    }

    private fun deviceMatchesPreference(
        device: AudioDeviceInfo?,
        address: String?,
        name: String?,
    ): Boolean {
        if (device == null || !isBluetoothMicDevice(device)) return false

        if (!address.isNullOrBlank() && device.address.isNotBlank() &&
            device.address.equals(address, ignoreCase = true)
        ) return true

        val normalizedName = name?.trim()?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
        if (normalizedName != null &&
            device.productName?.toString()?.trim()?.equals(normalizedName, ignoreCase = true) == true
        ) {
            val sameName = bluetoothCommunicationDevices().filter { candidate ->
                candidate.productName?.toString()?.trim()
                    ?.equals(normalizedName, ignoreCase = true) == true
            }
            if (sameName.size == 1 ||
                (sameName.isEmpty() && audioManager.communicationDevice?.id == device.id)
            ) return true
        }

        val resolved = findPreferredBluetoothCommunicationDevice(address, name) ?: return false
        return sameAudioEndpoint(device, resolved)
    }

    private fun sameAudioEndpoint(a: AudioDeviceInfo?, b: AudioDeviceInfo?): Boolean {
        if (a == null || b == null) return false
        if (a.id == b.id) return true
        if (a.address.isNotBlank() && b.address.isNotBlank() &&
            a.address.equals(b.address, ignoreCase = true)
        ) return true

        if (!isBluetoothMicDevice(a) || !isBluetoothMicDevice(b)) return false
        val bName = b.productName?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && !looksLikeMac(it) } ?: return false
        val sameName = bluetoothCommunicationDevices().filter { candidate ->
            candidate.productName?.toString()?.trim()?.equals(bName, ignoreCase = true) == true
        }
        return sameName.size == 1 &&
            a.productName?.toString()?.trim()?.equals(bName, ignoreCase = true) == true
    }

    private fun routeToPreferredBluetooth(address: String?, name: String?): RoutingState {
        if (address.isNullOrBlank() && name.isNullOrBlank()) {
            return RoutingState.Failed("Nessun dispositivo prioritario selezionato").also {
                _routingState.value = it
            }
        }

        val current = audioManager.communicationDevice
        if (deviceMatchesPreference(current, address, name)) {
            currentRoutedDevice = current
            return RoutingState.Active(
                current?.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: name?.takeIf { it.isNotBlank() }
                    ?: "Dispositivo Bluetooth"
            ).also { _routingState.value = it }
        }

        val target = findPreferredBluetoothCommunicationDevice(address, name)
            ?: return RoutingState.Failed("Dispositivo prioritario non connesso").also {
                _routingState.value = it
            }

        return routeToBluetooth(target)
    }

    /**
     * Wait until communicationDevice confirms the request; boolean acceptance is not enough.
     * Routing requests are serialized process-wide because Activity and CompanionDeviceService
     * use separate AudioRoutingManager instances but control the same AudioManager state.
     */
    suspend fun routeToPreferredBluetoothAndWait(
        address: String?,
        name: String?,
        timeoutMs: Long = 30_000L,
        pollMs: Long = 75L,
    ): RoutingState = routingMutex.withLock {
        val requested = routeToPreferredBluetooth(address, name)
        if (requested is RoutingState.Failed) return@withLock requested

        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(250L)
        try {
            do {
                val actual = audioManager.communicationDevice
                if (deviceMatchesPreference(actual, address, name)) {
                    currentRoutedDevice = actual
                    return@withLock RoutingState.Active(
                        actual?.productName?.toString()?.takeIf { it.isNotBlank() }
                            ?: name?.takeIf { it.isNotBlank() }
                            ?: "Dispositivo Bluetooth"
                    ).also { _routingState.value = it }
                }
                delay(pollMs.coerceAtLeast(25L))
            } while (SystemClock.elapsedRealtime() < deadline)

            clearRouting()
            RoutingState.Failed("Timeout: Android non ha attivato il dispositivo prioritario").also {
                _routingState.value = it
            }
        } catch (cancelled: CancellationException) {
            // Cancel the pending communication-device request made by this app. Do not leave
            // MODE_IN_COMMUNICATION or a delayed device switch behind after disconnect/cancel.
            clearRouting()
            throw cancelled
        }
    }

    private fun routeToBluetooth(device: AudioDeviceInfo): RoutingState {
        val deviceName = device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: "Dispositivo Bluetooth"
        _routingState.value = RoutingState.Routing(deviceName)

        return try {
            synchronized(audioModeLock) {
                if (savedAudioModeBeforeRouting == null) {
                    savedAudioModeBeforeRouting = audioManager.mode
                }
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val accepted = audioManager.setCommunicationDevice(device)
            if (!accepted) {
                restoreOwnAudioMode()
                RoutingState.Failed("setCommunicationDevice ha restituito false").also {
                    _routingState.value = it
                }
            } else {
                val actual = audioManager.communicationDevice
                if (sameAudioEndpoint(actual, device)) {
                    currentRoutedDevice = actual
                    RoutingState.Active(deviceName).also { _routingState.value = it }
                } else {
                    RoutingState.Routing(deviceName).also { _routingState.value = it }
                }
            }
        } catch (e: Exception) {
            restoreOwnAudioMode()
            RoutingState.Failed(e.message ?: "Errore routing").also {
                _routingState.value = it
            }
        }
    }

    private fun clearRouting() {
        try { audioManager.clearCommunicationDevice() } catch (e: Exception) {
            Logger.e("Error clearing communication device", e)
        } finally {
            restoreOwnAudioMode()
            currentRoutedDevice = null
            _routingState.value = RoutingState.Idle
        }
    }

    /** Clear only if the real system route is still the selected priority device. */
    fun clearRoutingIfPreferred(address: String?, name: String?): Boolean {
        if (!deviceMatchesPreference(audioManager.communicationDevice, address, name)) {
            // Another route already owns communication. Never force the global audio mode back
            // to a stale value in this branch: that could disturb Android Auto / another call.
            abandonOwnAudioModeOwnership()
            syncRoutingStateWithSystem()
            return false
        }
        clearRouting()
        return true
    }

    private fun restoreOwnAudioMode() {
        val oldMode = synchronized(audioModeLock) {
            val value = savedAudioModeBeforeRouting
            savedAudioModeBeforeRouting = null
            value
        } ?: return

        try {
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = oldMode
            }
        } catch (e: Exception) {
            Logger.w("Could not restore previous audio mode: ${e.javaClass.simpleName}")
        }
    }

    private fun abandonOwnAudioModeOwnership() {
        synchronized(audioModeLock) {
            savedAudioModeBeforeRouting = null
        }
    }

    fun isPreferredBluetoothAvailable(address: String?, name: String?): Boolean {
        if (address.isNullOrBlank() && name.isNullOrBlank()) return false
        return findPreferredBluetoothCommunicationDevice(address, name) != null ||
            deviceMatchesPreference(audioManager.communicationDevice, address, name)
    }

    fun isPreferredCommunicationDeviceActive(address: String?, name: String?): Boolean =
        deviceMatchesPreference(audioManager.communicationDevice, address, name)

    fun currentCommunicationDeviceLabel(): String {
        val device = audioManager.communicationDevice
        return device?.let(::deviceLabel) ?: "Nessun communication device"
    }

    fun currentBluetoothCommunicationDeviceName(): String? {
        val device = audioManager.communicationDevice ?: return null
        if (!isBluetoothMicDevice(device)) return null
        return device.productName?.toString()?.takeIf { it.isNotBlank() }
    }

    suspend fun testBluetoothMicrophone(
        source: MicTestSource,
        durationMs: Long = 4_500L,
        preferredAddress: String? = null,
        preferredName: String? = null,
        targetDisplayName: String? = null,
        routeMode: MicRouteMode = MicRouteMode.TARGET_PREFERRED,
        scenario: MicTestScenario = MicTestScenario.BASELINE,
    ): MicTestResult = withContext(Dispatchers.IO) {
        val targetLabel = targetDisplayName?.takeIf { it.isNotBlank() }
            ?: preferredName?.takeIf { it.isNotBlank() }
            ?: "Dispositivo prioritario"

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext publishMicTest(
                emptyMicResult(
                    source, routeMode, MicTestVerdict.PERMISSION_REQUIRED, targetLabel,
                    scenario = scenario,
                    details = "Permesso RECORD_AUDIO non concesso",
                )
            )
        }

        if (preferredAddress.isNullOrBlank() && preferredName.isNullOrBlank()) {
            return@withContext publishMicTest(
                emptyMicResult(
                    source, routeMode, MicTestVerdict.TARGET_NOT_CONFIGURED, targetLabel,
                    scenario = scenario,
                    details = "Nessun dispositivo prioritario configurato",
                )
            )
        }

        if (!isPreferredBluetoothAvailable(preferredAddress, preferredName)) {
            return@withContext publishMicTest(
                emptyMicResult(
                    source, routeMode, MicTestVerdict.TARGET_NOT_CONNECTED, targetLabel,
                    scenario = scenario,
                    details = "Il dispositivo prioritario non e disponibile come communication device Bluetooth",
                )
            )
        }

        val communicationDevice = audioManager.communicationDevice
        if (!deviceMatchesPreference(communicationDevice, preferredAddress, preferredName)) {
            return@withContext publishMicTest(
                emptyMicResult(
                    source, routeMode, MicTestVerdict.WRONG_DEVICE, targetLabel,
                    scenario = scenario,
                    actualInput = communicationDevice?.let(::deviceLabel) ?: "Nessun communication device",
                    details = "Il communication device reale non coincide con il dispositivo prioritario",
                )
            )
        }

        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val bluetoothInputs = inputDevices.filter(::isBluetoothMicDevice)
        val communicationAddress = communicationDevice?.address
        val communicationName = communicationDevice?.productName?.toString()?.trim()

        val exactInput = bluetoothInputs.firstOrNull { input ->
            !communicationAddress.isNullOrBlank() && input.address.isNotBlank() &&
                input.address.equals(communicationAddress, ignoreCase = true)
        } ?: bluetoothInputs.filter { input ->
            !communicationName.isNullOrBlank() &&
                input.productName?.toString()?.trim()
                    ?.equals(communicationName, ignoreCase = true) == true
        }.singleOrNull()

        val rememberedInput = lastObservedPriorityInput?.let { remembered ->
            inputDevices.firstOrNull { it.id == remembered.id && isBluetoothMicDevice(it) }
        }
        val requestedInput = exactInput ?: rememberedInput

        if (routeMode == MicRouteMode.TARGET_PREFERRED && requestedInput == null) {
            return@withContext publishMicTest(
                emptyMicResult(
                    source, routeMode, MicTestVerdict.INDETERMINATE, targetLabel,
                    scenario = scenario,
                    requestedInput = "Target input non identificabile in modo univoco",
                    details = buildString {
                        appendLine("Communication device: ${currentCommunicationDeviceLabel()}")
                        appendLine("Input Bluetooth disponibili:")
                        bluetoothInputs.forEach { appendLine("- ${deviceLabel(it)}") }
                    }.trim(),
                )
            )
        }

        val sampleRate = 16_000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            return@withContext publishMicTest(
                emptyMicResult(
                    source, routeMode, MicTestVerdict.ERROR, targetLabel,
                    scenario = scenario,
                    details = "AudioRecord.getMinBufferSize=$minBuffer",
                )
            )
        }

        val bufferSize = max(minBuffer * 2, 4096)
        val recorder = try {
            AudioRecord.Builder()
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
        } catch (t: Throwable) {
            return@withContext publishMicTest(
                emptyMicResult(
                    source, routeMode, MicTestVerdict.ERROR, targetLabel,
                    scenario = scenario,
                    details = "AudioRecord build: ${t.javaClass.simpleName}: ${t.message}",
                )
            )
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext publishMicTest(
                emptyMicResult(
                    source, routeMode, MicTestVerdict.ERROR, targetLabel,
                    scenario = scenario,
                    details = "AudioRecord non inizializzato",
                )
            )
        }

        var preferredAccepted = false
        var actualInput: AudioDeviceInfo? = null
        val observedRoutedDeviceIds = linkedSetOf<Int>()
        val observedRoutedDeviceLabels = linkedSetOf<String>()
        var baselineSumSquares = 0.0
        var baselineSamples = 0L
        var baselinePeak = 0
        var speechSumSquares = 0.0
        var speechSamples = 0L
        var speechPeak = 0
        var totalSamples = 0L
        var readErrors = 0
        val startMs = SystemClock.elapsedRealtime()
        val baselineEndMs = startMs + MIC_BASELINE_MS.coerceAtMost(durationMs / 2)
        val endMs = startMs + durationMs.coerceAtLeast(MIC_BASELINE_MS + 1_000L)
        val pcm = ShortArray(bufferSize / 2)

        try {
            preferredAccepted = when (routeMode) {
                MicRouteMode.SYSTEM_DEFAULT -> false
                MicRouteMode.TARGET_PREFERRED -> recorder.setPreferredDevice(requestedInput!!)
            }
            recorder.startRecording()

            fun observeRoutedDevice() {
                recorder.routedDevice?.let { routed ->
                    actualInput = routed
                    observedRoutedDeviceIds += routed.id
                    observedRoutedDeviceLabels += deviceLabel(routed)
                }
            }

            // routedDevice is meaningful only while recording is active. Sample it immediately
            // and on every loop, including silent/non-blocking reads, so a correctly routed but
            // silent microphone is reported as NO_AUDIO rather than INDETERMINATE.
            observeRoutedDevice()

            while (SystemClock.elapsedRealtime() < endMs) {
                currentCoroutineContext().ensureActive()
                observeRoutedDevice()
                val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                if (read == 0) {
                    Thread.sleep(10)
                    continue
                }
                if (read < 0) {
                    readErrors++
                    if (readErrors >= 3) break
                    Thread.sleep(10)
                    continue
                }

                observeRoutedDevice()
                totalSamples += read
                var chunkSquares = 0.0
                var chunkPeak = 0
                for (i in 0 until read) {
                    val value = pcm[i].toInt()
                    val abs = kotlin.math.abs(value)
                    if (abs > chunkPeak) chunkPeak = abs
                    chunkSquares += value.toDouble() * value.toDouble()
                }
                val chunkRms = sqrt(chunkSquares / read.coerceAtLeast(1))
                val now = SystemClock.elapsedRealtime()

                if (now < baselineEndMs) {
                    baselineSumSquares += chunkSquares
                    baselineSamples += read
                    if (chunkPeak > baselinePeak) baselinePeak = chunkPeak
                    _micLiveLevel.value = MicLiveLevel(
                        source, routeMode, MicTestPhase.CALIBRATING,
                        chunkRms, rmsToDbfs(chunkRms), MIN_VOICE_RMS,
                        rmsToDbfs(MIN_VOICE_RMS),
                    )
                } else {
                    speechSumSquares += chunkSquares
                    speechSamples += read
                    if (chunkPeak > speechPeak) speechPeak = chunkPeak
                    val baselineRmsNow = if (baselineSamples > 0) {
                        sqrt(baselineSumSquares / baselineSamples)
                    } else 0.0
                    val thresholdRmsNow = max(MIN_VOICE_RMS, baselineRmsNow * BASELINE_RMS_MULTIPLIER)
                    _micLiveLevel.value = MicLiveLevel(
                        source, routeMode, MicTestPhase.SPEAKING,
                        chunkRms, rmsToDbfs(chunkRms), thresholdRmsNow,
                        rmsToDbfs(thresholdRmsNow),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val result = emptyMicResult(
                source, routeMode, MicTestVerdict.ERROR, targetLabel,
                scenario = scenario,
                requestedInput = requestedInput?.let(::deviceLabel) ?: "Nessun preferred input",
                actualInput = actualInput?.let(::deviceLabel) ?: "N/D",
                preferredAccepted = preferredAccepted,
                details = "${t.javaClass.simpleName}: ${t.message}",
            )
            return@withContext publishMicTest(result)
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: Exception) {}
            recorder.release()
        }

        val baselineRms = if (baselineSamples > 0) sqrt(baselineSumSquares / baselineSamples) else 0.0
        val speechRms = if (speechSamples > 0) sqrt(speechSumSquares / speechSamples) else 0.0
        val thresholdRms = max(MIN_VOICE_RMS, baselineRms * BASELINE_RMS_MULTIPLIER)
        val thresholdPeak = max(MIN_VOICE_PEAK, (baselinePeak * BASELINE_PEAK_MULTIPLIER).toInt())
        val audioPresent = speechSamples > 0 && speechRms >= thresholdRms && speechPeak >= thresholdPeak

        val routeMatch = actualInputStronglyMatchesTarget(
            actualInput = actualInput,
            bluetoothInputs = bluetoothInputs,
            requestedInput = requestedInput,
            preferredAddress = preferredAddress,
            preferredName = preferredName,
        )

        val routeSwitchedDuringCapture = observedRoutedDeviceIds.size > 1
        val definitelyWrongDevice = actualInputDefinitelyDiffersFromTarget(
            actualInput = actualInput,
            requestedInput = requestedInput,
            preferredAddress = preferredAddress,
        )
        val verdict = when {
            readErrors >= 3 -> MicTestVerdict.ERROR
            actualInput == null -> MicTestVerdict.INDETERMINATE
            routeSwitchedDuringCapture -> MicTestVerdict.INDETERMINATE
            !isBluetoothMicDevice(actualInput!!) -> MicTestVerdict.WRONG_DEVICE
            definitelyWrongDevice -> MicTestVerdict.WRONG_DEVICE
            !routeMatch -> MicTestVerdict.INDETERMINATE
            !audioPresent -> MicTestVerdict.NO_AUDIO
            routeMode == MicRouteMode.TARGET_PREFERRED && !preferredAccepted ->
                MicTestVerdict.PREFERRED_REJECTED
            else -> MicTestVerdict.PASS
        }

        if (routeMode == MicRouteMode.SYSTEM_DEFAULT && routeMatch &&
            !routeSwitchedDuringCapture && actualInput != null
        ) {
            lastObservedPriorityInput = actualInput
        }

        _micLiveLevel.value = MicLiveLevel(
            source, routeMode, MicTestPhase.FINISHED,
            speechRms, rmsToDbfs(speechRms), thresholdRms, rmsToDbfs(thresholdRms),
        )

        publishMicTest(
            MicTestResult(
                source = source,
                routeMode = routeMode,
                scenario = scenario,
                verdict = verdict,
                targetDeviceName = targetLabel,
                requestedInput = requestedInput?.let(::deviceLabel)
                    ?: "Nessun preferred input (route attiva osservata)",
                actualInput = actualInput?.let(::deviceLabel) ?: "N/D",
                preferredDeviceAccepted = preferredAccepted,
                communicationDevice = currentCommunicationDeviceLabel(),
                peak = speechPeak,
                rms = speechRms,
                baselinePeak = baselinePeak,
                baselineRms = baselineRms,
                thresholdPeak = thresholdPeak,
                thresholdRms = thresholdRms,
                peakDbfs = peakToDbfs(speechPeak),
                rmsDbfs = rmsToDbfs(speechRms),
                thresholdDbfs = rmsToDbfs(thresholdRms),
                samplesRead = totalSamples,
                durationMs = SystemClock.elapsedRealtime() - startMs,
                routeMatchesRequested = routeMatch,
                details = buildString {
                    appendLine("SOURCE=${source.label} (${source.audioSource})")
                    appendLine("ROUTE_MODE=${routeMode.label}")
                    appendLine("SCENARIO=${scenario.label}")
                    appendLine("VERDICT=${verdict.name}")
                    appendLine("Target: $targetLabel")
                    appendLine("Communication device: ${currentCommunicationDeviceLabel()}")
                    appendLine("Preferred input: ${requestedInput?.let(::deviceLabel) ?: "nessuno"}")
                    appendLine("setPreferredDevice called: ${routeMode == MicRouteMode.TARGET_PREFERRED}")
                    appendLine("setPreferredDevice accepted: $preferredAccepted")
                    appendLine("Actual routed input: ${actualInput?.let(::deviceLabel) ?: "N/D"}")
                    appendLine("Observed routed inputs: ${observedRoutedDeviceLabels.joinToString(" | ").ifBlank { "N/D" }}")
                    appendLine("Route switched during capture: $routeSwitchedDuringCapture")
                    appendLine("Route match: $routeMatch")
                    appendLine("Baseline RMS=${"%.1f".format(baselineRms)} peak=$baselinePeak")
                    appendLine("Speech RMS=${"%.1f".format(speechRms)} peak=$speechPeak")
                    appendLine("Threshold RMS=${"%.1f".format(thresholdRms)} peak=$thresholdPeak")
                    appendLine("Samples=$totalSamples readErrors=$readErrors")
                }.trim(),
            )
        )
    }

    fun clearMicDiagnostics() {
        _lastMicTestResult.value = null
        _micTestResults.value = emptyMap()
        _allMicTestResults.value = emptyMap()
        _micLiveLevel.value = null
        lastObservedPriorityInput = null
    }

    private fun publishMicTest(result: MicTestResult): MicTestResult {
        _lastMicTestResult.value = result
        _micTestResults.value = _micTestResults.value.toMutableMap().apply {
            put(result.source, result)
        }
        _allMicTestResults.value = _allMicTestResults.value.toMutableMap().apply {
            put(MicTestKey(result.source, result.routeMode, result.scenario), result)
        }
        return result
    }

    private fun emptyMicResult(
        source: MicTestSource,
        routeMode: MicRouteMode,
        verdict: MicTestVerdict,
        targetName: String,
        scenario: MicTestScenario = MicTestScenario.BASELINE,
        requestedInput: String = "N/D",
        actualInput: String = "N/D",
        preferredAccepted: Boolean = false,
        details: String,
    ) = MicTestResult(
        source = source,
        routeMode = routeMode,
        scenario = scenario,
        verdict = verdict,
        targetDeviceName = targetName,
        requestedInput = requestedInput,
        actualInput = actualInput,
        preferredDeviceAccepted = preferredAccepted,
        communicationDevice = currentCommunicationDeviceLabel(),
        peak = 0,
        rms = 0.0,
        samplesRead = 0,
        durationMs = 0,
        details = details,
    )

    private fun actualInputDefinitelyDiffersFromTarget(
        actualInput: AudioDeviceInfo?,
        requestedInput: AudioDeviceInfo?,
        preferredAddress: String?,
    ): Boolean {
        if (actualInput == null) return false
        if (!isBluetoothMicDevice(actualInput)) return true

        // A MAC/address contradiction is strong evidence. Friendly-name differences are NOT:
        // OEM AudioDeviceInfo.productName may expose a model name while BluetoothDevice.alias
        // exposes the user-renamed label. Treat name-only mismatches as INDETERMINATE instead
        // of falsely declaring another physical device.
        if (!preferredAddress.isNullOrBlank() && actualInput.address.isNotBlank()) {
            return !actualInput.address.equals(preferredAddress, ignoreCase = true)
        }

        if (requestedInput != null && requestedInput.address.isNotBlank() &&
            actualInput.address.isNotBlank()
        ) {
            return !actualInput.address.equals(requestedInput.address, ignoreCase = true)
        }

        return false
    }

    private fun actualInputStronglyMatchesTarget(
        actualInput: AudioDeviceInfo?,
        bluetoothInputs: List<AudioDeviceInfo>,
        requestedInput: AudioDeviceInfo?,
        preferredAddress: String?,
        preferredName: String?,
    ): Boolean {
        if (actualInput == null || !isBluetoothMicDevice(actualInput)) return false

        if (!preferredAddress.isNullOrBlank() && actualInput.address.isNotBlank() &&
            actualInput.address.equals(preferredAddress, ignoreCase = true)
        ) return true

        val normalizedPreferredName = preferredName?.trim()
            ?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
        if (normalizedPreferredName != null &&
            actualInput.productName?.toString()?.trim()
                ?.equals(normalizedPreferredName, ignoreCase = true) == true
        ) {
            val matches = bluetoothInputs.count { input ->
                input.productName?.toString()?.trim()
                    ?.equals(normalizedPreferredName, ignoreCase = true) == true
            }
            if (matches == 1) return true
        }

        if (requestedInput != null) {
            if (actualInput.id == requestedInput.id) return true
            if (actualInput.address.isNotBlank() && requestedInput.address.isNotBlank() &&
                actualInput.address.equals(requestedInput.address, ignoreCase = true)
            ) return true

            val requestedName = requestedInput.productName?.toString()?.trim()
                ?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
            if (requestedName != null &&
                actualInput.productName?.toString()?.trim()
                    ?.equals(requestedName, ignoreCase = true) == true
            ) {
                val matches = bluetoothInputs.count { input ->
                    input.productName?.toString()?.trim()
                        ?.equals(requestedName, ignoreCase = true) == true
                }
                if (matches == 1) return true
            }
        }

        return false
    }

    private fun syncRoutingStateWithSystem() {
        val systemDevice = audioManager.communicationDevice
        val preferredAddress = preferences.pairedDeviceAddress
        val preferredName = preferences.pairedDeviceName
        val targetActive = preferences.hasPreferredDevice() &&
            deviceMatchesPreference(systemDevice, preferredAddress, preferredName)

        if (targetActive) {
            currentRoutedDevice = systemDevice
            _routingState.value = RoutingState.Active(
                systemDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: preferredName?.takeIf { it.isNotBlank() }
                    ?: "Dispositivo Bluetooth"
            )
            return
        }

        currentRoutedDevice = null
        val previous = _routingState.value
        if (previous is RoutingState.Active || previous is RoutingState.Routing) {
            // The target is no longer the system route. Ownership is now ambiguous, so do not
            // write AudioManager.mode here; simply forget our saved mode and mirror reality.
            abandonOwnAudioModeOwnership()
            _routingState.value = RoutingState.Idle
        }
    }

    private fun refreshAvailableDevices() {
        _availableDevices.value = bluetoothCommunicationDevices().map { device ->
            BluetoothAudioDevice(
                deviceInfo = device,
                name = device.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Dispositivo Bluetooth",
                type = device.type,
                typeLabel = deviceTypeToString(device.type),
            )
        }
    }

    private fun isBluetoothMicDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun deviceLabel(device: AudioDeviceInfo): String =
        "${device.productName ?: "Dispositivo"} (${deviceTypeToString(device.type)}, id=${device.id})"

    private fun deviceTypeToString(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfono telefono"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker telefono"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Cuffie cablate"
        else -> "Tipo $type"
    }

    private fun looksLikeMac(value: String): Boolean =
        Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(value.trim())

    private fun rmsToDbfs(rms: Double): Double =
        if (rms <= 0.0) DBFS_FLOOR
        else max(DBFS_FLOOR, 20.0 * log10(rms / PCM_FULL_SCALE))

    private fun peakToDbfs(peak: Int): Double =
        if (peak <= 0) DBFS_FLOOR
        else max(DBFS_FLOOR, 20.0 * log10(peak.toDouble() / PCM_FULL_SCALE))

    companion object {
        private val audioModeLock = Any()
        @Volatile private var savedAudioModeBeforeRouting: Int? = null
        private val routingMutex = Mutex()

        private const val MIC_BASELINE_MS = 1_200L
        private const val BASELINE_RMS_MULTIPLIER = 3.0
        private const val BASELINE_PEAK_MULTIPLIER = 1.8
        private const val MIN_VOICE_RMS = 250.0
        private const val MIN_VOICE_PEAK = 1500
        private const val PCM_FULL_SCALE = 32768.0
        private const val DBFS_FLOOR = -120.0
    }
}
