package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens

/**
 * Width-to-height ratio for artist photography.
 *
 * A proportional container keeps Deezer's square source images cropped by the
 * same amount across narrow previews and wide phone displays.
 */
private const val HeroAspectRatio = 1.25f

/**
 * Immersive hero section for the artist description screen.
 *
 * Renders a full-bleed artist photograph (from the Deezer remote URL or a local
 * placeholder icon when unavailable) with a vertical gradient overlay that
 * transitions from transparent at the top to the theme background at the bottom.
 * The artist name and collection statistics are anchored to the bottom of the
 * image, and a compact action row (Play, Shuffle, Favorites) sits directly below.
 * The parent lets this section extend behind a transparent status bar. A dark
 * top scrim guarantees contrast for white system icons, while the proportional
 * container prevents device width from changing the crop unexpectedly.
 *
 * @param artistName Display name rendered over the hero photograph.
 * @param artistImageUrl Deezer HTTPS URL for the artist photo, or `null` to show the
 *   placeholder icon.
 * @param trackCount Total number of tracks attributed to this artist.
 * @param albumCount Number of primary albums in the artist's discography.
 * @param isFollowed Whether the user has toggled the local follow state.
 * @param onPlayClick Callback starting sequential artist playback.
 * @param onShuffleClick Callback starting shuffle playback over the artist's queue.
 * @param onFollowClick Callback toggling the local follow state.
 * @param modifier Optional [Modifier] for the root [Column].
 */
@Composable
internal fun ArtistDescriptionHeroSection(
    artistName: String,
    artistImageUrl: String?,
    trackCount: Int,
    albumCount: Int,
    isFollowed: Boolean,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onFollowClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Hero image ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(HeroAspectRatio)
        ) {
            // The same purple profile placeholder used by artist list rows remains
            // visible while loading and whenever remote artwork is unavailable.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f)
                )
            }

            // Remote artist photograph — fades in once the URL is available.
            if (artistImageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(artistImageUrl)
                        .crossfade(MotionTokens.DurationMedium)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Keep the system-bar region consistently dark so white Android status
            // icons remain readable over photographs of any brightness.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.62f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Vertical gradient overlay — makes the artist name legible against
            // any image tone and blends the hero naturally into the page body.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                                1.0f to MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )

            // Artist name + stats anchored to the bottom of the image
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text(
                    text = artistName,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (albumCount > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.library_album_count, albumCount, albumCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                    if (albumCount > 0 && trackCount > 0) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                    }
                    if (trackCount > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.library_track_count, trackCount, trackCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }
            }
        }

        // ── Action row ─────────────────────────────────────────────────────────
        // Play (filled) + Shuffle (icon) + Favorites (outlined) arranged horizontally
        // directly below the hero image with standard horizontal padding.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Primary play action — takes all available space to appear most prominent
            Button(
                onClick = onPlayClick,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = stringResource(R.string.artist_description_play_label))
            }

            // Shuffle — compact circular button that mirrors the play action's height
            Surface(
                onClick = onShuffleClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 0.dp,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = stringResource(R.string.artist_description_shuffle_content_description),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Favorites toggle — outlined to contrast with the filled Play button.
            // The existing transient boolean is intentionally retained for now.
            OutlinedButton(
                onClick = onFollowClick,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isFollowed) AudiophilePrimary
                    else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = if (isFollowed) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isFollowed) {
                        stringResource(R.string.artist_description_following_label)
                    } else {
                        stringResource(R.string.artist_description_follow_label)
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF090B10, name = "ArtistDescriptionHeroSection — No Image")
@Composable
private fun ArtistDescriptionHeroSectionNoImagePreview() {
    AudiophileMusicPlayerTheme {
        ArtistDescriptionHeroSection(
            artistName = "Miles Davis",
            artistImageUrl = null,
            trackCount = 142,
            albumCount = 18,
            isFollowed = false,
            onPlayClick = {},
            onShuffleClick = {},
            onFollowClick = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF090B10, name = "ArtistDescriptionHeroSection — Followed")
@Composable
private fun ArtistDescriptionHeroSectionFollowedPreview() {
    AudiophileMusicPlayerTheme {
        ArtistDescriptionHeroSection(
            artistName = "Daft Punk",
            artistImageUrl = null,
            trackCount = 67,
            albumCount = 5,
            isFollowed = true,
            onPlayClick = {},
            onShuffleClick = {},
            onFollowClick = {}
        )
    }
}
