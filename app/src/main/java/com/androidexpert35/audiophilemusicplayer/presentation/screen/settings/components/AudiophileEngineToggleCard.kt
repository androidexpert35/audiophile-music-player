package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Card surfaced for the direct USB audiophile-mode master toggle.
 *
 * @param enabled Current switch value.
 * @param inProgress Whether the runtime engine is currently hot-swapping.
 * @param onToggle Invoked with the new value when the user flips the switch.
 */
@Composable
fun AudiophileEngineToggleCard(
    enabled: Boolean,
    inProgress: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val supportingText = when {
        inProgress && enabled -> stringResource(R.string.settings_audiophile_mode_switching_disabled)
        inProgress && !enabled -> stringResource(R.string.settings_audiophile_mode_switching_enabled)
        enabled -> stringResource(R.string.settings_audiophile_mode_enabled_description)
        else -> stringResource(R.string.settings_audiophile_mode_disabled_description)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_audiophile_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                if (inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                }
                Switch(
                    checked = enabled,
                    enabled = !inProgress,
                    onCheckedChange = onToggle
                )
            }
        }
    }
}

