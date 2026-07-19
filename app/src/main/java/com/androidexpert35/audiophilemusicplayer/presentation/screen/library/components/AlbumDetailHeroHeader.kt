package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.buildResolutionLabel
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.HiResGold
import com.androidexpert35.audiophilemusicplayer.presentation.theme.HiResGoldContainer
import com.androidexpert35.audiophilemusicplayer.presentation.theme.LossyGrey
import com.androidexpert35.audiophilemusicplayer.presentation.theme.LossyGreyContainer
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingMedium

/**
 * Immersive hero header for the album detail screen.
 *
 * Displays a large, perfectly square album artwork centred on the page with a
 * polished drop shadow, followed by the album title and artist metadata. The
 * technical codec and resolution pills live on their own row beneath them.
 *
 * @param album Album metadata to present in the header.
 * @param totalDurationMs Combined duration of all album tracks in milliseconds,
 *   used to derive the human-readable "X min" label beside the release year.
 * @param artistImageUrl Cached profile image for [album]'s artist, or `null` to use the
 *   standard artist placeholder.
 * @param onArtistClick Callback invoked when the user taps the artist name.
 * @param modifier Optional [Modifier] applied to the root [Column].
 * @param maxSampleRateHz Highest sample rate across the album's tracks in Hertz.
 *   Pass 0 to suppress the sample-rate pill.
 * @param maxBitDepth Highest bit depth across the album's tracks in bits per sample.
 *   Pass 0 when unknown.
 * @param codecSummary Human-readable codec label (e.g. "FLAC", "MP3").
 *   Pass an empty string when unknown.
 * @param isLossless Whether at least one track in the album is encoded losslessly,
 *   which drives the gold vs grey pill colour selection.
 */
@Composable
internal fun AlbumDetailHeroHeader(
    album: Album,
    totalDurationMs: Long,
    artistImageUrl: String?,
    onArtistClick: () -> Unit,
    modifier: Modifier = Modifier,
    maxSampleRateHz: Int = 0,
    maxBitDepth: Int = 0,
    codecSummary: String = "",
    isLossless: Boolean = false,
) {
    val context = LocalContext.current
    val totalMinutes = (totalDurationMs / 60_000L).coerceAtLeast(0L)
    val totalMinutesLabel = if (totalMinutes > 0) {
        stringResource(R.string.format_duration_minutes_short, totalMinutes)
    } else {
        null
    }

    // Memoize the ImageRequest so scroll-driven or state-triggered recompositions
    // do not cancel an in-flight Coil load by constructing a new request object.
    val imageRequest = remember(album.artUri, album.remoteArtUrl) {
        ImageRequest.Builder(context)
            .data(album.remoteArtUrl ?: album.artUri)
            .crossfade(MotionTokens.DurationMedium)
            .build()
    }
    val artistImageRequest = remember(artistImageUrl) {
        ImageRequest.Builder(context)
            .data(artistImageUrl)
            .crossfade(MotionTokens.DurationMedium)
            .build()
    }

    // Memoize the secondary metadata string — buildList + joinToString allocate.
    val metaText = remember(album.id, album.year, totalMinutesLabel) {
        buildList {
            if (album.year > 0) add(album.year.toString())
            totalMinutesLabel?.let(::add)
        }.joinToString(" • ")
    }

    // Chip 1: codec name only (e.g. "FLAC", "MP3") — mirrors MiniPlayerBar's pill
    // but without the bit-depth prefix, since that lives in the resolution chip.
    val codecLabel = remember(codecSummary) {
        codecSummary.trim().takeIf { it.isNotBlank() }
    }
    // Chip 2: combined resolution label (e.g. "24-bit / 96 kHz") shown only on the
    // album hero header, never in the mini player.
    val resolutionLabel = remember(maxBitDepth, maxSampleRateHz) {
        buildResolutionLabel(maxBitDepth, maxSampleRateHz)
    }
    // Mirror MiniPlayerBar: gold accent for lossless, grey for lossy.
    val tagTextColor = if (isLossless) HiResGold else LossyGrey
    val tagContainerColor = if (isLossless) HiResGoldContainer else LossyGreyContainer

    // Capture the token before remember — MaterialTheme.colorScheme is @Composable and
    // cannot be accessed inside a non-composable lambda body.
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val placeholderIconTint = remember(onSurfaceVariantColor) {
        onSurfaceVariantColor.copy(alpha = 0.4f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // Reserve height for the floating back-button bar overlaid by the parent screen.
            .padding(top = 56.dp)
    ) {
        // ── Square album artwork ──────────────────────────────────────────────
        DetailHeroArtwork {
            if (album.artUri != null || album.remoteArtUrl != null) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = stringResource(R.string.cd_album_art, album.title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Album,
                    contentDescription = null,
                    tint = placeholderIconTint,
                    modifier = Modifier.size(80.dp)
                )
            }
        }

        // ── Album title + metadata ────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = paddingMedium, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // ── Artist • year • duration ──────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    if (!artistImageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = artistImageRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Text(
                    text = album.artistName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        // `fill = false` reserves space for the following metadata
                        // without distributing artist and metadata to opposite edges.
                        .weight(1f, fill = false)
                        .clickable(onClick = onArtistClick)
                )

                if (metaText.isNotEmpty()) {
                    Text(
                        text = "• $metaText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ── Audio-quality pills ───────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Chip 1: codec only — e.g. "FLAC".
                if (codecLabel != null) {
                    Text(
                        text = codecLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = tagTextColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(tagContainerColor)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Chip 2: combined resolution — e.g. "24-bit / 96 kHz".
                if (resolutionLabel != null) {
                    Text(
                        text = resolutionLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = tagTextColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(tagContainerColor)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(
    name = "AlbumDetailHeroHeader — Hi-Res FLAC",
    showBackground = true,
    backgroundColor = 0xFF090B10
)
@Composable
private fun AlbumDetailHeroHeaderHiResPreview() {
    AudiophileMusicPlayerTheme {
        AlbumDetailHeroHeader(
            album = Album(
                id = 1L,
                title = "Random Access Memories",
                artistName = "Daft Punk",
                artUri = null,
                trackCount = 13,
                year = 2013
            ),
            totalDurationMs = 74 * 60_000L,
            artistImageUrl = null,
            onArtistClick = {},
            maxSampleRateHz = 96_000,
            maxBitDepth = 24,
            codecSummary = "FLAC",
            isLossless = true,
        )
    }
}

@Preview(
    name = "AlbumDetailHeroHeader — Lossy MP3",
    showBackground = true,
    backgroundColor = 0xFF090B10
)
@Composable
private fun AlbumDetailHeroHeaderLossyPreview() {
    AudiophileMusicPlayerTheme {
        AlbumDetailHeroHeader(
            album = Album(
                id = 2L,
                title = "The Dark Side of the Moon (50th Anniversary Remaster)",
                artistName = "Pink Floyd",
                artUri = null,
                trackCount = 10,
                year = 2023
            ),
            totalDurationMs = 42 * 60_000L,
            artistImageUrl = null,
            onArtistClick = {},
            maxSampleRateHz = 44_100,
            maxBitDepth = 0,
            codecSummary = "MP3",
            isLossless = false,
        )
    }
}
