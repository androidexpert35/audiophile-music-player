package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Confirms permanent removal of a local playlist before the listener's decision is irreversible.
 *
 * @param playlistName Name shown in the confirmation message.
 * @param onDismiss Invoked when the listener cancels without deleting the playlist.
 * @param onConfirm Invoked when the listener confirms the deletion.
 */
@Composable
internal fun DeletePlaylistDialog(
    playlistName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete_title)) },
        text = { Text(stringResource(R.string.playlist_delete_message, playlistName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.playlist_delete_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
