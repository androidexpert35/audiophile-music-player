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
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType

/**
 * Horizontally scrollable row of Material 3 [FilterChip] components for the library screen.
 *
 * Mirrors the Spotify "Your Library" filter row. Which sections appear and in what
 * order is the caller's responsibility — see [visibleSections] — so a section hidden
 * from Settings simply never gets a chip here. The chip corresponding to
 * [selectedContentType] is shown in its selected (filled-primary) state. Tapping an
 * already-selected chip deselects it, falling back to the first visible section.
 *
 * @param visibleSections Sections to render, already filtered and ordered by the
 *   caller's persisted display preferences.
 * @param selectedContentType Currently active catalogue filter.
 * @param onSelectContentType Callback invoked when the user taps a chip.
 * @param modifier Optional [Modifier] for the root [LazyRow].
 */
@Composable
internal fun LibraryFilterChipsRow(
    visibleSections: List<LibraryContentType>,
    selectedContentType: LibraryContentType,
    onSelectContentType: (LibraryContentType) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = visibleSections,
            key = { contentType -> contentType.name }
        ) { contentType ->
            val isSelected = selectedContentType == contentType
            FilterChip(
                selected = isSelected,
                onClick = {
                    // Tapping the active chip falls back to the first visible section.
                    onSelectContentType(
                        if (isSelected) {
                            visibleSections.firstOrNull() ?: LibraryContentType.TRACKS
                        } else {
                            contentType
                        }
                    )
                },
                label = {
                    Text(
                        text = stringResource(contentType.labelRes),
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
            visibleSections = LibraryContentType.entries,
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
            visibleSections = LibraryContentType.entries,
            selectedContentType = LibraryContentType.TRACKS,
            onSelectContentType = {}
        )
    }
}

