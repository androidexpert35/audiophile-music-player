package com.androidexpert35.audiophilemusicplayer.data.playback

import android.util.Log
import androidx.media3.session.MediaController
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackPersistenceRepository
import com.tony.coreui.domain.resource.getOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Re-hydrates [ctrl] with the last persisted playback session.
 *
 * Reads the persisted state from [playbackPersistenceRepository], rebuilds the
 * in-memory [trackMap], loads lightweight [androidx.media3.common.MediaItem]s,
 * and seeks to the saved position. Playback is intentionally left paused so the
 * user can resume when ready.
 *
 * The guard inside [commandMutex] ensures that if [PlaybackController.play] was
 * called between the session check in `getController` and the actual restoration
 * (e.g. the user tapped a track before this coroutine ran), the restore is
 * silently skipped without overwriting the active queue.
 *
 * Should be called from the application main-thread scope so all
 * [MediaController] mutations happen on the main thread.
 *
 * @param ctrl The newly connected [MediaController] to restore into.
 * @param commandMutex Mutex serialising all transport commands on this controller.
 * @param trackMap Mutable reverse-lookup map to be populated with the restored
 *   queue before any Media3 mutations occur.
 * @param playbackPersistenceRepository Repository providing the persisted session
 *   state and track resolution.
 * @param onUpdatePlaybackState Callback invoked after the controller is prepared
 *   to emit the initial [com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState] snapshot.
 * @param onUpdateQueueState Callback invoked after the controller is prepared to
 *   emit the initial [com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState] snapshot.
 */
internal suspend fun restorePlaybackSession(
    ctrl: MediaController,
    commandMutex: Mutex,
    trackMap: MutableMap<String, Track>,
    playbackPersistenceRepository: PlaybackPersistenceRepository,
    onUpdatePlaybackState: () -> Unit,
    onUpdateQueueState: () -> Unit,
) {
    val persistedState = playbackPersistenceRepository.restorePlaybackState().getOrNull() ?: run {
        Log.d(TAG, "No persisted session found — starting fresh")
        return
    }

    if (persistedState.queueTrackIds.isEmpty()) return

    val tracks = playbackPersistenceRepository
        .getTracksForQueue(persistedState.queueTrackIds)
        .getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: run {
        Log.w(TAG, "Persisted queue IDs could not be resolved — library may have changed")
        return
    }

    commandMutex.withLock {
        // Guard: a real play() call between the launch and now has already populated the queue
        if (ctrl.mediaItemCount > 0) {
            Log.d(TAG, "Queue already populated — skipping session restore")
            return@withLock
        }

        // Populate the reverse-lookup map before touching Media3
        trackMap.replaceWithTracks(tracks)

        val mediaItems = tracks.map { it.toRestorationMediaItem() }
        val safeIndex = persistedState.currentQueueIndex.coerceIn(0, tracks.lastIndex)
        val safePositionMs = resolveRestorePositionMs(
            persistedPositionMs = persistedState.playbackPositionMs,
            trackDurationMs = tracks[safeIndex].durationMs,
        )

        ctrl.setMediaItems(mediaItems, safeIndex, safePositionMs)
        ctrl.repeatMode = PlaybackStateMapper.toMedia3RepeatMode(persistedState.repeatMode)
        ctrl.shuffleModeEnabled =
            PlaybackStateMapper.toMedia3ShuffleEnabled(persistedState.shuffleMode)

        // Prepare but do NOT play — the user resumes intentionally
        ctrl.prepare()
        onUpdatePlaybackState()
        onUpdateQueueState()

        Log.d(
            TAG,
            "Session restored — track=${persistedState.currentTrackId}, " +
                "position=${persistedState.playbackPositionMs}ms, " +
                "queueSize=${tracks.size}"
        )
    }
}

/**
 * Sanitises the persisted playhead before it is applied to the restored queue.
 *
 * A session persisted at (or milliseconds before) end-of-stream — e.g. the
 * async save fired by the ENDED transition — would otherwise restore the track
 * at its very end, making the next play command hit EOF instantly and re-end,
 * which reads as a dead play button. Anything inside the final
 * [END_OF_TRACK_RESTORE_GUARD_MS] restarts the track from the top instead.
 *
 * @param persistedPositionMs Playhead recorded in the persisted session.
 * @param trackDurationMs Duration of the restored track; `<= 0` when unknown,
 *   in which case the persisted position is trusted as-is.
 */
private fun resolveRestorePositionMs(persistedPositionMs: Long, trackDurationMs: Long): Long {
    val position = persistedPositionMs.coerceAtLeast(0L)
    if (trackDurationMs <= 0L) return position
    return if (position >= trackDurationMs - END_OF_TRACK_RESTORE_GUARD_MS) 0L else position
}

/** Restored positions closer than this to the track end restart from zero. */
private const val END_OF_TRACK_RESTORE_GUARD_MS = 2_000L

private const val TAG = "PlaybackController"

