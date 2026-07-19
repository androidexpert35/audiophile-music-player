package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Resolved sample-rate output policy produced once per track load by
 * the output-rate resolver.
 *
 * When [resamplingActive] is `true`, the FFmpeg lavfi pipeline must append
 * `aresample=resampler=soxr:precision=33:cutoff=0.91:osr=[targetSampleRate]:dither_method=triangular_hp`
 * as its final stage so that AudioFlinger receives audio at [targetSampleRate]
 * rather than letting the kernel's lower-quality internal resampler convert it.
 *
 * When [resamplingActive] is `false`, the caller must **not** insert an identity
 * `aresample` stage — doing so wastes CPU and marginally degrades quality.
 *
 * @property targetSampleRate Sample rate (Hz) the downstream sink should be opened at.
 *   Always `48 000` when no USB DAC is connected; equals the source rate otherwise.
 * @property resamplingActive `true` when [targetSampleRate] differs from the
 *   source rate and a SoXR resampler stage must be inserted in the pipeline.
 */
data class OutputRatePolicy(
    val targetSampleRate: Int,
    val resamplingActive: Boolean,
) {
    companion object {
        /**
         * Fixed 48 kHz output rate applied on all non-USB paths.
         *
         * Android's AudioFlinger primary mixer is fixed at 48 kHz on internal
         * speaker, wired headphone, and A2DP / LHDC Bluetooth routes. Any source
         * that is not already at 48 kHz will be resampled by the kernel's mediocre
         * internal resampler unless we pre-resample here via libsoxr VHQ.
         */
        const val FIXED_NON_USB_RATE_HZ: Int = 48_000
    }
}

