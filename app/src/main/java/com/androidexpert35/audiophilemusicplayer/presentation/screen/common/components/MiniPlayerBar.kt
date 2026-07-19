package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.buildQualityLabel
import com.androidexpert35.audiophilemusicplayer.presentation.theme.HiResGold
import com.androidexpert35.audiophilemusicplayer.presentation.theme.HiResGoldContainer
import com.androidexpert35.audiophilemusicplayer.presentation.theme.LossyGrey
import com.androidexpert35.audiophilemusicplayer.presentation.theme.LossyGreyContainer

/**
 * Compact upper section of the floating composite bottom panel, showing the
 * currently playing track with transport controls.
 *
 * Renders transparently inside the parent glassmorphism [Surface] provided by
 * [AppShell] — it has no background or border of its own, achieving the seamless
 * look described in the composite panel specification.
 *
 * Layout (left → right):
 * - Rounded album-art thumbnail
 * - Track title + artist name, with a compact audio-quality pill (e.g. "24-BIT FLAC")
 *   shown inline next to the artist name
 * - Minimal white Play/Pause and Skip-Next icon buttons
 *
 * @param track The currently loaded track whose metadata and artwork are displayed.
 * @param playbackStatus Current discrete playback status driving the play/pause icon.
 * @param onPlayPauseClick Callback when the play/pause button is tapped.
 * @param onSkipPreviousClick Callback when the skip-previous button is tapped.
 * @param onSkipNextClick Callback when the skip-next button is tapped.
 * @param onMiniPlayerClick Callback when the track-info body is tapped (opens full player).
 * @param modifier Optional [Modifier] for the root layout.
 */
@Composable
fun MiniPlayerBar(
    track: Track,
    playbackStatus: PlaybackStatus,
    onPlayPauseClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onMiniPlayerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Throttle transport controls to reject accidental rapid bursts.
    val throttledPlayPause = rememberThrottledClick(onClick = onPlayPauseClick)
    val throttledSkipPrevious = rememberThrottledClick(onClick = onSkipPreviousClick)
    val throttledSkipNext = rememberThrottledClick(onClick = onSkipNextClick)

    val artUri = remember(track.artUri, track.albumId) {
        if (!track.artUri.isNullOrBlank()) {
            // DSD and other tracks with a pre-computed artwork URI (file:// or content://)
            // use it directly so the MediaStore albumart lookup is never attempted for
            // negative album IDs that MediaStore has no knowledge of.
            track.artUri
        } else {
            ContentUris.withAppendedId(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                track.albumId
            )
        }
    }

    // Memoize the ImageRequest keyed on the resolved art URI so that playback-status
    // changes (e.g. play→pause) do not rebuild the request and abort an in-flight load.
    val imageRequest = remember(artUri) {
        ImageRequest.Builder(context)
            .data(artUri)
            .crossfade(200)
            .build()
    }

    // Derive quality pill label and accent colour from the track's file-level format.
    val audioFormat = track.audioFormat
    val qualityLabel = remember(audioFormat) { buildQualityLabel(audioFormat) }
    val isLossless = audioFormat.isLossless
    val qualityTagColor = if (isLossless) HiResGold else LossyGrey
    val qualityContainerColor = if (isLossless) HiResGoldContainer else LossyGreyContainer

    Column(modifier = modifier.fillMaxWidth()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onMiniPlayerClick)
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Album art thumbnail ──────────────────────────────────────────
            AsyncImage(
                model = imageRequest,
                contentDescription = stringResource(R.string.cd_album_art, track.albumTitle),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
            )

            Spacer(modifier = Modifier.width(10.dp))

            // ── Track info + quality pill ────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // fill = false so the pill always has room and the artist name
                        // truncates first rather than pushing the pill out of sight.
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Compact quality pill — only rendered when format data is available.
                    if (qualityLabel != null) {
                        Text(
                            text = qualityLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = qualityTagColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(qualityContainerColor)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // ── Transport controls (right-aligned) ───────────────────────────
            // Skip-Previous
            IconButton(onClick = throttledSkipPrevious) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.cd_skip_previous),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Play/Pause — filled circle matching the player screen hero button style.
            FilledIconButton(
                onClick = throttledPlayPause,
                modifier = Modifier.size(42.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                AnimatedContent(
                    targetState = playbackStatus == PlaybackStatus.PLAYING,
                    transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f))
                            .togetherWith(fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f))
                    },
                    label = "mini_play_pause"
                ) { isPlaying ->
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.cd_pause else R.string.cd_play
                        ),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Skip-Next
            IconButton(onClick = throttledSkipNext) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.cd_skip_next),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

