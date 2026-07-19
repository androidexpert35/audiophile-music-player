package com.androidexpert35.audiophilemusicplayer.presentation.screen.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.BottomNavDestination
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.ShellBottomPanel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell.AppShellUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell.AppShellUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell.AppShellUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell.AppShellViewModel

/**
 * Global app shell that wraps all screen content with a floating bottom panel.
 *
 * The bottom panel is overlaid on top of screen content — screens scroll
 * *underneath* it for an immersive, edge-to-edge experience. It contains:
 * 1. A **mini-player bar** (visible whenever a track is loaded), fused
 *    visually with:
 * 2. The **bottom navigation bar** (always visible while shell chrome is active).
 *
 * The full-screen player overlay is managed one level above this composable in
 * [com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppNavigator],
 * so the shell chrome remains composed and untouched while the player is open.
 * Swiping the player down naturally reveals the mini-player and bottom nav
 * that were always live underneath it.
 *
 * @param showShellChrome Whether to render the mini-player and bottom nav (hidden on onboarding).
 * @param viewModel Shell-scoped ViewModel observing playback state and route.
 * @param content The screen content slot (typically the navigation host).
 */
@Composable
fun AppShell(
    showShellChrome: Boolean = true,
    viewModel: AppShellViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val model = uiState.data ?: AppShellUiModel()
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect one-shot effects (playback errors from mini-player)
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is AppShellUiEffect.PlaybackError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    AppShellContent(
        model = model,
        showShellChrome = showShellChrome,
        snackbarHostState = snackbarHostState,
        onEvent = viewModel::onEvent,
        content = content
    )
}

/**
 * Stateless content composable for the app shell.
 *
 * Uses a [Box] overlay approach: screen content fills the entire area while
 * [ShellBottomPanel] sits on top at the bottom edge, allowing content to scroll
 * behind it. The player overlay lives one level above (in
 * [com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppNavigator]),
 * so the shell remains composed and untouched while the player is open.
 *
 * @param model Current immutable shell UI state.
 * @param showShellChrome Whether the bottom panel is visible.
 * @param snackbarHostState Host for transient error messages.
 * @param onEvent Callback emitting shell user intents.
 * @param content The screen content slot.
 */
@Composable
private fun AppShellContent(
    model: AppShellUiModel,
    showShellChrome: Boolean,
    snackbarHostState: SnackbarHostState,
    onEvent: (AppShellUiEvent) -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    var panelHeightPx by remember { mutableIntStateOf(0) }

    // Stable lambda references — same instances across recompositions so child
    // composables can skip recomposition when unrelated shell state changes.
    val onPlayPauseClick    = remember(onEvent) { { onEvent(AppShellUiEvent.TogglePlayPause) } }
    val onSkipPreviousClick = remember(onEvent) { { onEvent(AppShellUiEvent.SkipPrevious) } }
    val onSkipNextClick     = remember(onEvent) { { onEvent(AppShellUiEvent.SkipNext) } }
    val onMiniPlayerClick   = remember(onEvent) { { onEvent(AppShellUiEvent.MiniPlayerClicked) } }
    val onDestinationSelected = remember(onEvent) {
        { destination: BottomNavDestination ->
            onEvent(AppShellUiEvent.BottomNavDestinationSelected(destination))
        }
    }

    CompositionLocalProvider(
        LocalShellBottomPadding provides with(density) { panelHeightPx.toDp() + 8.dp }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()

            ShellBottomPanel(
                model = model,
                visible = showShellChrome,
                onPlayPauseClick = onPlayPauseClick,
                onSkipPreviousClick = onSkipPreviousClick,
                onSkipNextClick = onSkipNextClick,
                onMiniPlayerClick = onMiniPlayerClick,
                onDestinationSelected = onDestinationSelected,
                onHeightChanged = { if (panelHeightPx != it) panelHeightPx = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            )

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = LocalShellBottomPadding.current + 16.dp)
            )
        }
    }
}


