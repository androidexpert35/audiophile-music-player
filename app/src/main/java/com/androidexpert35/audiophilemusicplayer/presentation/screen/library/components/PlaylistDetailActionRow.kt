package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary

/**
 * Offers play, shuffle, manual-order, and delete controls for a playlist.
 *
 * @param isEditing Whether manual ordering is active.
 * @param onPlayClick Starts sequential playback.
 * @param onShuffleClick Starts shuffled playback.
 * @param onEditClick Toggles manual ordering.
 * @param onDeleteClick Invoked when the listener requests deletion; omitted for playlists that
 *   can't be deleted, such as favorites.
 * @param modifier Optional modifier applied to the row.
 */
@Composable
internal fun PlaylistDetailActionRow(
    isEditing: Boolean,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            onClick = onEditClick,
            shape = CircleShape,
            color = if (isEditing) AudiophilePrimary else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isEditing) Icons.Filled.Check else Icons.Filled.Edit,
                contentDescription = stringResource(
                    if (isEditing) R.string.playlist_save_order_content_description
                    else R.string.playlist_edit_order_content_description
                ),
                tint = if (isEditing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
            }
        }
        if (onDeleteClick != null) {
            Surface(
                onClick = onDeleteClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_delete_playlist),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onShuffleClick) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = stringResource(R.string.playlist_shuffle_content_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            Surface(onClick = onPlayClick, shape = CircleShape, color = AudiophilePrimary,
                modifier = Modifier.size(56.dp), shadowElevation = 10.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.playlist_play_content_description),
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}
