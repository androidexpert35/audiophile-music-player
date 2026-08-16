package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings

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
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.SettingsSectionHeader
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.SueEnhancerCard
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.AudioEngineSettingsUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.AudioEngineSettingsUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.AudioEngineSettingsUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.AudioEngineSettingsViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Audio Engine &amp; DSP settings sub-screen.
 *
 * Hosts the bit-perfect engine toggle plus the Sonic Upscaling Enhancer and Hi-Res
 * Dynamic Remaster DSP stages. All actions are emitted back through [AudioEngineSettingsUiEvent].
 *
 * @param viewModel Hilt-provided ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEngineSettingsScreen(viewModel: AudioEngineSettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var isLossyRestorerDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isHiResRemasterDialogVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is AudioEngineSettingsUiEffect.ToggleError ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.NONE
    ) { model ->
        AudioEngineSettingsContent(
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

/** Stateless content composable for the Audio Engine &amp; DSP settings sub-screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioEngineSettingsContent(
    model: AudioEngineSettingsUiModel,
    snackbarHostState: SnackbarHostState,
    isLossyRestorerDialogVisible: Boolean,
    isHiResRemasterDialogVisible: Boolean,
    onShowLossyRestorerInfo: () -> Unit,
    onDismissLossyRestorerInfo: () -> Unit,
    onShowHiResRemasterInfo: () -> Unit,
    onDismissHiResRemasterInfo: () -> Unit,
    onEvent: (AudioEngineSettingsUiEvent) -> Unit,
) {
    val shellBottomPadding = LocalShellBottomPadding.current

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.settings_category_audio_engine_title)) },
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
            AudiophileEngineToggleCard(
                enabled = model.audiophileEngineEnabled,
                inProgress = model.isAudiophileEngineSwitchInProgress,
                onToggle = { onEvent(AudioEngineSettingsUiEvent.SetAudiophileEngineEnabled(it)) },
            )

            SettingsSectionHeader(titleRes = R.string.settings_section_smart_effects)
            SueEnhancerCard(
                enabled = model.sueEnabled,
                sueStatus = model.sueStatus,
                onInfoClick = onShowLossyRestorerInfo,
                onToggle = { onEvent(AudioEngineSettingsUiEvent.SetSueEnabled(it)) },
            )

            HiResRemasterCard(
                enabled = model.hiResRemasterEnabled,
                onInfoClick = onShowHiResRemasterInfo,
                onToggle = { onEvent(AudioEngineSettingsUiEvent.SetHiResRemasterEnabled(it)) },
            )
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
