package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Artist list row rendered inside the library catalogue.
 *
 * @param artist Artist rendered in the row.
 * @param onClick Callback opening the selected artist destination.
 * @param onImageRequest Callback requesting best-effort remote enrichment when
 *   this visible row has no cached artist image.
 * @param modifier Optional [Modifier] for the root surface.
 */
@Composable
internal fun LibraryArtistRow(
    artist: Artist,
    onClick: () -> Unit,
    onImageRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageRequest = remember(artist.imageUrl) {
        ImageRequest.Builder(context)
            .data(artist.imageUrl)
            .crossfade(200)
            .build()
    }

    // A list row becoming visible is a UI intent; the ViewModel owns lookup,
    // matching, persistence, and state updates.
    LaunchedEffect(artist.id, artist.imageUrl) {
        if (artist.imageUrl.isNullOrBlank()) onImageRequest?.invoke()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        val albumCountLabel = pluralStringResource(R.plurals.library_album_count, artist.albumCount, artist.albumCount)
        val trackCountLabel = pluralStringResource(R.plurals.library_track_count, artist.trackCount, artist.trackCount)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {}
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                if (!artist.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Memoize the secondary label — listOf + joinToString allocate every recomposition.
            val subtitleText = remember(artist.id, artist.albumCount, artist.trackCount) {
                listOf(
                    albumCountLabel,
                    trackCountLabel
                ).joinToString(separator = " • ")
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101114)
@Composable
private fun LibraryArtistRowPreview() {
    AudiophileMusicPlayerTheme {
        LibraryArtistRow(
            artist = Artist(
                id = 22L,
                name = "Miles Davis",
                albumCount = 18,
                trackCount = 142
            ),
            onClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
