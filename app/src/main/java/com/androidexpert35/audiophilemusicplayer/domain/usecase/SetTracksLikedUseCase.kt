package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.LikedSongsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Applies one liked status to a complete track collection.
 *
 * @property likedSongsRepository Repository coordinating liked membership and favorites storage.
 */
class SetTracksLikedUseCase(
    private val likedSongsRepository: LikedSongsRepository
) {
    /**
     * Persists the requested membership for every supplied track.
     *
     * @param trackIds Stable MediaStore identifiers of the tracks to update.
     * @param isLiked Whether all supplied tracks should be liked or unliked.
     * @return Success after the coordinated update, or a persistence error.
     */
    suspend operator fun invoke(
        trackIds: List<Long>,
        isLiked: Boolean
    ): Resource<Unit> = likedSongsRepository.setTracksLiked(trackIds, isLiked)
}
