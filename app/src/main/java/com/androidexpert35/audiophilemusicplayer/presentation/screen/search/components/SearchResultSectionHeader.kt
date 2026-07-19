package com.androidexpert35.audiophilemusicplayer.presentation.screen.search.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Section divider header shown above each grouped result cluster in the search screen.
 *
 * Displays the section name (e.g. "Artists") alongside a parenthetical count badge
 * so the user instantly knows how many items are in each group.
 *
 * @param title Human-readable section label (e.g. "Artists", "Albums", "Songs").
 * @param count Number of results in this section; appended as a count badge.
 * @param modifier Optional [Modifier] for the root row.
 */
@Composable
internal fun SearchResultSectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.search_section_count_badge, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101114)
@Composable
private fun SearchResultSectionHeaderPreview() {
    AudiophileMusicPlayerTheme {
        SearchResultSectionHeader(
            title = "Artists",
            count = 3,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

