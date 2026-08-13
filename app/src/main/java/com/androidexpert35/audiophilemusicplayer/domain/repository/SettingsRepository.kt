package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.UsbAudioStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences
import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.flow.Flow

/**
 * Persistent user preferences for the app.
 *
 * Currently exposes the audiophile-engine toggle that drives the dual-engine
 * strategy inside
 * [com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager].
 */
interface SettingsRepository {

    /** Retrieves the persisted sort and layout selections for the library sections. */
    suspend fun getLibraryDisplayPreferences(): Resource<LibraryDisplayPreferences>

    /** Persists the sort and layout selections for the library sections atomically. */
    suspend fun setLibraryDisplayPreferences(
        preferences: LibraryDisplayPreferences
    ): Resource<Unit>

    /**
     * Live stream of the "audiophile engine enabled" preference. Emits the
     * current stored value on subscription and every subsequent change.
     */
    fun observeAudiophileEngineEnabled(): Flow<Boolean>

    /**
     * Live stream describing whether a direct USB DAC is connected, permitted,
     * and ready for the audiophile engine.
     */
    fun observeUsbAudioStatus(): Flow<UsbAudioStatus>

    /**
     * Persists the "audiophile engine enabled" preference.
     *
     * Implementations must keep the runtime engine and the persisted value in
     * sync. If the engine cannot be switched or the preference cannot be
     * durably written, the method returns [Resource.Error] and leaves the app
     * on the previous mode.
     *
     * @param enabled `true` to use the bit-perfect FFmpeg engine, `false` to
     *   fall back to the battery-saving ExoPlayer engine.
     * @return [Resource.Success] when the runtime engine and stored preference
     *   both reflect the requested mode, otherwise [Resource.Error].
     */
    suspend fun setAudiophileEngineEnabled(enabled: Boolean): Resource<Unit>

    /**
     * Forces a fresh USB DAC scan so the settings screen can retry discovery.
     *
     * @return [Resource.Success] when the latest USB device snapshot is available,
     *   otherwise [Resource.Error].
     */
    suspend fun refreshUsbAudioDevices(): Resource<Unit>

    /**
     * Triggers a USB host permission request for the connected DAC.
     *
     * @return [Resource.Success] when the permission prompt was launched,
     *   otherwise [Resource.Error].
     */
    suspend fun requestUsbAudioPermission(): Resource<Unit>


    /**
     * Live stream of the "Sonic Upscaling Enhancer enabled" preference. Emits
     * the current stored value on subscription and every subsequent change.
     *
     * The enhancer is silently bypassed for lossless sources even when this
     * preference is `true`.
     */
    fun observeSueEnabled(): Flow<Boolean>

    /**
     * Persists the "Sonic Upscaling Enhancer enabled" preference.
     *
     * Takes effect on the next track load. The stage remains inactive for
     * lossless sources regardless of this value.
     *
     * @param enabled `true` to enable SUE for lossy-compressed sources.
     * @return [Resource.Success] on successful persistence, [Resource.Error]
     *   when the preference file could not be written.
     */
    suspend fun setSueEnabled(enabled: Boolean): Resource<Unit>

    /**
     * Live stream of the "Hi-Res Dynamic Remaster enabled" preference. Emits
     * the current stored value on subscription and every subsequent change.
     *
     * The remaster stage is silently bypassed for lossy sources even when this
     * preference is `true`.
     */
    fun observeHiResRemasterEnabled(): Flow<Boolean>

    /**
     * Persists the "Hi-Res Dynamic Remaster enabled" preference.
     *
     * Takes effect on the next track load. The stage remains inactive for
     * lossy sources regardless of this value.
     *
     * @param enabled `true` to activate 96 kHz oversampling and dynamic
     *   expansion for lossless sources (FLAC, WAV, ALAC).
     * @return [Resource.Success] on successful persistence, [Resource.Error]
     *   when the preference file could not be written.
     */
    suspend fun setHiResRemasterEnabled(enabled: Boolean): Resource<Unit>


}
