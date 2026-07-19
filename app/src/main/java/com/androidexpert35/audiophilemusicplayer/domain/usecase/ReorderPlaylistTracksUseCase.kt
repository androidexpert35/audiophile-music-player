package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Saves the listener's chosen order for a local playlist.
 *
 * @property playlistRepository Repository owning the playlist's M3U file.
 */
class ReorderPlaylistTracksUseCase(
    private val playlistRepository: PlaylistRepository
) {
    /**
     * @param playlistId Stable file-backed identifier of the playlist.
     * @param trackUris Complete URI list in the desired playback order.
     * @return Success after the M3U file has been reordered, or an error on failure.
     */
    suspend operator fun invoke(playlistId: String, trackUris: List<String>): Resource<Unit> =
        playlistRepository.reorderTracks(playlistId, trackUris)
}
