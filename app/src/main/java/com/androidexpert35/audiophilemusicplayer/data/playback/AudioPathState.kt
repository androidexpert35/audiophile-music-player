package com.androidexpert35.audiophilemusicplayer.data.playback

import android.media.AudioDeviceInfo

/**
 * Immutable snapshot of the audio path diagnostic state produced by
 * [AudioPathValidator].
 *
 * All fields are best-effort — they describe what the Android OS **claims** is
 * happening on the audio path, not what can be physically verified at the DAC.
 *
 * @property sourceFormat PCM shape of the decoded source file. `null` when no
 *   track is loaded or the validator has not yet received format information.
 * @property activeDevice The [AudioDeviceInfo] selected by Android's media
 *   routing policy at the time of this snapshot. `null` when device routing
 *   has not been resolved yet.
 * @property activeDeviceType `AudioDeviceInfo.TYPE_*` constant of the active
 *   device, or `0` when unknown. Derived from [activeDevice] when present and
 *   falls back to the engine path report's device-type sentinel.
 * @property activeDeviceName Human-readable product name of the active output
 *   device. `null` when routing is unresolved.
 * @property pathStatus The highest-fidelity tier the validator could confirm
 *   for the current stream. See [AudioPathStatus] for tier semantics.
 * @property isBitPerfectConfirmed `true` only when both the [pathStatus] is
 *   [AudioPathStatus.DIRECT_BIT_PERFECT] **and** the confirmation came from
 *   the Android 14+ mixer-attributes API or the hardware-bypass USB path.
 * @property isDirectPlaybackSupported `true` when
 *   `AudioTrack.isDirectPlaybackSupported` returned `true` for the current
 *   [sourceFormat] and media [android.media.AudioAttributes]. Populated
 *   independently of [pathStatus] so the UI can show "OS says: direct
 *   supported" even on a path that degraded to resampling at runtime.
 * @property outputSampleRateHz The sample rate the sink was actually opened at
 *   (from the engine's [com.androidexpert35.audiophilemusicplayer
 *   .data.playback.native_.PipelinePathReport]). May differ from
 *   [AudioSourceFormat.sampleRateHz] when SoX resampling is engaged.
 * @property isResamplingActive `true` when the engine path report indicates the
 *   HAL's native output rate differs from the sink sample rate, implying that
 *   software or hardware sample-rate conversion is active.
 */
data class AudioPathState(
    val sourceFormat: AudioSourceFormat? = null,
    val activeDevice: AudioDeviceInfo? = null,
    val activeDeviceType: Int = 0,
    val activeDeviceName: String? = null,
    val pathStatus: AudioPathStatus = AudioPathStatus.UNKNOWN,
    val isBitPerfectConfirmed: Boolean = false,
    val isDirectPlaybackSupported: Boolean = false,
    val outputSampleRateHz: Int = 0,
    val isResamplingActive: Boolean = false,
)

