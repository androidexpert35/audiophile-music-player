package com.androidexpert35.audiophilemusicplayer.data.playback.engine

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.SueProfileResolver
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reloads the current audiophile track when the SUE preference changes.
 *
 * The coordinator is intentionally gated to lossy sources only. Lossless tracks
 * (FLAC, WAV, ALAC, DSD, and PCM fallbacks derived from them) must remain
 * completely untouched by the SUE toggle so the control never alters their
 * sound or rebuilds their shared Hi-Res remaster path.
 *
 * This keeps the enhancer state aligned with the persisted toggle immediately
 * for lossy playback, without affecting the Standard engine or requiring the
 * user to skip tracks.
 *
 * @property settingsRepository Source of truth for the SUE preference.
 * @property engineManager Active-engine coordinator used to reload the track.
 * @property appScope Long-lived scope driving the preference observer.
 */
@Singleton
class SueSettingsCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val engineManager: AudioEngineManager,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /**
     * Starts observing SUE preference changes. Safe to call multiple times.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Log.i(TAG, "SUE settings coordinator started")
        appScope.launch {
            settingsRepository.observeSueEnabled()
                .drop(1)
                .distinctUntilChanged()
                .collect { enabled ->
                    val currentFormat = engineManager.currentFormat.value
                    if (!currentFormat.isSueApplicableLossySource()) {
                        Log.i(
                            TAG,
                            "SUE preference changed → enabled=$enabled; active track is lossless or idle, skipping reload",
                        )
                        return@collect
                    }

                    Log.i(TAG, "SUE preference changed → enabled=$enabled; reloading lossy track")
                    runCatching { engineManager.reloadCurrentTrack() }
                        .onFailure { throwable ->
                            Log.e(TAG, "Failed to reload track after SUE toggle: enabled=$enabled", throwable)
                        }
                }
        }
    }

    private companion object {
        const val TAG = "SueSettings"
    }
}

/**
 * Returns `true` when the active decoder format belongs to a lossy source that
 * SUE is allowed to modify.
 */
private fun AudioFormatInfo?.isSueApplicableLossySource(): Boolean {
    val format = this ?: return false
    return SueProfileResolver.resolve(format).isLossySource
}

