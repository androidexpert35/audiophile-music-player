package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibrarySectionDisplayPreference
import com.androidexpert35.audiophilemusicplayer.domain.model.library.Playlist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.model.track.isUnknownArtistName
import com.androidexpert35.audiophilemusicplayer.domain.model.track.sortedByAlbumOrder
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToPlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.CreatePlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetAlbumsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistImageUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibrarySectionOrderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLikedSongIdsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMediaStoreChangesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaylistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveRecentlyPlayedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ScanAndIndexMediaUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ToggleLikeSongUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay.PlayerOverlayManager
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.common.PlaybackStrings
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.fold
import com.tony.coreui.domain.resource.onError
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the music library browser screen.
 *
 * Loads tracks, albums, and artists from the device's MediaStore via domain use
 * cases and exposes the indexed library as a single [LibraryUiModel] snapshot.
 * Also observes the liked-songs collection and recently-played history in real
 * time so heart icons and "Recently Played" sort order always reflect current state.
 *
 * Continuously monitors the device's external audio MediaStore for new or removed
 * files. When a change is detected the library is automatically re-indexed and then
 * reloaded, so newly added audio files appear without requiring a manual refresh.
 *
 * @property getTracksUseCase Retrieves all audio tracks from MediaStore.
 * @property getAlbumsUseCase Retrieves all albums from MediaStore.
 * @property getArtistsUseCase Retrieves all artists from MediaStore.
 * @property getArtistImageUseCase Resolves and caches images for visible artist rows.
 * @property playTrackUseCase Starts playback via the Media3 engine.
 * @property toggleLikeSongUseCase Toggles the liked state of a track.
 * @property observeLikedSongIdsUseCase Live stream of liked track IDs.
 * @property observeRecentlyPlayedUseCase Live stream of recently-played track IDs.
 * @property scanAndIndexMediaUseCase Re-indexes the Room library cache from MediaStore.
 * @property observeMediaStoreChangesUseCase Emits whenever the device audio library changes.
 * @property observePlaybackStateUseCase Live playback snapshots used to highlight the active row.
 * @property observePlaylistsUseCase Streams the local M3U playlist collection.
 * @property createPlaylistUseCase Persists a newly named empty M3U playlist.
 * @property addTrackToPlaylistUseCase Appends a selected track to an M3U playlist.
 * @property playNextUseCase Inserts a selected track after the active queue item.
 * @property addTrackToQueueUseCase Appends a selected track to the active queue.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val getTracksUseCase: GetTracksUseCase,
    private val getAlbumsUseCase: GetAlbumsUseCase,
    private val getArtistsUseCase: GetArtistsUseCase,
    private val getLibraryDisplayPreferencesUseCase: GetLibraryDisplayPreferencesUseCase,
    private val observeLibraryDisplayPreferencesUseCase: ObserveLibraryDisplayPreferencesUseCase,
    private val observeLibrarySectionOrderUseCase: ObserveLibrarySectionOrderUseCase,
    private val getArtistImageUseCase: GetArtistImageUseCase,
    private val playTrackUseCase: PlayTrackUseCase,
    private val toggleLikeSongUseCase: ToggleLikeSongUseCase,
    private val observeLikedSongIdsUseCase: ObserveLikedSongIdsUseCase,
    private val observeRecentlyPlayedUseCase: ObserveRecentlyPlayedUseCase,
    private val scanAndIndexMediaUseCase: ScanAndIndexMediaUseCase,
    private val observeMediaStoreChangesUseCase: ObserveMediaStoreChangesUseCase,
    private val observePlaybackStateUseCase: ObservePlaybackStateUseCase,
    private val observePlaylistsUseCase: ObservePlaylistsUseCase,
    private val createPlaylistUseCase: CreatePlaylistUseCase,
    private val addTrackToPlaylistUseCase: AddTrackToPlaylistUseCase,
    private val playNextUseCase: PlayNextUseCase,
    private val addTrackToQueueUseCase: AddTrackToQueueUseCase,
    private val setLibraryDisplayPreferencesUseCase: SetLibraryDisplayPreferencesUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper,
    private val playerOverlayManager: PlayerOverlayManager
) : BaseViewModel<LibraryUiModel, LibraryUiEvent, LibraryUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    /** Latest file-backed collection used to rebuild artwork after a library re-index. */
    private var playlistEntries: List<Playlist> = emptyList()

    /** Prevents duplicate visible-row requests while one lookup is already running. */
    private val pendingArtistImageRequests = mutableSetOf<String>()

    /** Latest full display preference map, retained when writing a sort or layout update. */
    private var latestLibraryDisplayPreferences = LibraryDisplayPreferences()

    init {
        loadLibraryDisplayPreferences()
        loadFullLibrary()
        observeUserCollections()
        observePlaybackState()
        observePlaylists()
        observeAndReindexOnMediaStoreChanges()
        observeVisibleOrderedSections()
    }

    override fun handleEvent(event: LibraryUiEvent) {
        when (event) {
            is LibraryUiEvent.SelectContentType -> selectContentType(event.contentType)
            is LibraryUiEvent.LoadTracks -> loadTracks()
            is LibraryUiEvent.LoadAlbums -> loadAlbums()
            is LibraryUiEvent.LoadArtists -> loadArtists()
            is LibraryUiEvent.LoadArtistImage -> loadArtistImage(event.artist)
            is LibraryUiEvent.RefreshLibrary -> scanAndRefreshLibrary()
            is LibraryUiEvent.ShowCreatePlaylistDialog -> updatePlaylistDialogVisibility(true)
            is LibraryUiEvent.DismissCreatePlaylistDialog -> updatePlaylistDialogVisibility(false)
            is LibraryUiEvent.CreatePlaylist -> createPlaylist(event.name)
            is LibraryUiEvent.ShowPlaylistPicker -> showPlaylistPicker(event.track)
            is LibraryUiEvent.DismissPlaylistPicker -> dismissPlaylistPicker()
            is LibraryUiEvent.AddTrackToPlaylist -> addTrackToPlaylist(event.playlistId)
            is LibraryUiEvent.PlayNext -> playNext(event.track)
            is LibraryUiEvent.AddToQueue -> addToQueue(event.track)
            is LibraryUiEvent.OpenFacetTracks -> navigateToRoute(
                AppRoutes.libraryFacetTracksRoute(event.filter)
            )
            is LibraryUiEvent.SetFacetTrackFilter -> setFacetTrackFilter(event.filter)
            is LibraryUiEvent.ToggleViewMode -> toggleViewMode()
            is LibraryUiEvent.SetSortOrder -> setSortOrder(event.sortOrder)
            is LibraryUiEvent.ToggleLikeSong -> toggleLike(event.track.id)
            is LibraryUiEvent.OpenSearch -> navigateToRoute(AppRoutes.Search.route)
            is LibraryUiEvent.OpenSettings -> navigateToRoute(AppRoutes.SettingsHub.route)
            LibraryUiEvent.NavigateBack -> navigateUp()
            is LibraryUiEvent.OpenAlbumOverview -> navigateToRoute(
                AppRoutes.albumOverviewRoute(event.album.id)
            )
            is LibraryUiEvent.OpenPlaylistOverview -> navigateToRoute(
                AppRoutes.playlistOverviewRoute(event.playlist.id)
            )
            is LibraryUiEvent.OpenArtistDescription -> navigateToRoute(
                AppRoutes.artistDescriptionRoute(event.artist.name)
            )
            is LibraryUiEvent.OpenTrackAlbum -> openTrackAlbum(event.track)
            is LibraryUiEvent.OpenTrackArtist -> openTrackArtist(event.track)
            is LibraryUiEvent.PlayAlbum -> playAlbum(event.album)
            is LibraryUiEvent.PlayTrack -> playTrack(
                track = event.track,
                queue = uiState.value.data?.visibleTracks.orEmpty()
            )
        }
    }

    /** Keeps the creation dialog state in the screen's immutable UDF model. */
    private fun updatePlaylistDialogVisibility(isVisible: Boolean) {
        val current = uiState.value.data ?: LibraryUiModel()
        setSuccessState(current.copy(isCreatePlaylistDialogVisible = isVisible))
    }

    /** Opens the shared playlist selector for the supplied track. */
    private fun showPlaylistPicker(track: Track) {
        val current = uiState.value.data ?: LibraryUiModel()
        setSuccessState(current.copy(playlistPickerTrack = track))
    }

    /** Discards the pending track and closes the shared playlist selector. */
    private fun dismissPlaylistPicker() {
        val current = uiState.value.data ?: return
        setSuccessState(current.copy(playlistPickerTrack = null))
    }

    /** Creates an empty M3U playlist and leaves the collection observer to refresh the list. */
    private fun createPlaylist(name: String) {
        viewModelScope.launch(exceptionHandler) {
            createPlaylistUseCase(name).fold(
                onSuccess = { playlist ->
                    val current = uiState.value.data ?: LibraryUiModel()
                    setSuccessState(current.copy(isCreatePlaylistDialogVisible = false))
                    emitEffect(LibraryUiEffect.PlaylistSuccess(playlistName = playlist.name))
                },
                onError = { error ->
                    emitEffect(
                        LibraryUiEffect.PlaybackError(
                            error?.toUserMessage() ?: "Unable to create the playlist."
                        )
                    )
                }
            )
        }
    }

    /** Appends the pending track to the selected M3U playlist. */
    private fun addTrackToPlaylist(playlistId: String) {
        val track = uiState.value.data?.playlistPickerTrack ?: return
        viewModelScope.launch(exceptionHandler) {
            addTrackToPlaylistUseCase(playlistId, track).fold(
                onSuccess = {
                    val playlistName = playlistEntries.firstOrNull { it.id == playlistId }?.name.orEmpty()
                    dismissPlaylistPicker()
                    emitEffect(
                        LibraryUiEffect.PlaylistSuccess(
                            playlistName = playlistName,
                            trackTitle = track.title
                        )
                    )
                },
                onError = { error ->
                    emitEffect(
                        LibraryUiEffect.PlaybackError(
                            error?.toUserMessage() ?: "Unable to update the playlist."
                        )
                    )
                }
            )
        }
    }

    /** Inserts the selected track directly after the active playback item. */
    private fun playNext(track: Track) {
        updatePlaybackQueue(
            successMessage = resolveString(R.string.track_queued_next_success, track.title),
            command = { playNextUseCase(track) }
        )
    }

    /** Appends the selected track to the end of the active playback queue. */
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

    /** Runs a queue mutation and maps its result into transient library feedback. */
    private fun updatePlaybackQueue(
        successMessage: String,
        command: suspend () -> Resource<Unit>
    ) {
        viewModelScope.launch(exceptionHandler) {
            command().fold(
                onSuccess = { emitEffect(LibraryUiEffect.QueueUpdated(successMessage)) },
                onError = { error ->
                    emitEffect(
                        LibraryUiEffect.PlaybackError(
                            error?.toUserMessage() ?: PlaybackStrings.playbackCommandFailed
                        )
                    )
                }
            )
        }
    }

    /** Updates which catalogue section is currently visible on the library screen. */
    private fun selectContentType(contentType: LibraryContentType) {
        val current = uiState.value.data ?: LibraryUiModel(selectedContentType = contentType)
        setSuccessState(current.copy(selectedContentType = contentType, facetTrackFilter = null))
    }

    /** Shows one metadata collection through the same retained Songs list/grid surface. */
    private fun setFacetTrackFilter(filter: LibraryFacetFilter) {
        val current = uiState.value.data ?: LibraryUiModel()
        setSuccessState(
            current.copy(
                selectedContentType = LibraryContentType.TRACKS,
                facetTrackFilter = filter,
            )
        )
    }

    /** Flips the visible section between its retained list and two-column grid views. */
    private fun toggleViewMode() {
        val current = uiState.value.data ?: return
        setSuccessState(
            current.copy(
                gridViews = current.gridViews +
                    (current.selectedContentType to !current.isGridView)
            )
        )
        persistLibraryDisplayPreferences()
    }

    /**
     * Retains a new sort order for the visible section and re-sorts the catalogue immediately.
     *
     * @param sortOrder New sort strategy to persist in the UI model.
     */
    private fun setSortOrder(sortOrder: LibrarySortOrder) {
        val current = uiState.value.data ?: return
        setSuccessState(
            current.copy(
                sortOrders = current.sortOrders + (current.selectedContentType to sortOrder)
            ).withSortApplied()
        )
        persistLibraryDisplayPreferences()
    }

    /** Restores the user's per-section sort and layout choices from SharedPreferences. */
    private fun loadLibraryDisplayPreferences() {
        viewModelScope.launch(exceptionHandler) {
            getLibraryDisplayPreferencesUseCase().fold(
                onSuccess = { preferences ->
                    latestLibraryDisplayPreferences = preferences
                    val current = uiState.value.data ?: LibraryUiModel()
                    val restored = LibraryContentType.entries.associateWith { contentType ->
                        preferences.preferenceFor(contentType.name)
                    }
                    setSuccessState(
                        current.copy(
                            sortOrders = restored.mapValues { (_, preference) ->
                                LibrarySortOrder.entries.firstOrNull { it.name == preference.sortOrder }
                                    ?: LibrarySortOrder.RECENTLY_ADDED
                            },
                            gridViews = restored.mapValues { (_, preference) -> preference.isGridView },
                        ).withSortApplied()
                    )
                },
                onError = { /* Defaults remain usable when preferences cannot be read. */ },
            )
        }
    }

    /**
     * Keeps [LibraryUiModel.visibleOrderedSections] live so a section hidden or
     * reordered from the Library Sections settings screen applies immediately, without
     * requiring this screen to be recreated. Falls back the active selection to the
     * first remaining visible section if it was just hidden.
     */
    private fun observeVisibleOrderedSections() {
        combine(
            observeLibraryDisplayPreferencesUseCase(),
            observeLibrarySectionOrderUseCase(),
        ) { preferences, order ->
            latestLibraryDisplayPreferences = preferences
            val orderedTypes = order.mapNotNull { name ->
                LibraryContentType.entries.firstOrNull { it.name == name }
            }
            val missingTypes = LibraryContentType.entries.filterNot { it in orderedTypes }
            (orderedTypes + missingTypes).filter { contentType ->
                preferences.preferenceFor(contentType.name).isVisible
            }
        }
            .onEach { visibleOrderedSections ->
                // Both preference flows emit their stored value synchronously on
                // subscription, so this runs during init while the catalogue query is
                // still in flight and there is no model yet. Seeding an empty model
                // instead of skipping the emission is what makes the saved order
                // survive: the later catalogue load copies this snapshot forward, and
                // SharedPreferences never re-emits an unchanged value to correct it.
                val current = uiState.value.data ?: LibraryUiModel()
                val selectedContentType = if (current.selectedContentType in visibleOrderedSections) {
                    current.selectedContentType
                } else {
                    visibleOrderedSections.firstOrNull() ?: LibraryContentType.TRACKS
                }
                // Patches data only — the initial catalogue load owns the loading status
                // and must keep showing its spinner until the tracks actually arrive.
                updateUiData(
                    current.copy(
                        visibleOrderedSections = visibleOrderedSections,
                        selectedContentType = selectedContentType,
                    )
                )
            }
            .launchIn(viewModelScope)
    }

    /** Writes the current per-section sort and layout choices without delaying the UI update. */
    private fun persistLibraryDisplayPreferences() {
        val model = uiState.value.data ?: return
        val preferences = LibraryDisplayPreferences(
            sections = LibraryContentType.entries.associate { contentType ->
                contentType.name to LibrarySectionDisplayPreference(
                    sortOrder = model.sortOrders[contentType]?.name
                        ?: LibrarySortOrder.RECENTLY_ADDED.name,
                    isGridView = model.gridViews[contentType] ?: false,
                    isVisible = latestLibraryDisplayPreferences.preferenceFor(contentType.name).isVisible,
                )
            }
        )
        viewModelScope.launch(exceptionHandler) {
            setLibraryDisplayPreferencesUseCase(preferences)
        }
    }

    /**
     * Toggles the liked state of a track and emits an error effect on failure.
     *
     * @param trackId The MediaStore ID of the track to like or unlike.
     */
    private fun toggleLike(trackId: Long) {
        viewModelScope.launch(exceptionHandler) {
            toggleLikeSongUseCase(trackId).onError { error ->
                emitEffect(
                    LibraryUiEffect.PlaybackError(
                        error?.toUserMessage() ?: PlaybackStrings.likedSongsUpdateFailed
                    )
                )
            }
        }
    }

    /**
     * Observes the liked-song IDs and recently-played track IDs, merging each
     * emission into the live [LibraryUiModel] snapshot.
     *
     * Combines both streams into a single [combine] so only one model update is
     * emitted when both change simultaneously (e.g. on first cold start). The
     * current library data (tracks/albums/artists) is preserved across updates.
     */
    private fun observeUserCollections() {
        combine(
            observeLikedSongIdsUseCase(),
            observeRecentlyPlayedUseCase()
        ) { likedIds, recentlyPlayedIds ->
            likedIds to recentlyPlayedIds
        }
            .onEach { (likedIds, recentlyPlayedIds) ->
                val current = uiState.value.data ?: LibraryUiModel()
                setSuccessState(
                    current.copy(
                        likedSongIds = likedIds,
                        recentlyPlayedTrackIds = recentlyPlayedIds
                    ).withSortApplied()
                )
            }
            .launchIn(viewModelScope)
    }

    /**
     * Keeps [LibraryUiModel.currentPlayingTrackId] aligned with the active playback item so the
     * matching row stays highlighted in the Songs list and grid.
     *
     * The playback flow also emits on every position tick, so only the track identifier is
     * observed and de-duplicated — the model is rebuilt on an actual track change, never once
     * per progress update.
     *
     * The first emission arrives during init, while the catalogue query is still in flight and
     * no model exists yet. Seeding an empty model instead of dropping that emission is what makes
     * a track that was already playing when the screen opened highlight immediately: the later
     * catalogue load copies this snapshot forward, and the playback flow will not re-emit an
     * unchanged track to correct it.
     */
    private fun observePlaybackState() {
        observePlaybackStateUseCase()
            .map { playbackState -> playbackState.currentTrack?.id }
            .distinctUntilChanged()
            .onEach { playingTrackId ->
                val current = uiState.value.data ?: LibraryUiModel()
                updateUiData(current.copy(currentPlayingTrackId = playingTrackId))
            }
            .launchIn(viewModelScope)
    }

    /**
     * Maps M3U entries into UI summaries and derives their cover mosaics from the indexed tracks.
     *
     * The M3U file remains the source of truth for membership. Artwork is intentionally derived
     * here so a media rescan can refresh album art without rewriting the user's playlist file.
     */
    private fun observePlaylists() {
        observePlaylistsUseCase()
            .onEach { playlists ->
                playlistEntries = playlists
                val current = uiState.value.data ?: LibraryUiModel()
                setSuccessState(current.copy(playlists = playlists.toUiModels(current.tracks)))
            }
            .launchIn(viewModelScope)
    }

    /**
     * Subscribes to MediaStore audio content changes and re-indexes the Room library cache
     * whenever a new audio file is added or removed from the device.
     *
     * The initial emission from [ObserveMediaStoreChangesUseCase] is dropped because the
     * library is already loaded by [loadFullLibrary] during init. Subsequent emissions
     * are debounced by 1.5 seconds to coalesce rapid bursts (e.g. a batch file copy) into
     * a single re-index + reload cycle, keeping unnecessary scan work to a minimum.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeAndReindexOnMediaStoreChanges() {
        observeMediaStoreChangesUseCase()
            // Skip the immediate emission — the library was just loaded in init.
            .drop(1)
            // Coalesce bursts from batch file operations into a single scan trigger.
            .debounce(1_500L)
            .onEach {
                reindexAndReload()
            }
            .launchIn(viewModelScope)
    }

    /**
     * Re-runs the full MediaStore scan to refresh the Room index, then reloads the UI catalogue.
     *
     * Silently swallows scan errors so transient storage issues do not surface as hard errors
     * while the user is browsing; the existing cached data remains visible in the meantime.
     */
    private fun reindexAndReload() {
        viewModelScope.launch(exceptionHandler) {
            // Collect the scan flow to completion; errors are swallowed so the cached
            // library remains visible if a transient storage issue occurs mid-scan.
            scanAndIndexMediaUseCase().collect { /* progress ignored for background refresh */ }
            loadFullLibrary()
        }
    }

    /**
     * Starts a visible library re-index for an explicit header refresh action.
     *
     * Initial indexing belongs to the onboarding flow, which runs before the main navigation
     * graph is entered. Keeping this workflow behind the manual header action prevents merely
     * returning to the library destination from triggering an expensive full MediaStore scan.
     * [LibraryUiModel.isRefreshing] remains `true` for the entire scan and reload cycle so the
     * header refresh icon visibly rotates until the refreshed Room catalogue is displayed.
     */
    private fun scanAndRefreshLibrary() {
        if (uiState.value.data?.isRefreshing == true) return

        viewModelScope.launch(exceptionHandler) {
            // Mark as refreshing so the header button starts spinning.
            val current = uiState.value.data ?: LibraryUiModel()
            setSuccessState(current.copy(isRefreshing = true))

            runCatching {
                scanAndIndexMediaUseCase().collect { /* progress ignored for manual refresh */ }
            }

            // Reload from the freshly-updated Room index.
            loadFullLibrary()

            // Clear the refreshing flag — loadFullLibrary emits a new model so we must
            // wait for it to settle and then patch the flag in the next frame.
            val updated = uiState.value.data ?: LibraryUiModel()
            setSuccessState(updated.copy(isRefreshing = false))
        }
    }

    /**
     * Loads the complete library — tracks, albums, and artists in parallel.
     *
     * Uses [coroutineScope] with [async] so all three MediaStore queries run
     * concurrently and participate in structured concurrency: cancelling this
     * operation automatically cancels all in-flight queries. The active sort
     * order is applied to the result before the snapshot is emitted to the UI.
     */
    private fun loadFullLibrary() {
        launchUiStateUpdate(
            retryAction = ::loadFullLibrary,
            dataFetchBlock = {
                // All three queries run concurrently inside a structured coroutineScope.
                // If the outer coroutine is cancelled, all async children are cancelled too.
                val (tracksResult, albumsResult, artistsResult) = coroutineScope {
                    val tracksDeferred = async { getTracksUseCase() }
                    val albumsDeferred = async { getAlbumsUseCase() }
                    val artistsDeferred = async { getArtistsUseCase() }
                    Triple(tracksDeferred.await(), albumsDeferred.await(), artistsDeferred.await())
                }

                // Propagate the first error encountered in the concurrent batch.
                if (tracksResult is Resource.Error) return@launchUiStateUpdate tracksResult
                if (albumsResult is Resource.Error) return@launchUiStateUpdate albumsResult
                if (artistsResult is Resource.Error) return@launchUiStateUpdate artistsResult

                val tracks = (tracksResult as Resource.Success).data
                val albums = (albumsResult as Resource.Success).data
                val artists = (artistsResult as Resource.Success).data

                Resource.Success(Triple(tracks, albums, artists))
            },
            processSuccess = { (tracks, albums, artists) ->
                emitEffect(LibraryUiEffect.ScanComplete(tracks.size))
                val current = uiState.value.data
                val updated = current?.copy(
                    tracks = tracks,
                    albums = albums,
                    artists = artists,
                    playlists = playlistEntries.toUiModels(tracks)
                ) ?: LibraryUiModel(
                    tracks = tracks,
                    albums = albums,
                    artists = artists
                )
                // Preserve existing layout/sort preferences while refreshing catalogue data.
                updated.withSortApplied()
            }
        )
    }

    /**
     * Loads only tracks from MediaStore.
     */
    private fun loadTracks() {
        launchUiStateUpdate(
            retryAction = ::loadTracks,
            dataFetchBlock = { getTracksUseCase() },
            processSuccess = { tracks ->
                val current = uiState.value.data?.copy(
                    tracks = tracks,
                    playlists = playlistEntries.toUiModels(tracks)
                )
                    ?: LibraryUiModel(tracks = tracks)
                current.withSortApplied()
            }
        )
    }

    /**
     * Loads only albums from MediaStore.
     */
    private fun loadAlbums() {
        launchUiStateUpdate(
            retryAction = ::loadAlbums,
            dataFetchBlock = { getAlbumsUseCase() },
            processSuccess = { albums ->
                val current = uiState.value.data?.copy(albums = albums)
                    ?: LibraryUiModel(albums = albums)
                current.withSortApplied()
            }
        )
    }

    /**
     * Loads only artists from MediaStore.
     */
    private fun loadArtists() {
        launchUiStateUpdate(
            retryAction = ::loadArtists,
            dataFetchBlock = { getArtistsUseCase() },
            processSuccess = { artists ->
                val current = uiState.value.data?.copy(artists = artists)
                    ?: LibraryUiModel(artists = artists)
                current.withSortApplied()
            }
        )
    }

    /**
     * Resolves artwork for one visible artist row without blocking catalogue loading.
     *
     * Room serves previously resolved URLs immediately. On a cache miss the repository
     * performs strict Deezer matching, persists the result, and this method patches only
     * the matching immutable artist item in the current UI snapshot.
     *
     * @param artist Visible artist currently showing the profile placeholder.
     */
    private fun loadArtistImage(artist: Artist) {
        if (!artist.imageUrl.isNullOrBlank() || artist.name.isUnknownArtistName()) return

        val requestKey = artist.name.trim().lowercase(Locale.ROOT)
        if (requestKey.isBlank() || !pendingArtistImageRequests.add(requestKey)) return

        viewModelScope.launch(exceptionHandler) {
            try {
                val result = getArtistImageUseCase(artist.name)
                if (result is Resource.Success) {
                    val current = uiState.value.data ?: return@launch
                    setSuccessState(
                        current.copy(
                            artists = current.artists.map { candidate ->
                                if (candidate.id == artist.id) {
                                    candidate.copy(imageUrl = result.data)
                                } else {
                                    candidate
                                }
                            }
                        ).withSortApplied()
                    )
                }
            } finally {
                pendingArtistImageRequests.remove(requestKey)
            }
        }
    }

    /** Plays the selected album from its first indexed track and opens the player screen. */
    private fun playAlbum(album: Album) {
        val albumQueue = uiState.value.data
            ?.tracks
            .orEmpty()
            .filter { track -> track.albumId == album.id }
            .sortedByAlbumOrder()

        val firstTrack = albumQueue.firstOrNull()
        if (firstTrack == null) {
            emitEffect(
                LibraryUiEffect.PlaybackError(
                    resolveString(R.string.library_playback_album_unavailable, album.title)
                )
            )
            return
        }

        playTrack(track = firstTrack, queue = albumQueue)
    }

    /**
     * Plays the selected track within the provided queue and navigates to the full player.
     *
     * Navigation is triggered immediately so the screen responds without waiting for
     * Media3 queue preparation and IO-bound artwork loading to complete. The player
     * screen observes the playback state flow and will reflect the correct state
     * (BUFFERING → PLAYING) as the engine comes up. If the play command fails, an
     * error effect is emitted so the caller can surface a snackbar or similar feedback.
     */
    private fun playTrack(track: Track, queue: List<Track>) {
        if (queue.isEmpty()) {
            emitEffect(LibraryUiEffect.PlaybackError(LibraryStrings.playbackEmptyQueue))
            return
        }

        // Open the player overlay immediately so the UI never freezes waiting for the playback engine to
        // initialize, connect to the MediaSession, or load the current track's artwork.
        playerOverlayManager.open()

        viewModelScope.launch(exceptionHandler) {
            playTrackUseCase(track, queue).fold(
                onSuccess = { /* navigation already performed above */ },
                onError = { error ->
                    emitEffect(
                        LibraryUiEffect.PlaybackError(
                            error?.toUserMessage() ?: PlaybackStrings.playbackFailed
                        )
                    )
                }
            )
        }
    }

    /** Converts file-backed playlists into the precise summaries consumed by Compose. */
    private fun List<Playlist>.toUiModels(tracks: List<Track>): List<PlaylistUiModel> {
        val tracksByUri = tracks.associateBy(Track::uri)
        return map { playlist ->
            PlaylistUiModel(
                id = playlist.id,
                name = playlist.name,
                trackCount = playlist.trackUris.size,
                albumArtUris = playlist.trackUris
                    .asReversed()
                    .take(MAX_PLAYLIST_ARTWORKS)
                    .mapNotNull { uri -> tracksByUri[uri]?.artUri },
                kind = playlist.kind
            )
        }
    }

    private companion object {
        const val MAX_PLAYLIST_ARTWORKS = 4
    }
}
