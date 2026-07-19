package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.library.Playlist
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToPlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTrackToQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTracksToPlaylistUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddTracksToQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetAlbumsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetArtistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetTracksUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaylistsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTracksNextUseCase
import com.tony.coreui.data.strings.CoreUiStringProvider
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumOverviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getAlbumsUseCase = mockk<GetAlbumsUseCase>()
    private val getArtistsUseCase = mockk<GetArtistsUseCase>()
    private val getTracksUseCase = mockk<GetTracksUseCase>()
    private val playTrackUseCase = mockk<PlayTrackUseCase>()
    private val observePlaybackStateUseCase = mockk<ObservePlaybackStateUseCase>()
    private val observePlaylistsUseCase = mockk<ObservePlaylistsUseCase>()
    private val addTrackToPlaylistUseCase = mockk<AddTrackToPlaylistUseCase>()
    private val addTracksToPlaylistUseCase = mockk<AddTracksToPlaylistUseCase>()
    private val playNextUseCase = mockk<PlayNextUseCase>()
    private val playTracksNextUseCase = mockk<PlayTracksNextUseCase>()
    private val addTrackToQueueUseCase = mockk<AddTrackToQueueUseCase>()
    private val addTracksToQueueUseCase = mockk<AddTracksToQueueUseCase>()

    private val album = Album(
        id = 10L,
        title = "Kind of Blue",
        artistName = "Miles Davis",
        artUri = null,
        trackCount = 2,
        year = 1959
    )
    private val tracks = listOf(
        sampleTrack(id = 1L, number = 1),
        sampleTrack(id = 2L, number = 2)
    )
    private val playlist = Playlist(
        id = "favorites.m3u",
        name = "Favorites",
        trackUris = emptyList()
    )

    @Before
    fun setUpStringProvider() {
        mockkObject(CoreUiStringProvider)
        every { CoreUiStringProvider.get(any()) } returns "Unknown"
    }

    @After
    fun tearDownStringProvider() {
        unmockkObject(CoreUiStringProvider)
    }

    @Test
    fun `given loaded album when play album next selected then ordered tracks use batch command`() = runTest {
        stubLoadedAlbum()
        coEvery { playTracksNextUseCase.invoke(tracks) } returns Resource.Success(Unit)
        val viewModel = createViewModel()
        initialize(viewModel)

        viewModel.onEvent(AlbumOverviewUiEvent.PlayAlbumNext)
        advanceUntilIdle()

        coVerify(exactly = 1) { playTracksNextUseCase.invoke(tracks) }
    }

    @Test
    fun `given loaded album when add album to queue selected then ordered tracks use batch command`() = runTest {
        stubLoadedAlbum()
        coEvery { addTracksToQueueUseCase.invoke(tracks) } returns Resource.Success(Unit)
        val viewModel = createViewModel()
        initialize(viewModel)

        viewModel.onEvent(AlbumOverviewUiEvent.AddAlbumToQueue)
        advanceUntilIdle()

        coVerify(exactly = 1) { addTracksToQueueUseCase.invoke(tracks) }
    }

    @Test
    fun `given album playlist picker when destination selected then complete album is appended`() = runTest {
        stubLoadedAlbum()
        coEvery {
            addTracksToPlaylistUseCase.invoke(playlist.id, tracks)
        } returns Resource.Success(Unit)
        val viewModel = createViewModel()
        initialize(viewModel)

        viewModel.onEvent(AlbumOverviewUiEvent.ShowAlbumPlaylistPicker)
        assertEquals(tracks, viewModel.uiState.value.data?.playlistPickerTracks)

        viewModel.onEvent(AlbumOverviewUiEvent.AddTrackToPlaylist(playlist.id))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            addTracksToPlaylistUseCase.invoke(playlist.id, tracks)
        }
        assertTrue(viewModel.uiState.value.data?.playlistPickerTracks?.isEmpty() == true)
    }

    private fun stubLoadedAlbum() {
        every { observePlaybackStateUseCase.invoke() } returns flowOf(PlaybackState.IDLE)
        every { observePlaylistsUseCase.invoke() } returns flowOf(listOf(playlist))
        coEvery { getAlbumsUseCase.invoke() } returns Resource.Success(listOf(album))
        coEvery { getArtistsUseCase.invoke() } returns Resource.Success(
            listOf(Artist(1L, album.artistName, albumCount = 1, trackCount = tracks.size))
        )
        coEvery { getTracksUseCase.invoke() } returns Resource.Success(tracks)
    }

    private suspend fun TestScope.initialize(viewModel: AlbumOverviewViewModel) {
        viewModel.onEvent(AlbumOverviewUiEvent.Initialize(album.id))
        advanceUntilIdle()
        assertEquals(tracks, viewModel.uiState.value.data?.tracks)
    }

    private fun createViewModel(): AlbumOverviewViewModel = AlbumOverviewViewModel(
        getAlbumsUseCase = getAlbumsUseCase,
        getArtistsUseCase = getArtistsUseCase,
        getTracksUseCase = getTracksUseCase,
        playTrackUseCase = playTrackUseCase,
        observePlaybackStateUseCase = observePlaybackStateUseCase,
        observePlaylistsUseCase = observePlaylistsUseCase,
        addTrackToPlaylistUseCase = addTrackToPlaylistUseCase,
        addTracksToPlaylistUseCase = addTracksToPlaylistUseCase,
        playNextUseCase = playNextUseCase,
        playTracksNextUseCase = playTracksNextUseCase,
        addTrackToQueueUseCase = addTrackToQueueUseCase,
        addTracksToQueueUseCase = addTracksToQueueUseCase,
        navigationManager = FakeNavigationManager(),
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper
    )

    private fun sampleTrack(id: Long, number: Int): Track = Track(
        id = id,
        title = "Track $number",
        artistName = album.artistName,
        albumTitle = album.title,
        albumId = album.id,
        durationMs = 180_000L,
        uri = "content://tracks/$id",
        trackNumber = number,
        discNumber = 1,
        audioFormat = AudioFormat.UNKNOWN,
        fileSizeBytes = 1L,
        dateAdded = 0L
    )
}
