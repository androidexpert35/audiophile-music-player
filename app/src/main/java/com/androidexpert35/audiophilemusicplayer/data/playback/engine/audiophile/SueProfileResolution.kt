package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

/**
 * Immutable SUE activation decision resolved for a single track.
 *
 * @property isLossySource Whether the source is confirmed lossy-compressed.
 * @property codecTier Efficiency tier selected for the codec.
 * @property intensityProfile Final DSP intensity profile from the tier × bitrate matrix.
 * @property specialFlags Codec-specific processing overrides expressed as a bitmask.
 * @property codecDisplayName Human-readable codec label for diagnostics and UI.
 */
internal data class SueProfileResolution(
    val isLossySource: Boolean,
    val codecTier: SueCodecTier,
    val intensityProfile: SueIntensityProfile,
    val specialFlags: Int,
    val codecDisplayName: String,
)

