package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes

/**
 * Enumeration of bottom navigation destinations in the floating composite panel.
 *
 * Three destinations are displayed left-to-right with Italian/English labels
 * and minimalist icons:
 * 1. **Library** — opens the music catalogue screen.
 * 2. **Cerca** — opens the search screen.
 * 3. **Impostazioni** — opens the settings screen.
 *
 * @property icon Material icon vector displayed above the label.
 * @property labelRes String resource for the navigation label.
 * @property route Navigation route identifier matching [AppRoutes] entries.
 * @property isPlayerDestination `true` when tapping this item should open the now-playing
 *   overlay instead of navigating to a route. The shell ViewModel interprets this flag.
 */
enum class BottomNavDestination(
    val icon: ImageVector,
    val labelRes: Int,
    val route: String,
    val isPlayerDestination: Boolean = false
) {
    /** Local music catalogue — navigates to the library screen. */
    LIBRARY(
        icon = Icons.Filled.LibraryMusic,
        labelRes = R.string.nav_library,
        route = AppRoutes.Library.route,
        isPlayerDestination = false
    ),

    /** Full-text track/album/artist search destination. */
    SEARCH(
        icon = Icons.Filled.Search,
        labelRes = R.string.nav_cerca,
        route = AppRoutes.Search.route
    ),

    /** App settings and audio preferences destination. */
    SETTINGS(
        icon = Icons.Filled.Settings,
        labelRes = R.string.nav_impostazioni,
        route = AppRoutes.SettingsHub.route
    ),
}

