package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.library.Playlist
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow

/**
 * Streams the user's locally stored playlist collection.
 *
 * @property playlistRepository Repository owning the M3U files.
 */
class ObservePlaylistsUseCase(
    private val playlistRepository: PlaylistRepository
) {
    /**
     * @return A stream of every locally stored playlist.
     */
    operator fun invoke(): Flow<List<Playlist>> = playlistRepository.observePlaylists()
}
