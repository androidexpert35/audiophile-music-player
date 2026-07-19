package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Simple section header used to group related settings rows.
 *
 * @param titleRes Localized title displayed for the section.
 * @param modifier Modifier applied to the section title.
 */
@Composable
fun SettingsSectionHeader(
    @StringRes titleRes: Int,
    modifier: Modifier = Modifier
) {
    Text(
        text = stringResource(titleRes).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 4.dp)
    )
}

