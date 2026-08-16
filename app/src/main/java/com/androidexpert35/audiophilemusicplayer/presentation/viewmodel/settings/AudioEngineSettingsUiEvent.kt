package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * User intents emitted from the Audio Engine &amp; DSP settings sub-screen.
 */
sealed interface AudioEngineSettingsUiEvent {
    /** Toggle the audiophile (bit-perfect) playback engine. */
    data class SetAudiophileEngineEnabled(val enabled: Boolean) : AudioEngineSettingsUiEvent

    /** Enable or disable the Sonic Upscaling Enhancer for lossy tracks. */
    data class SetSueEnabled(val enabled: Boolean) : AudioEngineSettingsUiEvent

    /** Enable or disable the Hi-Res Dynamic Remaster for lossless tracks. */
    data class SetHiResRemasterEnabled(val enabled: Boolean) : AudioEngineSettingsUiEvent
}
