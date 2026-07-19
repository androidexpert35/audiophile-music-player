package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.model.track.extractNavigableArtistNames
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.LyricsState
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerUiEvent

/**
 * Self-contained player section that renders the context action chips (Queue, Lyrics, Go To)
 * and owns the queue sheet, lyrics sheet, and destination dialog visibility states locally.
 *
 * Keeping `showQueueSheet`, `showLyricsSheet`, and `showDestinationDialog` inside this
 * composable means opening or closing any overlay does **not** recompose `PlayerContent`
 * or any of its siblings — only this narrow wrapper recomposes.
 *
 * When the user opens the lyrics sheet, [PlayerUiEvent.RequestLyrics] is emitted to the
 * ViewModel. Re-tapping while the sheet is already visible does not re-emit the event —
 * the ViewModel guards against redundant fetches internally.
 *
 * @param track Currently playing track, used to compute navigation destinations.
 * @param queueState Current queue snapshot providing the track list and active index.
 * @param lyricsState Isolated [State] wrapping the current [LyricsState]. Forwarded to
 *   [LyricsSheet] without being read here, so lyrics ticks don't recompose this section.
 * @param positionMs Lambda returning the current playback position in milliseconds.
 *   Forwarded to [LyricsSheet] as a lambda to keep the caller stable.
 * @param onEvent Callback emitting player intents to the ViewModel.
 * @param modifier Optional [Modifier] for the action chip row.
 */
@Composable
internal fun PlayerContextSection(
    track: Track,
    queueState: QueueState,
    lyricsState: State<LyricsState>,
    positionMs: () -> Long,
    onEvent: (PlayerUiEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // Local dialog / sheet states — changes here recompose only this narrow composable.
    var showQueueSheet by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var showDestinationDialog by remember { mutableStateOf(false) }

    // Memoize artist navigation choices once per track — extracting and splitting
    // the artist string is O(N) and should not run on every recomposition.
    val artistChoices = remember(track.artistName) {
        extractNavigableArtistNames(track.artistName)
    }
    val canOpenAlbum = track.albumId != 0L
    val canOpenDestinations = canOpenAlbum || artistChoices.isNotEmpty()

    PlayerContextActionsRow(
        modifier = modifier,
        isQueueEnabled = queueState.tracks.isNotEmpty(),
        isLyricsEnabled = true,
        isDestinationEnabled = canOpenDestinations,
        onQueueClick = { showQueueSheet = true },
        onLyricsClick = {
            showLyricsSheet = true
            // Only dispatch the fetch event when lyrics haven't been loaded yet —
            // if already Success the sheet will render cached data immediately.
            val current = lyricsState.value
            if (current is LyricsState.Idle || current is LyricsState.Error) {
                onEvent(PlayerUiEvent.RequestLyrics)
            }
        },
        onDestinationClick = { showDestinationDialog = true }
    )

    if (showQueueSheet) {
        PlaybackQueueSheet(
            tracks = queueState.tracks,
            currentIndex = queueState.currentIndex,
            onDismiss = { showQueueSheet = false },
            onMove = { fromIndex, toIndex ->
                onEvent(PlayerUiEvent.MoveQueueItem(fromIndex, toIndex))
            },
            onTrackSelected = { selectedTrack ->
                showQueueSheet = false
                onEvent(PlayerUiEvent.Play(selectedTrack, queueState.tracks))
            }
        )
    }

    if (showLyricsSheet) {
        LyricsSheet(
            lyricsState = lyricsState,
            positionMs = positionMs,
            trackTitle = track.title,
            artistName = track.artistName,
            albumTitle = track.albumTitle,
            albumId = track.albumId,
            onDismiss = { showLyricsSheet = false },
            onEvent = onEvent,
            localArtUri = track.artUri,
        )
    }

    if (showDestinationDialog) {
        TrackDestinationDialog(
            trackTitle = track.title,
            albumTitle = track.albumTitle.takeIf { it.isNotBlank() },
            artistNames = artistChoices,
            onDismiss = { showDestinationDialog = false },
            onOpenAlbum = track.albumId
                .takeIf { it != 0L }
                ?.let { albumId ->
                    {
                        showDestinationDialog = false
                        onEvent(PlayerUiEvent.NavigateToAlbum(albumId))
                    }
                },
            onOpenArtist = { artistName ->
                showDestinationDialog = false
                onEvent(PlayerUiEvent.NavigateToArtist(artistName))
            }
        )
    }
}
