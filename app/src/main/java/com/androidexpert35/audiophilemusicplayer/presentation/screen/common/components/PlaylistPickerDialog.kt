package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.PlaylistArtworkMosaic
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.PlaylistUiModel

/**
 * Lets a listener select a destination playlist through a polished, artwork-led dialog.
 *
 * @param playlists Available M3U playlist summaries.
 * @param onDismiss Invoked when the selector is closed without an update.
 * @param onPlaylistSelected Invoked with the target M3U identifier.
 */
@Composable
fun PlaylistPickerDialog(
    playlists: List<PlaylistUiModel>,
    onDismiss: () -> Unit,
    onPlaylistSelected: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 24.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                PlaylistPickerHeader(onDismiss = onDismiss)
                if (playlists.isEmpty()) {
                    PlaylistPickerEmptyState()
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(playlists, key = PlaylistUiModel::id) { playlist ->
                            PlaylistPickerItem(
                                playlist = playlist,
                                onClick = { onPlaylistSelected(playlist.id) }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

/** Renders the title, guidance, and close control for the destination selector. */
@Composable
private fun PlaylistPickerHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .padding(end = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                modifier = Modifier.padding(bottom = 4.dp),
                text = stringResource(R.string.playlist_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.playlist_picker_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.cd_close_playlist_picker),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Gives an empty collection the same deliberate visual weight as a populated selector. */
@Composable
private fun PlaylistPickerEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(16.dp).size(28.dp)
            )
        }
        Text(
            text = stringResource(R.string.playlist_picker_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Renders one touch-friendly playlist destination with its existing cover mosaic. */
@Composable
private fun PlaylistPickerItem(
    playlist: PlaylistUiModel,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PlaylistArtworkMosaic(
                albumArtUris = playlist.albumArtUris,
                modifier = Modifier.size(52.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.playlist_track_count,
                        playlist.trackCount,
                        playlist.trackCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.playlist_add_track_action),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
        }
    }
}
