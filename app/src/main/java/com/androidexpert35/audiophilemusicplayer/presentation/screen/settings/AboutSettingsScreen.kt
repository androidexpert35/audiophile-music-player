package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.BuildConfig
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.AboutAppIntro
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.DonationCard
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.OpenSourceCard

/**
 * Presents the Audiophile project story and ways to support or inspect the app.
 *
 * Fully stateless — all content is static apart from [BuildConfig.VERSION_NAME], which
 * remains aligned with the installed build without duplicating release metadata in UI code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen() {
    val shellBottomPadding = LocalShellBottomPadding.current

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
            DonationCard()
            OpenSourceCard()
        }
    }
}
