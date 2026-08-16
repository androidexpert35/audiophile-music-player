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
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.QueueRetentionCard
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.PlaybackBehaviorSettingsUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.PlaybackBehaviorSettingsUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.PlaybackBehaviorSettingsUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.PlaybackBehaviorSettingsViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Playback Behavior settings sub-screen.
 *
 * @param viewModel Hilt-provided ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackBehaviorSettingsScreen(viewModel: PlaybackBehaviorSettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is PlaybackBehaviorSettingsUiEffect.ToggleError ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.NONE
    ) { model ->
        PlaybackBehaviorSettingsContent(
            model = model,
            snackbarHostState = snackbarHostState,
            onEvent = viewModel::onEvent,
        )
    }
}

/** Stateless content composable for the Playback Behavior settings sub-screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackBehaviorSettingsContent(
    model: PlaybackBehaviorSettingsUiModel,
    snackbarHostState: SnackbarHostState,
    onEvent: (PlaybackBehaviorSettingsUiEvent) -> Unit,
) {
    val shellBottomPadding = LocalShellBottomPadding.current

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.settings_category_playback_behavior_title)) },
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
            QueueRetentionCard(
                enabled = model.clearQueueOnExit,
                onToggle = { onEvent(PlaybackBehaviorSettingsUiEvent.SetClearQueueOnExit(it)) },
            )
        }
    }
}
