package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player

import app.cash.turbine.test
import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ClearQueueUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetLyricsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetTrackAnalysisUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.MoveQueueItemUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudioTelemetryUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLikedSongIdsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveQueueStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PausePlaybackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ReleaseUsbAudioUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ResumePlaybackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SeekToPositionUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetRepeatModeUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetShuffleModeUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SkipNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SkipPreviousUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ToggleLikeSongUseCase
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Behavioural tests for [PlayerViewModel]'s measured-signal read-out.
 *
 * The cases that matter are the ones the telemetry sheet renders differently: a track
 * with cached measurements, a track without them, and the boundary between the two as
 * playback moves on. The read must also stay tied to the track identity rather than to
 * playback progress — position ticks arrive four times a second and must never turn
 * into database reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observePlaybackStateUseCase = mockk<ObservePlaybackStateUseCase>()
    private val observeAudioTelemetryUseCase = mockk<ObserveAudioTelemetryUseCase>()
    private val observeQueueStateUseCase = mockk<ObserveQueueStateUseCase>()
    private val observeLikedSongIdsUseCase = mockk<ObserveLikedSongIdsUseCase>()
    private val getTrackAnalysisUseCase = mockk<GetTrackAnalysisUseCase>()

    private val playbackStateFlow = MutableStateFlow(PlaybackState.IDLE)
    private val queueStateFlow = MutableStateFlow(QueueState.EMPTY)
    private val likedSongIdsFlow = MutableStateFlow(emptySet<Long>())

    @Test
    fun `given an analysed track when it plays then its measurements are exposed`() = runTest {
        stubObservationUseCases()
        coEvery { getTrackAnalysisUseCase(TRACK_ID) } returns Resource.Success(analysis())
        val viewModel = createViewModel()

        viewModel.measuredSignalFlow.test {
            assertNull(awaitItem())

            playbackStateFlow.value = playing(TRACK_ID)
            advanceUntilIdle()

            assertEquals(stationary(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given a track that was never analysed when it plays then nothing is reported`() =
        runTest {
            stubObservationUseCases()
            coEvery { getTrackAnalysisUseCase(TRACK_ID) } returns Resource.Success(null)
            val viewModel = createViewModel()

            viewModel.measuredSignalFlow.test {
                assertNull(awaitItem())

                playbackStateFlow.value = playing(TRACK_ID)
                advanceUntilIdle()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            assertNull(viewModel.measuredSignalFlow.value)
        }

    @Test
    fun `given a storage failure when read then it degrades to no measurements`() = runTest {
        stubObservationUseCases()
        coEvery { getTrackAnalysisUseCase(TRACK_ID) } returns
            Resource.Error(ResourceError.DatabaseError("database closed"))
        val viewModel = createViewModel()

        viewModel.measuredSignalFlow.test {
            assertNull(awaitItem())

            playbackStateFlow.value = playing(TRACK_ID)
            advanceUntilIdle()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given an analysed track when the next track is unanalysed then values are dropped`() =
        runTest {
            stubObservationUseCases()
            coEvery { getTrackAnalysisUseCase(TRACK_ID) } returns Resource.Success(analysis())
            coEvery { getTrackAnalysisUseCase(OTHER_TRACK_ID) } returns Resource.Success(null)
            val viewModel = createViewModel()

            viewModel.measuredSignalFlow.test {
                assertNull(awaitItem())

                playbackStateFlow.value = playing(TRACK_ID)
                advanceUntilIdle()
                assertEquals(stationary(), awaitItem())

                playbackStateFlow.value = playing(OTHER_TRACK_ID)
                advanceUntilIdle()
                assertNull(awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `given playback progresses when position ticks then the cache is read once`() = runTest {
        stubObservationUseCases()
        coEvery { getTrackAnalysisUseCase(TRACK_ID) } returns Resource.Success(analysis())
        val viewModel = createViewModel()

        viewModel.measuredSignalFlow.test {
            assertNull(awaitItem())

            playbackStateFlow.value = playing(TRACK_ID)
            advanceUntilIdle()
            assertEquals(stationary(), awaitItem())

            playbackStateFlow.value = playing(TRACK_ID, positionMs = 250L)
            playbackStateFlow.value = playing(TRACK_ID, positionMs = 500L)
            playbackStateFlow.value = playing(TRACK_ID, positionMs = 750L)
            advanceUntilIdle()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { getTrackAnalysisUseCase(TRACK_ID) }
    }

    private fun stubObservationUseCases() {
        every { observePlaybackStateUseCase.invoke() } returns playbackStateFlow
        every { observeQueueStateUseCase.invoke() } returns queueStateFlow
        every { observeLikedSongIdsUseCase.invoke() } returns likedSongIdsFlow
        every { observeAudioTelemetryUseCase.invoke() } returns MutableStateFlow(AudioTelemetry.IDLE)
    }

    private fun createViewModel(): PlayerViewModel = PlayerViewModel(
        playTrackUseCase = mockk<PlayTrackUseCase>(relaxed = true),
        pausePlaybackUseCase = mockk<PausePlaybackUseCase>(relaxed = true),
        releaseUsbAudioUseCase = mockk<ReleaseUsbAudioUseCase>(relaxed = true),
        resumePlaybackUseCase = mockk<ResumePlaybackUseCase>(relaxed = true),
        seekToPositionUseCase = mockk<SeekToPositionUseCase>(relaxed = true),
        skipNextUseCase = mockk<SkipNextUseCase>(relaxed = true),
        skipPreviousUseCase = mockk<SkipPreviousUseCase>(relaxed = true),
        setRepeatModeUseCase = mockk<SetRepeatModeUseCase>(relaxed = true),
        setShuffleModeUseCase = mockk<SetShuffleModeUseCase>(relaxed = true),
        observePlaybackStateUseCase = observePlaybackStateUseCase,
        observeAudioTelemetryUseCase = observeAudioTelemetryUseCase,
        observeQueueStateUseCase = observeQueueStateUseCase,
        moveQueueItemUseCase = mockk<MoveQueueItemUseCase>(relaxed = true),
        clearQueueUseCase = mockk<ClearQueueUseCase>(relaxed = true),
        toggleLikeSongUseCase = mockk<ToggleLikeSongUseCase>(relaxed = true),
        observeLikedSongIdsUseCase = observeLikedSongIdsUseCase,
        getLyricsUseCase = mockk<GetLyricsUseCase>(relaxed = true),
        getTrackAnalysisUseCase = getTrackAnalysisUseCase,
        navigationManager = FakeNavigationManager(),
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper,
    )

    private fun playing(trackId: Long, positionMs: Long = 0L): PlaybackState = PlaybackState(
        status = PlaybackStatus.PLAYING,
        currentTrack = track(trackId),
        positionMs = positionMs,
        durationMs = 240_000L,
    )

    private fun track(trackId: Long): Track = Track(
        id = trackId,
        title = "Test Track",
        artistName = "Test Artist",
        albumTitle = "Test Album",
        albumId = 7L,
        durationMs = 240_000L,
        uri = "content://media/external/audio/media/$trackId",
        trackNumber = 1,
        discNumber = 1,
        audioFormat = AudioFormat.UNKNOWN,
        fileSizeBytes = 12_345L,
        dateAdded = 0L,
    )

    private fun analysis(): TrackAnalysis = TrackAnalysis(
        audioKey = "1:0a3f:9c8b7a6d5e4f3a2b1c0d9e8f7a6b5c4d",
        schemaVersion = TrackAnalysis.SCHEMA_VERSION,
        analysedAtEpochSeconds = 1_700_000_000L,
        stationary = stationary(),
        integral = null,
    )

    private fun stationary(): StationaryAnalysis = StationaryAnalysis(
        spectralRolloffHz = 19_500.0,
        spectralCentroidHz = 2_400.0,
        spectralSlope = -0.7,
        noiseFloorDbfs = -96.0,
        dcOffset = 0.0001,
        leftRmsDbfs = -14.0,
        rightRmsDbfs = -14.2,
        midRmsDbfs = -13.8,
        sideRmsDbfs = -22.0,
        interChannelCorrelation = 0.93,
        windowCount = 4,
        frameCount = 176_400L,
    )

    private companion object {
        const val TRACK_ID = 4_211L
        const val OTHER_TRACK_ID = 4_212L
    }
}
