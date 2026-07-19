package com.androidexpert35.audiophilemusicplayer.data.playback.usb

/**
 * Lifecycle events describing bit-perfect mixer routing on the USB DAC.
 *
 * Emitted by [UsbBitPerfectRouter] both for direct calls into the router and
 * for system-driven changes received through
 * [android.media.AudioManager.OnPreferredMixerAttributesChangedListener].
 */
sealed interface UsbRoutingEvent {

    /**
     * The router successfully applied a bit-perfect preference.
     *
     * @property deviceId    Stable [android.media.AudioDeviceInfo.getId] of the DAC.
     * @property sampleRate  Negotiated sample rate in Hz.
     * @property encoding    Negotiated [android.media.AudioFormat] encoding constant.
     * @property channelMask Negotiated `CHANNEL_OUT_*` mask.
     */
    data class Applied(
        val deviceId: Int,
        val sampleRate: Int,
        val encoding: Int,
        val channelMask: Int,
    ) : UsbRoutingEvent

    /**
     * The system reported a change to the preferred mixer attributes for the
     * router's media stream.
     *
     * @property isBitPerfect `true` when the active behaviour is
     *   [android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT].
     */
    data class Changed(
        val deviceId: Int,
        val sampleRate: Int,
        val encoding: Int,
        val channelMask: Int,
        val isBitPerfect: Boolean,
    ) : UsbRoutingEvent

    /**
     * The OS rejected or undid a routing request.
     *
     * @property reason Best-effort description of why the request failed.
     */
    data class Rejected(val deviceId: Int, val reason: String?) : UsbRoutingEvent

    /** Any active preference for the device has been cleared. */
    data class Cleared(val deviceId: Int) : UsbRoutingEvent
}

