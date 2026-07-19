package com.androidexpert35.audiophilemusicplayer.presentation.navigation

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

    /** Audio and application settings destination. */
    val Settings = destinationNode(route("settings_screen"))

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
