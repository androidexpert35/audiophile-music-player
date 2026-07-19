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
 * Reloads the current audiophile track when the Hi-Res Dynamic Remaster
 * preference changes.
 *
 * The coordinator is intentionally gated to lossless sources only. Lossy
 * tracks must remain completely untouched by the Hi-Res toggle so the control
 * never alters their sound or rebuilds a playback chain that is supposed to be
 * bypassed for compressed sources.
 *
 * This keeps the lossless remaster stage aligned with its own persisted toggle
 * immediately, without mutating the separate SUE preference or requiring the
 * user to skip tracks manually.
 *
 * @property settingsRepository Source of truth for the Hi-Res remaster preference.
 * @property engineManager Active-engine coordinator used to reload the track.
 * @property appScope Long-lived scope driving the preference observer.
 */
@Singleton
class HiResRemasterSettingsCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val engineManager: AudioEngineManager,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /**
     * Starts observing Hi-Res remaster preference changes. Safe to call multiple
     * times.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Log.i(TAG, "Hi-Res remaster settings coordinator started")
        appScope.launch {
            settingsRepository.observeHiResRemasterEnabled()
                .drop(1)
                .distinctUntilChanged()
                .collect { enabled ->
                    val currentFormat = engineManager.currentFormat.value
                    if (!currentFormat.isHiResApplicableLosslessSource()) {
                        Log.i(
                            TAG,
                            "Hi-Res remaster preference changed → enabled=$enabled; active track is lossy or idle, skipping reload",
                        )
                        return@collect
                    }

                    Log.i(TAG, "Hi-Res remaster preference changed → enabled=$enabled; reloading lossless track")
                    runCatching { engineManager.reloadCurrentTrack() }
                        .onFailure { throwable ->
                            Log.e(
                                TAG,
                                "Failed to reload track after Hi-Res remaster toggle: enabled=$enabled",
                                throwable,
                            )
                        }
                }
        }
    }

    private companion object {
        const val TAG = "HiResRemasterSettings"
    }
}

/**
 * Returns `true` when the active decoder format belongs to a lossless source
 * that Hi-Res Dynamic Remaster is allowed to modify.
 *
 * Two conditions must both hold:
 * 1. The source is lossless (not a lossy-compressed codec handled by SUE).
 * 2. The source is **not** already at native hi-res quality — sources with a
 *    bit depth ≥ 24-bit or a sample rate > 48 000 Hz are bypassed unconditionally
 *    because the remaster's oversampling step would be ineffective on them.
 */
private fun AudioFormatInfo?.isHiResApplicableLosslessSource(): Boolean {
    val format = this ?: return false
    if (SueProfileResolver.resolve(format).isLossySource) return false
    // Already native hi-res — the remaster stage is always bypassed, so toggling
    // the preference has no effect and a reload would be a no-op.
    return !(format.sourceBitDepth >= 24 || format.sampleRateHz > 48_000)
}

