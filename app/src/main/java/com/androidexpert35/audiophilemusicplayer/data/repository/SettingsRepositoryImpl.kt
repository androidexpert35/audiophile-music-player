package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.SharedPreferences
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EngineType
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioOutputProfile
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbDeviceScanner
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.UsbAudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.UsbAudioStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.common.PlaybackResourceError
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibrarySectionDisplayPreference
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsRepository] implementation backed by a dedicated
 * [SharedPreferences] file. The observation pipeline uses `callbackFlow` to
 * wrap [SharedPreferences.OnSharedPreferenceChangeListener] — the repo's
 * mandatory pattern for every register/unregister callback API — so the
 * listener is detached automatically when the collector's scope cancels.
 *
 * @property prefs Dedicated settings storage shared by the playback and USB
 *   infrastructure.
 * @property audioEngineManager Runtime coordinator that performs the actual
 *   playback-engine hot-swap requested by the user.
 * @property usbDeviceScanner USB DAC discovery and permission coordinator.
 * @property ioDispatcher Dispatcher used for the blocking preference commit.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val prefs: SharedPreferences,
    private val audioEngineManager: AudioEngineManager,
    private val usbDeviceScanner: UsbDeviceScanner,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SettingsRepository {

    override suspend fun getLibraryDisplayPreferences(): Resource<LibraryDisplayPreferences> =
        withContext(ioDispatcher) {
            runCatching {
                val entries = prefs.getStringSet(
                    SettingsPreferences.KEY_LIBRARY_DISPLAY_PREFERENCES,
                    emptySet(),
                ).orEmpty()
                LibraryDisplayPreferences(
                    sections = entries.mapNotNull(::decodeLibraryDisplayPreference).toMap()
                )
            }.fold(
                onSuccess = { Resource.Success(it) },
                onFailure = { Resource.Error(ResourceError.StorageError(it.message ?: "Unable to read library display preferences.")) },
            )
        }

    override suspend fun setLibraryDisplayPreferences(
        preferences: LibraryDisplayPreferences
    ): Resource<Unit> {
        val committed = withContext(ioDispatcher) {
            prefs.edit()
                .putStringSet(
                    SettingsPreferences.KEY_LIBRARY_DISPLAY_PREFERENCES,
                    preferences.sections.map { (section, preference) ->
                        encodeLibraryDisplayPreference(section, preference)
                    }.toSet(),
                )
                .commit()
        }
        return if (committed) {
            Resource.Success(Unit)
        } else {
            Resource.Error(ResourceError.StorageError("Unable to save library display preferences."))
        }
    }

    override fun observeAudiophileEngineEnabled(): Flow<Boolean> = callbackFlow {
        // Emit the current value synchronously so the first collector has a
        // real value before any change event arrives.
        trySend(
            prefs.getBoolean(
                SettingsPreferences.KEY_AUDIOPHILE_ENABLED,
                SettingsPreferences.DEFAULT_AUDIOPHILE_ENABLED,
            )
        )

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == SettingsPreferences.KEY_AUDIOPHILE_ENABLED) {
                trySend(
                    sp.getBoolean(
                        SettingsPreferences.KEY_AUDIOPHILE_ENABLED,
                        SettingsPreferences.DEFAULT_AUDIOPHILE_ENABLED,
                    )
                )
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override fun observeUsbAudioStatus(): Flow<UsbAudioStatus> = usbDeviceScanner.deviceState.map { state ->
        UsbAudioStatus(
            isDeviceConnected = state.connectedDevice != null,
            isPermissionGranted = state.isPermissionGranted,
            isDirectOutputReady = state.isDirectUsbReady,
            isDirectUsbTransportSupported = state.isDirectUsbTransportSupported,
            activeDeviceName = state.connectedDevice?.deviceName,
            supportedFormats = state.supportedProfiles.map(::toDomainFormat).distinct(),
            areSupportedFormatsEstimated = state.areSupportedProfilesEstimated,
            dsdOutputMode = state.dsdOutputMode,
        )
    }

    override suspend fun setAudiophileEngineEnabled(enabled: Boolean): Resource<Unit> {
        val previousEngine = audioEngineManager.activeEngineType.value
        val storedValue = prefs.getBoolean(
            SettingsPreferences.KEY_AUDIOPHILE_ENABLED,
            SettingsPreferences.DEFAULT_AUDIOPHILE_ENABLED,
        )

        if (storedValue == enabled) {
            try {
                audioEngineManager.reconcilePreferredEngine(preferAudiophile = enabled)
            } catch (_: Throwable) {
                // The stored preference is already authoritative; callers only
                // need the persisted state when no change is being requested.
            }
            return Resource.Success(Unit)
        }

        val commitSucceeded = withContext(ioDispatcher) {
            prefs.edit()
                .putBoolean(SettingsPreferences.KEY_AUDIOPHILE_ENABLED, enabled)
                .commit()
        }
        if (!commitSucceeded) {
            return Resource.Error(
                ResourceError.StorageError(
                    "Unable to save the audiophile mode setting."
                )
            )
        }

        if (enabled &&
            usbDeviceScanner.deviceState.value.connectedDevice != null &&
            !usbDeviceScanner.deviceState.value.isPermissionGranted
        ) {
            usbDeviceScanner.requestPermission()
        }

        val reconcileError = try {
            audioEngineManager.reconcilePreferredEngine(preferAudiophile = enabled)
            null
        } catch (throwable: Throwable) {
            throwable
        }
        if (reconcileError == null) {
            return Resource.Success(Unit)
        }

        withContext(ioDispatcher) {
            prefs.edit()
                .putBoolean(
                    SettingsPreferences.KEY_AUDIOPHILE_ENABLED,
                    previousEngine == EngineType.AUDIOPHILE,
                )
                .commit()
        }
        return Resource.Error(
            PlaybackResourceError(
                reconcileError.message
                    ?: "Unable to switch playback engine."
            )
        )
    }

    override suspend fun refreshUsbAudioDevices(): Resource<Unit> {
        val refreshError = runCatching {
            withContext(ioDispatcher) {
                usbDeviceScanner.refreshDeviceState()
            }
        }.exceptionOrNull()
        if (refreshError != null) {
            return Resource.Error(
                PlaybackResourceError(
                    refreshError.message
                        ?: "Unable to refresh the connected USB audio devices."
                )
            )
        }

        val audiophileEnabled = prefs.getBoolean(
            SettingsPreferences.KEY_AUDIOPHILE_ENABLED,
            SettingsPreferences.DEFAULT_AUDIOPHILE_ENABLED,
        )
        if (!audiophileEnabled) {
            return Resource.Success(Unit)
        }

        val reconcileError = try {
            if (audioEngineManager.activeEngineType.value == EngineType.AUDIOPHILE) {
                audioEngineManager.switchTo(EngineType.STANDARD)
            }
            audioEngineManager.reconcilePreferredEngine(preferAudiophile = true)
            null
        } catch (throwable: Throwable) {
            throwable
        }
        if (reconcileError != null) {
            return Resource.Error(
                PlaybackResourceError(
                    reconcileError.message
                        ?: "Unable to refresh the USB audio playback pipeline."
                )
            )
        }

        return Resource.Success(Unit)
    }

    override suspend fun requestUsbAudioPermission(): Resource<Unit> {
        return if (usbDeviceScanner.requestPermission()) {
            Resource.Success(Unit)
        } else {
            Resource.Error(
                PlaybackResourceError(
                    "No compatible USB DAC is connected, or the permission dialog could not be shown."
                )
            )
        }
    }


    override fun observeSueEnabled(): Flow<Boolean> = callbackFlow {
        // Emit the current value immediately so the first collector has a real
        // reading before any change event arrives.
        trySend(
            prefs.getBoolean(
                SettingsPreferences.KEY_SUE_ENABLED,
                SettingsPreferences.DEFAULT_SUE_ENABLED,
            )
        )

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == SettingsPreferences.KEY_SUE_ENABLED) {
                trySend(
                    sp.getBoolean(
                        SettingsPreferences.KEY_SUE_ENABLED,
                        SettingsPreferences.DEFAULT_SUE_ENABLED,
                    )
                )
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override suspend fun setSueEnabled(enabled: Boolean): Resource<Unit> {
        val storedValue = prefs.getBoolean(
            SettingsPreferences.KEY_SUE_ENABLED,
            SettingsPreferences.DEFAULT_SUE_ENABLED,
        )
        if (storedValue == enabled) return Resource.Success(Unit)

        val commitSucceeded = withContext(ioDispatcher) {
            prefs.edit()
                .putBoolean(SettingsPreferences.KEY_SUE_ENABLED, enabled)
                .commit()
        }
        return if (commitSucceeded) {
            Resource.Success(Unit)
        } else {
            Resource.Error(
                ResourceError.StorageError("Unable to save the Sonic Upscaling Enhancer setting.")
            )
        }
    }

    override fun observeHiResRemasterEnabled(): Flow<Boolean> = callbackFlow {
        // Emit the current value immediately so the first collector has a real
        // reading before any change event arrives.
        trySend(
            prefs.getBoolean(
                SettingsPreferences.KEY_HIRES_REMASTER_ENABLED,
                SettingsPreferences.DEFAULT_HIRES_REMASTER_ENABLED,
            )
        )

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == SettingsPreferences.KEY_HIRES_REMASTER_ENABLED) {
                trySend(
                    sp.getBoolean(
                        SettingsPreferences.KEY_HIRES_REMASTER_ENABLED,
                        SettingsPreferences.DEFAULT_HIRES_REMASTER_ENABLED,
                    )
                )
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override suspend fun setHiResRemasterEnabled(enabled: Boolean): Resource<Unit> {
        val storedValue = prefs.getBoolean(
            SettingsPreferences.KEY_HIRES_REMASTER_ENABLED,
            SettingsPreferences.DEFAULT_HIRES_REMASTER_ENABLED,
        )
        if (storedValue == enabled) return Resource.Success(Unit)

        val commitSucceeded = withContext(ioDispatcher) {
            prefs.edit()
                .putBoolean(SettingsPreferences.KEY_HIRES_REMASTER_ENABLED, enabled)
                .commit()
        }
        return if (commitSucceeded) {
            Resource.Success(Unit)
        } else {
            Resource.Error(
                ResourceError.StorageError("Unable to save the Hi-Res Dynamic Remaster setting.")
            )
        }
    }

    private fun toDomainFormat(profile: UsbAudioOutputProfile): UsbAudioFormat = UsbAudioFormat(
        sampleRateHz = profile.sampleRateHz,
        bitDepth = profile.bitDepth,
        channelCount = profile.channelCount,
    )

    private fun encodeLibraryDisplayPreference(
        section: String,
        preference: LibrarySectionDisplayPreference,
    ): String = listOf(section, preference.sortOrder, preference.isGridView).joinToString("|")

    private fun decodeLibraryDisplayPreference(
        value: String,
    ): Pair<String, LibrarySectionDisplayPreference>? {
        val parts = value.split("|", limit = 3)
        if (parts.size != 3 || parts[0].isBlank()) return null
        val gridView = parts[2].toBooleanStrictOrNull() ?: return null
        return parts[0] to LibrarySectionDisplayPreference(parts[1], gridView)
    }

}
