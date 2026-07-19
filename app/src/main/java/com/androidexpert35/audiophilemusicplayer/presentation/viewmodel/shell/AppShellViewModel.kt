package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackStatus
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObservePlaybackStateUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PausePlaybackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RecordRecentlyPlayedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ResumePlaybackUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SkipNextUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SkipPreviousUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.common.PlaybackStrings
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.onError
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the global app shell — the floating bottom panel that
 * contains the mini-player and bottom navigation bar.
 *
 * Observes the current playback state and the active navigation route,
 * combining them into a single [AppShellUiModel] snapshot. Routes user
 * intents from the mini-player controls and bottom nav to the appropriate
 * domain use cases or navigation commands.
 *
 * Also records each newly-played track to the recently-played history via
 * [RecordRecentlyPlayedUseCase] so the library screen can sort by recency
 * without any coupling between the library and playback layers.
 *
 * @property pausePlaybackUseCase Pauses the current track.
 * @property resumePlaybackUseCase Resumes from the paused position.
 * @property skipNextUseCase Advances to the next track in the queue.
 * @property skipPreviousUseCase Returns to the previous track in the queue.
 * @property observePlaybackStateUseCase Observes playback state changes.
 * @property recordRecentlyPlayedUseCase Persists a play event when a new track starts.
 * @property navigationManager Provides the current route and dispatches navigation commands.
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    private val pausePlaybackUseCase: PausePlaybackUseCase,
    private val resumePlaybackUseCase: ResumePlaybackUseCase,
    private val skipNextUseCase: SkipNextUseCase,
    private val skipPreviousUseCase: SkipPreviousUseCase,
    private val observePlaybackStateUseCase: ObservePlaybackStateUseCase,
    private val recordRecentlyPlayedUseCase: RecordRecentlyPlayedUseCase,
    private val navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<AppShellUiModel, AppShellUiEvent, AppShellUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    /**
     * Tracks the currently in-flight transport command so that rapid taps
     * cancel the previous intent and only the latest one executes.
     */
    private var transportJob: Job? = null

    /**
     * Authoritative source of truth for the player overlay visibility.
     *
     * Kept as a [MutableStateFlow] (not inside [AppShellUiModel] directly) so
     * it can be combined with domain flows without losing its value on every
     * playback or route emission.
     */
    private val _isPlayerOpen = MutableStateFlow(false)

    /**
     * Single shared subscription to the playback engine state.
     *
     * Both the main shell model and [positionFlow] subscribe to this one hot
     * flow, avoiding two separate binder connections to the MediaController.
     */
    private val sharedPlaybackFlow = observePlaybackStateUseCase()
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1
        )

    /**
     * Dedicated position + duration stream for the mini-player progress indicator.
     *
     * Served independently from [AppShellUiModel] so that position ticks (~4 Hz
     * during playback) do NOT rebuild [AppShellUiModel] and recompose [AppShell]
     * or [AppBottomNavBar] on every update. Only the [LinearProgressIndicator]'s
     * draw pass re-runs when this flow emits.
     */
    val positionFlow: StateFlow<Pair<Long, Long>> = sharedPlaybackFlow
        .map { state -> state.positionMs to state.durationMs }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0L to 0L
        )

    init {
        observeShellState()
    }

    override fun handleEvent(event: AppShellUiEvent) {
        when (event) {
            is AppShellUiEvent.TogglePlayPause -> togglePlayPause()
            is AppShellUiEvent.SkipNext -> executePlaybackCommand { skipNextUseCase() }
            is AppShellUiEvent.SkipPrevious -> executePlaybackCommand { skipPreviousUseCase() }
            // Open the full-screen overlay; no navigation — the player is an overlay
            // above the NavHost so the library stays in composition behind it.
            is AppShellUiEvent.MiniPlayerClicked -> _isPlayerOpen.value = true
            is AppShellUiEvent.ClosePlayer -> _isPlayerOpen.value = false
            is AppShellUiEvent.BottomNavDestinationSelected -> {
                // Player-flagged destinations open the now-playing overlay instead of
                // navigating; all other destinations use standard route navigation.
                if (event.destination.isPlayerDestination) {
                    _isPlayerOpen.value = true
                } else {
                    navigateToRoute(event.destination.route)
                }
            }
        }
    }

    /**
     * Combines the playback state stream, navigation route stream, and
     * player-overlay visibility into a single [AppShellUiModel] snapshot.
     *
     * Also observes route changes to auto-close the player overlay when the
     * user navigates to another destination (e.g. Album or Artist) from within
     * the player screen. Records a history entry via [recordRecentlyPlayedUseCase]
     * whenever [PlaybackState.currentTrack] changes to a new, non-null track.
     *
     * Position-only playback state ticks are suppressed via [distinctUntilChanged]
     * so that the 4 Hz position updates do NOT rebuild the model or trigger
     * recomposition of [AppShell] and [AppBottomNavBar]. The progress bar in
     * [MiniPlayerBar] receives its position from [positionFlow] instead.
     */
    private fun observeShellState() {
        // Auto-close the overlay whenever the nav back-stack changes so that
        // navigating to Album / Artist from within the player dismisses it cleanly.
        navigationManager.currentRoute
            .onEach { _isPlayerOpen.value = false }
            .launchIn(viewModelScope)

        // Count only tracks that genuinely reach PLAYING. A restored paused item or
        // a merely buffered queue entry must not inflate the personal ranking.
        // Filtering before distinctUntilChanged also prevents pause/resume cycles
        // from counting as additional starts for the same loaded track.
        sharedPlaybackFlow
            .filter { playbackState -> playbackState.status == PlaybackStatus.PLAYING }
            .map { it.currentTrack?.id }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { trackId ->
                recordRecentlyPlayedUseCase(trackId)
            }
            .launchIn(viewModelScope)

        combine(
            // Suppress position-only emissions so the model — and therefore
            // AppShell + AppBottomNavBar — only recomposes when something
            // meaningful changes (track switch, play/pause, duration update).
            sharedPlaybackFlow.distinctUntilChanged { old, new ->
                old.currentTrack == new.currentTrack &&
                    old.status == new.status &&
                    old.durationMs == new.durationMs
            },
            navigationManager.currentRoute,
            _isPlayerOpen
        ) { playbackState, route, isPlayerOpen ->
            AppShellUiModel(
                playbackState = playbackState,
                currentRoute = route,
                isPlayerOpen = isPlayerOpen
            )
        }
            .onEach { model -> updateUiData(model) }
            .launchIn(viewModelScope)
    }

    /**
     * Toggles between play and pause based on the current playback status.
     *
     * Cancels any previous in-flight transport command and reads the playback
     * status *inside* the new coroutine so the decision is based on the latest
     * state at execution time, avoiding stale-read races from rapid taps.
     */
    private fun togglePlayPause() {
        transportJob?.cancel()
        transportJob = viewModelScope.launch(exceptionHandler) {
            val status = uiState.value.data?.playbackState?.status ?: return@launch
            when (status) {
                PlaybackStatus.PLAYING -> pausePlaybackUseCase().onError { error ->
                    val message = error?.toUserMessage() ?: PlaybackStrings.playbackCommandFailed
                    emitEffect(AppShellUiEffect.PlaybackError(message))
                }
                PlaybackStatus.PAUSED -> resumePlaybackUseCase().onError { error ->
                    val message = error?.toUserMessage() ?: PlaybackStrings.playbackCommandFailed
                    emitEffect(AppShellUiEffect.PlaybackError(message))
                }
                else -> { /* Idle / Buffering / Error — no-op */ }
            }
        }
    }

    /**
     * Executes a simple playback command and emits a [AppShellUiEffect.PlaybackError]
     * on failure.
     *
     * Cancels any previous in-flight transport command before launching the new one.
     */
    private fun executePlaybackCommand(
        command: suspend () -> Resource<Unit>
    ) {
        transportJob?.cancel()
        transportJob = viewModelScope.launch(exceptionHandler) {
            command().onError { error ->
                val message = error?.toUserMessage() ?: PlaybackStrings.playbackCommandFailed
                emitEffect(AppShellUiEffect.PlaybackError(message))
            }
        }
    }
}
