package com.btmicfix.diagnostics

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Completely passive observer.
 *
 * It never opens AudioRecord/MediaRecorder, never requests audio focus, never changes AudioManager
 * mode and never selects/clears a communication device. All app-side APIs in this class are reads
 * or listener registrations.
 */
class PassiveDiagnosticsManager(private val context: Context) {

    data class LiveState(
        val elapsedMs: Long,
        val phase: PassiveObservationLogic.Phase,
        val audioMode: String,
        val communicationDevice: String,
        val recordings: List<String>,
        val inputDevices: List<String>,
        val outputDevices: List<String>,
        val communicationCandidates: List<String>,
        val networkSummary: String,
        val bluetoothSummary: String,
    ) {
        fun compact(): String = buildString {
            append("mode=$audioMode | comm=$communicationDevice")
            append(" | rec=${recordings.size}")
            if (recordings.isNotEmpty()) append(" [${recordings.joinToString(" || ")}]")
            append(" | net=$networkSummary | bt=$bluetoothSummary")
        }

        fun signature(): String = listOf(
            audioMode,
            communicationDevice,
            recordings.joinToString("|"),
            inputDevices.joinToString("|"),
            outputDevices.joinToString("|"),
            communicationCandidates.joinToString("|"),
            networkSummary,
            bluetoothSummary,
        ).joinToString("##")
    }

    data class TimelineEvent(
        val elapsedMs: Long,
        val phase: PassiveObservationLogic.Phase,
        val kind: String,
        val detail: String,
    )

    data class ObservationReport(
        val summary: String,
        val fullText: String,
    )

    private data class Sample(
        val elapsedMs: Long,
        val phase: PassiveObservationLogic.Phase,
        val state: LiveState,
    )

    private val audioManager: AudioManager = context.getSystemService<AudioManager>()
        ?: error("AudioManager unavailable")
    private val connectivityManager: ConnectivityManager? = context.getSystemService()
    private val bluetoothManager: BluetoothManager? = context.getSystemService()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _liveState = MutableStateFlow<LiveState?>(null)
    val liveState: StateFlow<LiveState?> = _liveState.asStateFlow()

    private val _lastReport = MutableStateFlow<ObservationReport?>(null)
    val lastReport: StateFlow<ObservationReport?> = _lastReport.asStateFlow()

    private val runGuard = AtomicBoolean(false)
    private val eventLock = Any()
    private var runStartedAt = 0L
    private val events = mutableListOf<TimelineEvent>()

    private val communicationListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        addCallbackEvent("COMM_DEVICE_CALLBACK", deviceLabel(device))
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        addCallbackEvent("MODE_CALLBACK", audioModeLabel(mode))
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            addCallbackEvent("DEVICE_ADDED", addedDevices.joinToString(" || ") { deviceLabel(it) })
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            addCallbackEvent("DEVICE_REMOVED", removedDevices.joinToString(" || ") { deviceLabel(it) })
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val list = configs.map(::recordingLabel)
            addCallbackEvent(
                "RECORDING_CALLBACK",
                if (list.isEmpty()) "no active recordings" else list.joinToString(" || "),
            )
        }
    }

    suspend fun runObservation(
        privilegedSnapshotProvider: (suspend (String) -> String)? = null,
    ): ObservationReport = coroutineScope {
        if (!runGuard.compareAndSet(false, true)) {
            return@coroutineScope _lastReport.value
                ?: ObservationReport("Osservazione già in corso", "Osservazione già in corso")
        }

        _running.value = true
        _lastReport.value = null
        synchronized(eventLock) { events.clear() }
        val samples = mutableListOf<Sample>()
        runStartedAt = SystemClock.elapsedRealtime()
        var listenersRegistered = false

        try {
            registerListeners()
            listenersRegistered = true
            addEvent(0L, PassiveObservationLogic.Phase.BASELINE, "START", "Passive observer started")

            val privilegedJobs = if (privilegedSnapshotProvider != null) {
                listOf(
                    async(Dispatchers.IO) {
                        delay(1_000L)
                        "BASELINE" to safePrivilegedSnapshot(privilegedSnapshotProvider, "BASELINE")
                    },
                    async(Dispatchers.IO) {
                        delay(3_600L)
                        "GEMINI_ONSET" to safePrivilegedSnapshot(privilegedSnapshotProvider, "GEMINI_ONSET")
                    },
                    async(Dispatchers.IO) {
                        delay(5_800L)
                        "GEMINI_MID" to safePrivilegedSnapshot(privilegedSnapshotProvider, "GEMINI_MID")
                    },
                    async(Dispatchers.IO) {
                        delay(16_000L)
                        "RECOVERY" to safePrivilegedSnapshot(privilegedSnapshotProvider, "RECOVERY")
                    },
                )
            } else emptyList()

            var lastSignature: String? = null
            var lastHeartbeatMs = -1_000L
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - runStartedAt
                val phase = PassiveObservationLogic.phaseFor(elapsed)
                val state = captureLiveState(elapsed, phase)
                _liveState.value = state

                if (elapsed % 250L < 120L || samples.isEmpty()) {
                    samples += Sample(elapsed, phase, state)
                }

                val signature = state.signature()
                if (signature != lastSignature) {
                    addEvent(elapsed, phase, "STATE_CHANGE", state.compact())
                    lastSignature = signature
                }
                if (elapsed - lastHeartbeatMs >= 1_000L) {
                    addEvent(elapsed, phase, "HEARTBEAT", state.compact())
                    lastHeartbeatMs = elapsed
                }

                if (elapsed >= PassiveObservationLogic.TOTAL_MS) break
                delay(100L)
            }

            val privilegedSnapshots = privilegedJobs.map { it.await() }
            val report = buildReport(samples, privilegedSnapshots)
            _lastReport.value = report
            report
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val report = ObservationReport(
                summary = "ERRORE OSSERVAZIONE: ${t.javaClass.simpleName}: ${t.message}",
                fullText = "ERRORE OSSERVAZIONE: ${t.javaClass.simpleName}: ${t.message}\n" +
                    synchronized(eventLock) { events.joinToString("\n", transform = ::formatEvent) },
            )
            _lastReport.value = report
            report
        } finally {
            if (listenersRegistered) unregisterListeners()
            _running.value = false
            runGuard.set(false)
        }
    }

    private suspend fun safePrivilegedSnapshot(
        provider: suspend (String) -> String,
        label: String,
    ): String = try {
        provider(label)
    } catch (t: Throwable) {
        "SNAPSHOT_ERROR: ${t.javaClass.simpleName}: ${t.message}"
    }

    private fun registerListeners() {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, communicationListener)
        audioManager.addOnModeChangedListener(context.mainExecutor, modeListener)
        audioManager.registerAudioRecordingCallback(recordingCallback, null)
    }

    private fun unregisterListeners() {
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Throwable) {}
        try { audioManager.removeOnCommunicationDeviceChangedListener(communicationListener) } catch (_: Throwable) {}
        try { audioManager.removeOnModeChangedListener(modeListener) } catch (_: Throwable) {}
        try { audioManager.unregisterAudioRecordingCallback(recordingCallback) } catch (_: Throwable) {}
    }

    private fun addCallbackEvent(kind: String, detail: String) {
        if (!_running.value || runStartedAt == 0L) return
        val elapsed = (SystemClock.elapsedRealtime() - runStartedAt).coerceAtLeast(0L)
        addEvent(elapsed, PassiveObservationLogic.phaseFor(elapsed), kind, detail)
    }

    private fun addEvent(
        elapsedMs: Long,
        phase: PassiveObservationLogic.Phase,
        kind: String,
        detail: String,
    ) {
        synchronized(eventLock) {
            events += TimelineEvent(elapsedMs, phase, kind, detail.replace('\n', ' '))
        }
    }

    private fun captureLiveState(
        elapsedMs: Long,
        phase: PassiveObservationLogic.Phase,
    ): LiveState {
        val recordings = try {
            audioManager.activeRecordingConfigurations.map(::recordingLabel)
        } catch (t: Throwable) {
            listOf("ERROR activeRecordingConfigurations: ${t.javaClass.simpleName}")
        }

        val inputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map(::deviceLabel).sorted()
        } catch (t: Throwable) {
            listOf("ERROR inputs: ${t.javaClass.simpleName}")
        }

        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::deviceLabel).sorted()
        } catch (t: Throwable) {
            listOf("ERROR outputs: ${t.javaClass.simpleName}")
        }

        val communicationCandidates = try {
            audioManager.availableCommunicationDevices.map(::deviceLabel).sorted()
        } catch (t: Throwable) {
            listOf("ERROR communication devices: ${t.javaClass.simpleName}")
        }

        val comm = try { deviceLabel(audioManager.communicationDevice) }
        catch (t: Throwable) { "ERROR: ${t.javaClass.simpleName}" }

        val mode = try { audioModeLabel(audioManager.mode) }
        catch (t: Throwable) { "ERROR: ${t.javaClass.simpleName}" }

        return LiveState(
            elapsedMs = elapsedMs,
            phase = phase,
            audioMode = mode,
            communicationDevice = comm,
            recordings = recordings,
            inputDevices = inputs,
            outputDevices = outputs,
            communicationCandidates = communicationCandidates,
            networkSummary = networkSummary(),
            bluetoothSummary = bluetoothSummary(),
        )
    }

    private fun networkSummary(): String {
        val cm = connectivityManager ?: return "ConnectivityManager unavailable"
        return try {
            val parts = cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                val transports = buildList {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELL")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BT")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETH")
                }
                val iface = cm.getLinkProperties(network)?.interfaceName
                if (transports.isEmpty()) null else transports.joinToString("+") +
                    (iface?.let { "@$it" } ?: "")
            }.distinct()
            if (parts.isEmpty()) "none" else parts.joinToString(",")
        } catch (t: Throwable) {
            "ERROR:${t.javaClass.simpleName}"
        }
    }

    private fun bluetoothSummary(): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return "permission-missing"

        val adapter = bluetoothManager?.adapter ?: return "adapter-unavailable"
        return try {
            listOf(
                "HEADSET=${profileState(adapter.getProfileConnectionState(BluetoothProfile.HEADSET))}",
                "A2DP=${profileState(adapter.getProfileConnectionState(BluetoothProfile.A2DP))}",
                "LE_AUDIO=${profileState(adapter.getProfileConnectionState(BluetoothProfile.LE_AUDIO))}",
            ).joinToString(" ")
        } catch (t: Throwable) {
            "ERROR:${t.javaClass.simpleName}"
        }
    }

    private fun buildReport(
        samples: List<Sample>,
        privilegedSnapshots: List<Pair<String, String>>,
    ): ObservationReport {
        val geminiSamples = samples.filter { it.phase == PassiveObservationLogic.Phase.GEMINI }
        val recoverySamples = samples.filter { it.phase == PassiveObservationLogic.Phase.RECOVERY }

        val geminiModes = geminiSamples.map { it.state.audioMode }.distinct()
        val geminiComm = geminiSamples.map { it.state.communicationDevice }.distinct()
        val geminiRecordings = geminiSamples.flatMap { it.state.recordings }.distinct()
        val recoveryComm = recoverySamples.map { it.state.communicationDevice }.distinct()
        val sawSilenced = geminiRecordings.any { it.contains("silenced=true", ignoreCase = true) }
        val sawScoRecording = geminiRecordings.any { it.contains("BT_SCO", ignoreCase = true) }
        val sawBuiltinRecording = geminiRecordings.any {
            it.contains("BUILTIN_MIC", ignoreCase = true) || it.contains("BUILTIN_EARPIECE", ignoreCase = true)
        }

        val summary = buildString {
            appendLine("OSSERVAZIONE PASSIVA COMPLETATA")
            appendLine("Nessun microfono aperto e nessun routing modificato da BTMicFix.")
            appendLine("Gemini window — mode: ${geminiModes.ifEmpty { listOf("<nessun dato>") }.joinToString(" -> ")}")
            appendLine("Gemini window — communication device: ${geminiComm.ifEmpty { listOf("<nessun dato>") }.joinToString(" -> ")}")
            appendLine("Registrazioni osservate: ${geminiRecordings.size}")
            appendLine("Input BT_SCO osservato nelle registrazioni: ${yesNo(sawScoRecording)}")
            appendLine("Input integrato osservato nelle registrazioni: ${yesNo(sawBuiltinRecording)}")
            appendLine("Client silenziato dalla capture policy: ${yesNo(sawSilenced)}")
            appendLine("Recovery — communication device: ${recoveryComm.ifEmpty { listOf("<nessun dato>") }.joinToString(" -> ")}")
            appendLine("Snapshot Shizuku raccolti: ${privilegedSnapshots.count { !it.second.startsWith("SNAPSHOT_") }}/${privilegedSnapshots.size}")
        }.trim()

        val timeline = synchronized(eventLock) { events.sortedBy { it.elapsedMs }.toList() }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val full = buildString {
            appendLine("BTMicFix 0.9.0 Passive Observer")
            appendLine("Generated: $date")
            appendLine("PASSIVE_INVARIANTS=NO_AUDIO_CAPTURE,NO_ROUTE_SELECTION,NO_MODE_CHANGE,NO_AUDIO_FOCUS,NO_AUDIO_POLICY_MUTATION")
            appendLine()
            appendLine("=== SUMMARY ===")
            appendLine(summary)
            appendLine()
            appendLine("=== DEVICE SNAPSHOT AT END ===")
            samples.lastOrNull()?.state?.let { state ->
                appendLine("Inputs:")
                state.inputDevices.forEach { appendLine("  $it") }
                appendLine("Outputs:")
                state.outputDevices.forEach { appendLine("  $it") }
                appendLine("Communication candidates:")
                state.communicationCandidates.forEach { appendLine("  $it") }
            }
            appendLine()
            appendLine("=== TIMELINE ===")
            timeline.forEach { appendLine(formatEvent(it)) }
            appendLine()
            appendLine("=== PRIVILEGED READ-ONLY SNAPSHOTS ===")
            if (privilegedSnapshots.isEmpty()) {
                appendLine("Shizuku unavailable/not used")
            } else {
                privilegedSnapshots.forEach { (label, text) ->
                    appendLine("--- $label ---")
                    appendLine(text)
                }
            }
        }.trim()

        return ObservationReport(summary, full)
    }

    private fun formatEvent(event: TimelineEvent): String =
        String.format(
            Locale.US,
            "T+%06.2fs [%s] %-20s %s",
            event.elapsedMs / 1_000.0,
            event.phase.name,
            event.kind,
            event.detail,
        )

    private fun recordingLabel(config: AudioRecordingConfiguration): String {
        val device = try { config.audioDevice } catch (_: Throwable) { null }
        return buildString {
            append("session=${config.clientAudioSessionId}")
            append(" client=${audioSourceLabel(config.clientAudioSource)}")
            append(" path=${try { audioSourceLabel(config.audioSource) } catch (_: Throwable) { "?" }}")
            append(" silenced=${try { config.isClientSilenced } catch (_: Throwable) { false }}")
            append(" device=${deviceLabel(device)}")
            append(" clientFmt=${formatLabel(config.clientFormat)}")
            append(" hwFmt=${formatLabel(config.format)}")
        }
    }

    private fun formatLabel(format: AudioFormat?): String {
        if (format == null) return "null"
        return "${format.sampleRate}Hz/chMask=${format.channelMask}/enc=${format.encoding}"
    }

    private fun deviceLabel(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        val name = device.productName?.toString()?.trim().orEmpty().ifBlank { "unnamed" }
        val address = device.address?.trim().orEmpty()
        return buildString {
            append(name)
            append(" (")
            append(deviceTypeLabel(device.type))
            append(",id=${device.id}")
            if (address.isNotBlank()) append(",addr=$address")
            append(",src=${device.isSource},sink=${device.isSink})")
        }
    }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        else -> "TYPE_$type"
    }

    private fun audioModeLabel(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
        AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
        7 -> "ASSISTANT_CONVERSATION(7)" // public constant exists on newer Android than compileSdk 34
        else -> "MODE_$mode"
    }

    private fun audioSourceLabel(source: Int): String = when (source) {
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
        else -> "SOURCE_$source"
    }

    private fun profileState(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "STATE_$state"
    }

    private fun yesNo(value: Boolean): String = if (value) "SI" else "NO"
}
