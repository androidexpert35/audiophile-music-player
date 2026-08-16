package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.ui.graphics.vector.ImageVector
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes

/**
 * Enumeration of Settings hub categories, each opening its own dedicated sub-screen.
 *
 * Mirrors the placement pattern of
 * [com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.BottomNavDestination]:
 * a small enum pairing an icon, a label, and a navigation route so the hub screen can
 * render every card from a single `entries` iteration.
 *
 * @property icon Leading icon shown on the category card.
 * @property titleRes String resource for the category title.
 * @property route Navigation route opened when the card is tapped.
 */
enum class SettingsCategory(
    val icon: ImageVector,
    val titleRes: Int,
    val route: String,
) {
    /** Bit-perfect engine, Sonic Upscaling Enhancer, and Hi-Res Dynamic Remaster. */
    AUDIO_ENGINE(
        icon = Icons.Rounded.GraphicEq,
        titleRes = R.string.settings_category_audio_engine_title,
        route = AppRoutes.SettingsAudioEngine.route,
    ),

    /** USB DAC discovery, permission, and playback readiness. */
    USB(
        icon = Icons.Rounded.Usb,
        titleRes = R.string.settings_category_usb_title,
        route = AppRoutes.SettingsUsb.route,
    ),

    /** Folders the library scan is scoped to. */
    LIBRARY_FOLDERS(
        icon = Icons.Rounded.Folder,
        titleRes = R.string.settings_category_library_folders_title,
        route = AppRoutes.SettingsLibraryFolders.route,
    ),

    /** Visibility and display order of the library's catalogue sections. */
    LIBRARY_SECTIONS(
        icon = Icons.Rounded.Reorder,
        titleRes = R.string.settings_category_library_sections_title,
        route = AppRoutes.SettingsLibrarySections.route,
    ),

    /** Playback session behavior, such as queue retention on app exit. */
    PLAYBACK_BEHAVIOR(
        icon = Icons.Rounded.Tune,
        titleRes = R.string.settings_category_playback_behavior_title,
        route = AppRoutes.SettingsPlaybackBehavior.route,
    ),

    /** Open-source attribution. */
    ABOUT(
        icon = Icons.Rounded.Code,
        titleRes = R.string.settings_category_about_title,
        route = AppRoutes.SettingsAbout.route,
    ),
}
