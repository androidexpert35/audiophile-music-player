package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibrarySortOrder

/**
 * Row containing the sort-order indicator and list/grid view toggle.
 *
 * The sort indicator on the left renders the active [sortOrder] label with a
 * swap-vert icon. Tapping it opens a [DropdownMenu] so the user can pick a
 * different [LibrarySortOrder] without navigating away. The trailing [IconButton]
 * toggles between list (single-column) and grid (two-column) view modes.
 *
 * @param sortOrder Currently active sort strategy.
 * @param isGridView Whether the two-column grid layout is currently active.
 * @param onSetSortOrder Invoked when the user selects a new sort order from the menu.
 * @param onToggleViewMode Invoked when the user taps the view-mode toggle icon.
 * @param modifier Optional [Modifier] for the root row.
 */
@Composable
internal fun LibrarySortToggleRow(
    sortOrder: LibrarySortOrder,
    isGridView: Boolean,
    onSetSortOrder: (LibrarySortOrder) -> Unit,
    onToggleViewMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Sort-order indicator + dropdown anchor
        Box {
            TextButton(
                onClick = { showSortMenu = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.SwapVert,
                    contentDescription = stringResource(R.string.cd_change_sort_order),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = sortOrder.toDisplayLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false }
            ) {
                LibrarySortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = order.toDisplayLabel(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (sortOrder == order) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        },
                        onClick = {
                            onSetSortOrder(order)
                            showSortMenu = false
                        },
                        leadingIcon = if (sortOrder == order) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        }

        // Grid / List view-mode toggle
        IconButton(onClick = onToggleViewMode) {
            Icon(
                imageVector = if (isGridView) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                contentDescription = stringResource(R.string.cd_toggle_library_view_mode),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Maps a [LibrarySortOrder] value to its user-visible display label.
 */
@Composable
private fun LibrarySortOrder.toDisplayLabel(): String = when (this) {
    LibrarySortOrder.RECENTLY_PLAYED -> stringResource(R.string.library_sort_recently_played)
    LibrarySortOrder.RECENTLY_ADDED -> stringResource(R.string.library_sort_recently_added)
    LibrarySortOrder.ALPHABETICAL -> stringResource(R.string.library_sort_alphabetical)
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibrarySortToggleRow – List view")
@Composable
private fun LibrarySortToggleRowListPreview() {
    AudiophileMusicPlayerTheme {
        LibrarySortToggleRow(
            sortOrder = LibrarySortOrder.RECENTLY_ADDED,
            isGridView = false,
            onSetSortOrder = {},
            onToggleViewMode = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibrarySortToggleRow – Grid view")
@Composable
private fun LibrarySortToggleRowGridPreview() {
    AudiophileMusicPlayerTheme {
        LibrarySortToggleRow(
            sortOrder = LibrarySortOrder.ALPHABETICAL,
            isGridView = true,
            onSetSortOrder = {},
            onToggleViewMode = {}
        )
    }
}



