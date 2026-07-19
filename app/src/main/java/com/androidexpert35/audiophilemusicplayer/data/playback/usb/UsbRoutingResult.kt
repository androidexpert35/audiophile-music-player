package com.androidexpert35.audiophilemusicplayer.data.playback.usb

/**
 * Outcome of a synchronous [UsbBitPerfectRouter.routeAudio] call.
 *
 * Surfaces enough detail for the playback engine to decide whether to reuse
 * the standard mixer path or surface a user-facing diagnostic.
 */
sealed interface UsbRoutingResult {

    /** The DAC accepted the requested bit-perfect profile. */
    data class Applied(val sampleRate: Int, val encoding: Int, val channelMask: Int) : UsbRoutingResult

    /** Running on a platform older than Android 14 — feature unavailable. */
    data object UnsupportedPlatform : UsbRoutingResult

    /** Device has no [android.media.AudioManager] (rare; emulator / headless). */
    data object NoAudioManager : UsbRoutingResult

    /** No USB audio sink is currently connected. */
    data object NoUsbDevice : UsbRoutingResult

    /**
     * The supplied PCM parameters could not be mapped to platform constants.
     *
     * @property detail Human-readable description of the unsupported value.
     */
    data class UnsupportedFormat(val detail: String) : UsbRoutingResult

    /** Querying the DAC's supported mixer attributes failed. */
    data class QueryFailed(val message: String?) : UsbRoutingResult

    /** The DAC reported no supported mixer attributes whatsoever. */
    data class UnsupportedDevice(val deviceId: Int) : UsbRoutingResult

    /** No supported attribute matched the exact requested profile. */
    data object NoMatchingProfile : UsbRoutingResult

    /** The OS rejected the request despite a matching profile being found. */
    data class Rejected(val reason: String?) : UsbRoutingResult
}

