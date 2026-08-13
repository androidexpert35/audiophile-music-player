package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.library.MusicFolder
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Settings card listing the folders the library is built from, with controls to add and
 * remove them.
 *
 * These folders are the entire scope of the library scan, so this card is the user's
 * direct control over what the catalogue contains: adding the folder that holds their DSD
 * albums is what makes those tracks appear, and leaving messenger media folders out is
 * what keeps voice notes from being listed as songs.
 *
 * @param folders Currently granted music folders, empty when none is granted yet.
 * @param onAddFolder Invoked when the user asks to add another folder.
 * @param onRemoveFolder Invoked with the folder identifier the user asked to drop.
 */
@Composable
fun MusicFoldersCard(
    folders: List<MusicFolder>,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_music_folders_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_music_folders_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (folders.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_music_folders_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                folders.forEach { folder ->
                    MusicFolderRow(
                        folder = folder,
                        onRemove = { onRemoveFolder(folder.id) },
                    )
                }
            }

            OutlinedButton(
                onClick = onAddFolder,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CreateNewFolder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.settings_music_folders_add_action),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Single granted folder entry showing its path, its storage volume, and a remove control.
 *
 * @param folder Folder to render.
 * @param onRemove Invoked when the user drops this folder from the scan scope.
 */
@Composable
private fun MusicFolderRow(
    folder: MusicFolder,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // A whole-volume grant has no path of its own; its volume name is the label.
                text = folder.displayPath.ifBlank { folder.storageLabel },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folder.storageLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.cd_remove_music_folder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Light — folders", showBackground = true)
@Preview(
    name = "Dark — folders",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun MusicFoldersCardPopulatedPreview() {
    AudiophileMusicPlayerTheme {
        MusicFoldersCard(
            folders = listOf(
                MusicFolder(
                    id = "tree/primary:Music",
                    displayPath = "Music",
                    storageLabel = "Internal shared storage",
                ),
                MusicFolder(
                    id = "tree/1a2b-3c4d:DSD",
                    displayPath = "DSD",
                    storageLabel = "SD card",
                ),
            ),
            onAddFolder = {},
            onRemoveFolder = {},
        )
    }
}

@Preview(name = "Light — empty", showBackground = true)
@Preview(
    name = "Dark — empty",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun MusicFoldersCardEmptyPreview() {
    AudiophileMusicPlayerTheme {
        MusicFoldersCard(folders = emptyList(), onAddFolder = {}, onRemoveFolder = {})
    }
}
