package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Labelled disc section header inserted between track groups on multi-disc albums.
 *
 * @param discNumber 1-based disc index to display in the header label.
 * @param modifier Optional [Modifier] applied to the root [Text].
 */
@Composable
fun AlbumDetailDiscHeader(
    discNumber: Int,
    modifier: Modifier = Modifier
) {
    Text(
        text = stringResource(R.string.library_disc_label, discNumber),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

