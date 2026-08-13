package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist

/**
 * Three-column artist card for the library grid, with all labels below square artwork.
 *
 * @param artist Artist rendered in this grid cell.
 * @param onClick Invoked when the artist is selected.
 * @param onImageRequest Requests best-effort enrichment when no cached artist image exists.
 * @param modifier Optional modifier for the root card.
 */
@Composable
internal fun LibraryArtistGridItem(
    artist: Artist,
    onClick: () -> Unit,
    onImageRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val collectionSummary = listOf(
        pluralStringResource(R.plurals.library_album_count, artist.albumCount, artist.albumCount),
        pluralStringResource(R.plurals.library_track_count, artist.trackCount, artist.trackCount)
    ).joinToString(separator = " · ")

    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ArtistArtwork(
            artist = artist,
            shape = MaterialTheme.shapes.medium,
            onImageRequest = onImageRequest,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = collectionSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
