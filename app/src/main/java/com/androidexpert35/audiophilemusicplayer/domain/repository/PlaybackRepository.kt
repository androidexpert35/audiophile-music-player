package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the audio playback engine.
 *
 * All commands are fire-and-forget (suspend for potential async bridging).
 * State is observed reactively via [Flow] emissions.
 */
interface PlaybackRepository {

    /**
     * Starts playback of [track] within the provided [queue].
     *
     * @param track The track to begin playing.
     * @param queue Full ordered list of tracks forming the playback queue.
     * @return [Resource.Success] on successful start, [Resource.Error] on failure.
     */
    suspend fun play(track: Track, queue: List<Track>): Resource<Unit>

    /**
     * Inserts [track] immediately after the active queue item.
     *
     * @param track Track that should play after the current item.
     * @return [Resource.Success] when the queue accepts the track, or [Resource.Error] on failure.
     */
    suspend fun playNext(track: Track): Resource<Unit>

    /**
     * Inserts an ordered group of tracks immediately after the active queue item.
     *
     * @param tracks Tracks to insert while preserving their supplied order.
     * @return [Resource.Success] when the queue accepts the complete group, or
     *   [Resource.Error] on failure.
     */
    suspend fun playNext(tracks: List<Track>): Resource<Unit>

    /**
     * Appends [track] to the end of the active playback queue.
     *
     * @param track Track that should become the final queue item.
     * @return [Resource.Success] when the queue accepts the track, or [Resource.Error] on failure.
     */
    suspend fun addToQueue(track: Track): Resource<Unit>

    /**
     * Appends an ordered group of tracks to the active playback queue.
     *
     * @param tracks Tracks that should become the final queue items.
     * @return [Resource.Success] when the queue accepts the complete group, or
     *   [Resource.Error] on failure.
     */
    suspend fun addToQueue(tracks: List<Track>): Resource<Unit>

    /**
     * Moves one item within the active playback queue without restarting playback.
     *
     * @param fromIndex Current zero-based queue position.
     * @param toIndex Target zero-based queue position.
     * @return [Resource.Success] when the queue order changes, or [Resource.Error] on failure.
     */
    suspend fun moveQueueItem(fromIndex: Int, toIndex: Int): Resource<Unit>

    /**
     * Removes every queued item except the currently playing track.
     *
     * The cleared queue must not be restored during the next app launch.
     *
     * @return [Resource.Success] when only the active track remains in the queue and its
     *   restorable session state is updated,
     *   or [Resource.Error] when the playback service cannot apply the change.
     */
    suspend fun clearQueue(): Resource<Unit>

    /**
     * Pauses the currently playing track.
     *
     * @return [Resource.Success] on success, [Resource.Error] if no media is loaded.
     */
    suspend fun pause(): Resource<Unit>

    /**
     * Pauses playback and releases exclusive USB audio ownership while keeping
     * the current queue and playhead available for a later resume.
     *
     * @return [Resource.Success] only after the playback service completes the
     *   output teardown, or [Resource.Error] when release fails.
     */
    suspend fun releaseUsbAudio(): Resource<Unit>

    /**
     * Resumes playback from the paused position.
     *
     * @return [Resource.Success] on success, [Resource.Error] if no media is loaded.
     */
    suspend fun resume(): Resource<Unit>

    /**
     * Seeks to the specified position within the current track.
     *
     * @param positionMs Target position in milliseconds.
     * @return [Resource.Success] on success, [Resource.Error] if out of range.
     */
    suspend fun seekTo(positionMs: Long): Resource<Unit>

    /**
     * Advances to the next track in the queue.
     *
     * @return [Resource.Success] on success, [Resource.Error] if at queue end
     *         and repeat is off.
     */
    suspend fun skipNext(): Resource<Unit>

    /**
     * Returns to the previous track in the queue.
     *
     * @return [Resource.Success] on success, [Resource.Error] if at queue start.
     */
    suspend fun skipPrevious(): Resource<Unit>

    /**
     * Sets the repeat mode for the current playback session.
     *
     * @param mode The desired [RepeatMode].
     */
    suspend fun setRepeatMode(mode: RepeatMode)

    /**
     * Sets the shuffle mode for the current playback session.
     *
     * @param mode The desired [ShuffleMode].
     */
    suspend fun setShuffleMode(mode: ShuffleMode)

    /**
     * Observes the current playback state as a reactive stream.
     *
     * @return A [Flow] emitting [PlaybackState] snapshots on every state change.
     */
    fun observePlaybackState(): Flow<PlaybackState>

    /**
     * Observes the current queue state as a reactive stream.
     *
     * @return A [Flow] emitting [QueueState] snapshots on every queue change.
     */
    fun observeQueueState(): Flow<QueueState>
}
