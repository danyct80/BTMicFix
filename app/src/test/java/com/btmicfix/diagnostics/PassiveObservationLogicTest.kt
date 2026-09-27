package com.btmicfix.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class PassiveObservationLogicTest {
    @Test
    fun phase_boundaries_are_stable() {
        assertEquals(PassiveObservationLogic.Phase.BASELINE, PassiveObservationLogic.phaseFor(0))
        assertEquals(PassiveObservationLogic.Phase.BASELINE, PassiveObservationLogic.phaseFor(2_999))
        assertEquals(PassiveObservationLogic.Phase.GEMINI, PassiveObservationLogic.phaseFor(3_000))
        assertEquals(PassiveObservationLogic.Phase.GEMINI, PassiveObservationLogic.phaseFor(14_999))
        assertEquals(PassiveObservationLogic.Phase.RECOVERY, PassiveObservationLogic.phaseFor(15_000))
        assertEquals(PassiveObservationLogic.Phase.RECOVERY, PassiveObservationLogic.phaseFor(19_999))
        assertEquals(PassiveObservationLogic.Phase.COMPLETE, PassiveObservationLogic.phaseFor(20_000))
    }

    @Test
    fun seconds_remaining_never_go_negative() {
        assertEquals(20, PassiveObservationLogic.secondsRemaining(0))
        assertEquals(17, PassiveObservationLogic.secondsRemaining(3_000))
        assertEquals(1, PassiveObservationLogic.secondsRemaining(19_001))
        assertEquals(0, PassiveObservationLogic.secondsRemaining(20_000))
        assertEquals(0, PassiveObservationLogic.secondsRemaining(40_000))
    }

    @Test
    fun cue_matches_gemini_window() {
        assertEquals(
            "ORA premi il tasto Cardo, apri Gemini e parla normalmente",
            PassiveObservationLogic.cueFor(PassiveObservationLogic.Phase.GEMINI),
        )
    }
}
