package com.androidexpert35.audiophilemusicplayer.presentation.screen.library

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.PlaylistPickerDialog
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryGridContent
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryListContent
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryFacetFilter
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Standard Songs surface scoped to one selected genre, year, or composer.
 *
 * The screen deliberately reuses the Library's list/grid content and its retained
 * Songs preferences, so sorting, view mode, track actions, and playback behaviour
 * are identical to the root Songs tab.
 *
 * @param filter Category value passed by the Library collection card.
 * @param viewModel Hilt-provided library ViewModel for the filtered Songs surface.
 */
@Composable
fun LibraryFacetTracksScreen(
    filter: LibraryFacetFilter,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(filter) {
        viewModel.onEvent(LibraryUiEvent.SetFacetTrackFilter(filter))
    }
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is LibraryUiEffect.PlaybackError -> snackbarHostState.showSnackbar(effect.message)
                is LibraryUiEffect.QueueUpdated -> snackbarHostState.showSnackbar(effect.message)
                is LibraryUiEffect.ScanError,
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
    ) { model ->
        LibraryFacetTracksContent(
            model = model,
            snackbarHostState = snackbarHostState,
            onEvent = viewModel::onEvent,
        )
    }
}

/** Stateless, filter-specific wrapper around the same Songs list/grid implementation. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LibraryFacetTracksContent(
    model: LibraryUiModel,
    snackbarHostState: SnackbarHostState,
    onEvent: (LibraryUiEvent) -> Unit,
) {
    val shellBottomPadding = LocalShellBottomPadding.current
    Scaffold(
        topBar = {
            AppTopBar(
                title = model.facetTrackFilter?.name.orEmpty(),
                onNavigateBack = { onEvent(LibraryUiEvent.NavigateBack) },
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = shellBottomPadding),
            )
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = model.isGridView,
            label = "FacetTracksViewModeTransition",
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            transitionSpec = {
                fadeIn(tween(MotionTokens.DurationMedium)) togetherWith
                    fadeOut(tween(MotionTokens.DurationShort))
            },
        ) { isGrid ->
            if (isGrid) {
                LibraryGridContent(
                    model = model,
                    playingTrackIdProvider = { null },
                    shellBottomPadding = shellBottomPadding,
                    onEvent = onEvent,
                )
            } else {
                LibraryListContent(
                    model = model,
                    playingTrackIdProvider = { null },
                    shellBottomPadding = shellBottomPadding,
                    onEvent = onEvent,
                )
            }
        }
        model.playlistPickerTrack?.let {
            PlaylistPickerDialog(
                playlists = model.playlists,
                onDismiss = { onEvent(LibraryUiEvent.DismissPlaylistPicker) },
                onPlaylistSelected = { playlistId ->
                    onEvent(LibraryUiEvent.AddTrackToPlaylist(playlistId))
                },
            )
        }
    }
}
