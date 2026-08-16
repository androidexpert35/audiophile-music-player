package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.SettingsCategory
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.SettingsCategoryCard
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.SettingsUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.SettingsUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.SettingsViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Settings hub screen entry point.
 *
 * Shows every settings category as a tappable card; each card opens its own
 * dedicated sub-screen (see [AppRoutes.SettingsFlow][com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes.SettingsFlow]).
 * Keeping the hub itself free of individual toggles is what lets new settings be
 * added without turning this screen back into a single long list.
 *
 * @param viewModel Hilt-provided Settings hub ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.NONE
    ) { model ->
        SettingsContent(
            model = model,
            onEvent = viewModel::onEvent,
        )
    }
}

/**
 * Stateless content composable for the Settings hub screen.
 *
 * @param model Current immutable Settings hub UI snapshot.
 * @param onEvent Callback emitting user intents to the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    model: SettingsUiModel,
    onEvent: (SettingsUiEvent) -> Unit,
) {
    val shellBottomPadding = LocalShellBottomPadding.current

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.nav_settings)) },
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
            SettingsCategory.entries.forEach { category ->
                SettingsCategoryCard(
                    category = category,
                    subtitle = category.subtitle(model),
                    onClick = { onEvent(SettingsUiEvent.OpenCategory(category)) },
                )
            }
        }
    }
}

/** Computes the live one-line status subtitle shown on this category's hub card. */
@Composable
private fun SettingsCategory.subtitle(model: SettingsUiModel): String = when (this) {
    SettingsCategory.AUDIO_ENGINE -> stringResource(
        if (model.audiophileEngineEnabled) {
            R.string.settings_category_audio_engine_subtitle_on
        } else {
            R.string.settings_category_audio_engine_subtitle_off
        }
    )

    SettingsCategory.USB -> stringResource(
        if (model.isUsbDacConnected) {
            R.string.settings_category_usb_subtitle_connected
        } else {
            R.string.settings_category_usb_subtitle_disconnected
        }
    )

    SettingsCategory.LIBRARY_FOLDERS -> if (model.musicFolderCount > 0) {
        pluralStringResource(
            R.plurals.settings_category_library_folders_subtitle,
            model.musicFolderCount,
            model.musicFolderCount,
        )
    } else {
        stringResource(R.string.settings_category_library_folders_subtitle_empty)
    }

    SettingsCategory.LIBRARY_SECTIONS -> stringResource(
        R.string.settings_category_library_sections_subtitle,
        model.visibleLibrarySectionCount,
        model.totalLibrarySectionCount,
    )

    SettingsCategory.PLAYBACK_BEHAVIOR -> stringResource(
        if (model.clearQueueOnExit) {
            R.string.settings_category_playback_behavior_subtitle_on
        } else {
            R.string.settings_category_playback_behavior_subtitle_off
        }
    )

    SettingsCategory.ABOUT -> stringResource(R.string.settings_category_about_subtitle)
}
