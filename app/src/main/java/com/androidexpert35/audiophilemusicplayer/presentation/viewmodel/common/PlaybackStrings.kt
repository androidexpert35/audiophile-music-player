package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.common

import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.tony.coreui.data.strings.CoreUiStringProvider
import com.tony.coreui.domain.resource.ResourceError

/**
 * Locale-independent fallback strings for transient playback error messages.
 *
 * These constants are the last-resort fallback that appears only when
 * [com.tony.coreui.domain.resource.ResourceError.toUserMessage]
 * returns `null` — which happens for [ResourceError.UnknownError] and any branch that
 * lacks a dedicated message. In normal operation the user sees the properly localised
 * message from the [ResourceError] descriptor; these strings are never surfaced in the
 * happy path.
 *
 * Using a shared object prevents the same raw literal from being scattered across six
 * ViewModel files, ensuring copy changes only need a single edit.
 */
internal object PlaybackStrings {

    /** Generic fallback when a play command fails. */
    val playbackFailed: String
        get() = CoreUiStringProvider.get(R.string.playback_failed)

    /** Fallback when a shuffled play command fails. */
    val shufflePlaybackFailed: String
        get() = CoreUiStringProvider.get(R.string.shuffle_playback_failed)

    /** Fallback for transport commands (skip, seek, play/pause) that fail without a typed error. */
    val playbackCommandFailed: String
        get() = CoreUiStringProvider.get(R.string.playback_command_failed)

    /** Fallback when the initial play command is rejected by the playback engine. */
    val startPlaybackFailed: String
        get() = CoreUiStringProvider.get(R.string.start_playback_failed)

    /** Fallback when a liked-songs toggle fails without a typed error descriptor. */
    val likedSongsUpdateFailed: String
        get() = CoreUiStringProvider.get(R.string.liked_songs_update_failed)

    /** Fallback when a lyrics fetch fails without a typed error descriptor. */
    val lyricsFetchFailed: String
        get() = CoreUiStringProvider.get(R.string.lyrics_fetch_failed)
}

