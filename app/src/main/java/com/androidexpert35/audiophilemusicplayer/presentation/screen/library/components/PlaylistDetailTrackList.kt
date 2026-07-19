package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.TrackOptionsMenu
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.TrackReorderState
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.toStableTrackEntryKeys
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary

/**
 * Adds animated playlist track rows to the parent screen's sole [LazyListScope].
 *
 * @param tracks Playlist tracks in current display order.
 * @param reorderState Gesture state shared with the parent scrolling list.
 * @param firstTrackListIndex Absolute lazy-list index of the first track row.
 * @param isEditing Whether the leading drag handles are enabled.
 * @param currentPlayingTrackId Active track identifier for row highlighting.
 * @param onTrackClick Starts an individual playlist track.
 * @param onMove Receives a valid track-relative index move during a drag.
 * @param onRemove Removes one entry from the pending playlist edit.
 * @param onGoToAlbum Navigates to the selected track's album.
 * @param onGoToArtist Navigates to the selected track's artist.
 */
internal fun LazyListScope.playlistDetailTrackItems(
    tracks: List<Track>,
    reorderState: TrackReorderState,
    firstTrackListIndex: Int,
    isEditing: Boolean,
    currentPlayingTrackId: Long?,
    onTrackClick: (Track) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onGoToAlbum: (Track) -> Unit,
    onGoToArtist: (Track) -> Unit
) {
    val entryKeys = tracks.toStableTrackEntryKeys()
    val lastTrackListIndex = firstTrackListIndex + tracks.lastIndex
    itemsIndexed(
        items = tracks,
        key = { index, _ -> entryKeys[index] }
    ) { trackIndex, track ->
        val entryKey = entryKeys[trackIndex]
        val isDragged = reorderState.isDragged(entryKey)
        PlaylistDetailTrackItem(
            track = track,
            index = trackIndex,
            isCurrentlyPlaying = currentPlayingTrackId == track.id,
            isEditing = isEditing,
            isDragged = isDragged,
            dragTranslationY = reorderState.translationFor(entryKey),
            onClick = { onTrackClick(track) },
            onRemoveClick = { onRemove(trackIndex) },
            onPlayNext = { onPlayNext(track) },
            onAddToQueue = { onAddToQueue(track) },
            onGoToAlbum = { onGoToAlbum(track) },
            onGoToArtist = { onGoToArtist(track) },
            dragHandleModifier = if (isEditing) {
                reorderState.dragHandleModifier(
                    entryKey = entryKey,
                    firstTrackListIndex = firstTrackListIndex,
                    lastTrackListIndex = lastTrackListIndex,
                    onMove = onMove
                )
            } else {
                Modifier
            },
            // The dragged row follows the finger directly; its siblings animate their
            // placement into the open gap using the parent LazyColumn's animation API.
            modifier = if (isDragged) Modifier else Modifier.animateItem(
                placementSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        )
    }
}

/**
 * Renders one playlist song row, including the floating visual treatment during a drag.
 */
@Composable
private fun PlaylistDetailTrackItem(
    track: Track,
    index: Int,
    isCurrentlyPlaying: Boolean,
    isEditing: Boolean,
    isDragged: Boolean,
    dragTranslationY: Float,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onGoToAlbum: () -> Unit,
    onGoToArtist: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier
) {
    val titleColor = if (isCurrentlyPlaying) AudiophilePrimary else MaterialTheme.colorScheme.onSurface
    val liftScale by animateFloatAsState(
        targetValue = if (isDragged) 1.025f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "PlaylistDragLiftScale"
    )
    val liftElevation by animateFloatAsState(
        targetValue = if (isDragged) 18f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "PlaylistDragLiftElevation"
    )
    val floatingShape = MaterialTheme.shapes.medium

    Column(
        modifier = modifier
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                translationY = dragTranslationY
                scaleX = liftScale
                scaleY = liftScale
                shadowElevation = liftElevation
                shape = floatingShape
                clip = isDragged
            }
            .then(
                if (isDragged) Modifier.clip(floatingShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                else Modifier
            )
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isEditing) Modifier else Modifier.clickable(onClick = onClick))
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (isEditing) {
                    Icon(
                        imageVector = Icons.Filled.DragIndicator,
                        contentDescription = stringResource(R.string.playlist_reorder_handle_content_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = dragHandleModifier.size(24.dp)
                    )
                } else if (isCurrentlyPlaying) {
                    Icon(Icons.Filled.Equalizer, null, tint = AudiophilePrimary, modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artistName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(formatTrackDuration(track.durationMs), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isEditing) {
                androidx.compose.material3.IconButton(onClick = onRemoveClick) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.playlist_remove_track_content_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                TrackOptionsMenu(
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onAddToPlaylist = null,
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp, end = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    }
}
