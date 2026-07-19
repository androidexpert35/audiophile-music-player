package com.androidexpert35.audiophilemusicplayer.data.playback

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ── Shared log tags ──────────────────────────────────────────────────────────

/**
 * Logcat tag used across the audiophile pipeline for signal-path diagnostics.
 *
 * Centralised here so every playback component emits to the same tag and a
 * single logcat filter (`AudiophilePath`) reveals the full negotiated path —
 * from codec selection through sink routing to bit-perfect mixer state.
 */
internal const val AUDIPHILE_PATH_TAG = "AudiophilePath"

// ── Shared USB constants ─────────────────────────────────────────────────────

/**
 * Sentinel device-type value written by the custom libusb USB sink into a
 * [com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport]
 * to signal that the path bypasses AudioFlinger entirely.
 *
 * Equals `UsbConstants.USB_CLASS_AUDIO` (= 1). Defined inline here to avoid a
 * dependency on `android.hardware.usb.UsbConstants` for a single constant comparison.
 * **Not** an `AudioDeviceInfo.TYPE_*` constant — callers must not use it for
 * live device-type routing decisions.
 *
 * Referenced by both [AudioPathValidator] (custom-sink detection) and
 * [AudioTelemetryCollector] (direct-USB-bypass diagnostic flag) so the value
 * has a single definition and cannot drift between the two consumers.
 */
internal const val USB_CLASS_AUDIO_SENTINEL = 1

// ── Shared timing constants ──────────────────────────────────────────────────

/**
 * Shared position-ticker interval used by both playback engines.
 *
 * 200 ms keeps the seek bar and telemetry overlay smooth (~5 updates/sec)
 * without flooding the main thread with state invalidations.
 */
internal const val POSITION_TICK_INTERVAL_MS = 200L

// ── Handler utilities ────────────────────────────────────────────────────────

/**
 * Runs [block] immediately when already on this handler's looper, otherwise posts it.
 */
internal inline fun Handler.runOrPost(crossinline block: () -> Unit) {
    if (Looper.myLooper() == looper) {
        block()
    } else {
        post { block() }
    }
}

/**
 * Cancels the current periodic job and starts a replacement in the same [CoroutineScope].
 */
internal inline fun CoroutineScope.restartTicker(
    currentJob: Job?,
    intervalMs: Long,
    crossinline onTick: () -> Unit,
): Job {
    currentJob?.cancel()
    return launch {
        while (isActive) {
            delay(intervalMs)
            onTick()
        }
    }
}

/**
 * Cancels the active job and returns `null` so callers can clear nullable job references in one place.
 */
internal fun Job?.cancelAndClear(): Job? {
    this?.cancel()
    return null
}

