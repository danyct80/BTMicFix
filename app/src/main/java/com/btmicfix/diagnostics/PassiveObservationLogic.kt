package com.btmicfix.diagnostics

/** Pure timing/logic for the passive Gemini observation flow. */
object PassiveObservationLogic {
    const val TOTAL_MS = 20_000L
    const val BASELINE_END_MS = 3_000L
    const val GEMINI_END_MS = 15_000L

    enum class Phase {
        BASELINE,
        GEMINI,
        RECOVERY,
        COMPLETE,
    }

    fun phaseFor(elapsedMs: Long): Phase = when {
        elapsedMs < 0L -> Phase.BASELINE
        elapsedMs < BASELINE_END_MS -> Phase.BASELINE
        elapsedMs < GEMINI_END_MS -> Phase.GEMINI
        elapsedMs < TOTAL_MS -> Phase.RECOVERY
        else -> Phase.COMPLETE
    }

    fun cueFor(phase: Phase): String = when (phase) {
        Phase.BASELINE -> "ATTENDI — non premere ancora il tasto Cardo"
        Phase.GEMINI -> "ORA premi il tasto Cardo, apri Gemini e parla normalmente"
        Phase.RECOVERY -> "RILASCIA — non toccare nulla, osserviamo il ritorno"
        Phase.COMPLETE -> "OSSERVAZIONE COMPLETATA"
    }

    fun secondsRemaining(elapsedMs: Long): Int {
        val remaining = (TOTAL_MS - elapsedMs).coerceAtLeast(0L)
        return ((remaining + 999L) / 1_000L).toInt()
    }
}
