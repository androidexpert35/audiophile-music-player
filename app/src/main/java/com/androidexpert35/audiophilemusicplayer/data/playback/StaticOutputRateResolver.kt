package com.androidexpert35.audiophilemusicplayer.data.playback

import com.androidexpert35.audiophilemusicplayer.data.playback.StaticOutputRateResolver.Companion.REASON_BASE_44_1_TO_48
import com.androidexpert35.audiophilemusicplayer.data.playback.StaticOutputRateResolver.Companion.REASON_HIRES_PASSTHROUGH
import com.androidexpert35.audiophilemusicplayer.data.playback.StaticOutputRateResolver.Companion.REASON_NATIVE_48
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministically resolves standard-PCM AudioTrack rates without probing.
 *
 * The resolver is a **pure function over the decoded source sample rate only** —
 * the output device is intentionally not consulted. The rules are:
 *
 * | Source rate        | Target rate | SoXR active | Reason                    |
 * |--------------------|-------------|-------------|---------------------------|
 * | 44 100 Hz (CD)     | 48 000 Hz   | yes         | [REASON_BASE_44_1_TO_48]  |
 * | 48 000 Hz          | 48 000 Hz   | no          | [REASON_NATIVE_48]        |
 * | > 48 000 Hz hi-res | source rate | no          | [REASON_HIRES_PASSTHROUGH]|
 *
 * **Rationale:**
 * - 44.1 kHz is the only "problem" sample rate: the 160:147 ratio is handled
 *   poorly by AudioFlinger's internal resampler, so we upfront-convert with
 *   libsoxr VHQ on every output (including Bluetooth).
 * - 48 kHz sources are already at the native AudioFlinger mixer rate for speaker
 *   and wired outputs; no resampling waste occurs.
 * - Hi-res sources (88.2, 96, 176.4, 192 kHz) are passed through at their
 *   source rate. On Bluetooth the codec stack negotiates a suitable rate; on
 *   internal speaker/wired paths AudioFlinger's in-built high-quality
 *   downsampler handles the conversion — inserting SoXR here adds latency and
 *   pipeline complexity with no perceptible gain.
 *
 * Must only be called after [PathClassifier] has classified the active session
 * as [PathType.STANDARD_PCM]; USB, SUE, Hi-Res Dynamic Remaster, and DSD paths
 * bypass this policy upstream.
 */
@Singleton
class StaticOutputRateResolver @Inject constructor() {

    /**
     * Resolves the static standard-PCM rate plan for the given source.
     *
     * The decision is deterministic and device-agnostic: the same source rate
     * always produces the same [Decision.Apply] regardless of the connected
     * output device.
     *
     * @param sourceSampleRate Sample rate of the decoded PCM source in Hz.
     * @return [Decision.Apply] describing the AudioTrack sink rate and whether
     *   the standalone libsoxr resampler stage must be engaged.
     */
    fun resolve(sourceSampleRate: Int): Decision.Apply {
        val rate = sourceSampleRate.coerceAtLeast(1)
        return when {
            // 44.1 kHz CD rate — always convert with SoXR VHQ to avoid the
            // AudioFlinger 160:147 ratio resampler on every output path.
            rate == RATE_44100 -> Decision.Apply(
                targetSampleRate = RATE_48000,
                resamplingActive = true,
                reason = REASON_BASE_44_1_TO_48,
            )

            // Already at the internal mixer rate — identity pass, no SoXR stage.
            rate == RATE_48000 -> Decision.Apply(
                targetSampleRate = RATE_48000,
                resamplingActive = false,
                reason = REASON_NATIVE_48,
            )

            // Hi-res source (88.2, 96, 176.4, 192 kHz…). Pass through at the
            // source rate and let the platform or BT codec handle any necessary
            // conversion. No SoXR stage inserted — overhead without audible gain.
            else -> Decision.Apply(
                targetSampleRate = rate,
                resamplingActive = false,
                reason = REASON_HIRES_PASSTHROUGH,
            )
        }
    }

    companion object {
        private const val RATE_44100: Int = 44_100
        private const val RATE_48000: Int = 48_000

        /** Diagnostic reason: SoXR upsampling 44.1 kHz → 48 kHz on any output. */
        const val REASON_BASE_44_1_TO_48: String = "BASE_44_1_TO_48"

        /** Diagnostic reason: source is already at 48 kHz, no SoXR needed. */
        const val REASON_NATIVE_48: String = "NATIVE_48"

        /** Diagnostic reason: hi-res source passed through at source rate. */
        const val REASON_HIRES_PASSTHROUGH: String = "HIRES_PASSTHROUGH"
    }
}

