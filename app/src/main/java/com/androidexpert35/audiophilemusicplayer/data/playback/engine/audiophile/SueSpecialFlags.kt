package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

/**
 * Bitmask flags describing codec-specific SUE processing overrides.
 *
 * The flags are resolved once per track load so both the Kotlin runtime and the
 * native filter-graph builder can apply the same codec-aware behaviour.
 */
object SueSpecialFlags {
    /** Skip the high-frequency EQ layer because the codec already reconstructs it. */
    const val SKIP_LAYER2_EQ: Int = 1 shl 0

    /** Blend in a small amount of odd harmonics to complement AAC-HE SBR output. */
    const val AAC_HE_ODD_HARMONICS_BLEND: Int = 1 shl 1

    /** Reserve the stereo image for codecs whose decoder already rebuilds it. */
    const val DISABLE_MID_SIDE_WIDENING: Int = 1 shl 2
}

