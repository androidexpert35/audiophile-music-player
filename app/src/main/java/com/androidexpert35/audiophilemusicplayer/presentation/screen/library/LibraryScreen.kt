package com.androidexpert35.audiophilemusicplayer.presentation.screen.library

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.CreatePlaylistDialog
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.PlaylistPickerDialog
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryFilterChipsRow
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryGridContent
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryHeaderRow
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryListContent
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Library screen entry point.
 *
 * Collects the library ViewModel state and renders the Spotify-style indexed local
 * catalogue, exposing tracks, albums, and artists through filter chips, a sort/view
 * toggle bar, and a dynamic list or two-column grid.
 *
 * @param viewModel Hilt-provided library ViewModel instance.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is LibraryUiEffect.PlaybackError -> snackbarHostState.showSnackbar(effect.message)
                is LibraryUiEffect.QueueUpdated -> snackbarHostState.showSnackbar(effect.message)
                is LibraryUiEffect.ScanError -> snackbarHostState.showSnackbar(effect.message)
                is LibraryUiEffect.ScanComplete -> Unit
                is LibraryUiEffect.PlaylistSuccess -> {
                    val message = effect.trackTitle?.let { trackTitle ->
                        context.getString(R.string.playlist_track_added_success, trackTitle, effect.playlistName)
                    } ?: context.getString(R.string.playlist_created_success, effect.playlistName)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.OVERLAY,
        content = { model ->
            LibraryContent(
                model = model,
                snackbarHostState = snackbarHostState,
                onEvent = viewModel::onEvent
            )
        }
    )
}

/**
 * Stateless Spotify-style library catalogue content.
 *
 * Provides a sticky header area (profile row + filter chips) that remains visible
 * at all times, with the sort/toggle row and item list or two-column grid scrolling
 * beneath it via [LibraryListContent] and [LibraryGridContent] respectively.
 * The mini-player and bottom navigation bar are managed by the shared [AppShell].
 *
 * @param model Current immutable library UI snapshot.
 * @param snackbarHostState Host for transient error messages.
 * @param onEvent Callback emitting user intents back to the ViewModel.
 */
@Composable
private fun LibraryContent(
    model: LibraryUiModel,
    snackbarHostState: SnackbarHostState,
    onEvent: (LibraryUiEvent) -> Unit
) {
    // Artists and Playlists are always rendered in list mode; the grid toggle only
    // applies to Songs and Albums.
    val showGridLayout = model.isGridView &&
        model.selectedContentType != LibraryContentType.ARTISTS &&
        model.selectedContentType != LibraryContentType.PLAYLISTS

    // Bottom padding sourced from the shell panel so content is never hidden under
    // the floating mini-player + bottom navigation bar.
    val shellBottomPadding = LocalShellBottomPadding.current

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = shellBottomPadding)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // Profile avatar | bold "Library" title | Refresh | Add (+)
            LibraryHeaderRow(
                onRefreshClick = { onEvent(LibraryUiEvent.RefreshLibrary) },
                isRefreshing = model.isRefreshing,
                onAddClick = { onEvent(LibraryUiEvent.ShowCreatePlaylistDialog) }
            )

            // Songs | Playlists | Albums | Artists — deselect by re-tapping.
            LibraryFilterChipsRow(
                selectedContentType = model.selectedContentType,
                onSelectContentType = { onEvent(LibraryUiEvent.SelectContentType(it)) }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Crossfade between list and grid modes when the view toggle is tapped.
            AnimatedContent(
                targetState = showGridLayout,
                label = "LibraryViewModeTransition",
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                transitionSpec = {
                    fadeIn(tween(MotionTokens.DurationMedium)) togetherWith
                        fadeOut(tween(MotionTokens.DurationShort))
                }
            ) { isGrid ->
                if (isGrid) {
                    LibraryGridContent(
                        model = model,
                        playingTrackIdProvider = { null }, // Stubbed until ViewModel exposes playbackState
                        shellBottomPadding = shellBottomPadding,
                        onEvent = onEvent
                    )
                } else {
                    LibraryListContent(
                        model = model,
                        playingTrackIdProvider = { null }, // Stubbed until ViewModel exposes playbackState
                        shellBottomPadding = shellBottomPadding,
                        onEvent = onEvent
                    )
                }
            }

            if (model.isCreatePlaylistDialogVisible) {
                CreatePlaylistDialog(
                    onDismiss = { onEvent(LibraryUiEvent.DismissCreatePlaylistDialog) },
                    onConfirm = { name -> onEvent(LibraryUiEvent.CreatePlaylist(name)) }
                )
            }

            model.playlistPickerTrack?.let {
                PlaylistPickerDialog(
                    playlists = model.playlists,
                    onDismiss = { onEvent(LibraryUiEvent.DismissPlaylistPicker) },
                    onPlaylistSelected = { playlistId ->
                        onEvent(LibraryUiEvent.AddTrackToPlaylist(playlistId))
                    }
                )
            }
        }
    }
}
