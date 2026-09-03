package com.androidexpert35.audiophilemusicplayer.presentation.screen.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.TelemetryStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.BlurredBackground
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.PlayerArtworkCarousel
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.PlayerBottomSheet
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.PlayerContextSection
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.PlayerControlsCard
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.PlayerDragHandle
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.PlayerEmptyState
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.PlayerOutputMenu
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingMedium
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingSmall
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.LyricsState
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Now-playing screen entry point.
 *
 * Collects [PlayerViewModel.uiState], [PlayerViewModel.positionFlow],
 * [PlayerViewModel.telemetryFlow], and [PlayerViewModel.lyricsFlow], then delegates
 * rendering to the stateless [PlayerContent] composable.
 *
 * `telemetryFlow`, `positionFlow`, and `lyricsFlow` are collected without `by` (as
 * [State] references) so that [PlayerScreen] does **not** subscribe to their individual
 * ticks. Only the leaf composables (`AudioInfoRow`, `SeekBar`, `LyricsSheet`) read the
 * [State.value] inside their own composition bodies, scoping recompositions to those
 * narrow leaves.
 *
 * @param isOpen Whether the player overlay is logically visible. Used by
 *   [PlayerBottomSheet] to reset its internal drag offset when the player reopens.
 * @param onDismissRequest Callback closing the sheet and returning to the previous destination.
 * @param viewModel Hilt-provided ViewModel instance.
 */
@Composable
fun PlayerScreen(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = LocalContext.current.findActivity()

    val positionState: State<Pair<Long, Long>> =
        viewModel.positionFlow.collectAsStateWithLifecycle()

    val telemetryState: State<AudioTelemetry> =
        viewModel.telemetryFlow.collectAsStateWithLifecycle()

    // Cached offline measurements of the playing track. Collected without `by` for the
    // same reason as the streams above, and read only inside the open telemetry sheet.
    val measuredSignalState: State<StationaryAnalysis?> =
        viewModel.measuredSignalFlow.collectAsStateWithLifecycle()

    // Collected without `by` so PlayerScreen does not subscribe to lyrics ticks.
    // Only LyricsSheet reads lyricsState.value inside its body.
    val lyricsState: State<LyricsState> =
        viewModel.lyricsFlow.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is PlayerUiEffect.PlaybackError -> snackbarHostState.showSnackbar(effect.message)
                is PlayerUiEffect.UsbAudioReleased -> snackbarHostState.showSnackbar(effect.message)
                PlayerUiEffect.ExitApplication -> activity?.finishAndRemoveTask()
                is PlayerUiEffect.TrackChanged -> Unit // Could trigger haptic feedback
            }
        }
    }



    Box(modifier = Modifier.fillMaxSize()) {
        PlayerBottomSheet(
            isOpen = isOpen,
            onDismissRequest = onDismissRequest
        ) {
            AppBaseScreen(
                uiState = uiState,
                onErrorDialogDismiss = viewModel::dismissErrorPopup,
                loadingType = BaseLoadingType.NONE,
                statusBarColor = Color.Transparent,
                useLightStatusIcons = true,
                containerColor = Color.Transparent,
                content = { model ->
                    PlayerContent(
                        model = model,
                        positionState = positionState,
                        telemetryState = telemetryState,
                        measuredSignalState = measuredSignalState,
                        lyricsState = lyricsState,
                        snackbarHostState = snackbarHostState,
                        onEvent = viewModel::onEvent
                    )
                }
            )
        }
    }
}

/**
 * Stateless content composable for the now-playing screen.
 *
 * Renders the full player layout using a fixed vertical [Column]. All recomposition
 * optimisations are applied here:
 * 1. `buildTrackSupportingText` and `estimateBitrateKbps` are wrapped in
 *    `remember(track.id)` to avoid re-allocating on every recomposition.
 * 2. [telemetryState] is passed down without reading `.value` here; only
 *    `PlayerTelemetrySection` → `AudioInfoRow` subscribes to telemetry ticks.
 * 3. [positionState] is passed to [PlayerControlsCard] as lambdas; only `SeekBar`
 *    reads the position inside its own `derivedStateOf` blocks.
 * 4. [lyricsState] is forwarded to [PlayerContextSection] without reading `.value`
 *    here; only `LyricsSheet` reads it inside its own composition body.
 * 5. Dialog visibility states live inside [PlayerContextSection] and
 *    `PlayerTelemetrySection` so toggling a dialog never recomposes this tree.
 *
 * @param model Current immutable UI state (no telemetry — delivered separately).
 * @param positionState Live position + duration [State]. Read only inside `SeekBar`.
 * @param telemetryState Live telemetry [State]. Read only inside `AudioInfoRow`.
 * @param measuredSignalState Cached measured-signal [State] for the current track.
 *   Read only inside the open telemetry sheet.
 * @param lyricsState Live lyrics [State]. Read only inside `LyricsSheet`.
 * @param snackbarHostState Host for displaying transient error messages.
 * @param onEvent Callback emitting user intents to the ViewModel.
 */
@Composable
private fun PlayerContent(
    model: PlayerUiModel,
    positionState: State<Pair<Long, Long>>,
    telemetryState: State<AudioTelemetry>,
    measuredSignalState: State<StationaryAnalysis?>,
    lyricsState: State<LyricsState>,
    snackbarHostState: SnackbarHostState,
    onEvent: (PlayerUiEvent) -> Unit
) {
    val currentTrack = model.playbackState.currentTrack

    Box(modifier = Modifier.fillMaxSize()) {
        // Solid background fallback shown before BlurredBackground renders
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )

        if (currentTrack != null) {
            BlurredBackground(albumId = currentTrack.albumId)
        }

        AnimatedVisibility(
            visible = currentTrack != null,
            enter = fadeIn(tween(MotionTokens.DurationMedium, easing = MotionTokens.EasingEmphasizedDecelerate)) +
                slideInVertically(
                    animationSpec = tween(MotionTokens.DurationLong, easing = MotionTokens.EasingEmphasizedDecelerate),
                    initialOffsetY = { it / 10 }
                ),
            exit = fadeOut(tween(MotionTokens.DurationShort, easing = MotionTokens.EasingEmphasizedAccelerate)) +
                slideOutVertically(
                    animationSpec = tween(MotionTokens.DurationShort, easing = MotionTokens.EasingEmphasizedAccelerate),
                    targetOffsetY = { it / 12 }
                )
        ) {
            currentTrack?.let { track ->
                // Allocation memoization: these calls use map/filter/joinToString and
                // arithmetic — wrapping in remember(track.id) ensures they run once per
                // track, not on every recomposition triggered by playback state changes.
                val supportingText = remember(track.id) { buildTrackSupportingText(track) }
                val estimatedBitrateKbps = remember(track.id) { estimateBitrateKbps(track) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = paddingSmall),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        PlayerDragHandle(modifier = Modifier.align(Alignment.TopCenter))
                        PlayerOutputMenu(
                            onReleaseDac = { onEvent(PlayerUiEvent.ReleaseUsbAudio) },
                            onExitAndRelease = {
                                onEvent(PlayerUiEvent.ExitAndReleaseUsbAudio)
                            },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }

                    Spacer(modifier = Modifier.height(paddingMedium))

                    // Dialog states live inside PlayerContextSection — toggling the queue
                    // sheet, lyrics sheet, or destination dialog recomposes only that narrow
                    // composable. lyricsState is forwarded without reading .value here.
                    PlayerContextSection(
                        track = track,
                        queueState = model.queueState,
                        lyricsState = lyricsState,
                        positionMs = { positionState.value.first },
                        onEvent = onEvent,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = paddingSmall)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    PlayerArtworkCarousel(
                        track = track,
                        queueTracks = model.queueState.tracks,
                        onTrackClick = { displayedTrack ->
                            onEvent(PlayerUiEvent.NavigateToAlbum(displayedTrack.albumId))
                        },
                        onSkipNext = { onEvent(PlayerUiEvent.SkipNext) },
                        onSkipPrevious = { onEvent(PlayerUiEvent.SkipPrevious) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    PlayerControlsCard(
                        title = track.title,
                        trackId = track.id,
                        supportingText = supportingText,
                        isFavorite = model.likedSongIds.contains(track.id),
                        playbackStatus = model.playbackState.status,
                        shuffleMode = model.queueState.shuffleMode,
                        repeatMode = model.queueState.repeatMode,
                        audioFormat = track.audioFormat,
                        telemetryState = telemetryState,
                        measuredSignalState = measuredSignalState,
                        fallbackBitrateKbps = estimatedBitrateKbps,
                        albumId = track.albumId,
                        positionState = positionState,
                        onEvent = onEvent,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        if (currentTrack == null) {
            PlayerEmptyState()
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Player Screen - Playing", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerScreenPlayingPreview() {
    val snackbarHostState = remember { SnackbarHostState() }
    val previewModel = previewPlayerUiModel()
    val positionState: State<Pair<Long, Long>> = remember {
        mutableStateOf(previewModel.playbackState.positionMs to previewModel.playbackState.durationMs)
    }
    val telemetryState: State<AudioTelemetry> = remember {
        mutableStateOf(previewAudioTelemetry())
    }
    val measuredSignalState: State<StationaryAnalysis?> = remember { mutableStateOf(null) }
    val lyricsState: State<LyricsState> = remember { mutableStateOf(LyricsState.Idle) }

    AudiophileMusicPlayerTheme {
        PlayerBottomSheet(
            isOpen = true,
            onDismissRequest = {}
        ) {
            PlayerContent(
                model = previewModel,
                positionState = positionState,
                telemetryState = telemetryState,
                measuredSignalState = measuredSignalState,
                lyricsState = lyricsState,
                snackbarHostState = snackbarHostState,
                onEvent = {}
            )
        }
    }
}

@Preview(name = "Player Screen - Empty", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerScreenEmptyPreview() {
    val snackbarHostState = remember { SnackbarHostState() }
    val positionState: State<Pair<Long, Long>> = remember { mutableStateOf(0L to 0L) }
    val telemetryState: State<AudioTelemetry> = remember { mutableStateOf(AudioTelemetry.IDLE) }
    val measuredSignalState: State<StationaryAnalysis?> = remember { mutableStateOf(null) }
    val lyricsState: State<LyricsState> = remember { mutableStateOf(LyricsState.Idle) }

    AudiophileMusicPlayerTheme {
        PlayerBottomSheet(
            isOpen = true,
            onDismissRequest = {}
        ) {
            PlayerContent(
                model = PlayerUiModel(),
                positionState = positionState,
                telemetryState = telemetryState,
                measuredSignalState = measuredSignalState,
                lyricsState = lyricsState,
                snackbarHostState = snackbarHostState,
                onEvent = {}
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Preview data helpers
// ---------------------------------------------------------------------------

private fun previewPlayerUiModel(): PlayerUiModel {
    val track = previewTrack()
    return PlayerUiModel(
        playbackState = PlaybackState(
            status = PlaybackStatus.PLAYING,
            currentTrack = track,
            positionMs = 97_000L,
            durationMs = track.durationMs,
            playbackSpeed = 1.0f
        ),
        queueState = QueueState(
            tracks = listOf(track),
            currentIndex = 0,
            repeatMode = RepeatMode.ALL,
            shuffleMode = ShuffleMode.OFF
        )
    )
}

private fun previewAudioTelemetry(): AudioTelemetry = AudioTelemetry(
    streamInfo = com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo.Pcm(
        codec = AudioCodec.FLAC,
        sampleRateHz = 96_000,
        bitDepth = 24,
        bitrateKbps = 3_200,
    ),
    isOffloaded = TelemetryStatus.ACTIVE,
    isDirectPlayback = TelemetryStatus.ACTIVE,
    isBitPerfect = TelemetryStatus.ACTIVE,
)

private fun previewTrack(): Track = Track(
    id = 1L,
    title = "So What",
    artistName = "Miles Davis",
    albumTitle = "Kind of Blue",
    albumId = 7L,
    durationMs = 565_000L,
    uri = "content://media/external/audio/media/1",
    trackNumber = 1,
    discNumber = 1,
    audioFormat = AudioFormat(
        sampleRateHz = 96_000,
        bitDepth = 24,
        channelCount = 2,
        codec = AudioCodec.FLAC,
        isLossless = true
    ),
    fileSizeBytes = 226_000_000L,
    dateAdded = 0L
)

/** Finds the host activity through any Compose theme wrappers. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
