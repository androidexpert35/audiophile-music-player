package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.LikedSongsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Provides a live stream of liked track IDs.
 *
 * Emits the complete set of liked track IDs immediately and on every subsequent
 * change, so the library and player screens can keep heart icons in sync without
 * polling. The returned [Flow] is cold and respects the collector's lifecycle.
 *
 * @property likedSongsRepository Repository that manages the liked-songs store.
 * @constructor Creates the use case with the required repository dependency.
 */
class ObserveLikedSongIdsUseCase(
    private val likedSongsRepository: LikedSongsRepository
) {
    /**
     * @return Cold [Flow] emitting the full [Set] of liked track IDs on every update.
     */
    operator fun invoke(): Flow<Set<Long>> =
        likedSongsRepository.observeLikedSongIds()
}

