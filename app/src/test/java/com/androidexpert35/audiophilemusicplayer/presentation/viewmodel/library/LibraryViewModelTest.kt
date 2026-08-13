package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.FakePlayerOverlayManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.library.Playlist
import com.androidexpert35.audiophilemusicplayer.domain.model.library.PlaylistKind
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToPlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.CreatePlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetAlbumsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistImageUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLikedSongIdsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMediaStoreChangesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaylistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveRecentlyPlayedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ScanAndIndexMediaUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ToggleLikeSongUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getTracksUseCase = mockk<GetTracksUseCase>()
    private val getAlbumsUseCase = mockk<GetAlbumsUseCase>()
    private val getArtistsUseCase = mockk<GetArtistsUseCase>()
    private val getArtistImageUseCase = mockk<GetArtistImageUseCase>()
    private val playTrackUseCase = mockk<PlayTrackUseCase>()
    private val toggleLikeSongUseCase = mockk<ToggleLikeSongUseCase>()
    private val observeLikedSongIdsUseCase = mockk<ObserveLikedSongIdsUseCase>()
    private val observeRecentlyPlayedUseCase = mockk<ObserveRecentlyPlayedUseCase>()
    private val scanAndIndexMediaUseCase = mockk<ScanAndIndexMediaUseCase>()
    private val observeMediaStoreChangesUseCase = mockk<ObserveMediaStoreChangesUseCase>()
    private val observePlaylistsUseCase = mockk<ObservePlaylistsUseCase>()
    private val createPlaylistUseCase = mockk<CreatePlaylistUseCase>()
    private val addTrackToPlaylistUseCase = mockk<AddTrackToPlaylistUseCase>()
    private val playNextUseCase = mockk<PlayNextUseCase>()
    private val addTrackToQueueUseCase = mockk<AddTrackToQueueUseCase>()
    private val navigationManager = FakeNavigationManager()
    private val playerOverlayManager = FakePlayerOverlayManager()

    private val tracks = listOf(
        Track(
            id = 1L,
            title = "So What",
            artistName = "Miles Davis",
            albumTitle = "Kind of Blue",
            albumId = 10L,
            durationMs = 545_000L,
            uri = "content://tracks/1",
            trackNumber = 1,
            discNumber = 1,
            audioFormat = AudioFormat.UNKNOWN,
            fileSizeBytes = 12_000_000L,
            dateAdded = 0L
        ),
        Track(
            id = 2L,
            title = "Freddie Freeloader",
            artistName = "Miles Davis",
            albumTitle = "Kind of Blue",
            albumId = 10L,
            durationMs = 589_000L,
            uri = "content://tracks/2",
            trackNumber = 2,
            discNumber = 1,
            audioFormat = AudioFormat.UNKNOWN,
            fileSizeBytes = 12_500_000L,
            dateAdded = 0L
        ),
        Track(
            id = 3L,
            title = "Blue in Green",
            artistName = "Miles Davis",
            albumTitle = "Kind of Blue",
            albumId = 10L,
            durationMs = 337_000L,
            uri = "content://tracks/3",
            trackNumber = 3,
            discNumber = 1,
            audioFormat = AudioFormat.UNKNOWN,
            fileSizeBytes = 11_800_000L,
            dateAdded = 0L
        )
    )

    private val albums = listOf(
        Album(
            id = 10L,
            title = "Kind of Blue",
            artistName = "Miles Davis",
            artUri = null,
            trackCount = 3,
            year = 1959
        )
    )

    private val artists = listOf(
        Artist(
            id = 20L,
            name = "Miles Davis",
            albumCount = 1,
            trackCount = 3
        )
    )

    @Test
    fun `given indexed catalogue when ViewModel initializes then ui model contains tracks albums and artists`() = runTest {
        stubInitialLibrary()

        val viewModel = createViewModel()
        advanceUntilIdle()

        val model = viewModel.uiState.value.data
        assertEquals(tracks, model?.tracks)
        assertEquals(albums, model?.albums)
        assertEquals(artists, model?.artists)
        assertEquals(LibraryContentType.TRACKS, model?.selectedContentType)
    }

    @Test
    fun `given visible artist has no image when enrichment succeeds then row receives cached URL`() = runTest {
        stubInitialLibrary()
        val imageUrl = "https://cdn.example/miles-davis.jpg"
        coEvery { getArtistImageUseCase.invoke(artists.first().name) } returns
            Resource.Success(imageUrl)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryUiEvent.LoadArtistImage(artists.first()))
        advanceUntilIdle()

        assertEquals(imageUrl, viewModel.uiState.value.data?.artists?.first()?.imageUrl)
        coVerify(exactly = 1) { getArtistImageUseCase.invoke(artists.first().name) }
    }

    @Test
    fun `given indexed catalogue when ViewModel initializes then it does not re-index automatically`() = runTest {
        stubInitialLibrary()

        createViewModel()
        advanceUntilIdle()

        verify(exactly = 0) { scanAndIndexMediaUseCase.invoke() }
    }

    @Test
    fun `given open search event when handled then search screen route is requested`() = runTest {
        stubInitialLibrary()

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryUiEvent.OpenSearch)
        advanceUntilIdle()

        assertEquals(AppRoutes.Search.route, navigationManager.lastRoute)
    }

    @Test
    fun `given playable track when track tap handled then playback starts and player overlay opens`() = runTest {
        stubInitialLibrary()
        coEvery { playTrackUseCase.invoke(tracks.first(), tracks) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryUiEvent.PlayTrack(tracks.first()))
        advanceUntilIdle()

        coVerify(exactly = 1) { playTrackUseCase.invoke(tracks.first(), tracks) }
        assertTrue(playerOverlayManager.opened)
    }

    @Test
    fun `given track action when play next handled then selected track is inserted next`() = runTest {
        stubInitialLibrary()
        coEvery { playNextUseCase.invoke(tracks[1]) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryUiEvent.PlayNext(tracks[1]))
        advanceUntilIdle()

        coVerify(exactly = 1) { playNextUseCase.invoke(tracks[1]) }
    }

    @Test
    fun `given track action when add to queue handled then selected track is appended`() = runTest {
        stubInitialLibrary()
        coEvery { addTrackToQueueUseCase.invoke(tracks[2]) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryUiEvent.AddToQueue(tracks[2]))
        advanceUntilIdle()

        coVerify(exactly = 1) { addTrackToQueueUseCase.invoke(tracks[2]) }
    }

    @Test
    fun `given album has no indexed tracks when play album handled then playback error effect is emitted`() = runTest {
        coEvery { getTracksUseCase.invoke() } returns Resource.Success(emptyList())
        coEvery { getAlbumsUseCase.invoke() } returns Resource.Success(albums)
        coEvery { getArtistsUseCase.invoke() } returns Resource.Success(artists)
        every { observeLikedSongIdsUseCase.invoke() } returns flowOf(emptySet())
        every { observeRecentlyPlayedUseCase.invoke(any()) } returns flowOf(emptyList())
        every { observeMediaStoreChangesUseCase.invoke() } returns kotlinx.coroutines.flow.emptyFlow()
        every { observePlaylistsUseCase.invoke() } returns flowOf(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        val effectDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.uiEffect.first()
        }

        viewModel.onEvent(LibraryUiEvent.PlayAlbum(albums.first()))

        assertTrue(effectDeferred.await() is LibraryUiEffect.PlaybackError)
        coVerify(exactly = 0) { playTrackUseCase.invoke(any(), any()) }
    }

    @Test
    fun `given open settings event when handled then settings route is requested`() = runTest {
        stubInitialLibrary()

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryUiEvent.OpenSettings)
        advanceUntilIdle()

        assertEquals(AppRoutes.Settings.route, navigationManager.lastRoute)
    }

    @Test
    fun `given different sort choices when switching sections then each section retains its own choice`() = runTest {
        stubInitialLibrary()

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryUiEvent.SetSortOrder(LibrarySortOrder.ALPHABETICAL))
        viewModel.onEvent(LibraryUiEvent.SelectContentType(LibraryContentType.ALBUMS))
        viewModel.onEvent(LibraryUiEvent.SetSortOrder(LibrarySortOrder.RECENTLY_PLAYED))
        viewModel.onEvent(LibraryUiEvent.SelectContentType(LibraryContentType.TRACKS))

        assertEquals(LibrarySortOrder.ALPHABETICAL, viewModel.uiState.value.data?.sortOrder)

        viewModel.onEvent(LibraryUiEvent.SelectContentType(LibraryContentType.ALBUMS))

        assertEquals(LibrarySortOrder.RECENTLY_PLAYED, viewModel.uiState.value.data?.sortOrder)
    }

    @Test
    fun `given favorites playlist when selected then playlist detail opens without starting playback`() = runTest {
        val favorites = Playlist(
            id = "favorites.m3u",
            name = "Liked Songs",
            trackUris = listOf(tracks.first().uri),
            kind = PlaylistKind.FAVORITES
        )
        stubInitialLibrary()
        every { observePlaylistsUseCase.invoke() } returns flowOf(listOf(favorites))

        val viewModel = createViewModel()
        advanceUntilIdle()
        val playlist = viewModel.uiState.value.data?.playlists?.single()

        viewModel.onEvent(LibraryUiEvent.OpenPlaylistOverview(requireNotNull(playlist)))
        advanceUntilIdle()

        assertEquals(AppRoutes.playlistOverviewRoute(favorites.id), navigationManager.lastRoute)
        assertEquals(PlaylistKind.FAVORITES, playlist.kind)
        coVerify(exactly = 0) { playTrackUseCase.invoke(any(), any()) }
    }

    private fun stubInitialLibrary() {
        coEvery { getTracksUseCase.invoke() } returns Resource.Success(tracks)
        coEvery { getAlbumsUseCase.invoke() } returns Resource.Success(albums)
        coEvery { getArtistsUseCase.invoke() } returns Resource.Success(artists)
        coEvery { playTrackUseCase.invoke(any(), any()) } returns Resource.Success(Unit)
        coEvery { toggleLikeSongUseCase.invoke(any()) } returns Resource.Success(Unit)
        every { observeLikedSongIdsUseCase.invoke() } returns flowOf(emptySet())
        every { observeRecentlyPlayedUseCase.invoke(any()) } returns flowOf(emptyList())
        every { observeMediaStoreChangesUseCase.invoke() } returns kotlinx.coroutines.flow.emptyFlow()
        every { observePlaylistsUseCase.invoke() } returns flowOf(emptyList())
    }

    private fun createViewModel(): LibraryViewModel = LibraryViewModel(
        getTracksUseCase = getTracksUseCase,
        getAlbumsUseCase = getAlbumsUseCase,
        getArtistsUseCase = getArtistsUseCase,
        getArtistImageUseCase = getArtistImageUseCase,
        playTrackUseCase = playTrackUseCase,
        toggleLikeSongUseCase = toggleLikeSongUseCase,
        observeLikedSongIdsUseCase = observeLikedSongIdsUseCase,
        observeRecentlyPlayedUseCase = observeRecentlyPlayedUseCase,
        scanAndIndexMediaUseCase = scanAndIndexMediaUseCase,
        observeMediaStoreChangesUseCase = observeMediaStoreChangesUseCase,
        observePlaylistsUseCase = observePlaylistsUseCase,
        createPlaylistUseCase = createPlaylistUseCase,
        addTrackToPlaylistUseCase = addTrackToPlaylistUseCase,
        playNextUseCase = playNextUseCase,
        addTrackToQueueUseCase = addTrackToQueueUseCase,
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper,
        playerOverlayManager = playerOverlayManager
    )
}
