package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryEmptyState
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.MusicFolderRow
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.LibraryFoldersSettingsUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.LibraryFoldersSettingsUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.LibraryFoldersSettingsUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.LibraryFoldersSettingsViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Library Folders settings sub-screen.
 *
 * @param viewModel Hilt-provided ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFoldersSettingsScreen(viewModel: LibraryFoldersSettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        viewModel.onEvent(LibraryFoldersSettingsUiEvent.MusicFolderPicked(treeUri?.toString()))
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is LibraryFoldersSettingsUiEffect.ToggleError ->
                    snackbarHostState.showSnackbar(effect.message)

                // No initial URI: the chooser opens where the user last browsed, which
                // beats a hardcoded Music/ root for anyone keeping albums elsewhere.
                LibraryFoldersSettingsUiEffect.PickMusicFolder -> folderPickerLauncher.launch(null)
            }
        }
    }

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.NONE
    ) { model ->
        LibraryFoldersSettingsContent(
            model = model,
            snackbarHostState = snackbarHostState,
            onEvent = viewModel::onEvent,
        )
    }
}

/** Stateless content composable for the Library Folders settings sub-screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFoldersSettingsContent(
    model: LibraryFoldersSettingsUiModel,
    snackbarHostState: SnackbarHostState,
    onEvent: (LibraryFoldersSettingsUiEvent) -> Unit,
) {
    val shellBottomPadding = LocalShellBottomPadding.current

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.settings_category_library_folders_title)) },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = shellBottomPadding)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEvent(LibraryFoldersSettingsUiEvent.AddMusicFolderTapped) },
                icon = {
                    Icon(imageVector = Icons.Outlined.CreateNewFolder, contentDescription = null)
                },
                text = { Text(text = stringResource(R.string.settings_music_folders_add_action)) },
                modifier = Modifier.padding(bottom = shellBottomPadding),
            )
        },
        floatingActionButtonPosition = FabPosition.End,
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = 16.dp,
                bottom = shellBottomPadding + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "description") {
                Text(
                    text = stringResource(R.string.settings_music_folders_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (model.musicFolders.isEmpty()) {
                item(key = "empty") {
                    LibraryEmptyState(
                        icon = Icons.Outlined.Folder,
                        title = stringResource(R.string.settings_music_folders_title),
                        message = stringResource(R.string.settings_music_folders_empty),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                items(items = model.musicFolders, key = { folder -> folder.id }) { folder ->
                    MusicFolderRow(
                        folder = folder,
                        onRemove = { onEvent(LibraryFoldersSettingsUiEvent.RemoveMusicFolder(folder.id)) },
                    )
                }
            }
        }
    }
}
