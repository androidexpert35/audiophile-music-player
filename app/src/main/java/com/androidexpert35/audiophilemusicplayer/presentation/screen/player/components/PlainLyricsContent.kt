package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Vertically scrollable plain-text lyrics block for tracks without synced lines.
 *
 * @param plainLyrics Full unformatted lyrics string.
 */
@Composable
internal fun PlainLyricsContent(plainLyrics: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.lyrics_plain_text_label),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            text = plainLyrics,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

