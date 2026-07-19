package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Appends one local track to a selected M3U playlist.
 *
 * @property playlistRepository Repository owning the M3U files.
 */
class AddTrackToPlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    /**
     * @param playlistId Stable file-backed playlist identifier.
     * @param track Track to append to the playlist.
     * @return Success after the file write, or an error if the write was not possible.
     */
    suspend operator fun invoke(playlistId: String, track: Track): Resource<Unit> =
        playlistRepository.addTrack(playlistId, track)
}
