package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Real-time snapshot of the audio signal path as it passes through the
 * hardware decoder and audio sink towards the DAC.
 *
 * Values are captured from the active playback engine's runtime telemetry
 * callbacks and represent what the hardware is *actually* processing —
 * not what the file claims. This enables audiophile-grade transparency.
 *
 * All format-specific information — including codec, sample rate, bit depth,
 * bitrate, and DSD transport details — is encapsulated inside [streamInfo].
 * Consuming code should `when`-match on the [OutputStreamInfo] subtype rather
 * than null-checking individual properties, which eliminates accidental use of
 * PCM fields during a DSD session and vice-versa.
 *
 * @property streamInfo Encapsulated format snapshot for the active stream.
 *   [OutputStreamInfo.Pcm] for standard PCM; [OutputStreamInfo.Dsd] for any
 *   DSD transport tier; [OutputStreamInfo.Unknown] when idle.
 * @property isOffloaded Whether audio offload (hardware decode path) is active.
 *   When `true`, the compressed bitstream is passed directly to the hardware
 *   DSP for decoding, bypassing the Android software audio pipeline entirely.
 * @property isDirectPlayback Whether the stream bypasses the Android mixer
 *   (direct mode).
 * @property isBitPerfect Whether the Android 14+ (API 34+) bit-perfect mixer
 *   behaviour is active. When `true`, the audio framework's internal resampler
 *   and software mixer are bypassed, delivering decoded PCM samples to the DAC
 *   exactly as the decoder produced them.
 * @property bitPerfectDiagnostics Detailed breakdown of the current bit-perfect
 *   evaluation, shown only inside the telemetry dialog's expandable advanced
 *   section.
 * @property isAudiophileEngineActive `true` when the app's FFmpeg/MediaTrack
 *   audiophile engine is the active playback owner, `false` when playback is
 *   running through the standard engine.
 * @property sueStatus Real-time status of the Sonic Upscaling Enhancer stage,
 *   or `null` when the audiophile engine is inactive or no track is loaded.
 */
data class AudioTelemetry(
    val streamInfo: OutputStreamInfo = OutputStreamInfo.Unknown,
    val isOffloaded: TelemetryStatus = TelemetryStatus.UNAVAILABLE,
    val isDirectPlayback: TelemetryStatus = TelemetryStatus.UNAVAILABLE,
    val isBitPerfect: TelemetryStatus = TelemetryStatus.UNAVAILABLE,
    val bitPerfectDiagnostics: BitPerfectDiagnostics? = null,
    val isAudiophileEngineActive: Boolean = false,
    val sueStatus: SueStatus? = null,
) {

    /**
     * `true` when soxr VHQ resampling is active in any pipeline stage:
     *
     * - **SUE lavfi**: the Sonic Upscaling Enhancer filter graph includes
     *   `aresample=resampler=soxr:precision=33` and is actively processing audio.
     * - **Hi-Res Dynamic Remaster lavfi**: the lossless remaster engine is active
     *   (oversampling to 88.2 / 96 kHz via soxr VHQ internally). Detected via
     *   [SueStatus.isHiResRemasterActive] since the remaster engine reuses the
     *   same native SUE stage plumbing.
     * - **Force-48k resampler**: the minimal libsoxr passthrough stage is active,
     *   converting the source to 48 kHz via
     *   `aresample=resampler=soxr:precision=33:cutoff=0.91:osr=48000:
     *   dither_method=triangular_hp`. Detected via [SueStatus.isForce48kResampleActive].
     * - **DSD tier-3 lavfi**: the FFmpeg DSD decimation filter chain includes
     *   `aresample=osr=88200:resampler=soxr:precision=33`, producing hi-res PCM.
     *
     * The UI uses this flag to decide whether to show "SoX VHQ" or
     * "Native HAL" in the resampler telemetry row.
     */
    val isSoxrActive: Boolean
        get() = sueStatus?.isActive == true
            || sueStatus?.isHiResRemasterActive == true
            || sueStatus?.isForce48kResampleActive == true
            || (streamInfo is OutputStreamInfo.Dsd && streamInfo.isResampled)

    companion object {
        /** Default idle state when no playback is active. */
        val IDLE = AudioTelemetry()
    }
}
