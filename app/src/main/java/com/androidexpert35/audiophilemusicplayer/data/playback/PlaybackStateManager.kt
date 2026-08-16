package com.androidexpert35.audiophilemusicplayer.data.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.androidexpert35.audiophilemusicplayer.data.playback.PlaybackStateManager.Companion.PERIODIC_SAVE_INTERVAL_MS
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PersistedPlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ClearPlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SavePlaybackStateUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service-side manager that saves the active [Player] session to Room at
 * precisely-timed lifecycle events.
 *
 * Originally typed against [androidx.media3.exoplayer.ExoPlayer]; after the
 * bit-perfect refactor it works against the generic [Player] interface so it
 * can bind to [AudiophileSimpleBasePlayer] (which is a `SimpleBasePlayer`, not
 * an ExoPlayer). Every accessor used inside the listener is part of the
 * `Player` contract.
 *
 * @property savePlaybackStateUseCase Domain use case that delegates persistence.
 * @property ioDispatcher IO dispatcher used for the background async-save scope.
 * @property mainScope Application main-thread scope driving the periodic
 *   while-playing save ticker — player state must be read on the main thread.
 */
@Singleton
class PlaybackStateManager @Inject constructor(
    private val savePlaybackStateUseCase: SavePlaybackStateUseCase,
    private val clearPlaybackStateUseCase: ClearPlaybackStateUseCase,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope private val mainScope: CoroutineScope,
) {

    private val saveScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var saveListener: Player.Listener? = null

    /**
     * Periodic while-playing save job.
     *
     * Event-driven saves alone (transition / pause / ended) leave a wide data-loss
     * window: an OS process kill mid-track skips `onDestroy`, so the restored
     * position silently falls back to the start of the track. Ticking every
     * [PERIODIC_SAVE_INTERVAL_MS] while audio flows caps that loss.
     */
    private var periodicSaveJob: Job? = null

    /** Serializes session writes and deletion so a cleared queue cannot be saved again. */
    private val persistenceMutex = Mutex()

    /** Invalidates queued asynchronous saves after a queue-clear operation. */
    private val persistenceGeneration = AtomicLong(0L)

    /**
     * Attaches the session-save listener to [player].
     *
     * @param player Any Media3 [Player] — in production this is
     *   [AudiophileSimpleBasePlayer].
     */
    fun attachToPlayer(player: Player) {
        val listener = createSaveListener(player)
        saveListener = listener
        player.addListener(listener)
        Log.d(TAG, "Save listener attached to Player")
    }

    /** Removes the listener prior to releasing the [player]. */
    fun detachFromPlayer(player: Player) {
        periodicSaveJob = periodicSaveJob.cancelAndClear()
        saveListener?.let { player.removeListener(it) }
        saveListener = null
        Log.d(TAG, "Save listener detached from Player")
    }

    /**
     * Captures and **synchronously** persists the current player state. Used
     * in `onDestroy` / `onTaskRemoved` where the process may exit immediately.
     */
    fun saveNow(player: Player) {
        val snapshot = capturePlayerState(player) ?: return
        val generation = persistenceGeneration.incrementAndGet()
        runBlocking {
            persistenceMutex.withLock {
                if (generation == persistenceGeneration.get()) {
                    savePlaybackStateUseCase(snapshot)
                    logSavedSnapshot(prefix = "Sync", snapshot = snapshot)
                }
            }
        }
    }

    /**
     * Synchronously deletes the restorable playback session.
     *
     * Used during task removal, where Android may terminate the process immediately after the
     * service finishes its teardown. Incrementing [persistenceGeneration] prevents an older
     * asynchronous save from recreating the deleted queue afterward.
     */
    fun clearNow() {
        runBlocking {
            persistenceMutex.withLock {
                persistenceGeneration.incrementAndGet()
                clearPlaybackStateUseCase()
            }
        }
        Log.d(TAG, "Persisted playback session cleared")
    }

    private fun createSaveListener(player: Player): Player.Listener =
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                scheduleAsyncSave(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startPeriodicSaves(player)
                } else {
                    stopPeriodicSaves()
                    val state = player.playbackState
                    if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                        scheduleAsyncSave(player)
                    }
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                // Repeat is part of the persisted session contract. Saving at the
                // point of change prevents a force-close from silently resetting it.
                scheduleAsyncSave(player)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                // The queue order and its shuffle flag must be restored together so
                // the next session continues with the user's selected traversal.
                scheduleAsyncSave(player)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (player.mediaItemCount == 0) {
                    clearNow()
                } else {
                    scheduleAsyncSave(player)
                }
            }
        }

    /** Starts the while-playing ticker; the snapshot is captured on the main thread. */
    private fun startPeriodicSaves(player: Player) {
        periodicSaveJob = mainScope.restartTicker(
            currentJob = periodicSaveJob,
            intervalMs = PERIODIC_SAVE_INTERVAL_MS,
        ) {
            scheduleAsyncSave(player)
        }
    }

    private fun stopPeriodicSaves() {
        periodicSaveJob = periodicSaveJob.cancelAndClear()
    }

    private fun scheduleAsyncSave(player: Player) {
        val snapshot = capturePlayerState(player) ?: return
        val generation = persistenceGeneration.incrementAndGet()
        saveScope.launch {
            persistenceMutex.withLock {
                if (generation == persistenceGeneration.get()) {
                    savePlaybackStateUseCase(snapshot)
                    logSavedSnapshot(prefix = "Async", snapshot = snapshot)
                }
            }
        }
    }

    private fun capturePlayerState(player: Player): PersistedPlaybackState? {
        if (player.mediaItemCount == 0) return null
        val mediaId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return null

        val queueIds = (0 until player.mediaItemCount).mapNotNull { index ->
            player.getMediaItemAt(index).mediaId.toLongOrNull()
        }

        return PersistedPlaybackState(
            currentTrackId = mediaId,
            playbackPositionMs = player.currentPosition.coerceAtLeast(0L),
            currentQueueIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            queueTrackIds = queueIds,
            repeatMode = PlaybackStateMapper.fromMedia3RepeatMode(player.repeatMode),
            shuffleMode = PlaybackStateMapper.fromMedia3ShuffleEnabled(player.shuffleModeEnabled)
        )
    }

    private fun logSavedSnapshot(prefix: String, snapshot: PersistedPlaybackState) {
        Log.d(
            TAG,
            "$prefix save — pos=${snapshot.playbackPositionMs}ms " +
                "track=${snapshot.currentTrackId} queue=${snapshot.queueTrackIds.size}"
        )
    }

    private companion object {
        const val TAG = "PlaybackStateManager"

        /**
         * Interval between while-playing snapshot saves.
         *
         * 15 s bounds the position lost to an unclean process death to a tolerable
         * skip-back, while writing to Room rarely enough to be inaudible on the
         * playback path (the write itself runs on the IO dispatcher).
         */
        const val PERIODIC_SAVE_INTERVAL_MS = 15_000L
    }
}
