package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType

/**
 * Horizontally scrollable row of Material 3 [FilterChip] components for the library screen.
 *
 * Mirrors the Spotify "Your Library" filter row with four chips: Songs, Playlists,
 * Albums, and Artists. The chip corresponding to [selectedContentType] is shown in its
 * selected (filled-primary) state. Tapping an already-selected chip deselects it,
 * returning the display to the default [LibraryContentType.TRACKS] view.
 *
 * @param selectedContentType Currently active catalogue filter.
 * @param onSelectContentType Callback invoked when the user taps a chip.
 * @param modifier Optional [Modifier] for the root [LazyRow].
 */
@Composable
internal fun LibraryFilterChipsRow(
    selectedContentType: LibraryContentType,
    onSelectContentType: (LibraryContentType) -> Unit,
    modifier: Modifier = Modifier
) {
    // Songs is the first and default filter so users land on their music immediately.
    val filterOptions = listOf(
        LibraryContentType.TRACKS to stringResource(R.string.library_tracks_section_label),
        LibraryContentType.PLAYLISTS to stringResource(R.string.library_playlists_section_label),
        LibraryContentType.ALBUMS to stringResource(R.string.library_albums_section_label),
        LibraryContentType.ARTISTS to stringResource(R.string.library_artists_section_label)
    )

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = filterOptions,
            key = { (contentType, _) -> contentType.name }
        ) { (contentType, label) ->
            val isSelected = selectedContentType == contentType
            FilterChip(
                selected = isSelected,
                onClick = {
                    // Tapping the active chip returns to the default Songs view.
                    onSelectContentType(
                        if (isSelected) LibraryContentType.TRACKS else contentType
                    )
                },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                    borderColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibraryFilterChipsRow – Albums selected")
@Composable
private fun LibraryFilterChipsRowAlbumsPreview() {
    AudiophileMusicPlayerTheme {
        LibraryFilterChipsRow(
            selectedContentType = LibraryContentType.ALBUMS,
            onSelectContentType = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibraryFilterChipsRow – Songs selected")
@Composable
private fun LibraryFilterChipsRowNoFilterPreview() {
    AudiophileMusicPlayerTheme {
        LibraryFilterChipsRow(
            selectedContentType = LibraryContentType.TRACKS,
            onSelectContentType = {}
        )
    }
}

