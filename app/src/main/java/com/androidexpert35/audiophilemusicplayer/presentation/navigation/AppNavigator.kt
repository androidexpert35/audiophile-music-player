package com.androidexpert35.audiophilemusicplayer.presentation.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay.PlayerOverlayManager
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.AppShell
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.PlayerScreen
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell.AppShellUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell.AppShellUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell.AppShellViewModel
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.navigation.compose.CoreUiNavigator

/**
 * Root composable that manages the app's navigation and hosts the global player overlay.
 *
 * Renders two distinct layers inside a [Box]:
 * 1. **[AppShell] + CoreUI navigator** — the full navigation host wrapped in the
 *    persistent shell chrome (mini-player + bottom nav). Always composed.
 * 2. **[PlayerScreen] overlay** — permanently composed and translated off-screen with
 *    `graphicsLayer { translationY = screenHeight }` when closed. Slides into view by
 *    animating `translationY` to `0f` when opened. Because the composition tree and
 *    the GPU texture (BlurredBackground) are already ready before the first open, the
 *    slide-in animation runs entirely in the GPU draw phase with zero first-frame
 *    composition cost.
 *
 * **Dismiss flow**: [PlayerBottomSheet] animates itself off-screen via its own drag
 * offset and calls [onDismissRequest] when complete. The outer `translationY` then
 * animates back to `screenHeight`, but since the content is already off-screen the
 * animation is invisible. On the next open, `translationY` animates from `screenHeight`
 * to `0f` for a jank-free GPU-layer slide-in.
 *
 * **Startup cost control**:
 * - When [startDestination] resolves to [AppStartDestination.Main], the graph is
 *   entered through [AppRoutes.MainRoot] so onboarding is never composed and its
 *   navigation transition never runs — the library gets the first frame to itself.
 * - The heavy player overlay (its `PlayerViewModel` flow collection and the
 *   `BlurredBackground` GPU layer) is not composed until the first library frame has
 *   been drawn. It is still pre-warmed off-screen well before the user can open it,
 *   so the slide-in stays jank-free, but it no longer competes with the initial
 *   composition burst that made the engine stutter on launch.
 *
 * @param navigationManager The manager that provides navigation commands.
 * @param playerOverlayManager Coordinator for player open requests outside the NavHost.
 * @param startDestination Resolved launch graph; while [AppStartDestination.Deciding]
 *   a plain themed background is shown so no onboarding UI flashes.
 */
@Composable
fun AppNavigator(
    navigationManager: NavigationManager,
    playerOverlayManager: PlayerOverlayManager,
    startDestination: AppStartDestination
) {
    if (startDestination is AppStartDestination.Deciding) {
        // Brief window while the persisted indexing flag is read: paint the eventual
        // background so the transition into the real start destination is invisible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
        return
    }

    val startsOnMain = startDestination is AppStartDestination.Main

    val navController = rememberNavController()

    val shellViewModel: AppShellViewModel = hiltViewModel()
    val shellUiState by shellViewModel.uiState.collectAsState()
    val shellModel = shellUiState.data ?: AppShellUiModel()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    // Before the NavHost reports its first entry, fall back to the route implied by
    // the chosen start destination so the shell chrome does not flip on launch.
    val defaultRoute = if (startsOnMain) AppRoutes.Library.route else AppRoutes.Onboarding.route
    val activeRoute = currentRoute ?: defaultRoute

    val showShellChrome = activeRoute != AppRoutes.Onboarding.route

    LaunchedEffect(playerOverlayManager) {
        playerOverlayManager.openRequests.collect {
            shellViewModel.onEvent(AppShellUiEvent.MiniPlayerClicked)
        }
    }

    val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val isPlayerOpen = shellModel.isPlayerOpen

    val playerTranslationY by animateFloatAsState(
        targetValue = if (isPlayerOpen) 0f else screenHeightPx,
        animationSpec = spring(
            dampingRatio = MotionTokens.PlayerSlideDamping,
            stiffness = MotionTokens.PlayerSlideStiffness
        ),
        label = "PlayerOverlayY"
    )

    // Defer composing the heavy player overlay until the first library frame has been
    // drawn, then keep it composed for the jank-free slide-in. Opening the player very
    // early (e.g. via an ACTION_VIEW intent) forces it in immediately.
    var playerWarmedUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { } // let the initial library frame render first
        withFrameNanos { } // and settle before adding the overlay's GPU layer
        playerWarmedUp = true
    }
    val composePlayer = playerWarmedUp || isPlayerOpen

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Layer 1: Shell + NavHost ─────────────────────────────────────────
        AppShell(showShellChrome) {
            CoreUiNavigator(
                navigationManager = navigationManager,
                root = if (startsOnMain) AppRoutes.MainRoot else AppRoutes.Root,
                navController = navController,
            ) {
                appNavigationGraph(navigationManager)
            }
        }

        // ── Layer 2: Full-screen player overlay ──────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = playerTranslationY }
        ) {
            if (composePlayer) {
                PlayerScreen(
                    isOpen = isPlayerOpen,
                    onDismissRequest = { shellViewModel.onEvent(AppShellUiEvent.ClosePlayer) }
                )
            }
        }
    }
}
