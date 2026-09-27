package com.btmicfix.shizuku

import com.btmicfix.IPrivilegedService
import com.btmicfix.util.Logger
import java.util.concurrent.TimeUnit

/** Shizuku UserService. Runs as shell and exposes narrowly-scoped audio diagnostics/actions. */
class PrivilegedServiceImpl : IPrivilegedService.Stub() {

    private val forceStateLock = Any()
    private var savedCommunicationForce: Int? = null
    private var savedRecordForce: Int? = null

    data class ForceResult(val success: Boolean, val message: String)
    data class ReadForceResult(val success: Boolean, val value: Int?, val message: String)

    override fun destroy() {
        // Last-resort safety: if the UserService is removed while it still owns a saved
        // force-use snapshot, restore that snapshot before terminating the shell process.
        // Normal UI cleanup already does this; this path protects Activity/process teardown.
        try {
            if (savedCommunicationForce != null || savedRecordForce != null) {
                clearForcedBluetoothSco()
            }
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
    }
}
