package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.model.library.Playlist
import com.androidexpert35.audiophilemusicplayer.domain.model.library.PlaylistKind
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.model.track.isUnknownArtistName
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.DeletePlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaylistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ReplacePlaylistTracksUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.common.PlaybackStrings
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.fold
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordinates filtered playback and ordering for a local M3U playlist.
 *
 * @property getTracksUseCase Retrieves the local media index used to resolve M3U URIs.
 * @property observePlaylistsUseCase Streams the file-backed playlist collection.
 * @property observePlaybackStateUseCase Streams the active song for row highlighting.
 * @property playTrackUseCase Starts a playlist queue at a selected song.
 * @property replacePlaylistTracksUseCase Persists confirmed membership and order changes.
 * @property playNextUseCase Inserts a playlist track after the active queue item.
 * @property addTrackToQueueUseCase Appends a playlist track to the active queue.
 * @property deletePlaylistUseCase Permanently removes the local playlist and its M3U file.
 */
@HiltViewModel
class PlaylistOverviewViewModel @Inject constructor(
    private val getTracksUseCase: GetTracksUseCase,
    private val observePlaylistsUseCase: ObservePlaylistsUseCase,
    private val observePlaybackStateUseCase: ObservePlaybackStateUseCase,
    private val playTrackUseCase: PlayTrackUseCase,
    private val replacePlaylistTracksUseCase: ReplacePlaylistTracksUseCase,
    private val playNextUseCase: PlayNextUseCase,
    private val addTrackToQueueUseCase: AddTrackToQueueUseCase,
    private val deletePlaylistUseCase: DeletePlaylistUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<PlaylistOverviewUiModel, PlaylistOverviewUiEvent, PlaylistOverviewUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    private var playlists: List<Playlist> = emptyList()
    private var indexedTracks: List<Track> = emptyList()
    private var selectedPlaylistId: String? = null

    init {
        updateUiData(PlaylistOverviewUiModel())
        observePlaylists()
        observePlaybackState()
    }

    override fun handleEvent(event: PlaylistOverviewUiEvent) = when (event) {
        is PlaylistOverviewUiEvent.Initialize -> initialize(event.playlistId)
        is PlaylistOverviewUiEvent.UpdateSearchQuery -> updateSearchQuery(event.query)
        is PlaylistOverviewUiEvent.SetSearchActive -> setSearchActive(event.isActive)
        PlaylistOverviewUiEvent.CloseSearch -> closeSearch()
        PlaylistOverviewUiEvent.PlayPlaylist -> playPlaylist()
        PlaylistOverviewUiEvent.ShufflePlaylist -> shufflePlaylist()
        is PlaylistOverviewUiEvent.PlayTrack -> playTrack(event.track)
        is PlaylistOverviewUiEvent.PlayNext -> playNext(event.track)
        is PlaylistOverviewUiEvent.AddToQueue -> addToQueue(event.track)
        is PlaylistOverviewUiEvent.OpenTrackAlbum -> openTrackAlbum(event.track)
        is PlaylistOverviewUiEvent.OpenTrackArtist -> openTrackArtist(event.track)
        PlaylistOverviewUiEvent.ToggleEditing -> toggleEditingOrSave()
        is PlaylistOverviewUiEvent.MoveTrack -> moveTrack(event.fromIndex, event.toIndex)
        is PlaylistOverviewUiEvent.RemoveTrack -> removeTrack(event.index)
        PlaylistOverviewUiEvent.NavigateBack -> navigateUp()
        PlaylistOverviewUiEvent.ShowDeletePlaylistDialog -> updateDeletePlaylistDialogVisibility(true)
        PlaylistOverviewUiEvent.DismissDeletePlaylistDialog -> updateDeletePlaylistDialogVisibility(false)
        PlaylistOverviewUiEvent.ConfirmDeletePlaylist -> deletePlaylist()
    }

    /** Starts loading the selected playlist and resolves its M3U entries against the local index. */
    private fun initialize(playlistId: String) {
        if (selectedPlaylistId == playlistId && indexedTracks.isNotEmpty()) return
        selectedPlaylistId = playlistId
        launchUiStateUpdate(
            retryAction = { initialize(playlistId) },
            dataFetchBlock = { getTracksUseCase() },
            processSuccess = { tracks ->
                indexedTracks = tracks
                buildModel(playlistId = playlistId, previous = uiState.value.data)
            }
        )
    }

    /** Keeps playlist metadata and membership synchronized after any M3U write. */
    private fun observePlaylists() {
        observePlaylistsUseCase()
            .onEach { updatedPlaylists ->
                playlists = updatedPlaylists
                selectedPlaylistId?.let { playlistId ->
                    updateUiData(buildModel(playlistId, uiState.value.data))
                }
            }
            .launchIn(viewModelScope)
    }

    /** Updates the now-playing indicator only when the active track belongs to this playlist. */
    private fun observePlaybackState() {
        observePlaybackStateUseCase()
            .onEach { state ->
                val model = uiState.value.data ?: return@onEach
                val playingId = state.currentTrack?.id?.takeIf { id -> model.tracks.any { it.id == id } }
                updateUiData(model.copy(currentPlayingTrackId = playingId))
            }
            .launchIn(viewModelScope)
    }

    /** Stores the local filter without re-querying the complete library. */
    private fun updateSearchQuery(query: String) {
        val model = uiState.value.data ?: return
        updateUiData(model.copy(searchQuery = query, isSearchActive = resolveSearchActive(model.isSearchActive, query)))
    }

    /** Keeps search results isolated from playlist overview content while the field is focused. */
    private fun setSearchActive(isActive: Boolean) {
        val model = uiState.value.data ?: return
        val shouldRemainActive = resolveSearchActive(isActive, model.searchQuery)
        if (model.isSearchActive != shouldRemainActive) {
            updateUiData(model.copy(isSearchActive = shouldRemainActive))
        }
    }

    /** Restores the complete playlist page after the listener dismisses its search. */
    private fun closeSearch() {
        val model = uiState.value.data ?: return
        updateUiData(model.copy(searchQuery = "", isSearchActive = false))
    }

    /** Enters manual ordering or saves it when the listener confirms with the check button. */
    private fun toggleEditingOrSave() {
        val model = uiState.value.data ?: return
        if (model.isEditing) {
            commitTrackOrder(model)
            return
        }
        if (model.unavailableTrackCount > 0) {
            emitEffect(PlaylistOverviewUiEffect.Message("Unavailable tracks must be removed before reordering."))
            return
        }
        updateUiData(model.copy(isEditing = true, searchQuery = "", isSearchActive = false))
    }

    /** Applies a valid move immediately so the list follows the listener's drag gesture. */
    private fun moveTrack(fromIndex: Int, toIndex: Int) {
        val model = uiState.value.data ?: return
        if (!model.isEditing || fromIndex !in model.tracks.indices || toIndex !in model.tracks.indices) return
        val reordered = model.tracks.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        updateUiData(model.copy(tracks = reordered))
    }

    /** Removes the selected entry locally; the check button remains the explicit save action. */
    private fun removeTrack(index: Int) {
        val model = uiState.value.data ?: return
        if (!model.isEditing || index !in model.tracks.indices) return
        val updatedTracks = model.tracks.toMutableList().apply { removeAt(index) }
        updateUiData(
            model.copy(
                tracks = updatedTracks,
                trackCount = (model.trackCount - 1).coerceAtLeast(0),
                albumArtUris = updatedTracks
                    .asReversed()
                    .mapNotNull(Track::artUri)
                    .take(MAX_PLAYLIST_ARTWORKS)
            )
        )
    }

    /** Persists the final order only when the listener explicitly confirms the edit session. */
    private fun commitTrackOrder(model: PlaylistOverviewUiModel) {
        if (!model.isEditing || model.unavailableTrackCount > 0) return
        viewModelScope.launch(exceptionHandler) {
            replacePlaylistTracksUseCase(model.playlistId, model.tracks.map(Track::uri)).fold(
                onSuccess = {
                    updateUiData(model.copy(isEditing = false))
                    emitEffect(PlaylistOverviewUiEffect.PlaylistUpdated)
                },
                onError = { error ->
                    emitEffect(PlaylistOverviewUiEffect.Message(
                        error?.toUserMessage() ?: "Unable to save the playlist order."
                    ))
                }
            )
        }
    }

    /** Keeps the delete-confirmation dialog state in the screen's immutable UDF model. */
    private fun updateDeletePlaylistDialogVisibility(isVisible: Boolean) {
        val model = uiState.value.data ?: return
        updateUiData(model.copy(isDeletePlaylistDialogVisible = isVisible))
    }

    /** Permanently removes the playlist and returns to the previous destination on success. */
    private fun deletePlaylist() {
        val model = uiState.value.data ?: return
        viewModelScope.launch(exceptionHandler) {
            deletePlaylistUseCase(model.playlistId).fold(
                onSuccess = {
                    updateUiData(model.copy(isDeletePlaylistDialogVisible = false))
                    emitEffect(PlaylistOverviewUiEffect.PlaylistDeleted)
                    navigateUp()
                },
                onError = { error ->
                    updateUiData(model.copy(isDeletePlaylistDialogVisible = false))
                    emitEffect(PlaylistOverviewUiEffect.Message(
                        error?.toUserMessage() ?: "Unable to delete the playlist."
                    ))
                }
            )
        }
    }

    /** Starts sequential playback using the visible playlist's persisted order. */
    private fun playPlaylist() {
        uiState.value.data?.tracks?.firstOrNull()?.let(::playTrack)
            ?: emitEffect(PlaylistOverviewUiEffect.Message("This playlist has no playable tracks."))
    }

    /** Starts a shuffled copy of the playlist while keeping its saved order untouched. */
    private fun shufflePlaylist() {
        val queue = uiState.value.data?.tracks.orEmpty().shuffled()
        val firstTrack = queue.firstOrNull()
            ?: return emitEffect(PlaylistOverviewUiEffect.Message("This playlist has no playable tracks."))
        viewModelScope.launch(exceptionHandler) {
            playTrackUseCase(firstTrack, queue).fold(
                onSuccess = {},
                onError = { error -> emitEffect(PlaylistOverviewUiEffect.Message(
                    error?.toUserMessage() ?: PlaybackStrings.shufflePlaybackFailed
                )) }
            )
        }
    }

    /** Starts a selected song with the complete ordered playlist as its queue. */
    private fun playTrack(track: Track) {
        val queue = uiState.value.data?.tracks.orEmpty()
        if (queue.isEmpty()) return
        viewModelScope.launch(exceptionHandler) {
            playTrackUseCase(track, queue).fold(
                onSuccess = {},
                onError = { error -> emitEffect(PlaylistOverviewUiEffect.Message(
                    error?.toUserMessage() ?: PlaybackStrings.playbackFailed
                )) }
            )
        }
    }

    /** Inserts the selected playlist track directly after the active playback item. */
    private fun playNext(track: Track) {
        updatePlaybackQueue(
            successMessage = resolveString(R.string.track_queued_next_success, track.title),
            command = { playNextUseCase(track) }
        )
    }

    /** Appends the selected playlist track to the end of the active playback queue. */
    private fun addToQueue(track: Track) {
        updatePlaybackQueue(
            successMessage = resolveString(R.string.track_added_to_queue_success, track.title),
            command = { addTrackToQueueUseCase(track) }
        )
    }

    /** Opens the album overview screen for the given track's album, if it has one. */
    private fun openTrackAlbum(track: Track) {
        if (track.albumId == 0L) return
        navigateToRoute(AppRoutes.albumOverviewRoute(track.albumId))
    }

    /** Opens the artist profile screen for the given track's artist, if it has one. */
    private fun openTrackArtist(track: Track) {
        if (track.artistName.isUnknownArtistName()) return
        navigateToRoute(AppRoutes.artistDescriptionRoute(track.artistName.trim()))
    }

    /** Runs a queue mutation and maps its result into playlist-screen feedback. */
    private fun updatePlaybackQueue(
        successMessage: String,
        command: suspend () -> com.tony.coreui.domain.resource.Resource<Unit>
    ) {
        viewModelScope.launch(exceptionHandler) {
            command().fold(
                onSuccess = { emitEffect(PlaylistOverviewUiEffect.Message(successMessage)) },
                onError = { error ->
                    emitEffect(
                        PlaylistOverviewUiEffect.Message(
                            error?.toUserMessage() ?: PlaybackStrings.playbackCommandFailed
                        )
                    )
                }
            )
        }
    }

    /** Builds a presentation snapshot while preserving local filter and edit state. */
    private fun buildModel(playlistId: String, previous: PlaylistOverviewUiModel?): PlaylistOverviewUiModel {
        val playlist = playlists.firstOrNull { it.id == playlistId }
        val tracksByUri = indexedTracks.associateBy(Track::uri)
        val tracks = playlist?.trackUris?.mapNotNull(tracksByUri::get).orEmpty()
        return PlaylistOverviewUiModel(
            playlistId = playlistId,
            playlistName = playlist?.name.orEmpty(),
            playlistKind = playlist?.kind ?: PlaylistKind.STANDARD,
            albumArtUris = playlist?.trackUris
                ?.asReversed()
                ?.mapNotNull { uri -> tracksByUri[uri]?.artUri }
                ?.take(MAX_PLAYLIST_ARTWORKS)
                .orEmpty(),
            tracks = tracks,
            trackCount = playlist?.trackUris?.size ?: 0,
            unavailableTrackCount = (playlist?.trackUris?.size ?: 0) - tracks.size,
            searchQuery = previous?.searchQuery.orEmpty(),
            isSearchActive = resolveSearchActive(previous?.isSearchActive == true, previous?.searchQuery.orEmpty()),
            isEditing = previous?.isEditing == true,
            currentPlayingTrackId = previous?.currentPlayingTrackId?.takeIf { id -> tracks.any { it.id == id } }
        )
    }

    /** Search stays active once explicitly opened or as long as a non-blank query exists. */
    private fun resolveSearchActive(explicitActive: Boolean, query: String) =
        explicitActive || query.isNotBlank()

    private companion object {
        const val MAX_PLAYLIST_ARTWORKS = 4
    }
}
