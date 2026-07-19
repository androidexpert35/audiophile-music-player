package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import com.androidexpert35.audiophilemusicplayer.R
import com.tony.coreui.data.strings.CoreUiStringProvider

/**
 * Centralized user-facing copy and formatting helpers for the library feature.
 *
 * The current editor-only validation flow does not reliably resolve newly added
 * Android string resources without a full build pass, so library-specific text
 * is centralized here to keep the feature cohesive and statically verifiable.
 */
object LibraryStrings {
    val title: String
        get() = CoreUiStringProvider.get(R.string.library_title)
    val subtitle: String
        get() = CoreUiStringProvider.get(R.string.library_subtitle)
    val tracksSectionLabel: String
        get() = CoreUiStringProvider.get(R.string.library_tracks_section_label)
    val playlistsSectionLabel: String
        get() = CoreUiStringProvider.get(R.string.library_playlists_section_label)
    val albumsSectionLabel: String
        get() = CoreUiStringProvider.get(R.string.library_albums_section_label)
    val artistsSectionLabel: String
        get() = CoreUiStringProvider.get(R.string.library_artists_section_label)
    // Favorites / Liked Songs item
    val favoritesItemTitle: String
        get() = CoreUiStringProvider.get(R.string.library_favorites_title)
    val favoritesItemSubtitle: String
        get() = CoreUiStringProvider.get(R.string.search_songs_section_label)
    val likeTrackContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_like_track)
    val unlikeTrackContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_unlike_track)
    val emptyLikedSongsTitle: String
        get() = CoreUiStringProvider.get(R.string.library_empty_liked_songs_title)
    val emptyLikedSongsMessage: String
        get() = CoreUiStringProvider.get(R.string.library_empty_liked_songs_message)
    val emptyRecentlyPlayedTitle: String
        get() = CoreUiStringProvider.get(R.string.library_empty_recently_played_title)
    val emptyRecentlyPlayedMessage: String
        get() = CoreUiStringProvider.get(R.string.library_empty_recently_played_message)

    // Sort order labels
    val sortRecentlyPlayed: String
        get() = CoreUiStringProvider.get(R.string.library_sort_recently_played)
    val sortRecentlyAdded: String
        get() = CoreUiStringProvider.get(R.string.library_sort_recently_added)
    val sortAlphabetical: String
        get() = CoreUiStringProvider.get(R.string.library_sort_alphabetical)

    // Accessibility / content descriptions for new UI elements
    val profileIconContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_library_profile_icon)
    val searchLibraryContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_search_library)
    val addToLibraryContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_add_to_library)
    val sortMenuContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_change_sort_order)
    val toggleViewModeContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_toggle_library_view_mode)

    // Empty state strings for playlists (future feature placeholder)
    val emptyPlaylistsTitle: String
        get() = CoreUiStringProvider.get(R.string.library_empty_playlists_title)
    val emptyPlaylistsMessage: String
        get() = CoreUiStringProvider.get(R.string.library_empty_playlists_message)
    val emptyCollectionTitle: String
        get() = CoreUiStringProvider.get(R.string.library_empty_collection_title)
    val emptyCollectionMessage: String
        get() = CoreUiStringProvider.get(R.string.library_empty_collection_message)
    val emptyTracksTitle: String
        get() = CoreUiStringProvider.get(R.string.library_empty_tracks_title)
    val emptyTracksMessage: String
        get() = CoreUiStringProvider.get(R.string.library_empty_tracks_message)
    val emptyAlbumsTitle: String
        get() = CoreUiStringProvider.get(R.string.library_empty_albums_title)
    val emptyAlbumsMessage: String
        get() = CoreUiStringProvider.get(R.string.library_empty_albums_message)
    val emptyArtistsTitle: String
        get() = CoreUiStringProvider.get(R.string.library_empty_artists_title)
    val emptyArtistsMessage: String
        get() = CoreUiStringProvider.get(R.string.library_empty_artists_message)
    val playbackEmptyQueue: String
        get() = CoreUiStringProvider.get(R.string.library_playback_empty_queue)
    val refreshLibraryContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_refresh_library)
    val openLibrarySettingsContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_open_library_settings)
    val albumOverviewBackContentDescription: String
        get() = CoreUiStringProvider.get(R.string.cd_navigate_back)
    val albumOverviewPlayAlbumLabel: String
        get() = CoreUiStringProvider.get(R.string.album_overview_play_album_label)
    val albumOverviewAlbumInfoTitle: String
        get() = CoreUiStringProvider.get(R.string.album_overview_album_info_title)
    val albumOverviewTrackListTitle: String
        get() = CoreUiStringProvider.get(R.string.album_overview_track_list_title)
    val albumOverviewNowPlayingLabel: String
        get() = CoreUiStringProvider.get(R.string.album_overview_now_playing_label)
    val albumOverviewNotFoundTitle: String
        get() = CoreUiStringProvider.get(R.string.album_overview_not_found_title)
    val albumOverviewNotFoundMessage: String
        get() = CoreUiStringProvider.get(R.string.album_overview_not_found_message)
    val albumOverviewEmptyTracksTitle: String
        get() = CoreUiStringProvider.get(R.string.album_overview_empty_tracks_title)
    val albumOverviewEmptyTracksMessage: String
        get() = CoreUiStringProvider.get(R.string.album_overview_empty_tracks_message)
    val albumOverviewFactsDuration: String
        get() = CoreUiStringProvider.get(R.string.album_overview_fact_duration)
    val albumOverviewFactsStorage: String
        get() = CoreUiStringProvider.get(R.string.album_overview_fact_storage)
    val albumOverviewFactsDiscs: String
        get() = CoreUiStringProvider.get(R.string.album_overview_fact_discs)
    val albumOverviewFactsFormats: String
        get() = CoreUiStringProvider.get(R.string.album_overview_fact_formats)
    val albumOverviewFactsQuality: String
        get() = CoreUiStringProvider.get(R.string.album_overview_fact_quality)
    val albumOverviewFactsLossless: String
        get() = CoreUiStringProvider.get(R.string.album_overview_fact_lossless)
    val albumOverviewFactsYear: String
        get() = CoreUiStringProvider.get(R.string.album_overview_fact_year)
    val artistOverviewBackContentDescription: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_back_content_description)
    val artistOverviewPlayArtistLabel: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_play_artist_label)
    val artistOverviewInfoTitle: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_info_title)
    val artistOverviewTrackListTitle: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_track_list_title)
    val artistOverviewNotFoundTitle: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_not_found_title)
    val artistOverviewEmptyTracksTitle: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_empty_tracks_title)
    val artistOverviewEmptyTracksMessage: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_empty_tracks_message)
    val artistOverviewFactsAlbums: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_fact_albums)
    val artistOverviewFactsTracks: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_fact_tracks)
    val artistOverviewFactsDuration: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_fact_duration)
    val artistOverviewFactsFormats: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_fact_formats)
    val artistOverviewFactsQuality: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_fact_quality)
    val artistOverviewFactsLossless: String
        get() = CoreUiStringProvider.get(R.string.artist_overview_fact_lossless)


    /** Builds the album playback failure message for a specific album title. */
    fun playbackAlbumUnavailable(albumTitle: String): String =
        CoreUiStringProvider.get(R.string.library_playback_album_unavailable, albumTitle)

    /** Formats a track count label with basic pluralization. */
    fun trackCount(count: Int): String =
        CoreUiStringProvider.getPlural(R.plurals.library_track_count, count, count)

    /** Formats an album count label with basic pluralization. */
    fun albumCount(count: Int): String =
        CoreUiStringProvider.getPlural(R.plurals.library_album_count, count, count)

    /** Formats a disc count label with basic pluralization. */
    fun discCount(count: Int): String =
        CoreUiStringProvider.getPlural(R.plurals.library_disc_count, count, count)

    /** Formats a lossless-track count label with basic pluralization. */
    fun losslessCount(count: Int): String =
        CoreUiStringProvider.getPlural(R.plurals.library_lossless_track_count, count, count)

    // Artist description screen strings
    val artistDescriptionPopularTracksTitle: String
        get() = CoreUiStringProvider.get(R.string.artist_description_popular_tracks_title)
    val artistDescriptionAlbumsTitle: String
        get() = CoreUiStringProvider.get(R.string.artist_description_albums_title)
    val artistDescriptionAppearsOnTitle: String
        get() = CoreUiStringProvider.get(R.string.artist_description_appears_on_title)
    val artistDescriptionPlayLabel: String
        get() = CoreUiStringProvider.get(R.string.artist_description_play_label)
    val artistDescriptionShuffleLabel: String
        get() = CoreUiStringProvider.get(R.string.artist_description_shuffle_label)
    val artistDescriptionFollowLabel: String
        get() = CoreUiStringProvider.get(R.string.artist_description_follow_label)
    val artistDescriptionFollowingLabel: String
        get() = CoreUiStringProvider.get(R.string.artist_description_following_label)
    val artistDescriptionBackContentDescription: String
        get() = CoreUiStringProvider.get(R.string.artist_description_back_content_description)
    val artistDescriptionShuffleContentDescription: String
        get() = CoreUiStringProvider.get(R.string.artist_description_shuffle_content_description)
    val artistDescriptionEmptyTracksTitle: String
        get() = CoreUiStringProvider.get(R.string.artist_description_empty_tracks_title)
    val artistDescriptionEmptyTracksMessage: String
        get() = CoreUiStringProvider.get(R.string.artist_description_empty_tracks_message)

    /** Builds the artist-overview error message for a missing artist destination. */
    fun artistOverviewNotFoundMessage(artistName: String): String =
        CoreUiStringProvider.get(R.string.artist_overview_not_found_message, artistName)

    /** Builds the artist-description error message for a missing artist destination. */
    fun artistDescriptionNotFoundMessage(artistName: String): String =
        CoreUiStringProvider.get(R.string.artist_description_not_found_message, artistName)

    // Album detail screen strings
    val albumDetailDownloadContentDescription: String
        get() = CoreUiStringProvider.get(R.string.album_detail_download_content_description)
    val albumDetailMoreOptionsContentDescription: String
        get() = CoreUiStringProvider.get(R.string.album_detail_more_options_content_description)
    val albumDetailTrackMoreOptionsContentDescription: String
        get() = CoreUiStringProvider.get(R.string.album_detail_track_more_options_content_description)
    val albumDetailNowPlayingContentDescription: String
        get() = CoreUiStringProvider.get(R.string.album_detail_now_playing_content_description)
    val albumDetailShuffleContentDescription: String
        get() = CoreUiStringProvider.get(R.string.album_detail_shuffle_content_description)

    /** Formats a disc section header label for multi-disc albums. */
    fun albumDetailDiscHeader(disc: Int): String =
        CoreUiStringProvider.get(R.string.library_disc_label, disc)
}

