package com.androidexpert35.audiophilemusicplayer.presentation.screen.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryAlbumRow
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryArtistRow
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryEmptyState
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryTrackRow
import com.androidexpert35.audiophilemusicplayer.presentation.screen.search.components.SearchInputBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.search.components.SearchResultSectionHeader
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.search.SearchUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.search.SearchUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.search.SearchUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.search.SearchViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Search screen entry point.
 *
 * Connects the Hilt-provided [SearchViewModel] to the stateless content composable,
 * collecting lifecycle-aware state and routing one-shot effects to the snackbar host.
 *
 * @param viewModel Hilt-provided search ViewModel instance.
 */
@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is SearchUiEffect.PlaybackError -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        // The search screen loads silently in the background — avoid a fullscreen spinner
        // for data that arrives within milliseconds from the Room cache.
        loadingType = BaseLoadingType.NONE
    ) { model ->
        SearchContent(
            model = model,
            snackbarHostState = snackbarHostState,
            onEvent = viewModel::onEvent
        )
    }
}

/**
 * Stateless search content composable.
 *
 * Renders a prominent search input at the top of a [LazyColumn] followed by
 * result sections grouped as Artists → Albums → Songs. Each section is only
 * shown when it contains at least one matching item, so the list always feels
 * focused and free of empty clutter.
 *
 * @param model Current immutable search UI state.
 * @param snackbarHostState Host for transient playback error messages.
 * @param onEvent Callback routing user intents back to the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchContent(
    model: SearchUiModel,
    snackbarHostState: SnackbarHostState,
    onEvent: (SearchUiEvent) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val shellBottomPadding = LocalShellBottomPadding.current

    val hasResults = model.artistResults.isNotEmpty() ||
        model.albumResults.isNotEmpty() ||
        model.trackResults.isNotEmpty()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.nav_search),
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = shellBottomPadding)
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = shellBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Search field ─────────────────────────────────────────────────
            item(key = "search_input") {
                SearchInputBar(
                    query = model.query,
                    onQueryChanged = { onEvent(SearchUiEvent.QueryChanged(it)) },
                    onClearQuery = { onEvent(SearchUiEvent.ClearSearch) }
                )
            }

            // ── Idle state (no query typed yet) ──────────────────────────────
            if (!model.isSearchActive) {
                item(key = "idle_state") {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(MotionTokens.DurationMedium)),
                        exit = fadeOut(tween(MotionTokens.DurationShort))
                    ) {
                        LibraryEmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.search_idle_title),
                            message = stringResource(R.string.search_idle_message)
                        )
                    }
                }
            }

            // ── No-results state (query active but nothing matched) ───────────
            if (model.isSearchActive && !hasResults) {
                item(key = "no_results_state") {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(MotionTokens.DurationMedium)),
                        exit = fadeOut(tween(MotionTokens.DurationShort))
                    ) {
                        LibraryEmptyState(
                            icon = Icons.Filled.SearchOff,
                            title = stringResource(R.string.search_no_results_title),
                            message = stringResource(R.string.search_no_results_message)
                        )
                    }
                }
            }

            // ── Artists section ───────────────────────────────────────────────
            if (model.artistResults.isNotEmpty()) {
                item(key = "artists_header") {
                    SearchResultSectionHeader(
                        title = stringResource(R.string.library_artists_section_label),
                        count = model.artistResults.size
                    )
                }
                items(
                    items = model.artistResults,
                    key = { artist -> "artist_${artist.id}" }
                ) { artist ->
                    LibraryArtistRow(
                        artist = artist,
                        onImageRequest = {
                            onEvent(SearchUiEvent.LoadArtistImage(artist))
                        },
                        onClick = { onEvent(SearchUiEvent.OpenArtistDescription(artist)) }
                    )
                }
            }

            // ── Albums section ────────────────────────────────────────────────
            if (model.albumResults.isNotEmpty()) {
                item(key = "albums_header") {
                    SearchResultSectionHeader(
                        title = stringResource(R.string.library_albums_section_label),
                        count = model.albumResults.size
                    )
                }
                items(
                    items = model.albumResults,
                    key = { album -> "album_${album.id}" }
                ) { album ->
                    LibraryAlbumRow(
                        album = album,
                        onClick = { onEvent(SearchUiEvent.OpenAlbumOverview(album)) }
                    )
                }
            }

            // ── Songs section ─────────────────────────────────────────────────
            if (model.trackResults.isNotEmpty()) {
                item(key = "songs_header") {
                    SearchResultSectionHeader(
                        title = stringResource(R.string.search_songs_section_label),
                        count = model.trackResults.size
                    )
                }
                items(
                    items = model.trackResults,
                    key = { track -> "track_${track.id}" }
                ) { track ->
                    LibraryTrackRow(
                        track = track,
                        onClick = { onEvent(SearchUiEvent.PlayTrack(track)) }
                    )
                }
            }
        }
    }
}
