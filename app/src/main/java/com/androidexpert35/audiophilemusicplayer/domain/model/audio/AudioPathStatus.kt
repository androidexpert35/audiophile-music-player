package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Describes the routing tier the Android audio framework assigned to the
 * current playback stream.
 *
 * Values are ordered from most-fidelity-preserving to least. The UI should
 * present higher-ranked tiers as progressively "purer".
 *
 * Derivation priority (highest wins):
 * 1. [DIRECT_BIT_PERFECT] — hardware USB bypass or API 34+ confirmed bit-perfect
 * 2. [OEM_WARNING]        — FLAG_DIRECT granted but OEM DSP may still intercept
 * 3. [DIRECT_SUPPORTED]   — FLAG_DIRECT granted, HAL claims no resampling
 * 4. [RESAMPLED]          — standard AudioFlinger mixer path
 * 5. [UNKNOWN]            — no path report available yet
 */
enum class AudioPathStatus {

    /**
     * Highest-fidelity tier — the audio stream reaches the DAC without any
     * Android software processing. Achieved by either:
     *
     * - The custom USB host path that bypasses AudioFlinger entirely by writing
     *   raw PCM directly to the USB isochronous endpoint, or
     * - Android 14+ (API 34) `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT`
     *   confirmed by the bit-perfect router.
     */
    DIRECT_BIT_PERFECT,

    /**
     * `AudioTrack.FLAG_DIRECT` was granted by the HAL and the path report
     * confirms no software resampling occurred. A bit-perfect guarantee requires
     * Android 14+ confirmation ([DIRECT_BIT_PERFECT]), but this tier indicates
     * the OS is at least honouring the native sample rate and bit depth.
     *
     * Typical on Android 13 (API 33) with a well-behaved USB Audio Class 2 HAL.
     */
    DIRECT_SUPPORTED,

    /**
     * `AudioTrack.FLAG_DIRECT` was granted by the HAL but the device is running
     * a vendor skin whose proprietary DSP daemon is known to intercept the direct
     * output path. The OS reports direct playback, but a genuinely bit-perfect
     * signal chain cannot be guaranteed on these OEM firmware builds.
     */
    OEM_WARNING,

    /**
     * The stream is passing through Android's standard AudioFlinger software
     * mixer. Sample-rate conversion and/or bit-depth normalisation are active.
     */
    RESAMPLED,

    /**
     * No path report has been received yet. The engine has not loaded a track,
     * or the validator has not received its first state update.
     */
    UNKNOWN,
}

