package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.rememberTrackReorderState
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.toStableTrackEntryKeys
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.formatTrackDuration
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import java.util.Locale

/**
 * Expressive Material Design 3 modal bottom sheet displaying the active playback queue.
 *
 * The sheet auto-scrolls to the currently active track on opening, groups tracks
 * under "Now Playing" and "Up Next" section headers, and presents each row with
 * a position badge, track metadata, and duration. Tracks that have already played
 * are rendered at reduced opacity to guide the listener's eye forward.
 *
 * @param tracks Ordered list of queue tracks.
 * @param currentIndex Zero-based index of the currently active track.
 * @param onDismiss Callback invoked when the sheet is dismissed.
 * @param onMove Callback receiving a queue item move while edit mode is active.
 * @param onTrackSelected Callback invoked when the user taps a queue row to jump to that track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackQueueSheet(
    tracks: List<Track>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onTrackSelected: (Track) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val reorderState = rememberTrackReorderState(listState)
    var isEditing by remember { mutableStateOf(false) }
    val entryKeys = remember(tracks) { tracks.toStableTrackEntryKeys() }

    // Scroll to the current track when the sheet first appears so the user
    // immediately sees where they are in the queue without manual scrolling.
    LaunchedEffect(currentIndex, isEditing) {
        if (!isEditing && tracks.isNotEmpty() && currentIndex in tracks.indices) {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = -80
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = null
    ) {
        // ── Sheet header ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Icon badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_queue_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = queueCountLabel(tracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { isEditing = !isEditing }) {
                Icon(
                    imageVector = if (isEditing) Icons.Filled.Check else Icons.Filled.Edit,
                    contentDescription = stringResource(
                        if (isEditing) R.string.player_queue_finish_edit_content_description
                        else R.string.player_queue_edit_content_description
                    ),
                    tint = if (isEditing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // ── Queue list or empty state ─────────────────────────────────────────
        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(R.string.player_queue_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.player_queue_empty_supporting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(
                    items = tracks,
                    key = { index, _ -> entryKeys[index] }
                ) { index, track ->
                    val isCurrent = index == currentIndex
                    val isPlayed = index < currentIndex
                    val entryKey = entryKeys[index]
                    val isDragged = reorderState.isDragged(entryKey)

                    // Section labels appear directly above key positions.
                    if (!isEditing && isCurrent) {
                        QueueSectionLabel(
                            label = stringResource(R.string.player_queue_section_now_playing),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (!isEditing && index == currentIndex + 1) {
                        QueueSectionLabel(
                            label = stringResource(R.string.player_queue_section_coming_up),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    QueueTrackRow(
                        track = track,
                        position = index + 1,
                        isCurrent = isCurrent,
                        isPlayed = isPlayed,
                        isEditing = isEditing,
                        isDragged = isDragged,
                        dragTranslationY = reorderState.translationFor(entryKey),
                        dragHandleModifier = if (isEditing) {
                            reorderState.dragHandleModifier(
                                entryKey = entryKey,
                                firstTrackListIndex = 0,
                                lastTrackListIndex = tracks.lastIndex,
                                onMove = onMove
                            )
                        } else {
                            Modifier
                        },
                        onClick = { onTrackSelected(track) },
                        modifier = if (isDragged) Modifier else Modifier.animateItem(
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    )
                }
            }
        }
    }
}

// ── Section label ─────────────────────────────────────────────────────────────

@Composable
private fun QueueSectionLabel(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = label.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
    )
}

// ── Queue track row ────────────────────────────────────────────────────────────

/**
 * Single row in the queue list.
 *
 * The current track receives a primary-coloured container and an animated
 * equalizer icon in place of the position badge. Tracks that have already
 * played are rendered at reduced opacity so the queue reads forward naturally.
 *
 * @param track Track whose metadata is displayed.
 * @param position One-based position in the full queue (shown as a badge).
 * @param isCurrent Whether this row represents the actively playing track.
 * @param isPlayed Whether this track has already been played in the current session.
 * @param isEditing Whether the leading drag handle is active.
 * @param isDragged Whether this row is floating under the listener's finger.
 * @param dragTranslationY Current floating-row vertical translation in pixels.
 * @param dragHandleModifier Gesture modifier attached only to the leading handle.
 * @param onClick Callback for jumping to this track.
 * @param modifier Optional modifier for lazy-list placement animation.
 */
@Composable
private fun QueueTrackRow(
    track: Track,
    position: Int,
    isCurrent: Boolean,
    isPlayed: Boolean,
    isEditing: Boolean,
    isDragged: Boolean,
    dragTranslationY: Float,
    dragHandleModifier: Modifier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val subtitleColor = when {
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Already-played tracks are de-emphasised so the eye tracks forward
    val rowAlpha = if (!isEditing && isPlayed) 0.45f else 1f
    val liftScale by animateFloatAsState(
        targetValue = if (isDragged) 1.025f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "QueueDragLiftScale"
    )
    val liftElevation by animateFloatAsState(
        targetValue = if (isDragged) 18f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "QueueDragLiftElevation"
    )
    val floatingShape = MaterialTheme.shapes.extraLarge

    Surface(
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
            .then(if (isEditing) Modifier else Modifier.clickable(onClick = onClick))
            .fillMaxWidth()
            .alpha(rowAlpha),
        shape = floatingShape,
        color = containerColor,
        tonalElevation = if (isCurrent) 0.dp else 1.dp,
        shadowElevation = if (isCurrent) 6.dp else 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Position badge / now-playing indicator ────────────────────────
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isEditing) {
                    Icon(
                        imageVector = Icons.Filled.DragIndicator,
                        contentDescription = stringResource(R.string.player_queue_reorder_handle_content_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = dragHandleModifier.size(24.dp)
                    )
                } else if (isCurrent) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = stringResource(R.string.player_queue_now_playing_content_description),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = position.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Title and subtitle ────────────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOf(track.artistName, track.albumTitle)
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .distinct()
                        .joinToString(separator = " • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Duration ──────────────────────────────────────────────────────
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = formatTrackDuration(track.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = subtitleColor
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun queueCountLabel(count: Int): String = if (count == 0) {
    stringResource(R.string.player_queue_empty_title)
} else {
    pluralStringResource(R.plurals.player_queue_song_count, count, count)
}

// ── Preview ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFF101114)
@Composable
private fun PlaybackQueueSheetPreview() {
    val durations = listOf(
        565_000L, 342_000L, 278_000L, 412_000L,
        396_000L, 487_000L, 521_000L, 303_000L
    )
    val sampleTracks = List(8) { i ->
        Track(
            id = i.toLong(),
            title = listOf(
                "So What", "Freddie Freeloader", "Blue in Green",
                "All Blues", "Flamenco Sketches", "Miles Runs the Voodoo Down",
                "Bitches Brew", "Spanish Key"
            )[i],
            artistName = "Miles Davis",
            albumTitle = "Kind of Blue",
            albumId = 7L,
            durationMs = durations[i],
            uri = "content://tracks/$i",
            trackNumber = i + 1,
            discNumber = 1,
            audioFormat = AudioFormat.UNKNOWN,
            fileSizeBytes = 40_000_000L,
            dateAdded = 0L
        )
    }
    AudiophileMusicPlayerTheme {
        PlaybackQueueSheet(
            tracks = sampleTracks,
            currentIndex = 2,
            onDismiss = {},
            onMove = { _, _ -> },
            onTrackSelected = {}
        )
    }
}
