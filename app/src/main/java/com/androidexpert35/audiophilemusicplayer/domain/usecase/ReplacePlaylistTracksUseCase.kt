package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Saves a playlist after the listener changes its membership or order.
 *
 * @property playlistRepository Repository owning the local M3U file.
 */
class ReplacePlaylistTracksUseCase(
    private val playlistRepository: PlaylistRepository
) {
    /**
     * @param playlistId Stable M3U filename identifier.
     * @param trackUris Complete desired playlist contents in playback order.
     * @return Success after replacing the M3U entries, or an error on failure.
     */
    suspend operator fun invoke(playlistId: String, trackUris: List<String>): Resource<Unit> =
        playlistRepository.replaceTracks(playlistId, trackUris)
}
