package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.BuildConfig
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.AboutAppIntro
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.DonationCard
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.OpenSourceCard
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.AboutSettingsUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings.AboutSettingsViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen

/**
 * Presents the Audiophile project story and ways to support or inspect the app.
 *
 * @param viewModel Coordinates preparation of the session diagnostic report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen(viewModel: AboutSettingsViewModel = hiltViewModel()) {
    val shellBottomPadding = LocalShellBottomPadding.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppBaseScreen(uiState = uiState, onErrorDialogDismiss = viewModel::dismissErrorPopup) { model ->
        Scaffold(
            topBar = { AppTopBar(title = stringResource(R.string.settings_category_about_title)) },
            containerColor = MaterialTheme.colorScheme.background,
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
                AboutAppIntro(versionName = BuildConfig.VERSION_NAME)
                Card {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.bug_report_action), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.bug_report_session_description), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            enabled = !model.preparingReport,
                            onClick = { viewModel.onEvent(AboutSettingsUiEvent.ReportBug) }
                        ) {
                            Text(stringResource(if (model.preparingReport) R.string.bug_report_preparing else R.string.bug_report_action))
                        }
                    }
                }
                DonationCard()
                OpenSourceCard()
            }
        }
    }
}
