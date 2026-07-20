package com.androidexpert35.audiophilemusicplayer.presentation.screen.library

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.library.PlaylistKind
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.rememberTrackReorderState
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.DeletePlaylistDialog
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryEmptyState
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.PlaylistDetailActionRow
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.PlaylistDetailHeader
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.PlaylistDetailTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.playlistDetailTrackItems
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingSMedium
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingSmall
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.PlaylistOverviewUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.PlaylistOverviewUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.PlaylistOverviewUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.PlaylistOverviewViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Presents one local playlist with in-place search, playback controls, and ordering.
 *
 * @param playlistId Stable M3U filename received from navigation.
 * @param viewModel Hilt-provided state holder for the selected playlist.
 */
@Composable
fun PlaylistOverviewScreen(
    playlistId: String,
    viewModel: PlaylistOverviewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(playlistId) {
        viewModel.onEvent(PlaylistOverviewUiEvent.Initialize(playlistId))
    }
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is PlaylistOverviewUiEffect.Message -> snackbarHostState.showSnackbar(effect.message)
                PlaylistOverviewUiEffect.PlaylistUpdated -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.playlist_updated_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                PlaylistOverviewUiEffect.PlaylistDeleted -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.playlist_deleted_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.OVERLAY,
        statusBarColor = Color.Transparent,
        containerColor = Color.Transparent
    ) { model ->
        PlaylistOverviewContent(model, snackbarHostState, viewModel::onEvent)
    }
}

/**
 * Stateless Spotify-inspired playlist surface, kept separate from ViewModel collection wiring.
 */
@Composable
private fun PlaylistOverviewContent(
    model: PlaylistOverviewUiModel,
    snackbarHostState: SnackbarHostState,
    onEvent: (PlaylistOverviewUiEvent) -> Unit
) {
    val shellBottomPadding = LocalShellBottomPadding.current
    val listState = rememberLazyListState()
    val reorderState = rememberTrackReorderState(listState)
    val isSearchActive = model.isSearchActive
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchResultsTopPadding = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp() + 56.dp + paddingSmall + paddingSmall + paddingSMedium
    }
    val visibleTracks = model.visibleTracks

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) listState.scrollToItem(0)
    }

    val closeSearch = {
        focusManager.clearFocus()
        keyboardController?.hide()
        onEvent(PlaylistOverviewUiEvent.CloseSearch)
    }

    BackHandler(enabled = isSearchActive, onBack = closeSearch)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = if (isSearchActive) searchResultsTopPadding else 0.dp,
                bottom = shellBottomPadding + 20.dp
            )
        ) {
            if (!isSearchActive) {
                item(key = "playlist_header") {
                    PlaylistDetailHeader(
                        name = model.playlistName,
                        kind = model.playlistKind,
                        albumArtUris = model.albumArtUris,
                        trackCount = model.trackCount,
                        totalDurationMs = model.tracks.sumOf { it.durationMs },
                        unavailableTrackCount = model.unavailableTrackCount
                    )
                }
                item(key = "playlist_actions") {
                    PlaylistDetailActionRow(
                        isEditing = model.isEditing,
                        onPlayClick = { onEvent(PlaylistOverviewUiEvent.PlayPlaylist) },
                        onShuffleClick = { onEvent(PlaylistOverviewUiEvent.ShufflePlaylist) },
                        onEditClick = { onEvent(PlaylistOverviewUiEvent.ToggleEditing) },
                        onDeleteClick = if (model.playlistKind == PlaylistKind.STANDARD) {
                            { onEvent(PlaylistOverviewUiEvent.ShowDeletePlaylistDialog) }
                        } else {
                            null
                        }
                    )
                }
            }
            if (visibleTracks.isEmpty()) {
                item(key = "playlist_empty") {
                    LibraryEmptyState(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = stringResource(
                            if (model.searchQuery.isBlank()) R.string.playlist_empty_tracks_title
                            else R.string.playlist_search_empty_title
                        ),
                        message = stringResource(
                            if (model.searchQuery.isBlank()) R.string.playlist_empty_tracks_message
                            else R.string.playlist_search_empty_message
                        ),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)
                    )
                }
            } else {
                playlistDetailTrackItems(
                    tracks = visibleTracks,
                    reorderState = reorderState,
                    firstTrackListIndex = if (isSearchActive) 0 else PLAYLIST_TRACK_FIRST_LIST_INDEX,
                    isEditing = model.isEditing && model.searchQuery.isBlank(),
                    currentPlayingTrackId = model.currentPlayingTrackId,
                    onTrackClick = { track -> onEvent(PlaylistOverviewUiEvent.PlayTrack(track)) },
                    onMove = { from, to -> onEvent(PlaylistOverviewUiEvent.MoveTrack(from, to)) },
                    onRemove = { index -> onEvent(PlaylistOverviewUiEvent.RemoveTrack(index)) },
                    onPlayNext = { track -> onEvent(PlaylistOverviewUiEvent.PlayNext(track)) },
                    onAddToQueue = { track -> onEvent(PlaylistOverviewUiEvent.AddToQueue(track)) },
                    onGoToAlbum = { track -> onEvent(PlaylistOverviewUiEvent.OpenTrackAlbum(track)) },
                    onGoToArtist = { track -> onEvent(PlaylistOverviewUiEvent.OpenTrackArtist(track)) }
                )
            }
        }

        PlaylistDetailTopBar(
            query = model.searchQuery,
            onQueryChange = { onEvent(PlaylistOverviewUiEvent.UpdateSearchQuery(it)) },
            onSearchActiveChange = { onEvent(PlaylistOverviewUiEvent.SetSearchActive(it)) },
            onNavigateBack = {
                if (isSearchActive) closeSearch()
                else onEvent(PlaylistOverviewUiEvent.NavigateBack)
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = shellBottomPadding + 8.dp)
        )

        if (model.isDeletePlaylistDialogVisible) {
            DeletePlaylistDialog(
                playlistName = model.playlistName,
                onDismiss = { onEvent(PlaylistOverviewUiEvent.DismissDeletePlaylistDialog) },
                onConfirm = { onEvent(PlaylistOverviewUiEvent.ConfirmDeletePlaylist) }
            )
        }
    }
}

/** Header and action controls always occupy the first two entries in the parent lazy list. */
private const val PLAYLIST_TRACK_FIRST_LIST_INDEX = 2
