package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.rememberFixedListReorderState
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.LibrarySectionRow
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.LibrarySectionsSettingsUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.LibrarySectionsSettingsUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.LibrarySectionsSettingsViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Library Sections settings sub-screen.
 *
 * Lets the user choose which library catalogue sections appear as filter chips and
 * drag-reorder them. A fixed, small ([LibraryContentType.entries] never grows beyond a
 * handful of values) `Column` list, deliberately not a `LazyColumn`, per this app's
 * settings-layout convention.
 *
 * @param viewModel Hilt-provided ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySectionsSettingsScreen(viewModel: LibrarySectionsSettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.NONE
    ) { model ->
        LibrarySectionsSettingsContent(
            model = model,
            onEvent = viewModel::onEvent,
        )
    }
}

/** Stateless content composable for the Library Sections settings sub-screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySectionsSettingsContent(
    model: LibrarySectionsSettingsUiModel,
    onEvent: (LibrarySectionsSettingsUiEvent) -> Unit,
) {
    val shellBottomPadding = LocalShellBottomPadding.current
    val reorderState = rememberFixedListReorderState(itemCount = model.rows.size)

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.settings_category_library_sections_title)) },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 16.dp,
                    bottom = shellBottomPadding + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_library_sections_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            model.rows.forEachIndexed { index, row ->
                LibrarySectionRowItem(
                    row = row,
                    modifier = Modifier
                        .graphicsLayer { translationY = reorderState.translationFor(index) }
                        .onGloballyPositioned { coordinates ->
                            reorderState.onItemPositioned(index, coordinates)
                        },
                    dragHandleModifier = reorderState.dragHandleModifier(index) { from, to ->
                        onEvent(LibrarySectionsSettingsUiEvent.MoveSection(from, to))
                    },
                    onToggleVisibility = {
                        onEvent(LibrarySectionsSettingsUiEvent.ToggleVisibility(row.section))
                    },
                )
            }
        }
    }
}

/** Icon representing each library catalogue section on its settings row. */
private val LibraryContentType.icon: ImageVector
    get() = when (this) {
        LibraryContentType.TRACKS -> Icons.Rounded.MusicNote
        LibraryContentType.PLAYLISTS -> Icons.AutoMirrored.Rounded.QueueMusic
        LibraryContentType.ALBUMS -> Icons.Rounded.Album
        LibraryContentType.ARTISTS -> Icons.Rounded.Person
        LibraryContentType.GENRES -> Icons.Rounded.MusicNote
        LibraryContentType.YEARS -> Icons.Rounded.Album
        LibraryContentType.COMPOSERS -> Icons.Rounded.Person
    }

@Composable
private fun LibrarySectionRowItem(
    row: LibrarySectionRow,
    dragHandleModifier: Modifier,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = stringResource(R.string.cd_drag_handle_reorder_section),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier.size(24.dp),
            )
            Icon(
                imageVector = row.section.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(row.section.labelRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = row.isVisible, onCheckedChange = { onToggleVisibility() })
        }
    }
}
