package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

/**
 * Resolved Sonic Upscaling Enhancer (SUE) intensity profile for the active track.
 *
 * The profile is selected once per track load from the codec-tier × bitrate
 * matrix and remains immutable for that track's lifetime.
 */
enum class SueIntensityProfile {
    AGGRESSIVE,
    MODERATE,
    LIGHT,
    SUBTLE,
    BYPASS,
}

