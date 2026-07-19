package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Canonical DSD sample-rate families supported by the audiophile playback path.
 *
 * Each rate is expressed as the standard 44.1 kHz base-clock multiplier used by
 * DSF, DSDIFF, WavPack DSD, native USB DSD transports, and DoP.
 *
 * @property multiplier 44.1 kHz base-rate multiplier (64, 128, or 256).
 * @property sampleRateHz One-bit DSD sample rate in Hertz.
 * @property displayName Human-readable label for UI and diagnostics.
 */
sealed interface DsdRate {
    val multiplier: Int
    val sampleRateHz: Int
    val displayName: String

    /** Standard DSD64 (2.8224 MHz) rate. */
    data object DSD64 : DsdRate {
        override val multiplier: Int = 64
        override val sampleRateHz: Int = 2_822_400
        override val displayName: String = "DSD64"
    }

    /** Standard DSD128 (5.6448 MHz) rate. */
    data object DSD128 : DsdRate {
        override val multiplier: Int = 128
        override val sampleRateHz: Int = 5_644_800
        override val displayName: String = "DSD128"
    }

    /** Standard DSD256 (11.2896 MHz) rate. */
    data object DSD256 : DsdRate {
        override val multiplier: Int = 256
        override val sampleRateHz: Int = 11_289_600
        override val displayName: String = "DSD256"
    }

    companion object {
        /**
         * Resolves a [DsdRate] from its 44.1 kHz base-clock [multiplier].
         *
         * @param multiplier DSD multiplier advertised by the decoder or sink.
         * @return Matching [DsdRate], or `null` when the rate is outside the app's supported range.
         */
        fun fromMultiplier(multiplier: Int): DsdRate? = when (multiplier) {
            DSD64.multiplier -> DSD64
            DSD128.multiplier -> DSD128
            DSD256.multiplier -> DSD256
            else -> null
        }

        /**
         * Resolves a [DsdRate] from a one-bit DSD [sampleRateHz].
         *
         * @param sampleRateHz Raw DSD sample rate in Hertz.
         * @return Matching [DsdRate], or `null` when the rate is unsupported.
         */
        fun fromSampleRate(sampleRateHz: Int): DsdRate? = when (sampleRateHz) {
            DSD64.sampleRateHz -> DSD64
            DSD128.sampleRateHz -> DSD128
            DSD256.sampleRateHz -> DSD256
            else -> null
        }
    }
}

