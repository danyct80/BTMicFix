package com.btmicfix.audio

/**
 * Pure routing policy used by BTMicFix 0.7.x.
 *
 * The core rule is intentionally simple: BTMicFix may place ONE communication-device request,
 * but it never fights another app for ownership of the audio mode. Phone calls, VoIP and voice
 * assistants are therefore allowed to take temporary priority. When Android gives the route back,
 * BTMicFix observes that transition instead of issuing another request.
 */
object RoutingPolicy {

    enum class Trigger {
        USER_ENABLE,
        AUTO_DEVICE_APPEARED,
        APP_RESUME,
        COMMUNICATION_DEVICE_CHANGED,
        AUDIO_MODE_CHANGED,
        TIMER_TICK,
    }

    enum class LogicalState {
        DISABLED,
        WAITING_FOR_DEVICE,
        ACTIVE,
        YIELDED_TO_OTHER_AUDIO_OWNER,
    }

    data class Snapshot(
        val requestEnabled: Boolean,
        val targetAvailable: Boolean,
        val targetIsCurrentCommunicationDevice: Boolean,
        val currentDeviceLabel: String?,
        val audioMode: Int,
    )

    /**
     * A fresh setCommunicationDevice() request is allowed only on deliberate lifecycle events.
     * It is NEVER allowed as a reaction to another app changing communication device/audio mode.
     */
    fun shouldIssueRequest(
        trigger: Trigger,
        autoRouteEnabled: Boolean,
        targetAvailable: Boolean,
        requestAlreadyOutstanding: Boolean,
    ): Boolean {
        if (!targetAvailable) return false
        return when (trigger) {
            Trigger.USER_ENABLE -> !requestAlreadyOutstanding
            Trigger.AUTO_DEVICE_APPEARED,
            Trigger.APP_RESUME -> autoRouteEnabled && !requestAlreadyOutstanding
            Trigger.COMMUNICATION_DEVICE_CHANGED,
            Trigger.AUDIO_MODE_CHANGED,
            Trigger.TIMER_TICK -> false
        }
    }

    /**
     * Derive UI/logic state from observed Android state without causing side effects.
     */
    fun evaluate(snapshot: Snapshot): LogicalState = when {
        !snapshot.requestEnabled && !snapshot.targetAvailable -> LogicalState.WAITING_FOR_DEVICE
        !snapshot.requestEnabled -> LogicalState.DISABLED
        snapshot.targetIsCurrentCommunicationDevice -> LogicalState.ACTIVE
        else -> LogicalState.YIELDED_TO_OTHER_AUDIO_OWNER
    }
}
