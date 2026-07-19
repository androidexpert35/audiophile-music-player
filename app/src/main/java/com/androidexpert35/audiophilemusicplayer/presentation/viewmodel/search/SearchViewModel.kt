package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.search

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.model.track.isUnknownArtistName
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetAlbumsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistImageUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay.PlayerOverlayManager
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.common.PlaybackStrings
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.fold
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the local-library search screen.
 *
 * Loads the full track, album, and artist catalogues into memory once on
 * initialisation and then filters them instantly (with a 120 ms debounce) as
 * the user types. This keeps search results responsive without issuing a
 * database round-trip for every keystroke.
 *
 * @property getTracksUseCase Retrieves all indexed audio tracks.
 * @property getAlbumsUseCase Retrieves all indexed albums.
 * @property getArtistsUseCase Retrieves all indexed artists.
 * @property getArtistImageUseCase Resolves and caches images for visible artist results.
 * @property playTrackUseCase Starts playback of a selected track within a queue.
 * @constructor Creates the ViewModel with required domain use cases and navigation manager.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val getTracksUseCase: GetTracksUseCase,
    private val getAlbumsUseCase: GetAlbumsUseCase,
    private val getArtistsUseCase: GetArtistsUseCase,
    private val getArtistImageUseCase: GetArtistImageUseCase,
    private val playTrackUseCase: PlayTrackUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper,
    private val playerOverlayManager: PlayerOverlayManager
) : BaseViewModel<SearchUiModel, SearchUiEvent, SearchUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    // Full catalogue cached after the initial DB load; never exposed to the UI directly.
    private var allTracks: List<Track> = emptyList()
    private var allAlbums: List<Album> = emptyList()
    private var allArtists: List<Artist> = emptyList()

    private val pendingArtistImageRequests = mutableSetOf<String>()

    // Backing state for the query; debounced before filtering to avoid excessive work.
    private val queryFlow = MutableStateFlow("")

    init {
        // Render the idle state immediately so the screen is never blank on entry.
        updateUiData(SearchUiModel())
        loadLibraryData()
        observeQuery()
    }

    override fun handleEvent(event: SearchUiEvent) {
        when (event) {
            is SearchUiEvent.QueryChanged -> {
                // Immediately update the displayed query so the OutlinedTextField shows
                // typed characters without waiting for the debounce pipeline to complete.
                // The queryFlow drives the debounced filter-only work.
                setSuccessState(currentModel().copy(query = event.query))
                queryFlow.value = event.query
            }
            is SearchUiEvent.ClearSearch -> clearSearch()
            is SearchUiEvent.LoadArtistImage -> loadArtistImage(event.artist)
            is SearchUiEvent.PlayTrack -> playTrack(event.track)
            is SearchUiEvent.OpenAlbumOverview -> navigateToRoute(
                AppRoutes.albumOverviewRoute(event.album.id)
            )
            is SearchUiEvent.OpenArtistDescription -> navigateToRoute(
                AppRoutes.artistDescriptionRoute(event.artist.name)
            )
        }
    }

    /**
     * Resolves artwork for one visible artist result and refreshes the active filter.
     *
     * @param artist Search result currently showing the profile placeholder.
     */
    private fun loadArtistImage(artist: Artist) {
        if (!artist.imageUrl.isNullOrBlank() || artist.name.isUnknownArtistName()) return

        val requestKey = artist.name.trim().lowercase(Locale.ROOT)
        if (requestKey.isBlank() || !pendingArtistImageRequests.add(requestKey)) return

        viewModelScope.launch(exceptionHandler) {
            try {
                val result = getArtistImageUseCase(artist.name)
                if (result is Resource.Success) {
                    allArtists = allArtists.map { candidate ->
                        if (candidate.id == artist.id) {
                            candidate.copy(imageUrl = result.data)
                        } else {
                            candidate
                        }
                    }
                    setSuccessState(applyFilter(currentModel(), queryFlow.value))
                }
            } finally {
                pendingArtistImageRequests.remove(requestKey)
            }
        }
    }

    /**
     * Loads tracks, albums, and artists concurrently from the Room index.
     *
     * The three queries run in parallel so the cold-start time is bounded by the
     * slowest query rather than their sum. Once all data is in memory, any query
     * that was already typed is applied immediately.
     */
    private fun loadLibraryData() {
        viewModelScope.launch(exceptionHandler) {
            coroutineScope {
                val tracksDeferred = async { getTracksUseCase() }
                val albumsDeferred = async { getAlbumsUseCase() }
                val artistsDeferred = async { getArtistsUseCase() }

                val tracksResult = tracksDeferred.await()
                val albumsResult = albumsDeferred.await()
                val artistsResult = artistsDeferred.await()

                if (tracksResult is Resource.Success) allTracks = tracksResult.data
                if (albumsResult is Resource.Success) allAlbums = albumsResult.data
                if (artistsResult is Resource.Success) allArtists = artistsResult.data
            }

            // Apply any query that was typed while the library was still loading.
            val pendingQuery = queryFlow.value
            val readyModel = currentModel().copy(isLibraryReady = true)
            setSuccessState(applyFilter(readyModel, pendingQuery))
        }
    }

    /**
     * Watches the query [MutableStateFlow] and re-filters the in-memory catalogue
     * each time the debounced value changes.
     *
     * The 120 ms debounce absorbs rapid keystrokes without cancelling the underlying
     * coroutine, which means filter results arrive within one animation frame of the
     * user pausing their typing. The [exceptionHandler] is included so any unexpected
     * exception inside [applyFilter] is handled gracefully instead of silently
     * terminating the query-observation coroutine.
     */
    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch(exceptionHandler) {
            queryFlow
                .debounce(120L)
                .collect { query ->
                    // Skip filtering until the library is in memory.
                    if (currentModel().isLibraryReady) {
                        setSuccessState(applyFilter(currentModel(), query))
                    }
                }
        }
    }

    /**
     * Resets the search field and clears all result sections.
     */
    private fun clearSearch() {
        queryFlow.value = ""
        setSuccessState(
            currentModel().copy(
                query = "",
                artistResults = emptyList(),
                albumResults = emptyList(),
                trackResults = emptyList(),
                isSearchActive = false
            )
        )
    }

    /**
     * Applies [query] to the in-memory catalogue and returns an updated [SearchUiModel].
     *
     * A blank [query] clears all result lists and deactivates search mode.
     * Matching is case-insensitive and substring-based across all relevant fields.
     *
     * @param model Current UI model to base the copy on.
     * @param query Free-text search string from the user.
     * @return Updated [SearchUiModel] with filtered results.
     */
    private fun applyFilter(model: SearchUiModel, query: String): SearchUiModel {
        if (query.isBlank()) {
            return model.copy(
                query = "",
                artistResults = emptyList(),
                albumResults = emptyList(),
                trackResults = emptyList(),
                isSearchActive = false
            )
        }

        val lowerQuery = query.lowercase(Locale.getDefault())

        return model.copy(
            query = query,
            artistResults = allArtists.filter { artist ->
                artist.name.lowercase(Locale.getDefault()).contains(lowerQuery)
            },
            albumResults = allAlbums.filter { album ->
                album.title.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                    album.artistName.lowercase(Locale.getDefault()).contains(lowerQuery)
            },
            trackResults = allTracks.filter { track ->
                track.title.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                    track.artistName.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                    track.albumTitle.lowercase(Locale.getDefault()).contains(lowerQuery)
            },
            isSearchActive = true
        )
    }

    /**
     * Starts playback of the selected track using the current search result tracks as the queue,
     * then navigates to the player screen.
     *
     * Navigation is initiated immediately so the UI never blocks waiting for the Media3 engine
     * to prepare the queue; the player screen observes the playback flow and adapts as the
     * engine transitions through BUFFERING → PLAYING.
     *
     * @param track Track to begin playing.
     */
    private fun playTrack(track: Track) {
        val model = currentModel()
        val queue = if (model.isSearchActive && model.trackResults.isNotEmpty()) {
            model.trackResults
        } else {
            allTracks
        }

        if (queue.isEmpty()) {
            emitEffect(
                SearchUiEffect.PlaybackError(
                    resolveString(R.string.search_playback_empty_queue)
                )
            )
            return
        }

        // Open the player overlay immediately so the UI never blocks waiting for the Media3 engine
        // to prepare the queue; the player screen observes the playback flow and adapts as the
        // engine transitions through BUFFERING → PLAYING.
        playerOverlayManager.open()

        viewModelScope.launch(exceptionHandler) {
            playTrackUseCase(track, queue).fold(
                onSuccess = { /* navigation already triggered above */ },
                onError = { error ->
                    emitEffect(
                        SearchUiEffect.PlaybackError(
                            error?.toUserMessage() ?: PlaybackStrings.playbackFailed
                        )
                    )
                }
            )
        }
    }

    /** Returns the current [SearchUiModel] snapshot or a default if none is set. */
    private fun currentModel(): SearchUiModel = uiState.value.data ?: SearchUiModel()
}
