// ✅ EXTRACTED FROM: BitPerfectPlaybackEngine.kt
package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.AUDIPHILE_PATH_TAG
import com.androidexpert35.audiophilemusicplayer.data.playback.Decision
import com.androidexpert35.audiophilemusicplayer.data.playback.PathClassifier
import com.androidexpert35.audiophilemusicplayer.data.playback.PathType
import com.androidexpert35.audiophilemusicplayer.data.playback.StaticOutputRateResolver
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves PCM sample-rate ownership for the enhancement stage on each
 * track load, and determines whether a given track will require a float32
 * pipeline carrier.
 *
 * Centralising these decisions avoids scattering path-classification logic
 * across [BitPerfectPlaybackEngine] and the session-loading helpers.
 *
 * ### Rate policy (STANDARD_PCM path only)
 *
 * The resolver is **device-agnostic**: the same source rate always produces the
 * same decision regardless of whether the route is speaker, wired, or Bluetooth.
 *
 * | Source rate | SoXR active | Reason                                        |
 * |-------------|-------------|-----------------------------------------------|
 * | 44 100 Hz   | yes → 48 k  | CD base rate; AF 160:147 resampler is avoided |
 * | 48 000 Hz   | no          | Already at native mixer rate                  |
 * | > 48 000 Hz | no          | Hi-res source passed through; AF or BT handles|
 *
 * @property pathClassifier           Classifies the effective playback path for a format.
 * @property staticOutputRateResolver Resolves the AudioTrack sink rate and SoXR flag
 *   for the standard PCM path based solely on source sample rate.
 */
@Singleton
internal class BitPerfectPcmRatePolicy @Inject constructor(
    private val pathClassifier: PathClassifier,
    private val staticOutputRateResolver: StaticOutputRateResolver,
) {

    private companion object {
        const val PATH_TAG = AUDIPHILE_PATH_TAG
    }

    /**
     * Resolves the PCM sample-rate ownership plan for one track load.
     *
     * When the standard PCM path would otherwise be resampled by AudioFlinger
     * and no other DSP path owns resampling, the plan sets
     * [PlaybackSampleRatePlan.needsStandaloneResampleStage] = `true` so the
     * engine builds a minimal libsoxr resampler stage instead of a full
     * SUE / Hi-Res Remaster graph.
     *
     * @param format          Decoded format of the source file.
     * @param sueEnabled      Whether the SUE toggle is currently on.
     * @param hiResEnabled    Whether the Hi-Res Dynamic Remaster toggle is on.
     * @param isUsbDacPresent `true` when a USB DAC is physically connected.
     * @return A [PlaybackSampleRatePlan] describing the resolved rate pipeline.
     */
    fun resolvePcmSampleRatePlan(
        format: AudioFormatInfo,
        sueEnabled: Boolean,
        hiResEnabled: Boolean,
        @Suppress("UNUSED_PARAMETER") isUsbDacPresent: Boolean = false,
    ): PlaybackSampleRatePlan {
        val sueWillBeActive = shouldUseSueStage(format, sueEnabled, hiResEnabled)
        val pathType = pathClassifier.classify(
            format = format,
            sueEnabled = sueEnabled,
            hiResEnabled = hiResEnabled,
        )
        val decision: Decision = when (pathType) {
            PathType.STANDARD_PCM -> {
                // The resolver inspects only the source rate — output device type is
                // not consulted. 44.1 kHz always gets SoXR (including BT), hi-res
                // always passes through, 48 kHz is identity.
                staticOutputRateResolver.resolve(
                    sourceSampleRate = format.sampleRateHz,
                ).also { resolved ->
                    Log.i(
                        PATH_TAG,
                        "PathClassifier: pathType=$pathType source=${format.sampleRateHz}Hz " +
                            "-> static resolver applied $resolved",
                    )
                }
            }
            else -> {
                Log.i(PATH_TAG, "PathClassifier: pathType=$pathType -> resolver bypassed")
                Decision.Bypass
            }
        }
        val standaloneResampleTargetRateHz = (decision as? Decision.Apply)
            ?.takeIf { pathType == PathType.STANDARD_PCM && !sueWillBeActive && it.resamplingActive }
            ?.targetSampleRate
        val activeOutputThreadSampleRateHz = (decision as? Decision.Apply)
            ?.takeIf { pathType == PathType.STANDARD_PCM }
            ?.targetSampleRate
            ?: 0
        return PlaybackSampleRatePlanner.planPcmRates(
            sourceSampleRateHz = format.sampleRateHz,
            sueWillBeActive = sueWillBeActive,
            standaloneResampleTargetRateHz = standaloneResampleTargetRateHz,
            pathType = pathType,
            decision = decision,
            activeOutputThreadSampleRateHz = activeOutputThreadSampleRateHz,
        )
    }

    /**
     * Returns `true` when the next track will use either a [SueStage] enhancement
     * path or a SoXR resampler stage (currently: 44.1 kHz standard PCM sources only).
     *
     * Both paths produce float32 PCM output, so this predicate determines
     * whether two adjacent tracks share the same downstream carrier format —
     * the key prerequisite for true gapless playback without rebuilding the sink.
     *
     * @param next         Format of the upcoming track.
     * @param sueEnabled   Whether the SUE toggle is currently on.
     * @param hiResEnabled Whether the Hi-Res Dynamic Remaster toggle is on.
     * @return `true` when the next track will produce float32 PCM output.
     */
    fun willTrackUseSueOrForce48kStage(
        next: AudioFormatInfo,
        sueEnabled: Boolean,
        hiResEnabled: Boolean,
    ): Boolean {
        val sueWillBeActive = shouldUseSueStage(next, sueEnabled, hiResEnabled)
        if (sueWillBeActive) return true

        val pathType = pathClassifier.classify(
            format = next,
            sueEnabled = sueEnabled,
            hiResEnabled = hiResEnabled,
        )
        if (pathType != PathType.STANDARD_PCM) return false

        // SoXR stage is needed only when source == 44.1 kHz (device-agnostic).
        return staticOutputRateResolver.resolve(
            sourceSampleRate = next.sampleRateHz,
        ).resamplingActive
    }
}
