package com.btmicfix.shizuku

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import com.btmicfix.IPrivilegedService
import com.btmicfix.audio.VoiceExclusionPolicy
import com.btmicfix.util.Logger
import java.util.concurrent.TimeUnit

/** Shizuku UserService. Runs as shell and exposes narrowly-scoped audio diagnostics/actions. */
class PrivilegedServiceImpl : IPrivilegedService.Stub() {

    private val forceStateLock = Any()
    private val strategyRoleLock = Any()
    private var savedCommunicationForce: Int? = null
    private var savedRecordForce: Int? = null
    private var activeStrategySnapshots: Map<Int, List<NativeRoleDevice>>? = null

    data class ForceResult(val success: Boolean, val message: String)
    data class ReadForceResult(val success: Boolean, val value: Int?, val message: String)
    data class NativeRoleDevice(val internalType: Int, val address: String)
    data class StrategyDescriptor(val id: Int, val name: String, val usages: Set<Int>)

    override fun destroy() {
        // Last-resort safety: if the UserService is removed while it still owns a saved
        // force-use snapshot, restore that snapshot before terminating the shell process.
        // Normal UI cleanup already does this; this path protects Activity/process teardown.
        try {
            if (savedCommunicationForce != null || savedRecordForce != null) {
                clearForcedBluetoothSco()
            }
            restoreActiveStrategySnapshotsBestEffort()
        } catch (t: Throwable) {
            Logger.e("PrivilegedService destroy cleanup failed", t)
        } finally {
            System.exit(0)
        }
    }

    override fun executeAudioCommand(command: String): String? {
        if (ALLOWED_PREFIXES.none { command.startsWith(it) }) {
            return "ERROR: command not allowed"
        }
        return runShell(command)
    }

    override fun getAudioDump(): String? = runShell("dumpsys audio")

    override fun forceAudioStrategy(strategy: Int, deviceType: Int): Boolean {
        if (strategy !in 0..15 || deviceType !in 0..30) return false
        return setForceUseBestEffort(strategy, deviceType).success
    }

    override fun forceBluetoothSco(): String = synchronized(forceStateLock) {
        // Snapshot the pre-existing global policy only once. Repeated force calls (e.g. lock
        // mode) must not overwrite the original values with BT_SCO=3, otherwise cleanup
        // would restore the forced state instead of the real previous state.
        if (savedCommunicationForce == null || savedRecordForce == null) {
            val beforeCommunication = getForceUseBestEffort(FOR_COMMUNICATION)
            val beforeRecord = getForceUseBestEffort(FOR_RECORD)
            if (!beforeCommunication.success || beforeCommunication.value == null ||
                !beforeRecord.success || beforeRecord.value == null
            ) {
                return@synchronized buildString {
                    appendLine("RESULT=FAILED")
                    appendLine("Cannot snapshot pre-force audio policy; refusing to modify global force-use state")
                    appendLine("COMMUNICATION before -> ${beforeCommunication.message}")
                    appendLine("RECORD before -> ${beforeRecord.message}")
                }.trim()
            }
            savedCommunicationForce = beforeCommunication.value
            savedRecordForce = beforeRecord.value
        }

        val communicationSet = setForceUseBestEffort(FOR_COMMUNICATION, FORCE_BT_SCO)
        val recordSet = setForceUseBestEffort(FOR_RECORD, FORCE_BT_SCO)
        val communicationRead = getForceUseBestEffort(FOR_COMMUNICATION)
        val recordRead = getForceUseBestEffort(FOR_RECORD)

        val communicationOk = communicationSet.success &&
            communicationRead.success && communicationRead.value == FORCE_BT_SCO
        val recordOk = recordSet.success &&
            recordRead.success && recordRead.value == FORCE_BT_SCO

        if (!communicationOk || !recordOk) {
            // Force-use is global process/system policy. Never intentionally leave a half-applied
            // state: immediately roll back to the snapshot taken above.
            val rollback = clearForcedBluetoothSco()
            val rollbackOk = rollback.contains("RESULT=CLEARED", ignoreCase = true)
            return@synchronized buildString {
                appendLine("RESULT=${if (rollbackOk) "FAILED_ROLLED_BACK" else "FAILED_DIRTY"}")
                appendLine("COMM_SUCCESS=$communicationOk")
                appendLine("RECORD_SUCCESS=$recordOk")
                appendLine("COMMUNICATION set -> ${communicationSet.message}")
                appendLine("RECORD set -> ${recordSet.message}")
                appendLine("COMMUNICATION verify -> ${communicationRead.message}")
                appendLine("RECORD verify -> ${recordRead.message}")
                appendLine("--- rollback ---")
                append(rollback)
            }.trim()
        }

        buildString {
            appendLine("RESULT=OK")
            appendLine("PREVIOUS_COMMUNICATION=$savedCommunicationForce")
            appendLine("PREVIOUS_RECORD=$savedRecordForce")
            appendLine("COMM_SUCCESS=true")
            appendLine("RECORD_SUCCESS=true")
            appendLine("COMMUNICATION set -> ${communicationSet.message}")
            appendLine("RECORD set -> ${recordSet.message}")
            appendLine("COMMUNICATION verify -> ${communicationRead.message}")
            appendLine("RECORD verify -> ${recordRead.message}")
        }.trim()
    }

    override fun clearForcedBluetoothSco(): String = synchronized(forceStateLock) {
        val currentCommunication = getForceUseBestEffort(FOR_COMMUNICATION)
        val currentRecord = getForceUseBestEffort(FOR_RECORD)

        val targetCommunication = savedCommunicationForce ?: run {
            val value = currentCommunication.value
            if (!currentCommunication.success || value == null ||
                (value != FORCE_NONE && value != FORCE_BT_SCO)
            ) {
                return@synchronized buildString {
                    appendLine("RESULT=CLEAR_FAILED")
                    appendLine("No saved policy and current COMMUNICATION force is not safely resettable")
                    appendLine("COMMUNICATION current -> ${currentCommunication.message}")
                    appendLine("RECORD current -> ${currentRecord.message}")
                }.trim()
            }
            FORCE_NONE
        }

        val targetRecord = savedRecordForce ?: run {
            val value = currentRecord.value
            if (!currentRecord.success || value == null ||
                (value != FORCE_NONE && value != FORCE_BT_SCO)
            ) {
                return@synchronized buildString {
                    appendLine("RESULT=CLEAR_FAILED")
                    appendLine("No saved policy and current RECORD force is not safely resettable")
                    appendLine("COMMUNICATION current -> ${currentCommunication.message}")
                    appendLine("RECORD current -> ${currentRecord.message}")
                }.trim()
            }
            FORCE_NONE
        }

        val communicationSet = setForceUseBestEffort(FOR_COMMUNICATION, targetCommunication)
        val recordSet = setForceUseBestEffort(FOR_RECORD, targetRecord)
        val communicationRead = getForceUseBestEffort(FOR_COMMUNICATION)
        val recordRead = getForceUseBestEffort(FOR_RECORD)

        val communicationOk = communicationSet.success &&
            communicationRead.success && communicationRead.value == targetCommunication
        val recordOk = recordSet.success &&
            recordRead.success && recordRead.value == targetRecord

        val resultCode = when {
            communicationOk && recordOk -> "CLEARED"
            communicationOk || recordOk -> "PARTIAL_CLEAR"
            else -> "CLEAR_FAILED"
        }

        if (communicationOk && recordOk) {
            savedCommunicationForce = null
            savedRecordForce = null
        }

        buildString {
            appendLine("RESULT=$resultCode")
            appendLine("RESTORE_COMMUNICATION=$targetCommunication")
            appendLine("RESTORE_RECORD=$targetRecord")
            appendLine("COMM_CLEAR_SUCCESS=$communicationOk")
            appendLine("RECORD_CLEAR_SUCCESS=$recordOk")
            appendLine("COMMUNICATION restore -> ${communicationSet.message}")
            appendLine("RECORD restore -> ${recordSet.message}")
            appendLine("COMMUNICATION verify -> ${communicationRead.message}")
            appendLine("RECORD verify -> ${recordRead.message}")
        }.trim()
    }

    override fun inspectVoiceExclusionCapabilities(): String = synchronized(strategyRoleLock) {
        return@synchronized try {
            val strategies = discoverTargetStrategies()
            val audioSystem = Class.forName("android.media.AudioSystem")
            val setPrimitive = audioSystem.declaredMethods.any { method ->
                method.name == "setDevicesRoleForStrategy" && method.parameterTypes.size == 4
            }
            val getRole = audioSystem.declaredMethods.any { method ->
                method.name == "getDevicesForRoleAndStrategy" && method.parameterTypes.size == 3
            }
            val clearRole = audioSystem.declaredMethods.any { method ->
                method.name == "clearDevicesRoleForStrategy" && method.parameterTypes.size == 2
            }

            buildString {
                appendLine("RESULT=${if (strategies.isNotEmpty() && setPrimitive && getRole && clearRole) "AVAILABLE" else "UNAVAILABLE"}")
                appendLine("SET_ROLE_METHOD=$setPrimitive")
                appendLine("GET_ROLE_METHOD=$getRole")
                appendLine("CLEAR_ROLE_METHOD=$clearRole")
                appendLine("TARGET_STRATEGY_COUNT=${strategies.size}")
                strategies.forEach { strategy ->
                    appendLine(
                        "STRATEGY id=${strategy.id} name=${strategy.name} usages=" +
                            strategy.usages.sorted().joinToString(",")
                    )
                }
            }.trim()
        } catch (t: Throwable) {
            Logger.e("inspectVoiceExclusionCapabilities failed", t)
            "RESULT=UNAVAILABLE\n${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * Temporary inverse-routing diagnostic.
     *
     * The selected head unit is added to DEVICE_ROLE_DISABLED only for product strategies
     * supporting VOICE_COMMUNICATION and/or ASSISTANT. Existing disabled-role lists are
     * snapshotted and restored exactly in finally. Media strategies are never touched.
     */
    override fun testVoiceDeviceExclusion(
        publicType: Int,
        address: String?,
        name: String?,
        durationMs: Int,
    ): String = synchronized(strategyRoleLock) {
        val safeAddress = address?.trim().orEmpty()
        val safeName = name?.trim().orEmpty()
        if (safeAddress.isBlank()) {
            return@synchronized "RESULT=REFUSED\nREASON=EMPTY_ADDRESS\nRefusing to disable a generic device type without a stable address"
        }
        val windowMs = durationMs.coerceIn(VoiceExclusionPolicy.MIN_WINDOW_MS, VoiceExclusionPolicy.MAX_WINDOW_MS)

        val strategies = try {
            discoverTargetStrategies()
        } catch (t: Throwable) {
            return@synchronized "RESULT=UNAVAILABLE\nSTRATEGY_DISCOVERY=${t.javaClass.simpleName}: ${t.message}"
        }
        if (strategies.isEmpty()) {
            return@synchronized "RESULT=UNAVAILABLE\nREASON=NO_VOICE_OR_ASSISTANT_STRATEGY"
        }

        val candidateTypes = candidateInternalOutputTypes(publicType)
        if (candidateTypes.isEmpty()) {
            return@synchronized "RESULT=REFUSED\nREASON=UNSUPPORTED_PUBLIC_TYPE_$publicType"
        }

        val snapshots = linkedMapOf<Int, List<NativeRoleDevice>>()
        val applied = mutableListOf<Int>()
        val report = mutableListOf<String>()
        report += "TARGET_NAME=${safeName.ifBlank { "unknown" }}"
        report += "TARGET_ADDRESS=$safeAddress"
        report += "PUBLIC_TYPE=$publicType"
        report += "CANDIDATE_INTERNAL_TYPES=${candidateTypes.joinToString(",") { "0x${it.toUInt().toString(16)}" }}"
        report += "WINDOW_MS=$windowMs"
        report += "TARGET_USAGES=VOICE_COMMUNICATION,ASSISTANT"
        report += "MEDIA_STRATEGIES_TOUCHED=false"

        try {
            for (strategy in strategies) {
                val snapshot = readDisabledDevices(strategy.id)
                if (!snapshot.success) {
                    throw IllegalStateException(
                        "Cannot snapshot disabled role for strategy ${strategy.id}: ${snapshot.message}"
                    )
                }
                snapshots[strategy.id] = snapshot.devices
            }
            activeStrategySnapshots = snapshots.toMap()

            for (strategy in strategies) {
                val previous = snapshots[strategy.id].orEmpty()
                val additions = candidateTypes.map { NativeRoleDevice(it, safeAddress) }
                val desired = (previous + additions).distinctBy { it.internalType to it.address }
                val setStatus = setDisabledDevices(strategy.id, desired)
                if (setStatus != AUDIO_STATUS_OK) {
                    throw IllegalStateException(
                        "setDevicesRoleForStrategy(${strategy.id}) -> $setStatus"
                    )
                }
                applied += strategy.id

                val verify = readDisabledDevices(strategy.id)
                val hasTarget = verify.success && verify.devices.any { device ->
                    device.address.equals(safeAddress, ignoreCase = true) &&
                        device.internalType in candidateTypes
                }
                report += "APPLY strategy=${strategy.id}/${strategy.name} status=$setStatus verified=$hasTarget"
                if (!hasTarget) {
                    throw IllegalStateException(
                        "Disabled role verification failed for strategy ${strategy.id}"
                    )
                }
            }

            report += "APPLY_RESULT=OK"
            report += "TEST_WINDOW_BEGIN"
            Thread.sleep(windowMs.toLong())
            report += "TEST_WINDOW_END"
        } catch (t: Throwable) {
            report += "APPLY_RESULT=FAILED"
            report += "ERROR=${t.javaClass.simpleName}: ${t.message}"
        } finally {
            var restoreAllOk = true
            for ((strategyId, previous) in snapshots.entries.reversed()) {
                val restoreStatus = restoreDisabledDevices(strategyId, previous)
                val verify = readDisabledDevices(strategyId)
                val restoreStatusOk = restoreStatus == AUDIO_STATUS_OK ||
                    (previous.isEmpty() && restoreStatus == AUDIO_STATUS_NAME_NOT_FOUND)
                val restored = restoreStatusOk && verify.success &&
                    sameRoleDeviceSet(previous, verify.devices)
                report += "RESTORE strategy=$strategyId status=$restoreStatus verified=$restored"
                if (!restored) restoreAllOk = false
            }
            activeStrategySnapshots = null
            report += "RESTORE_RESULT=${if (restoreAllOk) "OK" else "FAILED"}"
        }

        val applyOk = report.any { it == "APPLY_RESULT=OK" }
        val restoreOk = report.any { it == "RESTORE_RESULT=OK" }
        return@synchronized buildString {
            appendLine(
                "RESULT=" + when {
                    applyOk && restoreOk -> "OK"
                    !restoreOk -> "FAILED_DIRTY"
                    else -> "FAILED_ROLLED_BACK"
                }
            )
            report.forEach(::appendLine)
        }.trim()
    }

    private data class RoleReadResult(
        val success: Boolean,
        val devices: List<NativeRoleDevice>,
        val message: String,
    )

    private fun discoverTargetStrategies(): List<StrategyDescriptor> {
        val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
        val listMethod = strategyClass.getDeclaredMethod("getAudioProductStrategies").apply {
            isAccessible = true
        }
        val supportsMethod = strategyClass.getDeclaredMethod(
            "supportsAudioAttributes",
            AudioAttributes::class.java,
        ).apply { isAccessible = true }
        val getIdMethod = strategyClass.getDeclaredMethod("getId").apply { isAccessible = true }
        val getNameMethod = runCatching {
            strategyClass.getDeclaredMethod("getName").apply { isAccessible = true }
        }.getOrNull()

        @Suppress("UNCHECKED_CAST")
        val strategies = (listMethod.invoke(null) as? List<Any>).orEmpty()
        val targetAttributes = listOf(
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_ASSISTANT,
        ).associateWith { usage ->
            AudioAttributes.Builder().setUsage(usage).build()
        }

        return strategies.mapNotNull { strategy ->
            val usages = targetAttributes.filterValues { attrs ->
                (supportsMethod.invoke(strategy, attrs) as? Boolean) == true
            }.keys
            if (usages.isEmpty()) return@mapNotNull null
            StrategyDescriptor(
                id = (getIdMethod.invoke(strategy) as Number).toInt(),
                name = getNameMethod?.let { method ->
                    runCatching { method.invoke(strategy)?.toString() }.getOrNull()
                }.orEmpty().ifBlank { strategy.toString() },
                usages = usages,
            )
        }.distinctBy { it.id }
    }

    private fun candidateInternalOutputTypes(publicType: Int): List<Int> {
        val reflected = try {
            val method = AudioDeviceInfo::class.java.getDeclaredMethod(
                "convertDeviceTypeToInternalDevice",
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            (method.invoke(null, publicType) as? Number)?.toInt()
        } catch (_: Throwable) {
            null
        }

        val variants = mutableListOf<Int>()
        if (reflected != null && reflected != 0) variants += reflected
        when (publicType) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> variants += listOf(
                DEVICE_OUT_BLUETOOTH_SCO,
                DEVICE_OUT_BLUETOOTH_SCO_HEADSET,
                DEVICE_OUT_BLUETOOTH_SCO_CARKIT,
            )
            AudioDeviceInfo.TYPE_BLE_HEADSET -> variants += DEVICE_OUT_BLE_HEADSET
        }
        return variants.distinct()
    }

    private fun readDisabledDevices(strategyId: Int): RoleReadResult {
        return try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val attributesClass = Class.forName("android.media.AudioDeviceAttributes")
            val method = audioSystem.getDeclaredMethod(
                "getDevicesForRoleAndStrategy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                List::class.java,
            ).apply { isAccessible = true }
            val list = arrayListOf<Any>()
            val status = (method.invoke(null, strategyId, DEVICE_ROLE_DISABLED, list) as Number).toInt()
            if (status != AUDIO_STATUS_OK && status != AUDIO_STATUS_NAME_NOT_FOUND) {
                return RoleReadResult(false, emptyList(), "status=$status")
            }
            if (status == AUDIO_STATUS_NAME_NOT_FOUND) {
                return RoleReadResult(true, emptyList(), "none")
            }

            val getInternalType = attributesClass.getDeclaredMethod("getInternalType").apply {
                isAccessible = true
            }
            val getAddress = attributesClass.getDeclaredMethod("getAddress").apply {
                isAccessible = true
            }
            val devices = list.map { item ->
                NativeRoleDevice(
                    internalType = (getInternalType.invoke(item) as Number).toInt(),
                    address = getAddress.invoke(item)?.toString().orEmpty(),
                )
            }
            RoleReadResult(true, devices, "status=0 count=${devices.size}")
        } catch (t: Throwable) {
            RoleReadResult(false, emptyList(), "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun setDisabledDevices(strategyId: Int, devices: List<NativeRoleDevice>): Int {
        if (devices.isEmpty()) return clearDisabledDevices(strategyId)
        val primitiveAttempt = try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val method = audioSystem.getDeclaredMethod(
                "setDevicesRoleForStrategy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                IntArray::class.java,
                arrayOf<String>().javaClass,
            ).apply { isAccessible = true }
            val types = devices.map { it.internalType }.toIntArray()
            val addresses = devices.map { it.address }.toTypedArray()
            (method.invoke(null, strategyId, DEVICE_ROLE_DISABLED, types, addresses) as Number).toInt()
        } catch (t: Throwable) {
            Logger.w("Primitive setDevicesRoleForStrategy unavailable: ${t.javaClass.simpleName}")
            null
        }
        if (primitiveAttempt != null) return primitiveAttempt

        // Fallback for builds that expose only the List<AudioDeviceAttributes> overload.
        return try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val attributesClass = Class.forName("android.media.AudioDeviceAttributes")
            val ctor = attributesClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val roleDevices = devices.map { device ->
                ctor.newInstance(device.internalType, device.address, "BTMicFix exclusion probe")
            }
            val method = audioSystem.getDeclaredMethod(
                "setDevicesRoleForStrategy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                List::class.java,
            ).apply { isAccessible = true }
            (method.invoke(null, strategyId, DEVICE_ROLE_DISABLED, roleDevices) as Number).toInt()
        } catch (t: Throwable) {
            Logger.e("setDisabledDevices fallback failed", t)
            AUDIO_STATUS_EXCEPTION
        }
    }

    private fun clearDisabledDevices(strategyId: Int): Int {
        return try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val method = audioSystem.getDeclaredMethod(
                "clearDevicesRoleForStrategy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            (method.invoke(null, strategyId, DEVICE_ROLE_DISABLED) as Number).toInt()
        } catch (t: Throwable) {
            Logger.e("clearDisabledDevices failed", t)
            AUDIO_STATUS_EXCEPTION
        }
    }

    private fun restoreDisabledDevices(strategyId: Int, previous: List<NativeRoleDevice>): Int =
        if (previous.isEmpty()) clearDisabledDevices(strategyId)
        else setDisabledDevices(strategyId, previous)

    private fun sameRoleDeviceSet(
        expected: List<NativeRoleDevice>,
        actual: List<NativeRoleDevice>,
    ): Boolean = expected.map { it.internalType to it.address.lowercase() }.toSet() ==
        actual.map { it.internalType to it.address.lowercase() }.toSet()

    private fun restoreActiveStrategySnapshotsBestEffort() {
        val snapshots = activeStrategySnapshots ?: return
        synchronized(strategyRoleLock) {
            snapshots.forEach { (strategyId, devices) ->
                try {
                    restoreDisabledDevices(strategyId, devices)
                } catch (_: Throwable) {
                }
            }
            activeStrategySnapshots = null
        }
    }

    override fun getRoutingCapabilities(): String {
        val communication = getForceUseBestEffort(FOR_COMMUNICATION)
        val record = getForceUseBestEffort(FOR_RECORD)
        return buildString {
            appendLine("AudioSystem.getForceUse=${if (communication.success && record.success) "AVAILABLE" else "PARTIAL_OR_UNAVAILABLE"}")
            appendLine("FOR_COMMUNICATION=${communication.value ?: "N/D"}")
            append("FOR_RECORD=${record.value ?: "N/D"}")
        }
    }

    private fun getForceUseBestEffort(usage: Int): ReadForceResult {
        val reflection = try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val method = audioSystem.getDeclaredMethod(
                "getForceUse",
                Int::class.javaPrimitiveType,
            )
            method.isAccessible = true
            val raw = method.invoke(null, usage)
            val value = (raw as? Number)?.toInt()
            if (value != null) {
                return ReadForceResult(
                    success = true,
                    value = value,
                    message = "AudioSystem.getForceUse($usage) -> $value",
                )
            }
            "reflection returned null"
        } catch (t: Throwable) {
            Logger.w("getForceUse reflection unavailable: ${t.javaClass.simpleName}")
            "${t.javaClass.simpleName}: ${t.message}"
        }

        // Some OEM builds allow shell setForceUse() but hide getForceUse() reflection.
        // dumpsys audio exposes the same force-use state and was already proven useful on
        // affected Android/HyperOS devices, so use it as a read-only verification fallback.
        val dump = runShell("dumpsys audio")
        val value = parseForceUseFromDump(dump, usage)
        return if (value != null) {
            ReadForceResult(
                success = true,
                value = value,
                message = "dumpsys audio forceUse($usage) -> $value (reflection: $reflection)",
            )
        } else {
            ReadForceResult(
                success = false,
                value = null,
                message = "verification unavailable (reflection: $reflection)",
            )
        }
    }

    private fun parseForceUseFromDump(dump: String, usage: Int): Int? {
        val patterns = when (usage) {
            FOR_COMMUNICATION -> listOf(
                Regex("(?im)^\\s*Force use for communications?\\s*[:=]\\s*(\\d+)"),
                Regex("(?im)^\\s*FOR_COMMUNICATION\\s*[:=]\\s*(\\d+)"),
            )
            FOR_RECORD -> listOf(
                Regex("(?im)^\\s*Force use for record\\s*[:=]\\s*(\\d+)"),
                Regex("(?im)^\\s*FOR_RECORD\\s*[:=]\\s*(\\d+)"),
            )
            else -> emptyList()
        }
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    private fun setForceUseBestEffort(usage: Int, config: Int): ForceResult {
        return try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val method = audioSystem.getDeclaredMethod(
                "setForceUse",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            method.isAccessible = true
            val raw = method.invoke(null, usage, config)
            val status = (raw as? Number)?.toInt()
            ForceResult(
                success = status == AUDIO_STATUS_OK,
                message = "AudioSystem.setForceUse($usage,$config) -> $status",
            )
        } catch (t: Throwable) {
            Logger.e("setForceUse failed", t)
            ForceResult(false, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun runShell(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.appendLine(it) }
                }
            }.apply { start() }

            if (!process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reader.join(1_000L)
                return "ERROR(timeout): command exceeded ${SHELL_TIMEOUT_SECONDS}s"
            }
            reader.join(1_000L)
            val text = output.toString().trim()
            if (process.exitValue() == 0) text
            else "ERROR(exit=${process.exitValue()}): $text"
        } catch (t: Throwable) {
            "EXCEPTION: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    companion object {
        private val ALLOWED_PREFIXES = listOf("dumpsys audio", "cmd audio", "settings get")
        private const val FOR_COMMUNICATION = 0
        private const val FOR_RECORD = 2
        private const val FORCE_NONE = 0
        private const val FORCE_BT_SCO = 3
        private const val AUDIO_STATUS_OK = 0
        private const val SHELL_TIMEOUT_SECONDS = 8L
        private const val DEVICE_ROLE_DISABLED = 2
        private const val AUDIO_STATUS_NAME_NOT_FOUND = -2
        private const val AUDIO_STATUS_EXCEPTION = Int.MIN_VALUE
        private const val DEVICE_OUT_BLUETOOTH_SCO = 0x10
        private const val DEVICE_OUT_BLUETOOTH_SCO_HEADSET = 0x20
        private const val DEVICE_OUT_BLUETOOTH_SCO_CARKIT = 0x40
        private const val DEVICE_OUT_BLE_HEADSET = 0x20000000
    }
}
