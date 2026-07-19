package com.androidexpert35.audiophilemusicplayer.data.playback.engine

/**
 * Identifies which concrete [AudioPlayerEngine] strategy is active.
 *
 * The dual-engine architecture lets users trade absolute fidelity for
 * battery life at runtime without rebooting the playback service:
 * * [AUDIOPHILE] — the in-process FFmpeg + `AudioTrack` direct path. Default.
 * * [STANDARD]   — Jetpack Media3 ExoPlayer with the platform `MediaCodec` +
 *   `AudioFlinger` stack. No audio offload is enabled.
 */
enum class EngineType {
    /** Bit-perfect FFmpeg pipeline — maximum fidelity, higher CPU cost. */
    AUDIOPHILE,

    /** Standard ExoPlayer pipeline — lower CPU / better battery. */
    STANDARD,
}

