package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Permanently deletes a local M3U playlist from the user collection.
 *
 * @property playlistRepository Repository owning the M3U files.
 */
class DeletePlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    /**
     * @param playlistId Stable identifier of the playlist to remove.
     * @return Success when the playlist was deleted, or an error describing why it failed.
     */
    suspend operator fun invoke(playlistId: String): Resource<Unit> =
        playlistRepository.deletePlaylist(playlistId)
}
