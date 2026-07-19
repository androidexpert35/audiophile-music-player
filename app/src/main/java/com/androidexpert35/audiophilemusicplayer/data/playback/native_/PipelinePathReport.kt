package com.androidexpert35.audiophilemusicplayer.data.playback.native_

import com.androidexpert35.audiophilemusicplayer.data.playback.Decision
import com.androidexpert35.audiophilemusicplayer.data.playback.PathType

/**
 * Diagnostic description of the actual audio path obtained after
 * [AudioTrackSink] negotiation.
 *
 * FLAG_DIRECT is advisory — the HAL may strip it, substitute a different
 * encoding, or quietly re-sample to the mixer rate. This report captures what
 * the system **actually** gave us so the audiophile telemetry surface can
 * tell the user whether the current track is truly bit-perfect or has fallen
 * back through one of the mitigations.
 *
 * @property usedDirectFlag `true` when the sink was built with
 *   `AudioTrack.setFlags(FLAG_DIRECT)` and the Builder did not fail back to
 *   the standard mixer path.
 * @property usedFloatFallback `true` when the decoder's native integer PCM
 *   had to be routed through the second fallback rung (`PCM_FLOAT`).
 * @property encoding The `AudioFormat.ENCODING_PCM_*` constant actually
 *   handed to `AudioTrack.Builder`.
 * @property sampleRateHz Sample rate handed to the AudioTrack after any app-owned
 *   DSP or static standard-PCM SoXR stage has produced the sink format.
 * @property channelMask `AudioFormat.CHANNEL_OUT_*` mask used.
 * @property bufferFrames Size of the AudioTrack's internal buffer in **frames**
 *   (not bytes). Derived from `AudioTrack.getMinBufferSize * multiplier`.
 * @property nativeOutputSampleRateHz The system mixer's preferred output rate
 *   as reported by `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` (typically 48 kHz
 *   on most devices). **Important:** this property always reflects the shared
 *   AudioFlinger mixer's native rate, not the rate of the active audio stream. When
 *   `usedDirectFlag=true` the stream bypasses the mixer entirely, so a mismatch
 *   between this value and [sampleRateHz] does **not** imply resampling — those
 *   are two independent paths. The comparison is only meaningful on a non-direct
 *   (mixed) path where `usedDirectFlag=false`.
 * @property framesPerBuffer The device's preferred burst size
 *   (`AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER`). Used for
 *   telemetry/diagnosis only — [AudioTrackSink] sizes its buffer from the
 *   minimum required size, not this value.
 * @property routedDeviceType `AudioDeviceInfo.TYPE_*` of the device that
 *   `AudioTrack.getRoutedDevice()` ended up on. `0` when routing is unknown.
 * @property routedDeviceName Human-readable device label for UI display.
 * @property audioSessionId The sink's AudioSession ID, suitable for use by
 *   external equaliser / effect apps.
 * @property usbPcmSubslotBitDepth Physical PCM carrier width selected for a custom
 *   libusb UAC2 stream, or `0` outside that path.
 * @property usbPcmValidBitDepth Endpoint-declared `bBitResolution` within the
 *   selected USB PCM subslot, or `0` outside that path. This is the maximum
 *   precision retained when float DSP output is quantised for the DAC.
 * @property activeOutputThreadSampleRateHz Best-effort rate of the actual
 *   AudioFlinger output thread serving the track on standard PCM mixer paths.
 *   `0` when unknown or not applicable.
 * @property pathType High-level playback path classification for the active
 *   session.
 * @property decision Rate-resolution decision applied to the standard PCM path,
 *   or [Decision.Bypass] when another path owns sample-rate management.
 * @property dsdPipelineInfo Optional DSD transport diagnostics for the current
 *   track when the source is DSD and the engine negotiated native DSD or DoP.
 */
data class PipelinePathReport(
    val usedDirectFlag: Boolean,
    val usedFloatFallback: Boolean,
    val encoding: Int,
    val sampleRateHz: Int,
    val channelMask: Int,
    val bufferFrames: Int,
    val nativeOutputSampleRateHz: Int,
    val framesPerBuffer: Int,
    val routedDeviceType: Int,
    val routedDeviceName: String?,
    val audioSessionId: Int,
    val usbPcmSubslotBitDepth: Int = 0,
    val usbPcmValidBitDepth: Int = 0,
    val activeOutputThreadSampleRateHz: Int = 0,
    val pathType: PathType = PathType.STANDARD_PCM,
    val decision: Decision = Decision.Bypass,
    val dsdPipelineInfo: DsdPipelineInfo? = null,
    /**
     * Sonic Upscaling Enhancer diagnostics for the current track, or `null`
     * when the source is lossless, SUE is disabled, or the audiophile engine
     * is idle.
     */
    val sueInfo: SueInfo? = null,
)
