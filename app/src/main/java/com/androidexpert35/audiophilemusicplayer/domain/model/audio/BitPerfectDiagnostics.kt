package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Diagnostic snapshot of the audio routing path used by the "Advanced Audio
 * Path" section of the telemetry dialog.
 *
 * All fields are sourced directly from [AudioPathStatus] and the engine's
 * [com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport],
 * replacing the older multi-condition boolean checklist with a single
 * authoritative routing tier.
 *
 * @property pathStatus   The routing tier determined by the audio path validator
 *   for the current stream. [AudioPathStatus.DIRECT_BIT_PERFECT] means the signal
 *   reaches the DAC with no software processing.
 * @property activeDeviceName Human-readable product name of the output device
 *   active at snapshot time, or `null` when routing is unresolved.
 * @property outputRouteKind Framework-free transport family used to present the
 *   physical output honestly without exposing Android device constants.
 * @property isDirectPlayback `true` when `AudioTrack.FLAG_DIRECT` was negotiated
 *   with the HAL — the stream bypasses the standard software mixer.
 * @property isDirectUsbBypass `true` when playback is flowing through the custom
 *   USB host sink that writes directly to the isochronous USB endpoint, bypassing
 *   AudioFlinger entirely.
 * @property isMixerBitPerfect `true` when Android 14+ (API 34)
 *   `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` has been confirmed by the
 *   bit-perfect router for the current track/format.
 * @property noFloatFallback `true` when the sink did NOT fall back to
 *   `AudioFormat.ENCODING_PCM_FLOAT`. A float fallback implies the HAL could not
 *   accept the source bit depth and up-converted internally.
 * @property isSoftwareVolumeAtUnity `true` when direct-USB PCM has no app-owned
 *   attenuation. DSD transports ignore the PCM software-volume control.
 */
data class BitPerfectDiagnostics(
    val pathStatus: AudioPathStatus = AudioPathStatus.UNKNOWN,
    val activeDeviceName: String? = null,
    val outputRouteKind: OutputRouteKind = OutputRouteKind.UNKNOWN,
    val isDirectPlayback: Boolean = false,
    val isDirectUsbBypass: Boolean = false,
    val isMixerBitPerfect: Boolean = false,
    val noFloatFallback: Boolean = false,
    val isSoftwareVolumeAtUnity: Boolean = true,
)
