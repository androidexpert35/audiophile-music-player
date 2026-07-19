package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Settings card for the Hi-Res Dynamic Remaster engine.
 *
 * Activates a 96 kHz oversampling + upward dynamic expansion pipeline for
 * lossless sources (FLAC, WAV, ALAC). Lossy sources are completely unaffected
 * regardless of this toggle.
 *
 * @param enabled Current persisted toggle value.
 * @param onInfoClick Invoked when the user requests the technical explanation dialog.
 * @param onToggle Invoked with the new preference value when the user flips the switch.
 */
@Composable
fun HiResRemasterCard(
    enabled: Boolean,
    onInfoClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val descriptionText = if (enabled) {
        stringResource(R.string.settings_hires_remaster_enabled_description)
    } else {
        stringResource(R.string.settings_hires_remaster_disabled_description)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_hires_remaster_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = descriptionText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onInfoClick) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.cd_open_hires_remaster_info),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                    )
                }
            }
        }
    }
}

@Preview(name = "Light — enabled", showBackground = true)
@Preview(name = "Dark — enabled", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HiResRemasterCardEnabledPreview() {
    AudiophileMusicPlayerTheme {
        HiResRemasterCard(enabled = true, onInfoClick = {}, onToggle = {})
    }
}

@Preview(name = "Light — disabled", showBackground = true)
@Preview(name = "Dark — disabled", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HiResRemasterCardDisabledPreview() {
    AudiophileMusicPlayerTheme {
        HiResRemasterCard(enabled = false, onInfoClick = {}, onToggle = {})
    }
}

