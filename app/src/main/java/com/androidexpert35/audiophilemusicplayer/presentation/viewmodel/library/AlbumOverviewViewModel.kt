package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.model.track.sortedByAlbumOrder
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToPlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTracksToPlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTracksToQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetAlbumsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLikedSongIdsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaylistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTracksNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetTracksLikedUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.common.PlaybackStrings
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.common.computeAudioStats
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import com.tony.coreui.domain.resource.fold
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the album detail screen.
 *
 * Loads one album from the indexed library, prepares an ordered queue of album
 * tracks, and observes playback so the currently playing song can be
 * highlighted inside the album track list.
 *
 * @property getAlbumsUseCase Retrieves indexed albums.
 * @property getArtistsUseCase Retrieves cached artist profiles for the album header avatar.
 * @property getTracksUseCase Retrieves indexed tracks used to build the album queue.
 * @property playTrackUseCase Starts playback from a selected album track.
 * @property observePlaybackStateUseCase Observes the active playback snapshot for now-playing highlighting.
 * @property observeLikedSongIdsUseCase Streams persisted liked membership for album-heart state.
 * @property setTracksLikedUseCase Applies one liked status to every track in the album.
 * @property observePlaylistsUseCase Streams target playlists for the track action selector.
 * @property addTrackToPlaylistUseCase Appends a selected album track to an M3U playlist.
 * @property addTracksToPlaylistUseCase Appends the complete album to an M3U playlist in one update.
 * @property playNextUseCase Inserts an album track after the active queue item.
 * @property playTracksNextUseCase Inserts the ordered album after the active queue item.
 * @property addTrackToQueueUseCase Appends an album track to the active queue.
 * @property addTracksToQueueUseCase Appends the ordered album to the active queue.
 */
@HiltViewModel
class AlbumOverviewViewModel @Inject constructor(
    private val getAlbumsUseCase: GetAlbumsUseCase,
    private val getArtistsUseCase: GetArtistsUseCase,
    private val getTracksUseCase: GetTracksUseCase,
    private val playTrackUseCase: PlayTrackUseCase,
    private val observePlaybackStateUseCase: ObservePlaybackStateUseCase,
    private val observeLikedSongIdsUseCase: ObserveLikedSongIdsUseCase,
    private val setTracksLikedUseCase: SetTracksLikedUseCase,
    private val observePlaylistsUseCase: ObservePlaylistsUseCase,
    private val addTrackToPlaylistUseCase: AddTrackToPlaylistUseCase,
    private val addTracksToPlaylistUseCase: AddTracksToPlaylistUseCase,
    private val playNextUseCase: PlayNextUseCase,
    private val playTracksNextUseCase: PlayTracksNextUseCase,
    private val addTrackToQueueUseCase: AddTrackToQueueUseCase,
    private val addTracksToQueueUseCase: AddTracksToQueueUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<AlbumOverviewUiModel, AlbumOverviewUiEvent, AlbumOverviewUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    private var likedSongIds: Set<Long> = emptySet()

    init {
        updateUiData(AlbumOverviewUiModel())
        observePlaybackState()
        observeLikedSongs()
        observePlaylists()
    }

    override fun handleEvent(event: AlbumOverviewUiEvent) {
        when (event) {
            is AlbumOverviewUiEvent.Initialize -> initialize(event.albumId)
            is AlbumOverviewUiEvent.PlayAlbum -> playAlbum()
            is AlbumOverviewUiEvent.ShuffleAlbum -> shuffleAlbum()
            is AlbumOverviewUiEvent.PlayTrack -> playTrack(event.track)
            is AlbumOverviewUiEvent.PlayNext -> playNext(event.track)
            is AlbumOverviewUiEvent.AddToQueue -> addToQueue(event.track)
            is AlbumOverviewUiEvent.PlayAlbumNext -> playAlbumNext()
            is AlbumOverviewUiEvent.AddAlbumToQueue -> addAlbumToQueue()
            is AlbumOverviewUiEvent.ToggleLike -> toggleLike()
            is AlbumOverviewUiEvent.ShowTrackOptions -> showPlaylistPicker(event.track)
            is AlbumOverviewUiEvent.ShowAlbumPlaylistPicker -> showAlbumPlaylistPicker()
            is AlbumOverviewUiEvent.DismissPlaylistPicker -> dismissPlaylistPicker()
            is AlbumOverviewUiEvent.AddTrackToPlaylist -> addTrackToPlaylist(event.playlistId)
            is AlbumOverviewUiEvent.OpenArtist -> navigateToRoute(
                AppRoutes.artistDescriptionRoute(event.artistName)
            )
            is AlbumOverviewUiEvent.NavigateBack -> navigateUp()
        }
    }

    /** Opens the common playlist selector for the selected album track. */
    private fun showPlaylistPicker(track: Track) {
        val model = uiState.value.data ?: return
        updateUiData(model.copy(playlistPickerTracks = listOf(track)))
    }

    /** Opens the common playlist selector for every ordered track in this album. */
    private fun showAlbumPlaylistPicker() {
        val model = uiState.value.data ?: return
        if (model.tracks.isEmpty()) return
        updateUiData(model.copy(playlistPickerTracks = model.tracks))
    }

    /** Clears the album track currently awaiting playlist selection. */
    private fun dismissPlaylistPicker() {
        val model = uiState.value.data ?: return
        updateUiData(model.copy(playlistPickerTracks = emptyList()))
    }

    /** Appends the pending album track to the selected local M3U playlist. */
    private fun addTrackToPlaylist(playlistId: String) {
        val model = uiState.value.data ?: return
        val tracks = model.playlistPickerTracks
        if (tracks.isEmpty()) return

        viewModelScope.launch(exceptionHandler) {
            val result = if (tracks.size == 1) {
                addTrackToPlaylistUseCase(playlistId, tracks.single())
            } else {
                addTracksToPlaylistUseCase(playlistId, tracks)
            }
            result.fold(
                onSuccess = {
                    val playlistName = uiState.value.data?.playlists
                        ?.firstOrNull { playlist -> playlist.id == playlistId }
                        ?.name
                        .orEmpty()
                    dismissPlaylistPicker()
                    if (tracks.size == 1) {
                        emitEffect(
                            AlbumOverviewUiEffect.TrackAddedToPlaylist(
                                playlistName = playlistName,
                                trackTitle = tracks.single().title
                            )
                        )
                    } else {
                        emitEffect(
                            AlbumOverviewUiEffect.AlbumAddedToPlaylist(
                                playlistName = playlistName,
                                albumTitle = model.album?.title.orEmpty()
                            )
                        )
                    }
                },
                onError = { error ->
                    emitEffect(
                        AlbumOverviewUiEffect.PlaybackError(
                            error?.toUserMessage() ?: "Unable to update the playlist."
                        )
                    )
                }
            )
        }
    }

    /** Inserts the selected album track directly after the active playback item. */
    private fun playNext(track: Track) {
        updatePlaybackQueue(
            successMessage = resolveString(R.string.track_queued_next_success, track.title),
            command = { playNextUseCase(track) }
        )
    }

    /** Appends the selected album track to the end of the active playback queue. */
    private fun addToQueue(track: Track) {
        updatePlaybackQueue(
            successMessage = resolveString(R.string.track_added_to_queue_success, track.title),
            command = { addTrackToQueueUseCase(track) }
        )
    }

    /** Inserts the full ordered album immediately after the active queue item. */
    private fun playAlbumNext() {
        val model = uiState.value.data ?: return
        if (model.tracks.isEmpty()) return
        updatePlaybackQueue(
            successMessage = resolveString(
                R.string.album_queued_next_success,
                model.album?.title.orEmpty()
            ),
            command = { playTracksNextUseCase(model.tracks) }
        )
    }

    /** Appends the full ordered album to the end of the active playback queue. */
    private fun addAlbumToQueue() {
        val model = uiState.value.data ?: return
        if (model.tracks.isEmpty()) return
        updatePlaybackQueue(
            successMessage = resolveString(
                R.string.album_added_to_queue_success,
                model.album?.title.orEmpty()
            ),
            command = { addTracksToQueueUseCase(model.tracks) }
        )
    }

    /** Runs a queue mutation and maps its result into transient album feedback. */
    private fun updatePlaybackQueue(
        successMessage: String,
        command: suspend () -> Resource<Unit>
    ) {
        viewModelScope.launch(exceptionHandler) {
            command().fold(
                onSuccess = { emitEffect(AlbumOverviewUiEffect.QueueUpdated(successMessage)) },
                onError = { error ->
                    emitEffect(
                        AlbumOverviewUiEffect.PlaybackError(
                            error?.toUserMessage() ?: PlaybackStrings.playbackCommandFailed
                        )
                    )
                }
            )
        }
    }

    /** Keeps the picker list synchronized with every local M3U creation or update. */
    private fun observePlaylists() {
        observePlaylistsUseCase()
            .onEach { playlists ->
                val model = uiState.value.data ?: return@onEach
                updateUiData(
                    model.copy(
                        playlists = playlists.map { playlist ->
                            PlaylistUiModel(
                                id = playlist.id,
                                name = playlist.name,
                                trackCount = playlist.trackUris.size,
                                albumArtUris = emptyList(),
                                kind = playlist.kind
                            )
                        }
                    )
                )
            }
            .launchIn(viewModelScope)
    }

    /** Loads the selected album only when it changes or has not been loaded yet. */
    private fun initialize(albumId: Long) {
        val currentAlbumId = uiState.value.data?.album?.id
        if (currentAlbumId == albumId && uiState.value.data?.tracks?.isNotEmpty() == true) {
            return
        }
        loadAlbumOverview(albumId)
    }

    /** Keeps the album track list synchronized with the active playback item. */
    private fun observePlaybackState() {
        observePlaybackStateUseCase()
            .onEach { playbackState ->
                val model = uiState.value.data ?: return@onEach
                val playingTrackId = playbackState.currentTrack
                    ?.takeIf { track -> track.albumId == model.album?.id }
                    ?.id
                updateUiData(model.copy(currentPlayingTrackId = playingTrackId))
            }
            .launchIn(viewModelScope)
    }

    /** Keeps the album heart aligned with the persisted liked state of every album track. */
    private fun observeLikedSongs() {
        observeLikedSongIdsUseCase()
            .onEach { likedIds ->
                likedSongIds = likedIds
                val model = uiState.value.data ?: return@onEach
                updateUiData(model.copy(isLiked = model.tracks.areAllLiked(likedIds)))
            }
            .launchIn(viewModelScope)
    }

    /**
     * Loads album metadata and its ordered track list from the indexed library.
     *
     * Both queries run concurrently inside a [coroutineScope] so cancelling this
     * operation also cancels the in-flight MediaStore requests. The track list is
     * sorted using the canonical album order (disc → track number → title).
     *
     * @param albumId MediaStore album identifier to load.
     */
    private fun loadAlbumOverview(albumId: Long) {
        launchUiStateUpdate(
            retryAction = { loadAlbumOverview(albumId) },
            dataFetchBlock = {
                // Both queries run concurrently inside a structured coroutineScope so
                // cancellation of the outer coroutine propagates to both async children.
                val (albumsResult, artistsResult, tracksResult) = coroutineScope {
                    val albumsDeferred = async { getAlbumsUseCase() }
                    val artistsDeferred = async { getArtistsUseCase() }
                    val tracksDeferred = async { getTracksUseCase() }
                    Triple(
                        albumsDeferred.await(),
                        artistsDeferred.await(),
                        tracksDeferred.await()
                    )
                }

                if (albumsResult is Resource.Error) return@launchUiStateUpdate albumsResult
                if (tracksResult is Resource.Error) return@launchUiStateUpdate tracksResult

                val album = (albumsResult as Resource.Success).data.firstOrNull { it.id == albumId }
                    ?: return@launchUiStateUpdate Resource.Error(
                        ResourceError.LogicError(LibraryStrings.albumOverviewNotFoundMessage)
                    )

                val orderedTracks = (tracksResult as Resource.Success).data
                    .filter { track -> track.albumId == albumId }
                    .sortedByAlbumOrder()

                // Artist artwork is supplemental: a cached-profile lookup failure must
                // never prevent the local album from opening.
                val artistImageUrl = (artistsResult as? Resource.Success)
                    ?.data
                    ?.firstOrNull { artist ->
                        artist.name.equals(album.artistName, ignoreCase = true)
                    }
                    ?.imageUrl

                Resource.Success(Triple(album, orderedTracks, artistImageUrl))
            },
            processSuccess = { (album, tracks, artistImageUrl) ->
                buildUiModel(
                    album = album,
                    tracks = tracks,
                    artistImageUrl = artistImageUrl,
                    currentPlayingTrackId = uiState.value.data?.currentPlayingTrackId
                )
            }
        )
    }

    /** Starts album playback from the first available track in the album queue. */
    private fun playAlbum() {
        val firstTrack = uiState.value.data?.tracks?.firstOrNull()
        if (firstTrack == null) {
            emitEffect(AlbumOverviewUiEffect.PlaybackError(LibraryStrings.playbackEmptyQueue))
            return
        }
        playTrack(firstTrack)
    }

    /**
     * Shuffles the full album queue and starts playback from the first shuffled track.
     *
     * Preserving the shuffled list as the active queue allows skip-next / skip-previous
     * to navigate the same randomised ordering in the transport controls.
     */
    private fun shuffleAlbum() {
        val tracks = uiState.value.data?.tracks.orEmpty()
        if (tracks.isEmpty()) {
            emitEffect(AlbumOverviewUiEffect.PlaybackError(LibraryStrings.playbackEmptyQueue))
            return
        }

        val shuffledQueue = tracks.shuffled()
        val firstTrack = shuffledQueue.first()

        viewModelScope.launch(exceptionHandler) {
            playTrackUseCase(firstTrack, shuffledQueue).fold(
                onSuccess = {},
                onError = { error ->
                    emitEffect(
                        AlbumOverviewUiEffect.PlaybackError(
                            error?.toUserMessage() ?: PlaybackStrings.shufflePlaybackFailed
                        )
                    )
                }
            )
        }
    }

    /** Starts playback from the selected track within the current album queue. */
    private fun playTrack(track: Track) {
        val queue = uiState.value.data?.tracks.orEmpty()
        if (queue.isEmpty()) {
            emitEffect(AlbumOverviewUiEffect.PlaybackError(LibraryStrings.playbackEmptyQueue))
            return
        }

        viewModelScope.launch(exceptionHandler) {
            playTrackUseCase(track, queue).fold(
                onSuccess = {},
                onError = { error ->
                    emitEffect(
                        AlbumOverviewUiEffect.PlaybackError(
                            error?.toUserMessage() ?: PlaybackStrings.playbackFailed
                        )
                    )
                }
            )
        }
    }

    /** Sets every album track to the same persisted liked status. */
    private fun toggleLike() {
        val model = uiState.value.data ?: return
        if (model.tracks.isEmpty()) return

        val shouldBeLiked = !model.isLiked
        val trackIds = model.tracks.map(Track::id)
        viewModelScope.launch(exceptionHandler) {
            setTracksLikedUseCase(trackIds, shouldBeLiked).fold(
                onSuccess = {
                    likedSongIds = if (shouldBeLiked) {
                        likedSongIds + trackIds
                    } else {
                        likedSongIds - trackIds.toSet()
                    }
                    val current = uiState.value.data ?: return@fold
                    updateUiData(current.copy(isLiked = current.tracks.areAllLiked(likedSongIds)))
                },
                onError = { error ->
                    emitEffect(
                        AlbumOverviewUiEffect.PlaybackError(
                            error?.toUserMessage() ?: resolveString(R.string.liked_songs_update_failed)
                        )
                    )
                }
            )
        }
    }

    /**
     * Builds the full immutable album overview state from album and track data.
     *
     * Audio quality stats are computed once via [computeAudioStats] and destructured
     * into the individual model properties, avoiding repeated iteration over the
     * track list.
     *
     * @param album Selected album metadata.
     * @param tracks Ordered list of tracks belonging to the album.
     * @param artistImageUrl Cached profile image associated with [album]'s artist.
     * @param currentPlayingTrackId Identifier of the active playback track, if it belongs to this album.
     * @return Constructed [AlbumOverviewUiModel].
     */
    private fun buildUiModel(
        album: Album,
        tracks: List<Track>,
        artistImageUrl: String?,
        currentPlayingTrackId: Long?
    ): AlbumOverviewUiModel {
        val stats = tracks.computeAudioStats()

        return AlbumOverviewUiModel(
            album = album,
            tracks = tracks,
            currentPlayingTrackId = currentPlayingTrackId?.takeIf { id -> tracks.any { it.id == id } },
            isLiked = tracks.areAllLiked(likedSongIds),
            totalDurationMs = tracks.sumOf { it.durationMs },
            totalSizeBytes = tracks.sumOf { it.fileSizeBytes },
            discCount = tracks.maxOfOrNull { it.discNumber.coerceAtLeast(1) } ?: 0,
            losslessTrackCount = stats.losslessTrackCount,
            hiResTrackCount = stats.hiResTrackCount,
            maxSampleRateHz = stats.maxSampleRateHz,
            maxBitDepth = stats.maxBitDepth,
            codecSummary = stats.codecSummary,
            artistImageUrl = artistImageUrl,
            playlists = uiState.value.data?.playlists.orEmpty()
        )
    }
}

/** Returns true only when a non-empty album is represented entirely in liked songs. */
private fun List<Track>.areAllLiked(likedSongIds: Set<Long>): Boolean =
    isNotEmpty() && all { track -> track.id in likedSongIds }
