package com.androidexpert35.audiophilemusicplayer.presentation.navigation

import androidx.navigation.NavGraphBuilder
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.AlbumOverviewScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.ArtistDescriptionScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.LibraryScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.PlaylistOverviewScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.onboarding.OnboardingScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.search.SearchScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.AboutSettingsScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.AudioEngineSettingsScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.LibraryFoldersSettingsScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.LibrarySectionsSettingsScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.PlaybackBehaviorSettingsScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.SettingsScreen
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.UsbSettingsScreen
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.navigation.graph.destination
import com.tony.coreui.presentation.navigation.graph.flow

/**
 * Registers every Audiophile destination in a CoreUI-backed graph.
 */
fun NavGraphBuilder.appNavigationGraph(navigationManager: NavigationManager) {
    destination(AppRoutes.Onboarding) {
        OnboardingScreen(
            onNavigateToHome = {
                navigationManager.navigateAndClearBackStack(
                    route = AppRoutes.MainFlow.route,
                    popUpToRoute = AppRoutes.Onboarding.route,
                    inclusive = true
                )
            }
        )
    }
    mainNavGraph()
}

/**
 * Defines the main navigation flow with the library screen as the start destination.
 *
 * The player is intentionally absent because it is a persistent overlay
 * is rendered as a persistent overlay inside the app shell so the library remains
 * in composition while the player is open.
 */
fun NavGraphBuilder.mainNavGraph() {
    flow(AppRoutes.MainFlow) {
        destination(AppRoutes.Library) {
            LibraryScreen()
        }
        destination(AppRoutes.AlbumOverview) { backStackEntry ->
            val albumId = AppRoutes.AlbumOverview.routeDefinition.requireArgument(
                backStackEntry,
                AppRoutes.albumId
            )
            AlbumOverviewScreen(albumId = albumId)
        }
        destination(AppRoutes.PlaylistOverview) { backStackEntry ->
            val playlistId = AppRoutes.PlaylistOverview.routeDefinition.requireArgument(
                backStackEntry,
                AppRoutes.playlistId
            )
            PlaylistOverviewScreen(playlistId = playlistId)
        }
        destination(AppRoutes.ArtistDescription) { backStackEntry ->
            val artistName = AppRoutes.ArtistDescription.routeDefinition.requireArgument(
                backStackEntry,
                AppRoutes.artistName
            )
            ArtistDescriptionScreen(artistName = artistName)
        }
        destination(AppRoutes.Search) {
            SearchScreen()
        }
        flow(AppRoutes.SettingsFlow) {
            destination(AppRoutes.SettingsHub) {
                SettingsScreen()
            }
            destination(AppRoutes.SettingsAudioEngine) {
                AudioEngineSettingsScreen()
            }
            destination(AppRoutes.SettingsUsb) {
                UsbSettingsScreen()
            }
            destination(AppRoutes.SettingsLibraryFolders) {
                LibraryFoldersSettingsScreen()
            }
            destination(AppRoutes.SettingsLibrarySections) {
                LibrarySectionsSettingsScreen()
            }
            destination(AppRoutes.SettingsPlaybackBehavior) {
                PlaybackBehaviorSettingsScreen()
            }
            destination(AppRoutes.SettingsAbout) {
                AboutSettingsScreen()
            }
        }
    }
}
