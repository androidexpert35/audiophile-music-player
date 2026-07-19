package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Material 3 dialog explaining how a DSP engine works in technically honest terms.
 *
 * @param icon Visual cue matching the engine being explained.
 * @param titleRes String resource used as the dialog title.
 * @param bodyRes String resource containing the explanatory body copy.
 * @param onDismiss Callback invoked when the user dismisses the dialog.
 */
@Composable
internal fun DspInfoDialog(
    icon: ImageVector,
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    onDismiss: () -> Unit,
) {
    val bodyText = stringResource(bodyRes)
    val bodyBlocks = remember(bodyText) { DspInfoBodyParser.parse(bodyText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        title = {
            Text(text = stringResource(titleRes))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                bodyBlocks.forEach { block ->
                    when (block) {
                        is DspInfoBodyBlock.BulletList -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                block.items.forEach { item ->
                                    DspInfoBulletRow(text = item)
                                }
                            }
                        }

                        is DspInfoBodyBlock.Paragraph -> {
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_btn_understood))
            }
        },
    )
}

@Composable
private fun DspInfoBulletRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            modifier = Modifier.width(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

