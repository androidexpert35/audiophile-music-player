package com.androidexpert35.audiophilemusicplayer.data.playback

/**
 * Output-rate resolution result for one playback session.
 *
 * [Bypass] means another transport or DSP path already owns the sample-rate
 * policy. [Apply] means the standard PCM resolver selected the target rate that
 * the platform AudioTrack should open with, optionally enabling a final SoXR
 * resampling stage when the source rate differs.
 */
sealed class Decision {

    /**
     * Resolver is intentionally bypassed and the caller should keep its existing
     * transport or DSP pipeline unchanged.
     */
    data object Bypass : Decision()

    /**
     * Resolver selected a concrete AudioTrack target rate for the standard PCM
     * path.
     *
     * @property targetSampleRate Sample rate in Hz to use for the platform sink.
     * @property resamplingActive `true` when the FFmpeg chain must append a final
     *   SoXR `aresample` stage so the sink opens at [targetSampleRate].
     * @property reason Human-readable diagnostic reason used for logging and
     *   developer telemetry surfaces.
     */
    data class Apply(
        val targetSampleRate: Int,
        val resamplingActive: Boolean,
        val reason: String,
    ) : Decision()
}

