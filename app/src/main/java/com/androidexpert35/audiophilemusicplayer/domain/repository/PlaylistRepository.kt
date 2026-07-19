package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.library.Playlist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.flow.Flow

/**
 * Provides access to the user's local M3U playlist collection.
 */
interface PlaylistRepository {

    /**
     * Observes the complete local playlist collection.
     *
     * @return A stream that emits the full M3U-backed collection whenever this app changes it.
     */
    fun observePlaylists(): Flow<List<Playlist>>

    /**
     * Creates an empty, locally stored M3U playlist.
     *
     * @param name Name selected by the user.
     * @return The new playlist, or a validation/storage error.
     */
    suspend fun createPlaylist(name: String): Resource<Playlist>

    /**
     * Appends a track to the selected M3U playlist.
     *
     * @param playlistId Stable identifier of the target playlist.
     * @param track Local track to append.
     * @return Success when the M3U file has been updated, or a validation/storage error.
     */
    suspend fun addTrack(playlistId: String, track: Track): Resource<Unit>

    /**
     * Appends an ordered group of tracks to the selected M3U playlist in one update.
     *
     * @param playlistId Stable identifier of the target playlist.
     * @param tracks Local tracks to append in their supplied order.
     * @return Success when every entry has been written, or a storage error.
     */
    suspend fun addTracks(playlistId: String, tracks: List<Track>): Resource<Unit>

    /**
     * Persists a new order for every entry in a local playlist.
     *
     * @param playlistId Stable identifier of the playlist to update.
     * @param trackUris Complete ordered URI list. It must contain exactly the playlist's
     *   current entries, including intentional duplicates.
     * @return Success when the M3U order has been atomically replaced, or a storage error.
     */
    suspend fun reorderTracks(playlistId: String, trackUris: List<String>): Resource<Unit>

    /**
     * Replaces a playlist's entries and their playback order.
     *
     * @param playlistId Stable identifier of the playlist to update.
     * @param trackUris Complete URI list after the listener's additions, removals, and reordering.
     * @return Success when the M3U contents have been replaced, or a storage error.
     */
    suspend fun replaceTracks(playlistId: String, trackUris: List<String>): Resource<Unit>
}
