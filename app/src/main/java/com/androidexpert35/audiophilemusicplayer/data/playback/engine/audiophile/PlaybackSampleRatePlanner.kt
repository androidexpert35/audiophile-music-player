package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.Decision
import com.androidexpert35.audiophilemusicplayer.data.playback.PathType
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.PlaybackSampleRatePlanner.SUE_MASTER_MINIMUM_RATE_HZ


/**
 * Immutable resampling negotiation result for one PCM playback path.
 *
 * @property sueOutputRateHz Effective rate produced by SUE when active.
 * @property finalOutputRateHz Rate used to open the sink after all active DSP.
 * @property needsStandaloneResampleStage `true` when the engine should construct
 *   a minimal standalone SoXR stage for the standard PCM path instead of a full
 *   SUE / Hi-Res Remaster stage.
 * @property standaloneResampleTargetRateHz Final target rate for the minimal
 *   standalone SoXR stage when [needsStandaloneResampleStage] is `true`.
 * @property pathType High-level playback path classification for this track.
 * @property decision Active-output-rate resolution result associated with
 *   [pathType].
 * @property activeOutputThreadSampleRateHz Best-effort sample rate of the
 *   AudioFlinger output thread for the standard PCM path, or `0` when unknown.
 */
internal data class PlaybackSampleRatePlan(
    val sueOutputRateHz: Int,
    val finalOutputRateHz: Int,
    val needsStandaloneResampleStage: Boolean = false,
    val standaloneResampleTargetRateHz: Int? = null,
    val pathType: PathType = PathType.STANDARD_PCM,
    val decision: Decision = Decision.Bypass,
    val activeOutputThreadSampleRateHz: Int = 0,
)

/**
 * Resolves PCM sample-rate ownership for the enhancement stage
 * (lossy SUE, lossless Hi-Res remaster, or force-48k passthrough resampler).
 *
 * ### SUE as the Master 48 kHz Provider
 *
 * When the enhancement stage is active it is treated as the *master*
 * sample-rate provider: its output must always be at least
 * [SUE_MASTER_MINIMUM_RATE_HZ] (48 000 Hz) to avoid aliasing artefacts
 * in the Exciter's harmonic synthesis above the 19 500 Hz lowpass cutoff.
 *
 * ### Standalone Standard-PCM Resampler
 *
 * When the standalone target rate input is non-null, neither SUE nor Hi-Res
 * Remaster owns resampling for the current track. The planner therefore sets
 * [PlaybackSampleRatePlan.needsStandaloneResampleStage] so the engine builds a
 * minimal libsoxr stage that performs only the final sample-rate conversion for
 * the standard PCM path.
 *
 * The soxr VHQ resampler is always used internally by the native pipeline;
 * there is no user-facing toggle or Kotlin-side resampling stage.
 */
internal object PlaybackSampleRatePlanner {

    /**
     * Guaranteed minimum output rate for an active SUE stage.
     *
     * The Exciter's harmonic synthesis requires at least 48 000 Hz of carrier
     * bandwidth to avoid aliasing in the region above the 19 500 Hz apodising
     * lowpass cutoff.
     */
    internal const val SUE_MASTER_MINIMUM_RATE_HZ = 48_000

    /**
     * Computes the effective PCM sample-rate flow for a track.
     *
     * When [sueWillBeActive] is `true` the enhancement stage becomes the
     * resampling owner for the current path. The effective target is raised
     * to at least [SUE_MASTER_MINIMUM_RATE_HZ].
     *
     * When [standaloneResampleTargetRateHz] is non-null and [sueWillBeActive] is
     * `false`, a minimal libsoxr passthrough resampler is planned instead and
     * [PlaybackSampleRatePlan.needsStandaloneResampleStage] will be `true`.
     *
     * @param sourceSampleRateHz Decoder output rate before any DSP stages.
     * @param sueWillBeActive Whether SUE or Hi-Res Remaster is expected to process the track.
     * @param standaloneResampleTargetRateHz Optional target rate for the minimal
     *   standalone SoXR stage on the standard PCM path.
     * @param pathType High-level playback path classification for the current track.
     * @param decision Rate-resolution decision associated with [pathType].
     * @param activeOutputThreadSampleRateHz Best-effort active AudioFlinger output-thread rate.
     * @return A [PlaybackSampleRatePlan] describing the resolved pipeline.
     */
    internal fun planPcmRates(
        sourceSampleRateHz: Int,
        sueWillBeActive: Boolean,
        standaloneResampleTargetRateHz: Int? = null,
        pathType: PathType = PathType.STANDARD_PCM,
        decision: Decision = Decision.Bypass,
        activeOutputThreadSampleRateHz: Int = 0,
    ): PlaybackSampleRatePlan {
        val sanitizedSourceRateHz = sourceSampleRateHz.coerceAtLeast(1)
        val standaloneTargetRateHz = standaloneResampleTargetRateHz
            ?.takeIf { !sueWillBeActive && it > 0 && it != sanitizedSourceRateHz }

        val sueEffectiveTargetHz = if (sueWillBeActive) {
            maxOf(sanitizedSourceRateHz, SUE_MASTER_MINIMUM_RATE_HZ)
        } else {
            sanitizedSourceRateHz
        }

        val sueOutputRateHz = when {
            sueWillBeActive && sanitizedSourceRateHz < sueEffectiveTargetHz -> sueEffectiveTargetHz
            standaloneTargetRateHz != null -> standaloneTargetRateHz
            else -> sanitizedSourceRateHz
        }

        return PlaybackSampleRatePlan(
            sueOutputRateHz = sueOutputRateHz,
            finalOutputRateHz = sueOutputRateHz,
            needsStandaloneResampleStage = standaloneTargetRateHz != null,
            standaloneResampleTargetRateHz = standaloneTargetRateHz,
            pathType = pathType,
            decision = decision,
            activeOutputThreadSampleRateHz = activeOutputThreadSampleRateHz,
        )
    }
}
