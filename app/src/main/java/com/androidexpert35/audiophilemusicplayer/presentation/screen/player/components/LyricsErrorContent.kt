package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Error state shown inside the lyrics sheet, with a retry action.
 *
 * @param message Human-readable failure description.
 * @param onRetry Callback invoked when the user taps the retry button.
 */
@Composable
internal fun LyricsErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.lyrics_retry))
        }
    }
}

