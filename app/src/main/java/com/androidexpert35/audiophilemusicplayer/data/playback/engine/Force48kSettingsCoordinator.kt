package com.androidexpert35.audiophilemusicplayer.data.playback.engine

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retained as a lifecycle hook for forwards-compatibility but intentionally
 * inactive under the current source-rate-only resolver policy.
 *
 * ### Why this coordinator is now a no-op
 *
 * The previous design re-probed or re-resolved the AudioTrack sample rate
 * whenever the output route changed (speaker → Bluetooth → wired, etc.) because
 * the old policy applied a *device-specific* decision (e.g.
 * `BT_NATIVE_PASSTHROUGH` vs `INTERNAL_FORCE_48`).
 *
 * The current [com.androidexpert35.audiophilemusicplayer.data.playback.StaticOutputRateResolver]
 * is **device-agnostic**: it looks only at the source sample rate:
 * - 44.1 kHz → SoXR to 48 kHz on **every** output, including Bluetooth.
 * - 48 kHz → identity passthrough on every output.
 * - Hi-res (> 48 kHz) → passthrough at source rate on every output.
 *
 * Because the rate decision is now invariant over device-route changes, there is
 * no need to rebuild the pipeline on plug/unplug events for the standard PCM
 * path. USB DAC plug/unplug is handled separately by
 * [com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioLifecycleManager].
 *
 * The [start] method is kept so callers in `Application.onCreate` remain
 * unchanged, and the injection site stays consistent for future policy changes.
 */
@Singleton
class Force48kSettingsCoordinator @Inject constructor() {

    /**
     * No-op under the current device-agnostic rate policy.
     *
     * Previously triggered route-change observation. Now retained only for API
     * stability and forwards-compatibility. Safe to call multiple times.
     */
    fun start() {
        Log.d(TAG, "start() — no-op under source-rate-only policy; route changes don't affect rate decisions")
    }

    private companion object {
        const val TAG = "Force48kCoordinator"
    }
}
