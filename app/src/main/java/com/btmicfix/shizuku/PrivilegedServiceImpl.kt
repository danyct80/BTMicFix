package com.btmicfix.shizuku

import com.btmicfix.IPrivilegedService
import com.btmicfix.util.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Read-only Shizuku UserService used by the 0.9 passive observer.
 *
 * IMPORTANT: this service never changes AudioSystem, AudioPolicy, Bluetooth, audio mode,
 * communication devices, focus, capture policy, or any other routing state. It only reads
 * dumpsys output and returns a filtered snapshot to the app.
 */
class PrivilegedServiceImpl : IPrivilegedService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun collectPassiveSnapshot(label: String): String {
        val safeLabel = label.replace('\n', ' ').take(80)
        val audio = filterDump(
            runShell("dumpsys audio"),
            AUDIO_KEYWORDS,
            maxLines = 420,
        )
        val policy = filterDump(
            runShell("dumpsys media.audio_policy"),
            POLICY_KEYWORDS,
            maxLines = 520,
        )
        val flinger = if (safeLabel.startsWith("GEMINI")) {
            filterDump(
                runShell("dumpsys media.audio_flinger"),
                FLINGER_KEYWORDS,
                maxLines = 360,
            )
        } else {
            "<skipped outside Gemini window>"
        }
        val bluetooth = filterDump(
            runShell("dumpsys bluetooth_manager"),
            BLUETOOTH_KEYWORDS,
            maxLines = 280,
        )

        return buildString {
            appendLine("=== PRIVILEGED PASSIVE SNAPSHOT: $safeLabel ===")
            appendLine("epochMs=${System.currentTimeMillis()}")
            appendLine("--- dumpsys audio (filtered) ---")
            appendLine(audio.ifBlank { "<no matching lines>" })
            appendLine("--- dumpsys media.audio_policy (filtered) ---")
            appendLine(policy.ifBlank { "<no matching lines>" })
            appendLine("--- dumpsys media.audio_flinger (filtered) ---")
            appendLine(flinger.ifBlank { "<no matching lines>" })
            appendLine("--- dumpsys bluetooth_manager (filtered) ---")
            appendLine(bluetooth.ifBlank { "<no matching lines>" })
        }.trim()
    }

    private fun filterDump(text: String, keywords: Set<String>, maxLines: Int): String {
        if (text.startsWith("ERROR:", ignoreCase = true)) return text
        val lines = text.lineSequence().toList()
        if (lines.isEmpty()) return ""

        val keep = linkedSetOf<Int>()
        lines.forEachIndexed { index, line ->
            val lower = line.lowercase()
            if (keywords.any(lower::contains)) {
                // Keep a little context without returning a potentially multi-megabyte dumpsys.
                for (i in (index - 1).coerceAtLeast(0)..(index + 2).coerceAtMost(lines.lastIndex)) {
                    keep += i
                }
            }
        }

        return keep.asSequence()
            .sorted()
            .take(maxLines)
            .joinToString("\n") { lines[it] }
    }

    private fun runShell(command: String): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val readerThread = thread(start = true, name = "BTMicFix-dumpsys-reader") {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { seq ->
                    seq.forEach { line -> output.appendLine(line) }
                }
            }

            val finished = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(500L)
                "ERROR: timeout while running $command"
            } else {
                readerThread.join(1_000L)
                output.toString().trim()
            }
        } catch (t: Throwable) {
            Logger.e("Passive dumpsys failed: $command", t)
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            try { process?.inputStream?.close() } catch (_: Throwable) {}
            try { process?.errorStream?.close() } catch (_: Throwable) {}
            try { process?.outputStream?.close() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val SHELL_TIMEOUT_SECONDS = 3L

        private val AUDIO_KEYWORDS = setOf(
            "mode", "communication", "record", "recording", "capture", "input",
            "voice", "assistant", "device", "sco", "bluetooth", "focus", "session",
            "source", "silenc", "route", "uid", "projection", "android auto",
        )

        private val POLICY_KEYWORDS = setOf(
            "input", "record", "capture", "source", "device", "route", "strategy",
            "voice", "assistant", "sco", "bluetooth", "active", "session", "uid",
            "mix", "primary", "remote", "projection",
        )

        private val FLINGER_KEYWORDS = setOf(
            "record", "input", "capture", "active", "session", "source", "device",
            "thread", "silenc", "voice", "assistant", "sco", "bluetooth",
        )

        private val BLUETOOTH_KEYWORDS = setOf(
            "connected", "connecting", "active", "headset", "a2dp", "le audio",
            "le_audio", "sco", "profile", "bond", "device", "audio",
        )
    }
}
