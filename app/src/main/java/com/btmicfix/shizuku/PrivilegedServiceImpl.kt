package com.btmicfix.shizuku

import com.btmicfix.IPrivilegedService
import com.btmicfix.util.Logger

/**
 * Shizuku UserService implementation that runs as the ADB shell user (UID 2000).
 *
 * IMPORTANT:
 * - The public AudioManager.setCommunicationDevice() API remains the primary route selector.
 * - This privileged service is only an Android Auto/OEM fallback.
 * - The privileged force is intentionally restricted to Bluetooth SCO for communication/record.
 *
 * Two mechanisms are attempted, in this order:
 *  1) hidden AudioSystem.setForceUse() via reflection from the shell process;
 *  2) `cmd audio set-force-use` only if the ROM exposes that shell command.
 *
 * Not every Android/HyperOS build exposes either mechanism. The returned diagnostic string
 * explicitly says what succeeded or failed, so the UI never claims a privileged force worked
 * when it did not.
 */
class PrivilegedServiceImpl : IPrivilegedService.Stub() {

    companion object {
        // AudioSystem force-use constants. These values are stable in AOSP.
        private const val FOR_COMMUNICATION = 0
        private const val FOR_RECORD = 2
        private const val FORCE_NONE = 0
        private const val FORCE_BT_SCO = 3
        private const val AUDIO_STATUS_OK = 0

        // Generic command API is diagnostic-only and tightly allowlisted.
        private val ALLOWED_PREFIXES = listOf(
            "dumpsys audio",
            "dumpsys media.audio_policy",
            "cmd audio",
            "settings get",
        )
    }

    override fun destroy() {
        Logger.i("PrivilegedService: destroy() called, shutting down")
        System.exit(0)
    }

    override fun executeAudioCommand(command: String): String? {
        if (ALLOWED_PREFIXES.none { command.startsWith(it) }) {
            Logger.e("PrivilegedService: blocked disallowed command: $command")
            return "ERROR: Command not in allowlist"
        }
        return runShell(command)
    }

    override fun getAudioDump(): String? {
        val audio = runShell("dumpsys audio").orEmpty()
        val policy = runShell("dumpsys media.audio_policy").orEmpty()
        return buildString {
            appendLine("===== dumpsys audio =====")
            appendLine(audio)
            appendLine("===== dumpsys media.audio_policy =====")
            appendLine(policy)
        }.trim()
    }

    /**
     * Legacy entry point. Here deviceType is interpreted as an AudioSystem FORCE_* config,
     * NOT an AudioDeviceInfo.TYPE_* value. Kept only so older UI/code still compiles.
     */
    override fun forceAudioStrategy(strategy: Int, deviceType: Int): Boolean {
        if (strategy !in 0..15 || deviceType !in 0..20) return false
        return setForceUseBestEffort(strategy, deviceType).success
    }

    override fun forceBluetoothSco(): String {
        val comm = setForceUseBestEffort(FOR_COMMUNICATION, FORCE_BT_SCO)
        val record = setForceUseBestEffort(FOR_RECORD, FORCE_BT_SCO)
        val verification = readForceUseSummary()

        val ok = comm.success || record.success
        val result = buildString {
            appendLine(if (ok) "RESULT=PARTIAL_OR_OK" else "RESULT=FAILED")
            appendLine("COMMUNICATION -> ${comm.message}")
            appendLine("RECORD        -> ${record.message}")
            appendLine("--- policy verification ---")
            append(verification.ifBlank { "No force-use lines found in dumpsys output" })
        }.trim()

        Logger.i("PrivilegedService forceBluetoothSco:\n$result")
        return result
    }

    override fun clearForcedBluetoothSco(): String {
        val comm = setForceUseBestEffort(FOR_COMMUNICATION, FORCE_NONE)
        val record = setForceUseBestEffort(FOR_RECORD, FORCE_NONE)
        val verification = readForceUseSummary()

        return buildString {
            appendLine("COMMUNICATION clear -> ${comm.message}")
            appendLine("RECORD clear        -> ${record.message}")
            appendLine("--- policy verification ---")
            append(verification.ifBlank { "No force-use lines found in dumpsys output" })
        }.trim()
    }

    override fun getRoutingCapabilities(): String {
        val help = runShell("cmd audio help").orEmpty()
        val hasSetForceUse = help.contains("set-force-use", ignoreCase = true)
        val reflectionProbe = probeAudioSystemReflection()

        return buildString {
            appendLine("AudioSystem reflection: $reflectionProbe")
            appendLine("cmd audio set-force-use: ${if (hasSetForceUse) "AVAILABLE" else "NOT LISTED"}")
            appendLine("--- cmd audio help ---")
            append(help.ifBlank { "No output" })
        }.trim()
    }

    private data class ForceResult(val success: Boolean, val message: String)

    /**
     * Prefer hidden AudioSystem.setForceUse() because many current ROMs do not expose an
     * equivalent `cmd audio` shell subcommand. If reflection is blocked by hidden-API policy,
     * fall back to the shell command only when `cmd audio help` advertises it.
     */
    private fun setForceUseBestEffort(usage: Int, config: Int): ForceResult {
        val reflected = setForceUseViaReflection(usage, config)
        if (reflected.success) return reflected

        val help = runShell("cmd audio help").orEmpty()
        if (!help.contains("set-force-use", ignoreCase = true)) {
            return ForceResult(
                false,
                "reflection failed (${reflected.message}); cmd audio set-force-use not exposed by ROM"
            )
        }

        val shell = runShell("cmd audio set-force-use $usage $config")
        val shellOk = shell != null &&
            !shell.startsWith("ERROR", ignoreCase = true) &&
            !shell.contains("Unknown command", ignoreCase = true) &&
            !shell.contains("Exception", ignoreCase = true)

        return if (shellOk) {
            ForceResult(true, "shell accepted: ${shell.ifBlank { "OK (no output)" }}")
        } else {
            ForceResult(false, "reflection failed (${reflected.message}); shell failed: ${shell ?: "null"}")
        }
    }

    private fun setForceUseViaReflection(usage: Int, config: Int): ForceResult {
        return try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            val method = audioSystem.getDeclaredMethod(
                "setForceUse",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            method.isAccessible = true
            val returnValue = method.invoke(null, usage, config)
            val code = (returnValue as? Int) ?: AUDIO_STATUS_OK
            if (code == AUDIO_STATUS_OK) {
                ForceResult(true, "AudioSystem.setForceUse($usage,$config) -> $code")
            } else {
                ForceResult(false, "AudioSystem.setForceUse($usage,$config) -> error $code")
            }
        } catch (t: Throwable) {
            val root = t.cause ?: t
            ForceResult(false, "${root.javaClass.simpleName}: ${root.message ?: "no message"}")
        }
    }

    private fun probeAudioSystemReflection(): String {
        return try {
            val audioSystem = Class.forName("android.media.AudioSystem")
            audioSystem.getDeclaredMethod(
                "setForceUse",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            "METHOD PRESENT"
        } catch (t: Throwable) {
            "UNAVAILABLE (${(t.cause ?: t).javaClass.simpleName})"
        }
    }

    private fun readForceUseSummary(): String {
        val policy = runShell("dumpsys media.audio_policy").orEmpty()
        val audio = runShell("dumpsys audio").orEmpty()
        val combined = "$policy\n$audio"
        return combined.lineSequence()
            .filter {
                it.contains("force use", ignoreCase = true) ||
                it.contains("force_use", ignoreCase = true) ||
                it.contains("communications:", ignoreCase = true) ||
                it.contains("record:", ignoreCase = true)
            }
            .take(30)
            .joinToString("\n")
    }

    private fun runShell(command: String): String? {
        return try {
            Logger.d("PrivilegedService: executing: $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                output.trim()
            } else {
                val msg = error.ifBlank { output }.trim()
                Logger.e("PrivilegedService: command failed (exit=$exitCode): $msg")
                "ERROR(exit=$exitCode): $msg"
            }
        } catch (e: Exception) {
            Logger.e("PrivilegedService: command exception: $command", e)
            "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
