package com.androidexpert35.audiophilemusicplayer.presentation.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * @param navigationManager The manager that provides navigation commands.
 * @param playerOverlayManager Coordinator for player open requests outside the NavHost.
 */
@Composable
fun AppNavigator(
    navigationManager: NavigationManager,
    playerOverlayManager: PlayerOverlayManager
) {
    val navController = rememberNavController()

    val shellViewModel: AppShellViewModel = hiltViewModel()
    val shellUiState by shellViewModel.uiState.collectAsState()
    val shellModel = shellUiState.data ?: AppShellUiModel()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val activeRoute = currentRoute ?: AppRoutes.Onboarding.route

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

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Layer 1: Shell + NavHost ─────────────────────────────────────────
        AppShell(showShellChrome) {
            CoreUiNavigator(
                navigationManager = navigationManager,
                root = AppRoutes.Root,
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
            PlayerScreen(
                isOpen = isPlayerOpen,
                onDismissRequest = { shellViewModel.onEvent(AppShellUiEvent.ClosePlayer) }
            )
        }
    }
}
