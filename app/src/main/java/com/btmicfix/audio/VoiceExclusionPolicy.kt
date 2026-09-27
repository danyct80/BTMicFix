package com.btmicfix.audio

/** Pure logic for the inverse-routing experiment. No Android calls live here. */
object VoiceExclusionPolicy {
    const val DEFAULT_WINDOW_MS = 20_000
    const val MIN_WINDOW_MS = 5_000
    const val MAX_WINDOW_MS = 30_000

    enum class TargetUsage {
        VOICE_COMMUNICATION,
        ASSISTANT,
    }

    fun isSafeCandidate(address: String?, isPriorityDevice: Boolean): Boolean =
        !address.isNullOrBlank() && !isPriorityDevice

    fun targetUsages(): Set<TargetUsage> = setOf(
        TargetUsage.VOICE_COMMUNICATION,
        TargetUsage.ASSISTANT,
    )

    fun touchesMediaStrategy(): Boolean = false

    fun mustRestorePolicyAfterTest(): Boolean = true
}
