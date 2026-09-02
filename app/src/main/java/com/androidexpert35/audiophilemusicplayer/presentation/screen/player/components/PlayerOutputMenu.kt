package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.androidexpert35.audiophilemusicplayer.R

/** Exposes explicit USB release and application-exit controls from the player. */
@Composable
internal fun PlayerOutputMenu(
    onReleaseDac: () -> Unit,
    onExitAndRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_player_output_menu),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_release_dac)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.UsbOff,
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onReleaseDac()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_exit_and_release_dac)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onExitAndRelease()
                },
            )
        }
    }
}
