package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Track-identity header rendered at the top of the lyrics sheet.
 *
 * Layout: a small album-art tile on the left, followed by a column with the
 * track title and an optional `artist · album` subtitle.
 *
 * @param trackTitle Title of the currently playing track.
 * @param artistName Artist name shown in the subtitle.
 * @param albumTitle Album title shown in the subtitle, separated from the
 *   artist name by a middle dot.
 * @param albumId MediaStore album identifier used to resolve album art.
 * @param localArtUri Optional pre-computed art URI (e.g. extracted DSD art).
 * @param remoteArtUrl Optional remote cover URL used as a network fallback.
 * @param modifier Modifier applied to the root row.
 */
@Composable
internal fun LyricsSheetHeader(
    trackTitle: String,
    artistName: String,
    albumTitle: String,
    albumId: Long,
    localArtUri: String? = null,
    remoteArtUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    // Build the subtitle from non-blank parts only so a missing field never
    // leaves a trailing " · ".
    val subtitle = remember(artistName, albumTitle) {
        listOf(artistName, albumTitle)
            .filter { it.isNotBlank() }
            .joinToString(separator = " · ")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LyricsSheetMiniArt(
            albumId = albumId,
            albumTitle = albumTitle,
            localArtUri = localArtUri,
            remoteArtUrl = remoteArtUrl,
            modifier = Modifier.size(56.dp),
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = trackTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

