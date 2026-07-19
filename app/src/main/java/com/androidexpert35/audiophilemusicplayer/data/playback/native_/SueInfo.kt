package com.androidexpert35.audiophilemusicplayer.data.playback.native_

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.SueStatus

/**
 * Data-layer diagnostic snapshot for the Sonic Upscaling Enhancer (SUE) stage.
 *
 * Produced by [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine]
 * once per track load and attached to [PipelinePathReport]. The
 * [com.androidexpert35.audiophilemusicplayer.data.playback.AudioTelemetryCollector]
 * maps this to the domain [SueStatus] model via [toSueStatus].
 *
 * @property enabled         Whether the SUE setting was enabled at track load time.
 * @property isActive        Whether the filter graph was successfully initialised and
 *   will process audio (`enabled AND isLossy AND isProvisioned`).
 * @property isLossy         Whether the source codec is lossy-compressed. `false` when
 *   the source is FLAC, WAV, ALAC, DSD, or any lossless format — the stage is
 *   bypassed entirely and the audio is not modified.
 * @property isProvisioned   Whether the native FFmpeg filter graph was successfully
 *   built. `false` when libavfilter is absent from the build, or when the resolved
 *   profile is BYPASS (high-efficiency codec at high bitrate).
 * @property intensityProfile Human-readable intensity profile name for the Settings
 *   UI (e.g., `"MODERATE"`, `"AGGRESSIVE"`, `"BYPASS"`).
 * @property codecDisplayName Short display name of the source codec (e.g., `"MP3"`,
 *   `"AAC"`), shown in the Settings card subtitle when SUE is active.
 * @property isHiResRemasterEnabled Whether the Hi-Res Dynamic Remaster setting was
 *   enabled when this track pipeline was prepared, even when the stage is later
 *   bypassed because the source is lossy, already hi-res, or routed bit-perfect.
 * @property isHiResRemasterActive Whether the Hi-Res Dynamic Remaster engine is
 *   currently active for the loaded track. `true` only for lossless sources when the
 *   Hi-Res toggle is enabled and the native stage was provisioned successfully.
 *   Mutually exclusive with [isActive].
 * @property isForce48kResampleActive Whether the force-48k libsoxr resampler stage is
 *   currently active for the loaded track. `true` when the feature is enabled, no USB
 *   DAC is connected, and the source rate differs from 48 kHz. Mutually exclusive with
 *   [isActive] and [isHiResRemasterActive] — a track is processed by at most one stage.
 * @property failureReason Best-effort native diagnostic message captured when SUE
 *   was expected to activate but its filter graph could not be initialised.
 */
data class SueInfo(
    val enabled: Boolean,
    val isActive: Boolean,
    val isLossy: Boolean,
    val isProvisioned: Boolean,
    val intensityProfile: String,
    val codecDisplayName: String,
    val isHiResRemasterEnabled: Boolean = false,
    val isHiResRemasterActive: Boolean = false,
    val isForce48kResampleActive: Boolean = false,
    val failureReason: String? = null,
)

/**
 * Maps this data-layer [SueInfo] to the domain [SueStatus] model consumed
 * by the presentation layer.
 *
 * @return A [SueStatus] derived from this SUE diagnostic snapshot.
 */
fun SueInfo.toSueStatus(): SueStatus = SueStatus(
    isEnabled                = enabled,
    isActive                 = isActive,
    isLossy                  = isLossy,
    isProvisioned            = isProvisioned,
    intensityProfile         = intensityProfile,
    codecDisplayName         = codecDisplayName,
    isHiResRemasterEnabled   = isHiResRemasterEnabled,
    isHiResRemasterActive    = isHiResRemasterActive,
    isForce48kResampleActive = isForce48kResampleActive,
    failureReason            = failureReason,
)

