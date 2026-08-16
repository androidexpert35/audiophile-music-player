package com.androidexpert35.audiophilemusicplayer.presentation.navigation

import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes.MainFlow
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes.Root
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes.SettingsFlow
import com.tony.coreui.presentation.navigation.graph.destinationNode
import com.tony.coreui.presentation.navigation.graph.flowNode
import com.tony.coreui.presentation.navigation.graph.rootNode
import com.tony.coreui.presentation.navigation.route.longPathArgument
import com.tony.coreui.presentation.navigation.route.route
import com.tony.coreui.presentation.navigation.route.stringPathArgument
import com.tony.coreui.presentation.navigation.route.with

/**
 * Defines Audiophile's navigation graph through CoreUI typed route primitives.
 */
object AppRoutes {

    /** Identifier passed to the album overview destination. */
    val albumId = longPathArgument("albumId")

    /** Artist name passed to the artist description destination. */
    val artistName = stringPathArgument("artistName")

    /** Stable playlist filename passed to the playlist overview destination. */
    val playlistId = stringPathArgument("playlistId")

    /** Initial permission and indexing destination. */
    val Onboarding = destinationNode(route("onboarding_screen"))

    /** Main library browser destination. */
    val Library = destinationNode(route("library_screen"))

    /** Album detail destination. */
    val AlbumOverview = destinationNode(route("album_overview", albumId))

    /** Playlist detail destination. */
    val PlaylistOverview = destinationNode(route("playlist_overview", playlistId))

    /** Artist profile destination. */
    val ArtistDescription = destinationNode(route("artist_description", artistName))

    /** Local-library search destination. */
    val Search = destinationNode(route("search_screen"))

    /** Settings hub destination — shows the category cards. */
    val SettingsHub = destinationNode(route("settings_hub_screen"))

    /** Audio engine and DSP settings destination (bit-perfect engine, SUE, Hi-Res Remaster). */
    val SettingsAudioEngine = destinationNode(route("settings_audio_engine_screen"))

    /** USB DAC settings destination. */
    val SettingsUsb = destinationNode(route("settings_usb_screen"))

    /** Music folder scan-scope settings destination. */
    val SettingsLibraryFolders = destinationNode(route("settings_library_folders_screen"))

    /** Library section visibility and ordering settings destination. */
    val SettingsLibrarySections = destinationNode(route("settings_library_sections_screen"))

    /** Playback behavior settings destination (e.g. queue retention on exit). */
    val SettingsPlaybackBehavior = destinationNode(route("settings_playback_behavior_screen"))

    /** Open-source attribution destination. */
    val SettingsAbout = destinationNode(route("settings_about_screen"))

    /** Nested graph hosting the Settings hub and its category sub-screens. */
    val SettingsFlow = flowNode(
        route = "settings_flow_node",
        startDestination = SettingsHub
    )

    /** Every route inside [SettingsFlow], used to keep the Settings tab highlighted on sub-screens. */
    val settingsRoutes: Set<String> = setOf(
        SettingsHub.route,
        SettingsAudioEngine.route,
        SettingsUsb.route,
        SettingsLibraryFolders.route,
        SettingsLibrarySections.route,
        SettingsPlaybackBehavior.route,
        SettingsAbout.route,
    )

    /** Nested graph containing the persistent app shell destinations. */
    val MainFlow = flowNode(
        route = "main_flow_node",
        startDestination = Library
    )

    /** Root graph used for the normal onboarding-first application start. */
    val Root = rootNode(
        route = "audiophile_root",
        startDestination = Onboarding
    )

    /**
     * Root graph used when onboarding can be skipped because media permission is
     * granted and the library is already indexed. Starts directly on [MainFlow] so
     * the app never mounts the onboarding screen or plays the onboarding→home
     * navigation transition on launch. Shares [Root]'s route so the hosted graph
     * definition is identical; only the entry point differs.
     */
    val MainRoot = rootNode(
        route = "audiophile_root",
        startDestination = MainFlow
    )

    /** Builds a concrete album overview route. */
    fun albumOverviewRoute(id: Long): String =
        AlbumOverview.routeDefinition.createRoute(albumId with id)

    /** Builds a concrete playlist overview route with safely encoded text. */
    fun playlistOverviewRoute(id: String): String =
        PlaylistOverview.routeDefinition.createRoute(playlistId with id)

    /** Builds a concrete artist profile route with safely encoded text. */
    fun artistDescriptionRoute(name: String): String =
        ArtistDescription.routeDefinition.createRoute(artistName with name)
}
