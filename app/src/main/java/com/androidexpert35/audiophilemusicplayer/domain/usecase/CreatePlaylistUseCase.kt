package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.library.Playlist
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Creates an empty M3U playlist in the local user collection.
 *
 * @property playlistRepository Repository owning the M3U files.
 */
class CreatePlaylistUseCase(
    private val playlistRepository: PlaylistRepository
) {
    /**
     * @param name Name selected by the user.
     * @return The persisted playlist or an error describing why creation failed.
     */
    suspend operator fun invoke(name: String): Resource<Playlist> = playlistRepository.createPlaylist(name)
}
