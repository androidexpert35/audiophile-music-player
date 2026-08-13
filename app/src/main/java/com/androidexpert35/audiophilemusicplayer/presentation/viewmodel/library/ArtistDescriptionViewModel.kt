package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.model.track.containsNavigableArtist
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetAlbumsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistImageUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMostPlayedTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the artist description screen.
 *
 * Loads the full artist track list and album catalogue concurrently, partitions
 * tracks into a popular subset and a full queue, splits albums into primary
 * discography and featured-appearance collections, and lazily enriches the hero
 * section with a remote artist image fetched from the Deezer integration without
 * blocking the initial content render.
 *
 * @property getTracksUseCase Retrieves indexed tracks used to build artist queues.
 * @property getAlbumsUseCase Retrieves indexed albums used to derive the discography.
 * @property getArtistImageUseCase Fetches (or returns a cached) Deezer artist image URL.
 * @property observeMostPlayedTracksUseCase Observes the personal play-count ranking.
 * @property playTrackUseCase Starts playback for the selected or first-in-queue track.
 * @property observePlaybackStateUseCase Observes active playback for now-playing highlights.
 */
@HiltViewModel
class ArtistDescriptionViewModel @Inject constructor(
    private val getTracksUseCase: GetTracksUseCase,
    private val getAlbumsUseCase: GetAlbumsUseCase,
    private val getArtistImageUseCase: GetArtistImageUseCase,
    private val observeMostPlayedTracksUseCase: ObserveMostPlayedTracksUseCase,
    private val playTrackUseCase: PlayTrackUseCase,
    private val observePlaybackStateUseCase: ObservePlaybackStateUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<ArtistDescriptionUiModel, ArtistDescriptionUiEvent, ArtistDescriptionUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    /** Active Room ranking observation for the artist currently on screen. */
    private var mostPlayedTracksJob: Job? = null

    init {
        updateUiData(ArtistDescriptionUiModel())
        observePlaybackState()
    }

    override fun handleEvent(event: ArtistDescriptionUiEvent) {
        when (event) {
            is ArtistDescriptionUiEvent.Initialize -> initialize(event.artistName)
            is ArtistDescriptionUiEvent.PlayArtist -> playArtist()
            is ArtistDescriptionUiEvent.ShuffleArtist -> shuffleArtist()
            is ArtistDescriptionUiEvent.PlayTrack -> playTrack(event.track)
            is ArtistDescriptionUiEvent.ToggleFollow -> toggleFollow()
            is ArtistDescriptionUiEvent.OpenAlbum -> navigateToRoute(
                AppRoutes.albumOverviewRoute(event.albumId)
            )
            is ArtistDescriptionUiEvent.NavigateBack -> navigateUp()
        }
    }

    /**
     * Loads the selected artist only when the name changes or has not been loaded yet.
     *
     * @param artistName Normalised artist name from the navigation back-stack entry.
     */
    private fun initialize(artistName: String) {
        val normalizedName = artistName.trim()
        val currentName = uiState.value.data?.artistName
        if (currentName.equals(normalizedName, ignoreCase = true) &&
            uiState.value.data?.allTracks?.isNotEmpty() == true
        ) {
            return
        }
        loadArtistProfile(normalizedName)
    }

    /** Synchronises the visible track list with the currently playing track. */
    private fun observePlaybackState() {
        observePlaybackStateUseCase()
            .onEach { playbackState ->
                val model = uiState.value.data ?: return@onEach
                val playingId = playbackState.currentTrack
                    ?.takeIf { track -> containsNavigableArtist(track.artistName, model.artistName) }
                    ?.id
                updateUiData(model.copy(currentPlayingTrackId = playingId))
            }
            .launchIn(viewModelScope)
    }

    /**
     * Concurrently loads tracks and albums from the indexed library, derives the
     * popular-songs subset and the album partition, and builds the initial UI model.
     *
     * Both queries run inside a [coroutineScope] so cancellation of the outer
     * coroutine automatically cancels both in-flight MediaStore requests. After a
     * successful fetch a separate non-blocking coroutine enriches the hero section
     * with the remote Deezer artist image without blocking the initial content render.
     *
     * @param artistName Trimmed artist name to query.
     */
    private fun loadArtistProfile(artistName: String) {
        if (artistName.isBlank()) {
            handleError(Resource.Error(ResourceError.LogicError(LibraryStrings.artistDescriptionNotFoundMessage(artistName))))
            return
        }

        launchUiStateUpdate(
            retryAction = { loadArtistProfile(artistName) },
            dataFetchBlock = {
                // Both queries run concurrently inside a structured coroutineScope so
                // cancellation of the outer coroutine propagates to both async children.
                val (tracksResult, albumsResult) = coroutineScope {
                    val tracksDeferred = async { getTracksUseCase() }
                    val albumsDeferred = async { getAlbumsUseCase() }
                    tracksDeferred.await() to albumsDeferred.await()
                }

                if (tracksResult is Resource.Error) return@launchUiStateUpdate tracksResult
                if (albumsResult is Resource.Error) return@launchUiStateUpdate albumsResult

                val allTracks = (tracksResult as Resource.Success).data
                val allAlbums = (albumsResult as Resource.Success).data

                val artistTracks = allTracks
                    .filter { track -> containsNavigableArtist(track.artistName, artistName) }
                    .sortedWith(
                        compareBy<Track> { it.albumTitle.lowercase() }
                            .thenBy { it.discNumber }
                            .thenBy { track -> if (track.trackNumber > 0) track.trackNumber else Int.MAX_VALUE }
                            .thenBy { it.title.lowercase() }
                    )

                if (artistTracks.isEmpty()) {
                    return@launchUiStateUpdate Resource.Error(
                        ResourceError.LogicError(LibraryStrings.artistDescriptionNotFoundMessage(artistName))
                    )
                }

                Resource.Success(artistTracks to allAlbums)
            },
            processSuccess = { (artistTracks, allAlbums) ->
                buildUiModel(
                    artistName = artistName,
                    artistTracks = artistTracks,
                    allAlbums = allAlbums,
                    currentPlayingTrackId = uiState.value.data?.currentPlayingTrackId,
                    isFollowed = uiState.value.data?.isFollowed ?: false
                )
            },
            // Artist image is fetched after the main data is ready so the content
            // appears instantly and the hero image enriches itself independently.
            invokeOnCompletion = { success ->
                if (success) {
                    fetchArtistImage(artistName)
                    observeMostPlayedTracks(artistName)
                }
            }
        )
    }

    /**
     * Keeps the visible popular row synchronized with persisted personal play counts.
     *
     * Replacing the job on artist changes prevents a late Room emission from the
     * previous profile from overwriting the newly selected artist.
     *
     * @param artistName Artist identity associated with the current track collection.
     */
    private fun observeMostPlayedTracks(artistName: String) {
        val tracks = uiState.value.data
            ?.takeIf { model -> model.artistName.equals(artistName, ignoreCase = true) }
            ?.allTracks
            .orEmpty()

        mostPlayedTracksJob?.cancel()
        mostPlayedTracksJob = observeMostPlayedTracksUseCase(tracks)
            .onEach { rankedTracks ->
                val model = uiState.value.data ?: return@onEach
                if (!model.artistName.equals(artistName, ignoreCase = true)) return@onEach
                updateUiData(model.copy(popularTracks = rankedTracks))
            }
            .launchIn(viewModelScope)
    }

    /**
     * Fetches the remote Deezer artist image URL in a separate non-blocking coroutine
     * and patches only the [ArtistDescriptionUiModel.artistImageUrl] field, leaving
     * all other state unchanged.
     *
     * @param artistName Artist name used as the Deezer search query.
     */
    private fun fetchArtistImage(artistName: String) {
        viewModelScope.launch(exceptionHandler) {
            val result = getArtistImageUseCase(artistName)
            if (result is Resource.Success) {
                val model = uiState.value.data ?: return@launch
                // Ignore a late response when navigation has already reused this
                // ViewModel for another artist.
                if (!model.artistName.equals(artistName, ignoreCase = true)) return@launch
                updateUiData(model.copy(artistImageUrl = result.data))
            }
        }
    }

    /** Starts sequential playback from the first track in the artist's full queue. */
    private fun playArtist() {
        val firstTrack = uiState.value.data?.allTracks?.firstOrNull()
        if (firstTrack == null) {
            emitEffect(ArtistDescriptionUiEffect.PlaybackError(LibraryStrings.playbackEmptyQueue))
            return
        }
        playTrack(firstTrack)
    }

    /**
     * Shuffles the full artist queue and starts playback from the first shuffled track.
     *
     * Preserving the shuffled list as the active queue allows skip-next / skip-previous
     * to navigate the same randomised ordering in the transport controls.
     */
    private fun shuffleArtist() {
        val tracks = uiState.value.data?.allTracks.orEmpty()
        if (tracks.isEmpty()) {
            emitEffect(ArtistDescriptionUiEffect.PlaybackError(LibraryStrings.playbackEmptyQueue))
            return
        }

        val shuffledQueue = tracks.shuffled()
        val firstTrack = shuffledQueue.first()

        viewModelScope.launch(exceptionHandler) {
            playTrackUseCase(firstTrack, shuffledQueue).fold(
                onSuccess = {},
                onError = { error ->
                    emitEffect(
                        ArtistDescriptionUiEffect.PlaybackError(
                            error?.toUserMessage() ?: PlaybackStrings.shufflePlaybackFailed
                        )
                    )
                }
            )
        }
    }

    /**
     * Starts playback from the selected track within the full artist queue.
     *
     * @param track Track selected from the popular-songs row or any artist surface.
     */
    private fun playTrack(track: Track) {
        val queue = uiState.value.data?.allTracks.orEmpty()
        if (queue.isEmpty()) {
            emitEffect(ArtistDescriptionUiEffect.PlaybackError(LibraryStrings.playbackEmptyQueue))
            return
        }

        viewModelScope.launch(exceptionHandler) {
            playTrackUseCase(track, queue).fold(
                onSuccess = {},
                onError = { error ->
                    emitEffect(
                        ArtistDescriptionUiEffect.PlaybackError(
                            error?.toUserMessage() ?: PlaybackStrings.playbackFailed
                        )
                    )
                }
            )
        }
    }

    /**
     * Toggles the in-memory follow state for this artist.
     *
     * The follow state is not persisted beyond the ViewModel lifetime.
     */
    private fun toggleFollow() {
        val model = uiState.value.data ?: return
        updateUiData(model.copy(isFollowed = !model.isFollowed))
    }

    /**
     * Builds the full immutable artist description state from the filtered track list
     * and the full album catalogue.
     *
     * Audio quality stats are computed once via [computeAudioStats] to avoid iterating
     * the track list five separate times. The personal most-played ranking is attached
     * reactively after this base model is published.
     *
     * @param artistName Resolved artist name.
     * @param artistTracks All tracks whose artist credit includes [artistName].
     * @param allAlbums Full album catalogue from which primary and appearance albums are derived.
     * @param currentPlayingTrackId Identifier of the active playback track if it belongs to this artist.
     * @param isFollowed Previously persisted follow state carried over from the last model.
     * @return Constructed [ArtistDescriptionUiModel].
     */
    private fun buildUiModel(
        artistName: String,
        artistTracks: List<Track>,
        allAlbums: List<Album>,
        currentPlayingTrackId: Long?,
        isFollowed: Boolean
    ): ArtistDescriptionUiModel {
        val artistAlbumIds = artistTracks.map { it.albumId }.toSet()

        // Primary albums: albums where the album's own artist credit matches the target.
        val primaryAlbums = allAlbums
            .filter { album ->
                album.id in artistAlbumIds &&
                    containsNavigableArtist(album.artistName, artistName)
            }
            .sortedByDescending { it.year }

        // Appears-on albums: albums containing the artist as a featured credit but
        // whose primary artist is someone else.
        val appearsOnAlbums = allAlbums
            .filter { album ->
                album.id in artistAlbumIds &&
                    !containsNavigableArtist(album.artistName, artistName)
            }
            .sortedByDescending { it.year }

        // Compute all audio stats in one pass rather than five separate iterations.
        val stats = artistTracks.computeAudioStats()

        return ArtistDescriptionUiModel(
            artistName = artistName,
            artistImageUrl = uiState.value.data
                ?.takeIf { model ->
                    model.artistName.equals(artistName, ignoreCase = true)
                }
                ?.artistImageUrl,
            popularTracks = emptyList(),
            allTracks = artistTracks,
            albums = primaryAlbums,
            appearsOnAlbums = appearsOnAlbums,
            currentPlayingTrackId = currentPlayingTrackId?.takeIf { id ->
                artistTracks.any { it.id == id }
            },
            isFollowed = isFollowed,
            albumCount = primaryAlbums.size,
            totalDurationMs = artistTracks.sumOf { it.durationMs },
            losslessTrackCount = stats.losslessTrackCount,
            hiResTrackCount = stats.hiResTrackCount,
            maxSampleRateHz = stats.maxSampleRateHz,
            maxBitDepth = stats.maxBitDepth,
            codecSummary = stats.codecSummary
        )
    }

}
