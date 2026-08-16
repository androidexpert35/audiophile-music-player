package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.UsbDeviceInfoCard
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.UsbSettingsUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.UsbSettingsUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.UsbSettingsUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.UsbSettingsViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * USB &amp; DAC settings sub-screen.
 *
 * @param viewModel Hilt-provided ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbSettingsScreen(viewModel: UsbSettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is UsbSettingsUiEffect.ToggleError -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.NONE
    ) { model ->
        UsbSettingsContent(
            model = model,
            snackbarHostState = snackbarHostState,
            onEvent = viewModel::onEvent,
        )
    }
}

/** Stateless content composable for the USB &amp; DAC settings sub-screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsbSettingsContent(
    model: UsbSettingsUiModel,
    snackbarHostState: SnackbarHostState,
    onEvent: (UsbSettingsUiEvent) -> Unit,
) {
    val shellBottomPadding = LocalShellBottomPadding.current

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.settings_category_usb_title)) },
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
            UsbDeviceInfoCard(
                status = model.usbAudioStatus,
                isUsbPlaybackActive = model.isUsbPlaybackActive,
                activePlaybackDeviceName = model.activeUsbPlaybackDeviceName,
                isRefreshInProgress = model.isUsbDeviceRefreshInProgress,
                onRefresh = { onEvent(UsbSettingsUiEvent.RefreshUsbAudioDevices) },
                onRequestPermission = { onEvent(UsbSettingsUiEvent.RequestUsbAudioPermission) },
            )
        }
    }
}
