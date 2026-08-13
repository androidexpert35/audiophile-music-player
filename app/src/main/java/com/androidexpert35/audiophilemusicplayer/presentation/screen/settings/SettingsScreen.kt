package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.AudiophileEngineToggleCard
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.DspInfoDialog
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.HiResRemasterCard
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.MusicFoldersCard
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.OpenSourceCard
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.SettingsSectionHeader
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.SueEnhancerCard
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.UsbDeviceInfoCard
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.SettingsUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.SettingsUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.SettingsUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.SettingsViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Settings screen entry point.
 *
 * Hosts the direct USB audiophile controls surfaced on top of the shared app
 * shell. The screen remains declarative: state flows from the ViewModel and
 * all user actions are emitted back through [SettingsUiEvent].
 *
 * @param viewModel Hilt-provided Settings ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var isLossyRestorerDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isHiResRemasterDialogVisible by rememberSaveable { mutableStateOf(false) }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        viewModel.onEvent(SettingsUiEvent.MusicFolderPicked(treeUri?.toString()))
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is SettingsUiEffect.ToggleError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }

                // No initial URI: the chooser opens where the user last browsed, which
                // beats a hardcoded Music/ root for anyone keeping albums elsewhere.
                SettingsUiEffect.PickMusicFolder -> folderPickerLauncher.launch(null)
            }
        }
    }

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.NONE
    ) { model ->
        SettingsContent(
            model = model,
            snackbarHostState = snackbarHostState,
            isLossyRestorerDialogVisible = isLossyRestorerDialogVisible,
            isHiResRemasterDialogVisible = isHiResRemasterDialogVisible,
            onShowLossyRestorerInfo = { isLossyRestorerDialogVisible = true },
            onDismissLossyRestorerInfo = { isLossyRestorerDialogVisible = false },
            onShowHiResRemasterInfo = { isHiResRemasterDialogVisible = true },
            onDismissHiResRemasterInfo = { isHiResRemasterDialogVisible = false },
            onEvent = viewModel::onEvent,
        )
    }
}

/**
 * Stateless content composable for the Settings screen.
 *
 * @param model Current immutable Settings UI snapshot.
 * @param snackbarHostState Host used for transient error messages.
 * @param isLossyRestorerDialogVisible Whether the lossy-restorer info dialog is visible.
 * @param isHiResRemasterDialogVisible Whether the hi-res remaster info dialog is visible.
 * @param onShowLossyRestorerInfo Callback requesting the lossy-restorer dialog.
 * @param onDismissLossyRestorerInfo Callback dismissing the lossy-restorer dialog.
 * @param onShowHiResRemasterInfo Callback requesting the hi-res remaster dialog.
 * @param onDismissHiResRemasterInfo Callback dismissing the hi-res remaster dialog.
 * @param onEvent Callback emitting user intents to the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    model: SettingsUiModel,
    snackbarHostState: SnackbarHostState,
    isLossyRestorerDialogVisible: Boolean,
    isHiResRemasterDialogVisible: Boolean,
    onShowLossyRestorerInfo: () -> Unit,
    onDismissLossyRestorerInfo: () -> Unit,
    onShowHiResRemasterInfo: () -> Unit,
    onDismissHiResRemasterInfo: () -> Unit,
    onEvent: (SettingsUiEvent) -> Unit,
) {
    val shellBottomPadding = LocalShellBottomPadding.current

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.nav_settings)) },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = shellBottomPadding)
            )
        },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            SettingsSectionHeader(titleRes = R.string.settings_section_general)

            AudiophileEngineToggleCard(
                enabled = model.audiophileEngineEnabled,
                inProgress = model.isAudiophileEngineSwitchInProgress,
                onToggle = { onEvent(SettingsUiEvent.SetAudiophileEngineEnabled(it)) },
            )

            SettingsSectionHeader(titleRes = R.string.settings_section_smart_effects)
            SueEnhancerCard(
                enabled = model.sueEnabled,
                sueStatus = model.sueStatus,
                onInfoClick = onShowLossyRestorerInfo,
                onToggle = { onEvent(SettingsUiEvent.SetSueEnabled(it)) },
            )

            HiResRemasterCard(
                enabled = model.hiResRemasterEnabled,
                onInfoClick = onShowHiResRemasterInfo,
                onToggle = { onEvent(SettingsUiEvent.SetHiResRemasterEnabled(it)) },
            )

            SettingsSectionHeader(titleRes = R.string.settings_section_usb_settings)
            UsbDeviceInfoCard(
                status = model.usbAudioStatus,
                isUsbPlaybackActive = model.isUsbPlaybackActive,
                activePlaybackDeviceName = model.activeUsbPlaybackDeviceName,
                isRefreshInProgress = model.isUsbDeviceRefreshInProgress,
                onRefresh = { onEvent(SettingsUiEvent.RefreshUsbAudioDevices) },
                onRequestPermission = { onEvent(SettingsUiEvent.RequestUsbAudioPermission) },
            )

            SettingsSectionHeader(titleRes = R.string.settings_section_library)
            MusicFoldersCard(
                folders = model.musicFolders,
                onAddFolder = { onEvent(SettingsUiEvent.AddMusicFolderTapped) },
                onRemoveFolder = { folderId ->
                    onEvent(SettingsUiEvent.RemoveMusicFolder(folderId))
                },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsSectionHeader(titleRes = R.string.settings_section_about)
            OpenSourceCard()
        }
    }

    if (isLossyRestorerDialogVisible) {
        DspInfoDialog(
            icon = Icons.Rounded.AutoAwesome,
            titleRes = R.string.dialog_lossy_restorer_title,
            bodyRes = R.string.dialog_lossy_restorer_body,
            onDismiss = onDismissLossyRestorerInfo,
        )
    }

    if (isHiResRemasterDialogVisible) {
        DspInfoDialog(
            icon = Icons.Rounded.GraphicEq,
            titleRes = R.string.dialog_hires_remaster_title,
            bodyRes = R.string.dialog_hires_remaster_body,
            onDismiss = onDismissHiResRemasterInfo,
        )
    }
}
