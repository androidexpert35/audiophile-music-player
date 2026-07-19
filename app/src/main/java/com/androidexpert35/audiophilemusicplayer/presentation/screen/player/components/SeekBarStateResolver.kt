package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import kotlin.math.abs

/**
 * Resolves the effective seek-bar position while a drag or freshly-issued seek
 * is in flight.
 *
 * The player UI can receive stale playback-state snapshots for a short window
 * after the user releases the thumb, especially when the audiophile engine
 * posts the seek onto its dedicated audio thread. This helper keeps the thumb
 * and elapsed label pinned to the requested seek target until the external
 * playback position converges, preventing the visible `0 -> target` jump.
 */
internal object SeekBarStateResolver {

    /** Sentinel meaning no pending seek target is currently being held in the UI. */
    const val NoPendingSeekPositionMs: Long = -1L

    private const val SEEK_SETTLE_TOLERANCE_MS = 750L

    /**
     * Computes the playback position that should currently be displayed.
     *
     * Drag gestures win first, then any pending seek target, then the external
     * playback position reported by the player layer.
     */
    fun resolveDisplayedPositionMs(
        externalPositionMs: Long,
        externalDurationMs: Long,
        dragFraction: Float,
        pendingSeekPositionMs: Long,
        pendingSeekDurationMs: Long,
    ): Long {
        val safeDurationMs = externalDurationMs.coerceAtLeast(0L)

        if (!dragFraction.isNaN() && safeDurationMs > 0L) {
            return (dragFraction * safeDurationMs)
                .toLong()
                .coerceIn(0L, safeDurationMs)
        }

        if (
            pendingSeekPositionMs != NoPendingSeekPositionMs &&
            safeDurationMs > 0L &&
            pendingSeekDurationMs == safeDurationMs
        ) {
            return pendingSeekPositionMs.coerceIn(0L, safeDurationMs)
        }

        return if (safeDurationMs > 0L) {
            externalPositionMs.coerceIn(0L, safeDurationMs)
        } else {
            externalPositionMs.coerceAtLeast(0L)
        }
    }

    /**
     * Returns whether a previously issued seek has settled enough that the UI
     * can stop pinning the slider to the optimistic target.
     */
    fun shouldClearPendingSeek(
        externalPositionMs: Long,
        externalDurationMs: Long,
        pendingSeekPositionMs: Long,
        pendingSeekDurationMs: Long,
    ): Boolean {
        if (pendingSeekPositionMs == NoPendingSeekPositionMs) return false

        val safeExternalDurationMs = externalDurationMs.coerceAtLeast(0L)
        if (safeExternalDurationMs <= 0L) return true
        if (pendingSeekDurationMs > 0L && safeExternalDurationMs != pendingSeekDurationMs) {
            return true
        }

        return abs(externalPositionMs.coerceAtLeast(0L) - pendingSeekPositionMs.coerceAtLeast(0L)) <=
            SEEK_SETTLE_TOLERANCE_MS
    }
}
