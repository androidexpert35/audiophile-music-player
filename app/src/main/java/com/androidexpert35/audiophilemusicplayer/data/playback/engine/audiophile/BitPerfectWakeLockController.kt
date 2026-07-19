package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Manages the `PARTIAL_WAKE_LOCK` used to keep the CPU alive during active
 * bit-perfect playback.
 *
 * Acquired on play, released on pause / stop / error. All calls are wrapped
 * in [runCatching] so a lock failure never crashes the audio pipeline.
 *
 * The underlying [PowerManager.WakeLock] is lazy-initialised the first time
 * [acquire] or [release] is called, deferring the [PowerManager] lookup until
 * it is actually needed.
 *
 * @property context Application context used to obtain [PowerManager].
 * @property tag WakeLock tag string, typically `"<ClassName>:Playback"`.
 */
internal class BitPerfectWakeLockController(
    context: Context,
    private val tag: String,
) {

    private val wakeLock: PowerManager.WakeLock by lazy(LazyThreadSafetyMode.NONE) {
        val powerManager = context.getSystemService(PowerManager::class.java)
            ?: error("PowerManager unavailable for playback wake lock ($tag)")
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
        }
    }

    /**
     * Acquires the wake lock if not already held.
     *
     * Failure is logged and swallowed so that a missing permission can never
     * crash the audio pipeline.
     */
    @SuppressLint("WakelockTimeout")
    fun acquire() {
        runCatching {
            if (!wakeLock.isHeld) wakeLock.acquire()
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to acquire wake lock ($tag): ${throwable.message}", throwable)
        }
    }

    /**
     * Releases the wake lock if currently held.
     *
     * Failure is logged and swallowed so a failed release can never interrupt
     * an ongoing pause or stop sequence.
     */
    fun release() {
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to release wake lock ($tag): ${throwable.message}", throwable)
        }
    }

    /**
     * Runs [block] and releases the wake lock if [block] throws or terminates
     * without completing normally.
     *
     * Used on the play path so a mid-play failure (e.g. sink construction error)
     * never strands a held wake lock and drains the battery.
     *
     * @param block Work to execute with automatic rollback on failure.
     */
    inline fun withRollback(block: () -> Unit) {
        var completed = false
        try {
            block()
            completed = true
        } finally {
            if (!completed) release()
        }
    }

    private companion object {
        const val TAG = "AudiophileEngine"
    }
}

