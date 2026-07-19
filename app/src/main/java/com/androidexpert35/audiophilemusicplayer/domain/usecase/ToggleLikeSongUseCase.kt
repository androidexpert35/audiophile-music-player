package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.LikedSongsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Toggles the liked status of a track.
 *
 * If the track is currently liked it will be unliked; if it is not liked it will
 * be added to the liked-songs collection. The change is persisted locally and
 * propagated to all observers via the repository's reactive stream.
 *
 * @property likedSongsRepository Repository that manages the liked-songs store.
 * @constructor Creates the use case with the required repository dependency.
 */
class ToggleLikeSongUseCase(
    private val likedSongsRepository: LikedSongsRepository
) {
    /**
     * Executes the toggle operation for the given track.
     *
     * @param trackId Stable MediaStore identifier of the track to like or unlike.
     * @return [Resource.Success] when the toggle succeeds,
     *         [Resource.Error] if the persistence layer rejects the write.
     */
    suspend operator fun invoke(trackId: Long): Resource<Unit> =
        likedSongsRepository.toggleLike(trackId)
}

