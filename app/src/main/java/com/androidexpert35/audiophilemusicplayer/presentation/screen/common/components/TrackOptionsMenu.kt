package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Presents the shared track actions used across library, album, and playlist rows.
 *
 * Null callbacks omit actions that do not belong to the current context. In
 * particular, playlist detail rows pass no playlist callback because the listener is
 * already inside the destination playlist, and album/artist detail rows omit the
 * destination that matches the screen already on display to avoid a redundant nav.
 *
 * @param onPlayNext Inserts the track immediately after the active queue item.
 * @param onAddToQueue Appends the track to the end of the active queue.
 * @param onAddToPlaylist Opens the playlist destination selector when supported.
 * @param onGoToAlbum Navigates to the track's album, or `null` when unavailable or redundant.
 * @param onGoToArtist Navigates to the track's artist, or `null` when unavailable or redundant.
 * @param iconTint Optional explicit tint for the three-dots trigger icon.
 * @param modifier Optional modifier for the menu anchor.
 */
@Composable
internal fun TrackOptionsMenu(
    onPlayNext: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    iconTint: Color? = null,
    modifier: Modifier = Modifier
) {
    if (onPlayNext == null && onAddToQueue == null && onAddToPlaylist == null &&
        onGoToAlbum == null && onGoToArtist == null
    ) {
        return
    }

    var isVisible by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { isVisible = true },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_track_more_options),
                tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = isVisible,
            onDismissRequest = { isVisible = false }
        ) {
            onPlayNext?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.track_play_next_action)) },
                    leadingIcon = {
                        Icon(Icons.Filled.SkipNext, contentDescription = null)
                    },
                    onClick = {
                        isVisible = false
                        action()
                    }
                )
            }
            onAddToQueue?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.track_add_to_queue_action)) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                    },
                    onClick = {
                        isVisible = false
                        action()
                    }
                )
            }
            onAddToPlaylist?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_add_track_action)) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                    },
                    onClick = {
                        isVisible = false
                        action()
                    }
                )
            }
            onGoToAlbum?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.track_go_to_album_action)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Album, contentDescription = null)
                    },
                    onClick = {
                        isVisible = false
                        action()
                    }
                )
            }
            onGoToArtist?.let { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.track_go_to_artist_action)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Person, contentDescription = null)
                    },
                    onClick = {
                        isVisible = false
                        action()
                    }
                )
            }
        }
    }
}
