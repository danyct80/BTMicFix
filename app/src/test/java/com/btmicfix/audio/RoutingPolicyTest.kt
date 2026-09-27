package com.btmicfix.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPolicyTest {

    @Test
    fun userEnableIssuesExactlyOneRequestWhenTargetExists() {
        assertTrue(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.USER_ENABLE,
                autoRouteEnabled = false,
                targetAvailable = true,
                requestAlreadyOutstanding = false,
            )
        )
    }

    @Test
    fun assistantTakingRouteNeverTriggersReassert() {
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.COMMUNICATION_DEVICE_CHANGED,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.AUDIO_MODE_CHANGED,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )
    }

    @Test
    fun timerCanNeverBecomeARouteHoldLoop() {
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.TIMER_TICK,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )
    }

    @Test
    fun autoRouteOnlyRequestsWhenNoOutstandingSelectionExists() {
        assertTrue(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.AUTO_DEVICE_APPEARED,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = false,
            )
        )
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.AUTO_DEVICE_APPEARED,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )
    }

    @Test
    fun externalOwnerProducesYieldedStateNotDisabledState() {
        assertEquals(
            RoutingPolicy.LogicalState.YIELDED_TO_OTHER_AUDIO_OWNER,
            RoutingPolicy.evaluate(
                RoutingPolicy.Snapshot(
                    requestEnabled = true,
                    targetAvailable = true,
                    targetIsCurrentCommunicationDevice = false,
                    currentDeviceLabel = "other",
                    audioMode = 3,
                )
            )
        )
    }

    @Test
    fun routeReturningToTargetBecomesActiveWithoutNewRequest() {
        assertEquals(
            RoutingPolicy.LogicalState.ACTIVE,
            RoutingPolicy.evaluate(
                RoutingPolicy.Snapshot(
                    requestEnabled = true,
                    targetAvailable = true,
                    targetIsCurrentCommunicationDevice = true,
                    currentDeviceLabel = "target",
                    audioMode = 0,
                )
            )
        )
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.COMMUNICATION_DEVICE_CHANGED,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )
    }

    @Test
    fun disconnectedTargetCannotBeRouted() {
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.USER_ENABLE,
                autoRouteEnabled = true,
                targetAvailable = false,
                requestAlreadyOutstanding = false,
            )
        )
    }
    @Test
    fun geminiTakeoverFlowYieldsAndReturnsWithoutReassertion() {
        val yielded = RoutingPolicy.evaluate(
            RoutingPolicy.Snapshot(
                requestEnabled = true,
                targetAvailable = true,
                targetIsCurrentCommunicationDevice = false,
                currentDeviceLabel = "assistant-route",
                audioMode = 3,
            )
        )
        assertEquals(RoutingPolicy.LogicalState.YIELDED_TO_OTHER_AUDIO_OWNER, yielded)
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                RoutingPolicy.Trigger.AUDIO_MODE_CHANGED,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                RoutingPolicy.Trigger.COMMUNICATION_DEVICE_CHANGED,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )

        val returned = RoutingPolicy.evaluate(
            RoutingPolicy.Snapshot(
                requestEnabled = true,
                targetAvailable = true,
                targetIsCurrentCommunicationDevice = true,
                currentDeviceLabel = "target",
                audioMode = 0,
            )
        )
        assertEquals(RoutingPolicy.LogicalState.ACTIVE, returned)
    }

    @Test
    fun phoneCallFlowAlsoNeverTriggersRouteFight() {
        assertEquals(
            RoutingPolicy.LogicalState.YIELDED_TO_OTHER_AUDIO_OWNER,
            RoutingPolicy.evaluate(
                RoutingPolicy.Snapshot(
                    requestEnabled = true,
                    targetAvailable = true,
                    targetIsCurrentCommunicationDevice = false,
                    currentDeviceLabel = "phone-call-route",
                    audioMode = 2,
                )
            )
        )
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                RoutingPolicy.Trigger.TIMER_TICK,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )
    }

    @Test
    fun userEnableCannotFightAnOutstandingRequest() {
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.USER_ENABLE,
                autoRouteEnabled = false,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )
    }

    @Test
    fun autoRouteDisabledBlocksAppearanceAndResumeRequests() {
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.AUTO_DEVICE_APPEARED,
                autoRouteEnabled = false,
                targetAvailable = true,
                requestAlreadyOutstanding = false,
            )
        )
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.APP_RESUME,
                autoRouteEnabled = false,
                targetAvailable = true,
                requestAlreadyOutstanding = false,
            )
        )
    }

    @Test
    fun appResumeAutoRouteIsOneShot() {
        assertTrue(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.APP_RESUME,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = false,
            )
        )
        assertFalse(
            RoutingPolicy.shouldIssueRequest(
                trigger = RoutingPolicy.Trigger.APP_RESUME,
                autoRouteEnabled = true,
                targetAvailable = true,
                requestAlreadyOutstanding = true,
            )
        )
    }

    @Test
    fun disabledPreferenceStaysDisabledEvenWhenTargetIsPresent() {
        assertEquals(
            RoutingPolicy.LogicalState.DISABLED,
            RoutingPolicy.evaluate(
                RoutingPolicy.Snapshot(
                    requestEnabled = false,
                    targetAvailable = true,
                    targetIsCurrentCommunicationDevice = false,
                    currentDeviceLabel = "target",
                    audioMode = 0,
                )
            )
        )
    }

    @Test
    fun transientTargetProfileLossKeepsOutstandingRequestYielded() {
        assertEquals(
            RoutingPolicy.LogicalState.YIELDED_TO_OTHER_AUDIO_OWNER,
            RoutingPolicy.evaluate(
                RoutingPolicy.Snapshot(
                    requestEnabled = true,
                    targetAvailable = false,
                    targetIsCurrentCommunicationDevice = false,
                    currentDeviceLabel = "system-route",
                    audioMode = 0,
                )
            )
        )
    }

}
