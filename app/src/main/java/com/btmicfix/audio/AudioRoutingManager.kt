package com.btmicfix.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
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

    private val requestLock = Any()
    @Volatile private var requestOutstanding = false
    @Volatile private var autoRouteSuppressedUntilDisconnect = false
    private var requestedAddress: String? = null
    private var requestedName: String? = null
    private var requestedDisplayName: String? = null

    private val communicationDeviceChangedListener =
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            Logger.i("Communication device changed: ${device?.let(::deviceLabel) ?: "none"}")
            refreshAvailableDevices()
            syncRoutingStateWithSystem()
        }

    private val modeChangedListener = AudioManager.OnModeChangedListener { mode ->
        Logger.i("Audio mode changed: ${audioModeLabel(mode)}")
        // Observation only. Never re-assert a route because another app changed audio mode.
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
        /** Android accepted our one-shot request but has not selected it yet. */
        data class Requested(val deviceName: String) : RoutingState()
        data class Active(val deviceName: String) : RoutingState()
        /** Another audio owner (phone/VoIP/assistant) temporarily has the communication route. */
        data class Yielded(
            val deviceName: String,
            val currentDevice: String,
            val audioMode: String,
        ) : RoutingState()
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
        audioManager.addOnModeChangedListener(context.mainExecutor, modeChangedListener)
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
        try { audioManager.removeOnModeChangedListener(modeChangedListener) } catch (_: Exception) {}
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

    /**
     * Place at most one communication-device selection request.
     *
     * IMPORTANT: BTMicFix deliberately NEVER calls AudioManager.setMode(). Android documents
     * that simultaneous setCommunicationDevice() requests are prioritized in favor of the app
     * controlling the audio mode. That means phone/VoIP/assistant sessions must be free to take
     * temporary priority. We observe that as Yielded and never fight it with a timer/retry loop.
     */
    suspend fun requestPreferredRoute(
        address: String?,
        name: String?,
        displayName: String? = null,
        trigger: RoutingPolicy.Trigger = RoutingPolicy.Trigger.USER_ENABLE,
        autoRouteEnabled: Boolean = false,
        timeoutMs: Long = 2_500L,
        pollMs: Long = 75L,
    ): RoutingState = routingMutex.withLock {
        if (address.isNullOrBlank() && name.isNullOrBlank()) {
            return@withLock RoutingState.Failed("Nessun dispositivo prioritario selezionato").also {
                _routingState.value = it
            }
        }

        if (trigger == RoutingPolicy.Trigger.USER_ENABLE) {
            autoRouteSuppressedUntilDisconnect = false
        }

        val target = findPreferredBluetoothCommunicationDevice(address, name)
        val alreadyOutstanding = isRouteRequestOutstandingFor(address, name)
        val shouldIssue = RoutingPolicy.shouldIssueRequest(
            trigger = trigger,
            autoRouteEnabled = autoRouteEnabled,
            targetAvailable = target != null,
            requestAlreadyOutstanding = alreadyOutstanding,
        )

        if (!shouldIssue) {
            syncRoutingStateWithSystem()
            return@withLock _routingState.value
        }

        if (target == null) {
            return@withLock RoutingState.Failed("Dispositivo prioritario non connesso").also {
                _routingState.value = it
            }
        }

        val label = displayName?.takeIf { it.isNotBlank() }
            ?: target.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: "Dispositivo Bluetooth"

        val accepted = try {
            audioManager.setCommunicationDevice(target)
        } catch (e: Exception) {
            Logger.e("setCommunicationDevice failed", e)
            false
        }

        if (!accepted) {
            return@withLock RoutingState.Failed("setCommunicationDevice ha restituito false").also {
                _routingState.value = it
            }
        }

        synchronized(requestLock) {
            requestOutstanding = true
            requestedAddress = address
            requestedName = name
            requestedDisplayName = label
        }
        _routingState.value = RoutingState.Requested(label)

        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(250L)
        do {
            val actual = audioManager.communicationDevice
            if (deviceMatchesPreference(actual, address, name)) {
                currentRoutedDevice = actual
                return@withLock RoutingState.Active(label).also { _routingState.value = it }
            }
            delay(pollMs.coerceAtLeast(25L))
        } while (SystemClock.elapsedRealtime() < deadline)

        // The request was accepted but another app may currently own the audio mode. Keep our
        // request alive and yield. Android can return the route later without any re-assertion.
        syncRoutingStateWithSystem()
        return@withLock _routingState.value
    }

    /** Explicit user/configuration action only. Never called because another app took the route. */
    fun deactivatePreferredRoute(suppressAutoRouteUntilDisconnect: Boolean = true) {
        autoRouteSuppressedUntilDisconnect = suppressAutoRouteUntilDisconnect
        val hadRequest = synchronized(requestLock) {
            val value = requestOutstanding
            requestOutstanding = false
            requestedAddress = null
            requestedName = null
            requestedDisplayName = null
            value
        }
        if (hadRequest) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (e: Exception) {
                Logger.e("Error clearing BTMicFix communication-device request", e)
            }
        }
        currentRoutedDevice = null
        _routingState.value = RoutingState.Idle
    }

    /** Platform automatically cancels the selection on physical target disconnect. */
    fun notePreferredDeviceDisconnected(address: String?) {
        // A real disconnect ends a manual suppression window; the next physical appearance may
        // auto-route again if the user left automatic routing enabled.
        autoRouteSuppressedUntilDisconnect = false
        val matchesOutstanding = synchronized(requestLock) {
            requestOutstanding &&
                !address.isNullOrBlank() &&
                !requestedAddress.isNullOrBlank() &&
                requestedAddress.equals(address, ignoreCase = true)
        }
        if (!matchesOutstanding) return

        synchronized(requestLock) {
            requestOutstanding = false
            requestedAddress = null
            requestedName = null
            requestedDisplayName = null
        }
        currentRoutedDevice = null
        _routingState.value = RoutingState.Idle
    }

    fun isRouteRequestOutstanding(): Boolean = synchronized(requestLock) { requestOutstanding }

    fun isAutoRouteSuppressedUntilDisconnect(): Boolean = autoRouteSuppressedUntilDisconnect

    fun isRouteRequestOutstandingFor(address: String?, name: String?): Boolean =
        synchronized(requestLock) {
            if (!requestOutstanding) return@synchronized false
            val addressMatch = !address.isNullOrBlank() && !requestedAddress.isNullOrBlank() &&
                requestedAddress.equals(address, ignoreCase = true)
            val nameMatch = !name.isNullOrBlank() && !requestedName.isNullOrBlank() &&
                requestedName.equals(name, ignoreCase = true)
            addressMatch || nameMatch
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

    @SuppressLint("MissingPermission")
    fun resolveStableBluetoothAddress(device: BluetoothAudioDevice): String? {
        device.deviceInfo.address.trim().takeIf { it.isNotBlank() }?.let { return it }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return null

        val adapter = context.getSystemService<BluetoothManager>()?.adapter ?: return null
        val targetName = device.name.trim()
        val matches = try {
            adapter.bondedDevices.filter { bonded ->
                val alias = try { bonded.alias } catch (_: Throwable) { null }
                val name = try { bonded.name } catch (_: Throwable) { null }
                listOfNotNull(alias, name).any { it.trim().equals(targetName, ignoreCase = true) }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        return matches.singleOrNull()?.address
    }

    fun matchesPreferredDevice(
        device: BluetoothAudioDevice,
        preferredAddress: String?,
        preferredName: String?,
    ): Boolean = deviceMatchesPreference(device.deviceInfo, preferredAddress, preferredName)

    fun isCurrentCommunicationDevice(device: BluetoothAudioDevice): Boolean =
        sameAudioEndpoint(audioManager.communicationDevice, device.deviceInfo)

    fun currentCommunicationDeviceName(): String? =
        audioManager.communicationDevice?.productName?.toString()?.takeIf { it.isNotBlank() }

    fun currentAudioModeLabel(): String = audioModeLabel(audioManager.mode)

    suspend fun testBluetoothMicrophone(
        source: MicTestSource,
        durationMs: Long = 7_000L,
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
        var communicationRouteLostDuringCapture = false
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
                if (!deviceMatchesPreference(
                        audioManager.communicationDevice,
                        preferredAddress,
                        preferredName,
                    )
                ) {
                    communicationRouteLostDuringCapture = true
                }
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
            preferredName = preferredName,
        )
        val verdict = when {
            readErrors >= 3 -> MicTestVerdict.ERROR
            actualInput == null -> MicTestVerdict.INDETERMINATE
            routeSwitchedDuringCapture -> MicTestVerdict.INDETERMINATE
            communicationRouteLostDuringCapture -> MicTestVerdict.INDETERMINATE
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
                    appendLine("Communication route left target during capture: $communicationRouteLostDuringCapture")
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
        preferredName: String?,
    ): Boolean {
        if (actualInput == null) return false
        if (!isBluetoothMicDevice(actualInput)) return true

        val actualName = actualInput.productName?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
        val requestedName = requestedInput?.productName?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && !looksLikeMac(it) }
        val normalizedPreferredName = preferredName?.trim()
            ?.takeIf { it.isNotBlank() && !looksLikeMac(it) }

        // Bluetooth communication output and microphone input are different AudioDeviceInfo
        // endpoints. OEMs may expose different ids AND different/masked addresses for the two
        // endpoints of the same headset. Therefore an address mismatch alone is NOT proof of a
        // different physical device. An exact friendly-name match is stronger evidence here.
        if (normalizedPreferredName != null &&
            actualName?.equals(normalizedPreferredName, ignoreCase = true) == true
        ) return false
        if (requestedName != null && actualName?.equals(requestedName, ignoreCase = true) == true) {
            return false
        }

        // Only call it definitely wrong when two independent identity hints disagree: both
        // endpoints expose non-empty addresses that differ AND both expose usable names that
        // also differ. Otherwise the safe verdict is INDETERMINATE, never WRONG_DEVICE.
        val addressContradiction = when {
            !preferredAddress.isNullOrBlank() && actualInput.address.isNotBlank() ->
                !actualInput.address.equals(preferredAddress, ignoreCase = true)
            requestedInput != null && requestedInput.address.isNotBlank() && actualInput.address.isNotBlank() ->
                !actualInput.address.equals(requestedInput.address, ignoreCase = true)
            else -> false
        }
        val nameContradiction = when {
            normalizedPreferredName != null && actualName != null ->
                !actualName.equals(normalizedPreferredName, ignoreCase = true)
            requestedName != null && actualName != null ->
                !actualName.equals(requestedName, ignoreCase = true)
            else -> false
        }
        return addressContradiction && nameContradiction
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
            // Input/output ids are expected to differ. For a user-visible Bluetooth alias that
            // exactly matches the selected priority target, accept the input even if the OEM
            // exposes a different endpoint address. If duplicate BT inputs share the same name,
            // fall through to stronger requested-input evidence instead.
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
        val request = synchronized(requestLock) {
            Triple(requestOutstanding, requestedAddress, requestedName)
        }
        val outstanding = request.first
        val address = request.second
        val name = request.third
        val label = synchronized(requestLock) {
            requestedDisplayName?.takeIf { it.isNotBlank() }
        } ?: name?.takeIf { !it.isNullOrBlank() }
            ?: preferences.pairedDeviceName?.takeIf { !it.isNullOrBlank() }
            ?: "Dispositivo Bluetooth"

        if (!outstanding) {
            currentRoutedDevice = null
            _routingState.value = RoutingState.Idle
            return
        }

        val targetAvailable = findPreferredBluetoothCommunicationDevice(address, name) != null ||
            deviceMatchesPreference(audioManager.communicationDevice, address, name)

        // Do not infer a physical disconnect from a transient AudioDeviceInfo/profile flap.
        // CompanionDeviceService performs the debounced physical-disappearance decision and
        // calls notePreferredDeviceDisconnected(). Until then our accepted request stays logical.
        val systemDevice = audioManager.communicationDevice
        val targetIsCurrent = deviceMatchesPreference(systemDevice, address, name)
        val logical = RoutingPolicy.evaluate(
            RoutingPolicy.Snapshot(
                requestEnabled = true,
                targetAvailable = targetAvailable,
                targetIsCurrentCommunicationDevice = targetIsCurrent,
                currentDeviceLabel = systemDevice?.let(::deviceLabel),
                audioMode = audioManager.mode,
            )
        )

        when (logical) {
            RoutingPolicy.LogicalState.ACTIVE -> {
                currentRoutedDevice = systemDevice
                _routingState.value = RoutingState.Active(label)
            }
            RoutingPolicy.LogicalState.YIELDED_TO_OTHER_AUDIO_OWNER -> {
                currentRoutedDevice = null
                _routingState.value = RoutingState.Yielded(
                    deviceName = label,
                    currentDevice = systemDevice?.let(::deviceLabel) ?: "route di sistema",
                    audioMode = audioModeLabel(audioManager.mode),
                )
            }
            RoutingPolicy.LogicalState.DISABLED,
            RoutingPolicy.LogicalState.WAITING_FOR_DEVICE -> {
                currentRoutedDevice = null
                _routingState.value = RoutingState.Idle
            }
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

    private fun audioModeLabel(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
        AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
        else -> "MODE_$mode"
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
        private val routingMutex = Mutex()

        private const val MIC_BASELINE_MS = 2_500L
        private const val BASELINE_RMS_MULTIPLIER = 3.0
        private const val BASELINE_PEAK_MULTIPLIER = 1.8
        private const val MIN_VOICE_RMS = 250.0
        private const val MIN_VOICE_PEAK = 1500
        private const val PCM_FULL_SCALE = 32768.0
        private const val DBFS_FLOOR = -120.0
    }
}
