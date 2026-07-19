package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.os.Handler

/**
 * Schedules and cancels the idle-sink-release countdown for
 * [BitPerfectPlaybackEngine].
 *
 * When the engine remains in [com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState.PAUSED]
 * for [delayMs] milliseconds the [onRelease] callback fires on the audio
 * thread, allowing the OS to reclaim audio hardware and USB-DAC bandwidth.
 * The countdown is cancelled whenever the player resumes, stops, seeks, or
 * loads a new track.
 *
 * A pre-allocated [Runnable] wrapping [onRelease] is retained so
 * [Handler.removeCallbacks] can cancel it with zero allocation and no
 * object-identity ambiguity across repeated schedule/cancel cycles.
 *
 * @property handler Audio-thread [Handler] used to post the delayed release.
 * @property delayMs Idle duration in milliseconds before [onRelease] fires.
 * @param onRelease Callback invoked on the audio thread when the timeout elapses.
 */
internal class BitPerfectIdleSinkReleaseScheduler(
    private val handler: Handler,
    private val delayMs: Long,
    onRelease: () -> Unit,
) {

    /**
     * Pre-allocated [Runnable] wrapping [onRelease].
     *
     * Using a stable reference lets [Handler.removeCallbacks] cancel the
     * exact pending callback without any heap allocation.
     */
    private val releaseRunnable: Runnable = Runnable(onRelease)

    /**
     * Arms the idle countdown, replacing any existing pending schedule.
     *
     * Cancels any stale pending callback first so repeated [schedule] calls
     * (e.g. rapid pause → pause) never stack multiple pending runnables.
     */
    fun schedule() {
        handler.removeCallbacks(releaseRunnable)
        handler.postDelayed(releaseRunnable, delayMs)
    }

    /**
     * Disarms the pending idle countdown.
     *
     * Safe to call when no countdown is pending — the [Handler.removeCallbacks]
     * no-ops gracefully in that case.
     */
    fun cancel() {
        handler.removeCallbacks(releaseRunnable)
    }
}

