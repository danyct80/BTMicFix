package com.btmicfix.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceExclusionPolicyTest {

    @Test
    fun headUnitNeedsStableAddress() {
        assertFalse(VoiceExclusionPolicy.isSafeCandidate(null, false))
        assertFalse(VoiceExclusionPolicy.isSafeCandidate("", false))
        assertTrue(VoiceExclusionPolicy.isSafeCandidate("AA:BB:CC:DD:EE:FF", false))
    }

    @Test
    fun priorityHeadsetCanNeverBeExcludedByInverseProbe() {
        assertFalse(
            VoiceExclusionPolicy.isSafeCandidate(
                address = "AA:BB:CC:DD:EE:FF",
                isPriorityDevice = true,
            )
        )
    }

    @Test
    fun probeTargetsOnlyVoiceAndAssistant() {
        assertEquals(
            setOf(
                VoiceExclusionPolicy.TargetUsage.VOICE_COMMUNICATION,
                VoiceExclusionPolicy.TargetUsage.ASSISTANT,
            ),
            VoiceExclusionPolicy.targetUsages(),
        )
        assertFalse(VoiceExclusionPolicy.touchesMediaStrategy())
    }

    @Test
    fun probeAlwaysRequiresRollback() {
        assertTrue(VoiceExclusionPolicy.mustRestorePolicyAfterTest())
    }
}
