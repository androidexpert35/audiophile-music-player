package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Appends an ordered track collection to one local M3U playlist.
 *
 * @property playlistRepository Repository owning ordered playlist writes.
 */
class AddTracksToPlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    /**
     * @param playlistId Stable identifier of the destination playlist.
     * @param tracks Ordered tracks to append.
     * @return Success after writing every entry, or a storage error.
     */
    suspend operator fun invoke(
        playlistId: String,
        tracks: List<Track>
    ): Resource<Unit> = playlistRepository.addTracks(playlistId, tracks)
}
