package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.usecase.GetLyricsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.MoveQueueItemUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudioTelemetryUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLikedSongIdsUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveQueueStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PausePlaybackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ResumePlaybackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SeekToPositionUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetRepeatModeUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetShuffleModeUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SkipNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SkipPreviousUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ToggleLikeSongUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.common.PlaybackStrings
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerViewModel.Companion.PLAY_PAUSE_DEBOUNCE_MS
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.fold
import com.tony.coreui.domain.resource.onError
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the now-playing / player screen.
 *
 * Combines the three core reactive streams — playback state, audio telemetry,
 * and queue state — into a single [PlayerUiModel] snapshot. Routes all user
 * intents ([PlayerUiEvent]) to the corresponding domain use cases.
 *
 * The audio telemetry stream is the audiophile's "instrument panel": it
 * reports what the hardware is *actually* processing (sample rate, bit depth,
 * codec, offload status) in real time.
 *
 * Lyrics are fetched lazily: only when the user taps the Lyrics button is
 * [GetLyricsUseCase] invoked. In-flight requests are cancelled and the
 * [lyricsFlow] is reset to [LyricsState.Idle] whenever the current track changes.
 *
 * @property playTrackUseCase Starts playback of a track within a queue.
 * @property pausePlaybackUseCase Pauses the current track.
 * @property resumePlaybackUseCase Resumes from the paused position.
 * @property seekToPositionUseCase Seeks to a specific position.
 * @property skipNextUseCase Advances to the next track.
 * @property skipPreviousUseCase Returns to the previous track.
 * @property setRepeatModeUseCase Sets the repeat mode.
 * @property setShuffleModeUseCase Sets the shuffle mode.
 * @property observePlaybackStateUseCase Observes playback state changes.
 * @property observeAudioTelemetryUseCase Observes real-time audio telemetry.
 * @property observeQueueStateUseCase Observes queue state changes.
 * @property moveQueueItemUseCase Repositions one item in the active playback queue.
 * @property toggleLikeSongUseCase Toggles the liked state of a track.
 * @property observeLikedSongIdsUseCase Live stream of liked track IDs, used to
 *   keep the heart icon in the now-playing panel in sync with the library.
 * @property getLyricsUseCase Fetches synchronized or plain-text lyrics from LRCLIB.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playTrackUseCase: PlayTrackUseCase,
    private val pausePlaybackUseCase: PausePlaybackUseCase,
    private val resumePlaybackUseCase: ResumePlaybackUseCase,
    private val seekToPositionUseCase: SeekToPositionUseCase,
    private val skipNextUseCase: SkipNextUseCase,
    private val skipPreviousUseCase: SkipPreviousUseCase,
    private val setRepeatModeUseCase: SetRepeatModeUseCase,
    private val setShuffleModeUseCase: SetShuffleModeUseCase,
    private val observePlaybackStateUseCase: ObservePlaybackStateUseCase,
    private val observeAudioTelemetryUseCase: ObserveAudioTelemetryUseCase,
    private val observeQueueStateUseCase: ObserveQueueStateUseCase,
    private val moveQueueItemUseCase: MoveQueueItemUseCase,
    private val toggleLikeSongUseCase: ToggleLikeSongUseCase,
    private val observeLikedSongIdsUseCase: ObserveLikedSongIdsUseCase,
    private val getLyricsUseCase: GetLyricsUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<PlayerUiModel, PlayerUiEvent, PlayerUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    /**
     * Tracks the currently in-flight transport command (seek, skip).
     * Cancelled and replaced on each new command so that rapid taps do not pile up
     * concurrent coroutines racing against the playback engine.
     *
     * Play/pause commands use a separate debounce strategy ([lastPlayPauseTimestamp])
     * and do NOT cancel in-flight work, since cancelling a partially-dispatched
     * play or pause command creates an orphaned IPC message that corrupts the
     * ExoPlayer state machine.
     */
    private var transportJob: Job? = null

    /**
     * Timestamp (in [SystemClock.elapsedRealtime] ms) of the last play or pause command.
     *
     * Used by [executePlayPauseCommand] to gate rapid consecutive toggles within the
     * [PLAY_PAUSE_DEBOUNCE_MS] window, preventing the MediaController IPC queue from
     * being flooded with contradictory play/pause commands before ExoPlayer finishes
     * its previous state transition.
     */
    private var lastPlayPauseTimestamp = 0L

    /**
     * Backing mutable flow for lyrics state.
     *
     * Reset to [LyricsState.Idle] whenever the current track changes so stale
     * lyrics never bleed across track boundaries.
     */
    private val _lyricsFlow = MutableStateFlow<LyricsState>(LyricsState.Idle)

    /**
     * Current lyrics state for the now-playing track.
     *
     * Served independently from [PlayerUiModel] — lyrics state transitions
     * (Idle → Loading → Success/Error) do NOT rebuild the entire model tree
     * and only the [LyricsSheet] composable observes this flow.
     */
    val lyricsFlow: StateFlow<LyricsState> = _lyricsFlow.asStateFlow()

    /**
     * In-flight lyrics fetch coroutine.
     *
     * Cancelled on each new [PlayerUiEvent.RequestLyrics] call so rapid
     * re-taps don't queue concurrent network requests. Also cancelled when
     * the current track changes in [observePlaybackStreams].
     */
    private var lyricsJob: Job? = null

    /**
     * Single shared subscription to the playback engine so the two downstream
     * observers ([positionFlow] and the model combine) do not open separate
     * MediaController binder connections.
     */
    private val sharedPlaybackFlow = observePlaybackStateUseCase()
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1
        )

    /**
     * Dedicated position + duration stream for [SeekBar].
     *
     * Served independently from [PlayerUiModel] so that position ticks (~4 Hz
     * during playback) do NOT rebuild [PlayerUiModel] and recompose the entire
     * [PlayerContent] tree. Only [SeekBar]'s [derivedStateOf] observers re-run
     * when this flow emits.
     */
    val positionFlow: StateFlow<Pair<Long, Long>> = sharedPlaybackFlow
        .map { state -> state.positionMs to state.durationMs }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0L to 0L
        )

    /**
     * Isolated audio telemetry stream for [AudioInfoRow].
     *
     * Served independently from [PlayerUiModel] so that fast-changing telemetry
     * properties (buffer utilization, bitrate) do NOT rebuild [PlayerUiModel] and
     * force the entire [PlayerContent] tree — album art, controls, seek bar — to
     * recompose. Only [AudioInfoRow] subscribes by reading the [State] produced from
     * this flow directly inside its composition body.
     */
    val telemetryFlow: StateFlow<AudioTelemetry> = observeAudioTelemetryUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AudioTelemetry.IDLE
        )

    init {
        updateUiData(PlayerUiModel())
        observePlaybackStreams()
    }

    override fun handleEvent(event: PlayerUiEvent) {
        when (event) {
            is PlayerUiEvent.Play -> playTrack(event)
            is PlayerUiEvent.Pause -> executePlayPauseCommand { pausePlaybackUseCase() }
            is PlayerUiEvent.Resume -> executePlayPauseCommand { resumePlaybackUseCase() }
            is PlayerUiEvent.SkipNext -> executePlaybackCommand { skipNextUseCase() }
            is PlayerUiEvent.SkipPrevious -> executePlaybackCommand { skipPreviousUseCase() }
            is PlayerUiEvent.SeekTo -> executePlaybackCommand { seekToPositionUseCase(event.positionMs) }
            is PlayerUiEvent.SetRepeatMode -> executeAsync { setRepeatModeUseCase(event.mode) }
            is PlayerUiEvent.SetShuffleMode -> executeAsync { setShuffleModeUseCase(event.mode) }
            is PlayerUiEvent.MoveQueueItem -> moveQueueItem(event.fromIndex, event.toIndex)
            is PlayerUiEvent.NavigateToAlbum -> openAlbumOverview(event.albumId)
            is PlayerUiEvent.NavigateToArtist -> openArtistProfile(event.artistName)
            is PlayerUiEvent.ToggleLikeSong -> toggleLike(event.trackId)
            is PlayerUiEvent.RequestLyrics -> fetchLyrics()
        }
    }

    /** Opens the album overview after dismissing the modal player destination. */
    private fun openAlbumOverview(albumId: Long) {
        if (albumId == 0L) return
        navigateAwayFromPlayer(
            AppRoutes.albumOverviewRoute(albumId)
        )
    }

    /** Opens the artist profile after dismissing the modal player destination. */
    private fun openArtistProfile(artistName: String) {
        val normalizedArtistName = artistName.trim()
        if (normalizedArtistName.isBlank()) return

        navigateAwayFromPlayer(
            AppRoutes.artistDescriptionRoute(normalizedArtistName)
        )
    }

    /**
     * Dismisses the player sheet first so contextual destinations replace it instead
     * of stacking behind the modal player dialog in the back stack.
     */
    private fun navigateAwayFromPlayer(route: String) {
        popBackStack()
        navigateToRoute(route)
    }

    /**
     * Toggles the liked state of the given track and emits a [PlayerUiEffect.PlaybackError]
     * on failure so the UI can surface a transient snackbar.
     *
     * @param trackId The MediaStore ID of the track to like or unlike.
     */
    private fun toggleLike(trackId: Long) {
        viewModelScope.launch(exceptionHandler) {
            toggleLikeSongUseCase(trackId).onError { error ->
                emitEffect(
                    PlayerUiEffect.PlaybackError(
                        error?.toUserMessage() ?: PlaybackStrings.likedSongsUpdateFailed
                    )
                )
            }
        }
    }

    /**
     * Combines the four core reactive streams — playback state, audio telemetry,
     * queue state, and liked-song IDs — into a single [PlayerUiModel] snapshot
     * and pushes updates through the UDF state pipeline.
     *
     * Position-only playback state ticks are suppressed via [distinctUntilChanged]
     * so that the 4 Hz position updates do NOT rebuild [PlayerUiModel] or trigger
     * recomposition of [PlayerContent] and its expensive children (artwork,
     * controls, etc.). The [SeekBar] receives live position via [positionFlow].
     *
     * Including liked-song IDs here means the heart icon in the now-playing panel
     * stays in sync with the library screen without any additional polling.
     */
    private fun observePlaybackStreams() {
        combine(
            // Suppress position-only ticks — only rebuild the model when something
            // meaningful changes so PlayerContent and its subtree stay stable.
            sharedPlaybackFlow.distinctUntilChanged { old, new ->
                old.currentTrack == new.currentTrack &&
                    old.status == new.status &&
                    old.durationMs == new.durationMs
            },
            observeQueueStateUseCase(),
            observeLikedSongIdsUseCase()
        ) { playbackState, queueState, likedSongIds ->
            PlayerUiModel(
                playbackState = playbackState,
                queueState = queueState,
                likedSongIds = likedSongIds
            )
        }
            .onEach { model ->
                // Capture the previous track BEFORE calling updateUiData so the
                // comparison below sees old vs. new correctly. Reading from
                // uiState.value.data after updateUiData() would return the new
                // track on both sides, making the condition permanently false.
                val previousTrack = uiState.value.data?.playbackState?.currentTrack

                updateUiData(model)

                // Emit a TrackChanged effect when the current track changes,
                // so the UI can scroll or animate the now-playing indicator.
                model.playbackState.currentTrack?.let { track ->
                    if (previousTrack?.id != track.id) {
                        emitEffect(PlayerUiEffect.TrackChanged(track))
                        // Cancel any in-flight lyrics request and reset to Idle so
                        // the new track starts without stale lyrics from the previous one.
                        lyricsJob?.cancel()
                        _lyricsFlow.value = LyricsState.Idle
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Starts playback of a specific track within its queue context.
     */
    private fun playTrack(event: PlayerUiEvent.Play) {
        viewModelScope.launch(exceptionHandler) {
            playTrackUseCase(event.track, event.queue)
                .onError { error ->
                    val message = error?.toUserMessage() ?: PlaybackStrings.startPlaybackFailed
                    emitEffect(PlayerUiEffect.PlaybackError(message))
                }
        }
    }

    /** Applies one queue-editor move without cancelling adjacent drag commands. */
    private fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch(exceptionHandler) {
            moveQueueItemUseCase(fromIndex, toIndex)
                .onError { error ->
                    val message = error?.toUserMessage() ?: PlaybackStrings.playbackCommandFailed
                    emitEffect(PlayerUiEffect.PlaybackError(message))
                }
        }
    }

    /**
     * Fetches lyrics for the currently playing track.
     *
     * No-ops when there is no active track. Cancels any previous in-flight lyrics
     * request before starting a new one. Already-`Success` state is re-fetched if
     * the user retaps — the repository will serve the cached result instantly.
     */
    private fun fetchLyrics() {
        val currentTrack = uiState.value.data?.playbackState?.currentTrack ?: return
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch(exceptionHandler) {
            _lyricsFlow.value = LyricsState.Loading
            val durationSeconds = (currentTrack.durationMs / 1_000).toInt()
            val result = getLyricsUseCase(
                trackTitle = currentTrack.title,
                artistName = currentTrack.artistName,
                albumName = currentTrack.albumTitle,
                durationSeconds = durationSeconds,
            )
            _lyricsFlow.value = result.fold(
                onSuccess = { lyrics ->
                    when {
                        lyrics.isInstrumental -> LyricsState.Instrumental
                        lyrics.lines.isNotEmpty() -> LyricsState.Success(lyrics)
                        !lyrics.plainLyrics.isNullOrBlank() -> LyricsState.Success(lyrics)
                        else -> LyricsState.NotFound
                    }
                },
                onError = { error ->
                    LyricsState.Error(error?.toUserMessage() ?: PlaybackStrings.lyricsFetchFailed)
                }
            )
        }
    }


    /**
     * Debounced dispatcher for play and pause commands.
     *
     * Unlike [executePlaybackCommand], this method does **not** cancel any in-flight
     * job. Cancelling a play/pause coroutine that has already dispatched an IPC command
     * to the [MediaController] creates an orphaned command — the IPC message has already
     * been enqueued on the binder thread but the coroutine's post-command logic (state
     * update, error handling) is aborted, leaving the ViewModel state out of sync with
     * the actual ExoPlayer state.
     *
     * Instead, commands are gated by [PLAY_PAUSE_DEBOUNCE_MS]: if the user taps faster
     * than the debounce window, excess events are silently dropped. The first command
     * in the burst is fully dispatched and completes cleanly, and ExoPlayer's state
     * machine receives exactly one well-ordered command per toggle.
     *
     * @param command The suspend function representing the domain play or pause call.
     */
    private fun executePlayPauseCommand(
        command: suspend () -> Resource<Unit>
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayPauseTimestamp < PLAY_PAUSE_DEBOUNCE_MS) return
        lastPlayPauseTimestamp = now

        viewModelScope.launch(exceptionHandler) {
            command()
                .onError { error ->
                    val message = error?.toUserMessage() ?: PlaybackStrings.playbackCommandFailed
                    emitEffect(PlayerUiEffect.PlaybackError(message))
                }
        }
    }

    /**
     * Executes a simple playback command (skip, seek) and
     * emits a [PlayerUiEffect.PlaybackError] on failure.
     *
     * Cancels any previous in-flight transport command before launching the
     * new one so that rapid taps only execute the most recent intent. This
     * cancel-latest strategy is safe for seek and skip because these commands
     * are idempotent and position-bearing — the latest value is always correct.
     *
     * Do NOT use this for play/pause; use [executePlayPauseCommand] instead.
     */
    private fun executePlaybackCommand(
        command: suspend () -> Resource<Unit>
    ) {
        transportJob?.cancel()
        transportJob = viewModelScope.launch(exceptionHandler) {
            command()
                .onError { error ->
                    val message = error?.toUserMessage() ?: PlaybackStrings.playbackCommandFailed
                    emitEffect(PlayerUiEffect.PlaybackError(message))
                }
        }
    }

    private companion object {
        /**
         * Minimum interval between consecutive play or pause commands in milliseconds.
         *
         * Commands arriving within this window are dropped. 300 ms is chosen to be
         * comfortably longer than ExoPlayer's typical state-transition latency on the
         * offload audio path (~100–200 ms on common SoCs), yet short enough to remain
         * imperceptible to deliberate user interaction.
         */
        const val PLAY_PAUSE_DEBOUNCE_MS = 300L
    }
}
