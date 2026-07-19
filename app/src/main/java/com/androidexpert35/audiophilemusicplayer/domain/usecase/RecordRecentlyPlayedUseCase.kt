package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.RecentlyPlayedRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Records one distinct playback start for a track.
 *
 * Called by the app shell whenever [PlaybackState.currentTrack] changes to a new
 * track, ensuring the history is updated without requiring the library screen to be
 * active. The store keeps one row per track, refreshing its recency timestamp
 * and incrementing its personal play count.
 *
 * @property recentlyPlayedRepository Repository managing the playback-history store.
 * @constructor Creates the use case with the required repository dependency.
 */
class RecordRecentlyPlayedUseCase(
    private val recentlyPlayedRepository: RecentlyPlayedRepository
) {
    /**
     * Persists a play event for [trackId].
     *
     * @param trackId Stable MediaStore identifier of the track that started playing.
     * @return [Resource.Success] when the record is written,
     *         [Resource.Error] if the persistence layer rejects the write.
     */
    suspend operator fun invoke(trackId: Long): Resource<Unit> =
        recentlyPlayedRepository.recordPlayed(trackId)
}
