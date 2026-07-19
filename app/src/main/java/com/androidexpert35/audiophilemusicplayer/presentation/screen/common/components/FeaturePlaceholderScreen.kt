package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Reusable expressive placeholder screen for destinations that are not yet fully implemented.
 *
 * It keeps the navigation flow visually cohesive by presenting a premium Material 3
 * surface instead of an empty route while feature work is still in progress.
 *
 * @param icon Hero icon shown above the copy.
 * @param eyebrow Small supporting label used to frame the destination.
 * @param title Primary placeholder headline.
 * @param message Supporting descriptive copy.
 * @param modifier Optional [Modifier] for the root layout.
 */
@Composable
fun FeaturePlaceholderScreen(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.placeholder_chip_material3)) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.placeholder_chip_audiophile)) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            disabledLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.placeholder_chip_in_progress)) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            disabledLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    )
                }
            }
        }
    }
}

