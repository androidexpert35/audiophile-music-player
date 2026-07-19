package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

/**
 * Visual lifecycle state for an app-owned audio processing stage.
 *
 * The telemetry dialog uses this to make a clear distinction between a feature
 * that is disabled, a feature that is enabled but currently idle, and a feature
 * that is actively modifying the live PCM stream.
 */
internal enum class ProcessingStageState {
    /** The feature is disabled or no pipeline telemetry is available. */
    OFF,

    /** The feature is enabled and ready, but this source is not being processed. */
    ARMED,

    /** The feature is enabled but intentionally bypassed for this source. */
    BYPASSED,

    /** The feature attempted to engage but its native stage is unavailable. */
    UNAVAILABLE,

    /** The feature is actively processing the current stream. */
    PROCESSING,
}

