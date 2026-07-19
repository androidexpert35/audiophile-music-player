package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.RecentlyPlayedRepository
import kotlinx.coroutines.flow.Flow

/**
 * Provides a live stream of recently-played track IDs, most-recent first.
 *
 * The stream emits immediately with the current history and then again on every
 * change (i.e. whenever a new track starts playing). The caller supplies a [limit]
 * so the list stays bounded and scroll performance is not degraded.
 *
 * @property recentlyPlayedRepository Repository managing the playback-history store.
 * @constructor Creates the use case with the required repository dependency.
 */
class ObserveRecentlyPlayedUseCase(
    private val recentlyPlayedRepository: RecentlyPlayedRepository
) {
    /**
     * @param limit Maximum number of distinct track IDs to include in each emission.
     *   Defaults to 200, which comfortably covers the visible library for most users
     *   without loading the full catalogue into memory.
     * @return Cold [Flow] emitting an ordered [List] of track IDs on every update.
     */
    operator fun invoke(limit: Int = 200): Flow<List<Long>> =
        recentlyPlayedRepository.observeRecentlyPlayedTrackIds(limit)
}

