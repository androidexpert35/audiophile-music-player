package com.androidexpert35.audiophilemusicplayer.data.playback.engine

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.AUDIPHILE_PATH_TAG
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-lived coordinator that binds the persisted "audiophile engine enabled"
 * user preference to the runtime [AudioEngineManager].
 *
 * Eagerly instantiated by the
 * [com.androidexpert35.audiophilemusicplayer.AudiophileMusicPlayerApp] class so
 * the stored preference is applied during process start — before the first
 * `MediaController` command reaches the playback service.
 *
 * @property settingsRepository Source of truth for the engine preference.
 * @property engineManager Hot-swap coordinator for both playback strategies.
 * @property appScope Long-lived scope driving the observation collector.
 */
@Singleton
class AudioEngineSettingsCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val engineManager: AudioEngineManager,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /**
     * Starts the collector that mirrors the persisted preference onto the
     * engine manager. Safe to call multiple times — only the first call has
     * effect.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Log.i(AUDIPHILE_PATH_TAG, "Audio-engine settings coordinator started")
        appScope.launch {
            settingsRepository.observeAudiophileEngineEnabled()
                .distinctUntilChanged()
                .collect { enabled ->
                    Log.i(AUDIPHILE_PATH_TAG, "Persisted audiophile preference=$enabled")
                    runCatching { engineManager.reconcilePreferredEngine(enabled) }
                        .onFailure { throwable ->
                            Log.e(TAG, "Failed to apply persisted audiophile mode: $enabled", throwable)
                        }
                }
        }
    }

    private companion object {
        const val TAG = "AudioEngineSettings"
    }
}


