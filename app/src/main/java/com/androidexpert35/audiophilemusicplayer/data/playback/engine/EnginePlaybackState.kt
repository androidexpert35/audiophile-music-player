package com.androidexpert35.audiophilemusicplayer.data.playback.engine

/**
 * High-level playback lifecycle of the bit-perfect engine.
 *
 * Distinct from the domain [com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackStatus]
 * because this captures the engine's internal progression — including the
 * load-before-play [LOADING] phase that the user-facing state collapses into
 * BUFFERING.
 */
enum class EnginePlaybackState {
    /** No track loaded, no sink open. Initial state and the target of [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine.stop]. */
    IDLE,

    /** Decoder is being opened and the sink is being negotiated. */
    LOADING,

    /** Track is loaded; `playWhenReady == false`. */
    READY,

    /** Track is loaded and actively writing samples to the HAL. */
    PLAYING,

    /** Track is loaded; write loop is suspended. */
    PAUSED,

    /** Natural end-of-stream reached; the queue advance (if any) is the caller's decision. */
    ENDED,

    /** Unrecoverable failure — details available via the engine log tag. */
    ERROR,
    ;

    /**
     * Returns `true` when the engine is not actively producing audio output.
     *
     * Used by telemetry and UI consumers to suppress stale format displays
     * during loading, idle, and natural end-of-stream transitions.
     */
    val isInactive: Boolean
        get() = this == IDLE || this == LOADING || this == ENDED
}

