package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.SueStatus

/**
 * Immutable UI state for the Audio Engine &amp; DSP settings sub-screen.
 *
 * @property audiophileEngineEnabled Current value of the dual-engine toggle. When `true`
 *   playback prefers the direct USB audiophile engine whenever a permitted DAC is
 *   available; when `false` it falls back to ExoPlayer for reduced CPU / battery impact.
 * @property isAudiophileEngineSwitchInProgress Whether the app is currently hot-swapping
 *   playback engines after a user toggle.
 * @property sueEnabled Whether the Sonic Upscaling Enhancer is currently enabled in settings.
 * @property sueStatus Real-time status snapshot from the active audiophile pipeline, or
 *   `null` when no track is loaded or the standard engine is active.
 * @property hiResRemasterEnabled Whether the Hi-Res Dynamic Remaster is currently enabled
 *   in settings.
 */
data class AudioEngineSettingsUiModel(
    val audiophileEngineEnabled: Boolean = false,
    val isAudiophileEngineSwitchInProgress: Boolean = false,
    val sueEnabled: Boolean = true,
    val sueStatus: SueStatus? = null,
    val hiResRemasterEnabled: Boolean = true,
)
