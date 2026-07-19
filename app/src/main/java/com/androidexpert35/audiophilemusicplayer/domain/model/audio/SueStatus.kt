package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Real-time status of the Sonic Upscaling Enhancer (SUE) stage in the
 * audiophile pipeline.
 *
 * Exposed through [AudioTelemetry] so the player and Settings screens can
 * surface the current SUE state without directly accessing any data-layer types.
 *
 * @property isEnabled       Whether the user has enabled SUE in Settings.
 * @property isActive        Whether the filter graph is currently processing audio
 *   (`isEnabled AND isLossy AND isProvisioned`).
 * @property isLossy         Whether the active source codec is lossy-compressed.
 *   `false` for FLAC, WAV, ALAC, DSD — SUE is bypassed and the signal is
 *   delivered bit-perfect, regardless of the toggle state.
 * @property isProvisioned   Whether the native FFmpeg `libavfilter` filter graph
 *   was successfully initialised for this track. `false` when the build lacks
 *   `libavfilter.so`, or when the resolved intensity profile is BYPASS.
 * @property intensityProfile Human-readable name of the resolved intensity profile,
 *   for example `"MODERATE"`, `"AGGRESSIVE"`, or `"BYPASS"`.
 * @property codecDisplayName Short display name of the active source codec (e.g.
 *   `"MP3"`, `"AAC"`, `"Opus"`), shown in the Settings subtitle when active.
 * @property isHiResRemasterEnabled Whether the Hi-Res Dynamic Remaster setting
 *   was enabled when the current track pipeline was prepared. This lets the UI
 *   distinguish a disabled feature from an enabled feature that is intentionally
 *   idle because the current source is ineligible or already high resolution.
 * @property isHiResRemasterActive Whether the Hi-Res Dynamic Remaster engine is
 *   currently active for the loaded track. `true` only for lossless sources (FLAC,
 *   WAV, ALAC) when the Hi-Res toggle is enabled and the native stage was provisioned
 *   successfully. Mutually exclusive with [isActive]: a track is either processed by
 *   the lossy SUE engine or the lossless Hi-Res Remaster engine, never both.
 * @property isForce48kResampleActive Whether the force-48k libsoxr resampler is
 *   currently active for the loaded track. `true` when the feature is enabled,
 *   no USB DAC is connected, and the source sample rate is not already 48 kHz.
 *   In this state the FFmpeg lavfi pipeline contains
 *   `aresample=resampler=soxr:precision=33:cutoff=0.91:osr=48000:dither_method=triangular_hp`
 *   as its final stage. Mutually exclusive with [isActive] and [isHiResRemasterActive].
 * @property failureReason Best-effort diagnostic message when SUE was expected to
 *   activate for the current lossy source but the native stage could not be
 *   initialised.
 */
data class SueStatus(
    val isEnabled: Boolean = false,
    val isActive: Boolean = false,
    val isLossy: Boolean = false,
    val isProvisioned: Boolean = false,
    val intensityProfile: String = "",
    val codecDisplayName: String = "",
    val isHiResRemasterEnabled: Boolean = false,
    val isHiResRemasterActive: Boolean = false,
    val isForce48kResampleActive: Boolean = false,
    val failureReason: String? = null,
)

