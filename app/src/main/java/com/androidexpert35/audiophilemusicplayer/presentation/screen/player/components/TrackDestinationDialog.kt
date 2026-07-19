package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Dialog presenting contextual navigation targets for the current track.
 *
 * @param trackTitle Title of the current track.
 * @param albumTitle Album title associated with the current track, or `null` when unavailable.
 * @param artistNames Parsed navigable artists for the current track.
 * @param onDismiss Callback dismissing the dialog.
 * @param onOpenAlbum Callback opening the album destination, or `null` when unavailable.
 * @param onOpenArtist Callback opening the selected artist destination.
 */
@Composable
internal fun TrackDestinationDialog(
    trackTitle: String,
    albumTitle: String?,
    artistNames: List<String>,
    onDismiss: () -> Unit,
    onOpenAlbum: (() -> Unit)?,
    onOpenArtist: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.player_go_to_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_go_to_dialog_message, trackTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (onOpenAlbum != null && !albumTitle.isNullOrBlank()) {
                    TrackDestinationRow(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Album,
                                contentDescription = null
                            )
                        },
                        title = stringResource(R.string.player_go_to_album_title),
                        subtitle = albumTitle,
                        onClick = onOpenAlbum
                    )
                }

                artistNames.forEach { artistName ->
                    TrackDestinationRow(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null
                            )
                        },
                        title = stringResource(R.string.player_go_to_artist_title),
                        subtitle = artistName,
                        onClick = { onOpenArtist(artistName) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun TrackDestinationRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                icon()
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

