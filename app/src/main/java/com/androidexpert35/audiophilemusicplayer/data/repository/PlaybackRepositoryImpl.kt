package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.playback.PlaybackController
import com.androidexpert35.audiophilemusicplayer.domain.model.common.PlaybackResourceError
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackRepository] implementation delegating all commands and state
 * observations to the [PlaybackController] which manages the Media3
 * [androidx.media3.session.MediaController] connection.
 *
 * @property playbackController Bridge to the playback service and the
 *   Media3 session.
 */
@Singleton
class PlaybackRepositoryImpl @Inject constructor(
    private val playbackController: PlaybackController
) : PlaybackRepository {

    override suspend fun play(track: Track, queue: List<Track>): Resource<Unit> = runCatching {
        playbackController.play(track, queue)
        Resource.Success(Unit)
    }.getOrElse { throwable ->
        Resource.Error(
            PlaybackResourceError(throwable.message ?: "Failed to start playback")
        )
    }

    override suspend fun playNext(track: Track): Resource<Unit> =
        queueMutation(errorMessage = "Failed to schedule next track") {
            playbackController.playNext(track)
        }

    override suspend fun playNext(tracks: List<Track>): Resource<Unit> =
        queueMutation(errorMessage = "Failed to schedule tracks next") {
            playbackController.playNext(tracks)
        }

    override suspend fun addToQueue(track: Track): Resource<Unit> =
        queueMutation(errorMessage = "Failed to add track to queue") {
            playbackController.addToQueue(track)
        }

    override suspend fun addToQueue(tracks: List<Track>): Resource<Unit> =
        queueMutation(errorMessage = "Failed to add tracks to queue") {
            playbackController.addToQueue(tracks)
        }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int): Resource<Unit> =
        queueMutation(errorMessage = "Failed to reorder playback queue") {
            playbackController.moveQueueItem(fromIndex, toIndex)
        }

    override suspend fun clearQueue(): Resource<Unit> =
        queueMutation(errorMessage = "Failed to clear playback queue") {
            playbackController.clearQueue()
        }

    override suspend fun pause(): Resource<Unit> = runCatching {
        playbackController.pause()
        Resource.Success(Unit)
    }.getOrElse { throwable ->
        Resource.Error(
            PlaybackResourceError(throwable.message ?: "Failed to pause")
        )
    }

    override suspend fun releaseUsbAudio(): Resource<Unit> = runCatching {
        playbackController.releaseUsbAudio()
        Resource.Success(Unit)
    }.getOrElse { throwable ->
        Resource.Error(
            PlaybackResourceError(throwable.message ?: "Failed to release USB audio")
        )
    }

    override suspend fun resume(): Resource<Unit> = runCatching {
        playbackController.resume()
        Resource.Success(Unit)
    }.getOrElse { throwable ->
        Resource.Error(
            PlaybackResourceError(throwable.message ?: "Failed to resume")
        )
    }

    override suspend fun seekTo(positionMs: Long): Resource<Unit> = runCatching {
        playbackController.seekTo(positionMs)
        Resource.Success(Unit)
    }.getOrElse { throwable ->
        Resource.Error(
            PlaybackResourceError(throwable.message ?: "Failed to seek")
        )
    }

    override suspend fun skipNext(): Resource<Unit> = runCatching {
        playbackController.skipNext()
        Resource.Success(Unit)
    }.getOrElse { throwable ->
        Resource.Error(
            PlaybackResourceError(throwable.message ?: "Failed to skip next")
        )
    }

    override suspend fun skipPrevious(): Resource<Unit> = runCatching {
        playbackController.skipPrevious()
        Resource.Success(Unit)
    }.getOrElse { throwable ->
        Resource.Error(
            PlaybackResourceError(throwable.message ?: "Failed to skip previous")
        )
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        playbackController.setRepeatMode(mode)
    }

    override suspend fun setShuffleMode(mode: ShuffleMode) {
        playbackController.setShuffleMode(mode)
    }

    override fun observePlaybackState(): Flow<PlaybackState> =
        playbackController.playbackState

    override fun observeQueueState(): Flow<QueueState> =
        playbackController.queueState

    /** Converts queue-controller failures into the repository's playback error value. */
    private suspend inline fun queueMutation(
        errorMessage: String,
        crossinline mutation: suspend () -> Unit
    ): Resource<Unit> = runCatching {
        mutation()
        Resource.Success(Unit)
    }.getOrElse { throwable ->
        Resource.Error(
            PlaybackResourceError(throwable.message ?: errorMessage)
        )
    }
}
