package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.RecentlyPlayedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Builds a live personal most-played ranking for a supplied track collection.
 *
 * The repository determines ordering from persisted play counts; this use case
 * maps those stable IDs back to domain tracks without exposing database details
 * to presentation.
 *
 * @property recentlyPlayedRepository Repository managing local playback statistics.
 * @constructor Creates the ranking use case with its playback-history dependency.
 */
class ObserveMostPlayedTracksUseCase(
    private val recentlyPlayedRepository: RecentlyPlayedRepository
) {
    /**
     * Observes ranked tracks that have at least one recorded playback.
     *
     * @param tracks Candidate tracks, normally already filtered to one artist.
     * @param limit Maximum number of ranked tracks to emit; defaults to five.
     * @return Cold [Flow] emitting tracks in descending personal play-count order.
     */
    operator fun invoke(
        tracks: List<Track>,
        limit: Int = DEFAULT_LIMIT
    ): Flow<List<Track>> {
        if (tracks.isEmpty() || limit <= 0) return flowOf(emptyList())

        val tracksById = tracks.associateBy(Track::id)
        return recentlyPlayedRepository
            .observeMostPlayedTrackIds(tracksById.keys.toList(), limit)
            .map { rankedIds -> rankedIds.mapNotNull(tracksById::get) }
    }

    private companion object {
        /** Default size of the artist overview's personal ranking. */
        const val DEFAULT_LIMIT: Int = 5
    }
}
