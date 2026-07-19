package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.LyricLine
import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.Lyrics
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileBlack
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.LyricsState
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerUiEvent

/**
 * Full-height [ModalBottomSheet] rendering synchronized or plain-text lyrics for
 * the currently playing track.
 *
 * This composable is a **thin orchestrator**: it owns the sheet shell (blurred
 * background, scrim, drag handle, header) and dispatches the body slot to a
 * focused sub-composable selected from [lyricsState]:
 * - [LyricsState.Loading] → centred progress indicator
 * - [LyricsState.Success] with synced lines → [SyncedLyricsContent]
 * - [LyricsState.Success] with plain text only → [PlainLyricsContent]
 * - [LyricsState.NotFound] / [LyricsState.Instrumental] → [LyricsEmptyMessage]
 * - [LyricsState.Error] → [LyricsErrorContent]
 *
 * @param lyricsState Live [State] wrapping the current [LyricsState]. Read inside
 *   this composable so only this sheet recomposes on state transitions.
 * @param positionMs Lambda returning the current playback position in
 *   milliseconds. Captured as a lambda to avoid recomposing the caller on ticks.
 * @param trackTitle Title of the currently playing track.
 * @param artistName Artist name for the header subtitle.
 * @param albumTitle Album title for the header subtitle.
 * @param albumId MediaStore album identifier used by the blurred background and
 *   the header thumbnail. Pass `0L` to show the dark fallback background.
 * @param onDismiss Callback that closes this sheet.
 * @param onEvent Callback emitting user intents to the ViewModel (used for retry).
 * @param localArtUri Optional pre-computed art URI passed through to the header
 *   thumbnail (covers DSD `file://` art).
 * @param remoteArtUrl Optional remote cover URL used as a network fallback for
 *   the header thumbnail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSheet(
    lyricsState: State<LyricsState>,
    positionMs: () -> Long,
    trackTitle: String,
    artistName: String,
    albumTitle: String,
    albumId: Long,
    onDismiss: () -> Unit,
    onEvent: (PlayerUiEvent) -> Unit,
    localArtUri: String? = null,
    remoteArtUrl: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.50f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
    ) {
        LyricsSheetContent(
            lyricsState = lyricsState,
            positionMs = positionMs,
            trackTitle = trackTitle,
            artistName = artistName,
            albumTitle = albumTitle,
            albumId = albumId,
            localArtUri = localArtUri,
            remoteArtUrl = remoteArtUrl,
            onEvent = onEvent,
        )
    }
}

@Composable
private fun LyricsSheetContent(
    lyricsState: State<LyricsState>,
    positionMs: () -> Long,
    trackTitle: String,
    artistName: String,
    albumTitle: String,
    albumId: Long,
    localArtUri: String?,
    remoteArtUrl: String?,
    onEvent: (PlayerUiEvent) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            // Solid fallback while BlurredBackground renders its first frame.
            .background(AudiophileBlack),
    ) {
        if (albumId != 0L) {
            BlurredBackground(albumId = albumId)
        }

        // Extra dark overlay to keep white lyric text legible at all brightness levels.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f)),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            TopEdgeHighlight()
            DragHandle()

            LyricsSheetHeader(
                trackTitle = trackTitle,
                artistName = artistName,
                albumTitle = albumTitle,
                albumId = albumId,
                localArtUri = localArtUri,
                remoteArtUrl = remoteArtUrl,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // weight(1f) gives the body composables a bounded height so the
            // SyncedLyricsContent centering math has no layout feedback loop.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                LyricsBody(
                    lyricsState = lyricsState,
                    positionMs = positionMs,
                    onEvent = onEvent,
                )
            }
        }
    }
}

/**
 * Switches between the lyrics body composables based on the current [LyricsState].
 */
@Composable
private fun LyricsBody(
    lyricsState: State<LyricsState>,
    positionMs: () -> Long,
    onEvent: (PlayerUiEvent) -> Unit,
) {
    when (val state = lyricsState.value) {
        is LyricsState.Idle -> Unit

        is LyricsState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }

        is LyricsState.Success -> {
            if (state.lyrics.lines.isNotEmpty()) {
                SyncedLyricsContent(
                    lyrics = state.lyrics,
                    positionMs = positionMs,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlainLyricsContent(plainLyrics = state.lyrics.plainLyrics ?: "")
            }
        }

        is LyricsState.NotFound -> LyricsEmptyMessage(
            message = stringResource(R.string.lyrics_not_found),
        )

        is LyricsState.Instrumental -> LyricsEmptyMessage(
            message = stringResource(R.string.lyrics_instrumental),
        )

        is LyricsState.Error -> LyricsErrorContent(
            message = state.message,
            onRetry = { onEvent(PlayerUiEvent.RequestLyrics) },
        )
    }
}

/**
 * Thin glass-highlight line rendered along the top edge of the sheet —
 * reinforces the material boundary between the player screen and the panel.
 */
@Composable
private fun TopEdgeHighlight() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        AudiophileGlassHighlight.copy(alpha = 0.55f),
                        AudiophileGlassHighlight.copy(alpha = 0.85f),
                        AudiophileGlassHighlight.copy(alpha = 0.55f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/** Centred drag-handle pill matching the player's glass-highlight tint. */
@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 4.dp)
                .background(
                    color = AudiophileGlassHighlight,
                    shape = RoundedCornerShape(50),
                ),
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Lyrics Sheet – Synced", showBackground = true, backgroundColor = 0xFF0D0F14)
@Composable
private fun LyricsSheetSyncedPreview() {
    AudiophileMusicPlayerTheme {
        val lines = listOf(
            LyricLine(0L, ""),
            LyricLine(12_000L, "In the cool of the night"),
            LyricLine(15_500L, "We shall not be moved"),
            LyricLine(19_000L, "Standing on the edge of time"),
            LyricLine(23_000L, "Watching the tide roll in"),
            LyricLine(27_000L, "Nothing to hold but the wind"),
        )
        val state = remember {
            mutableStateOf<LyricsState>(
                LyricsState.Success(
                    Lyrics(lines = lines, plainLyrics = null, isInstrumental = false),
                ),
            )
        }
        LyricsSheet(
            lyricsState = state,
            positionMs = { 15_500L },
            trackTitle = "We Shall Not Be Moved",
            artistName = "Miles Davis",
            albumTitle = "Kind of Blue",
            albumId = 0L,
            onDismiss = {},
            onEvent = {},
        )
    }
}

@Preview(name = "Lyrics Sheet – Loading", showBackground = true, backgroundColor = 0xFF0D0F14)
@Composable
private fun LyricsSheetLoadingPreview() {
    AudiophileMusicPlayerTheme {
        val state = remember { mutableStateOf<LyricsState>(LyricsState.Loading) }
        LyricsSheet(
            lyricsState = state,
            positionMs = { 0L },
            trackTitle = "Blue in Green",
            artistName = "Miles Davis",
            albumTitle = "Kind of Blue",
            albumId = 0L,
            onDismiss = {},
            onEvent = {},
        )
    }
}

@Preview(name = "Lyrics Sheet – Not Found", showBackground = true, backgroundColor = 0xFF0D0F14)
@Composable
private fun LyricsSheetNotFoundPreview() {
    AudiophileMusicPlayerTheme {
        val state = remember { mutableStateOf<LyricsState>(LyricsState.NotFound) }
        LyricsSheet(
            lyricsState = state,
            positionMs = { 0L },
            trackTitle = "All Blues",
            artistName = "Miles Davis",
            albumTitle = "Kind of Blue",
            albumId = 0L,
            onDismiss = {},
            onEvent = {},
        )
    }
}

@Preview(name = "Lyrics Sheet – Error", showBackground = true, backgroundColor = 0xFF0D0F14)
@Composable
private fun LyricsSheetErrorPreview() {
    AudiophileMusicPlayerTheme {
        val state = remember {
            mutableStateOf<LyricsState>(
                LyricsState.Error("Could not load lyrics. Check your connection."),
            )
        }
        LyricsSheet(
            lyricsState = state,
            positionMs = { 0L },
            trackTitle = "Flamenco Sketches",
            artistName = "Miles Davis",
            albumTitle = "Kind of Blue",
            albumId = 0L,
            onDismiss = {},
            onEvent = {},
        )
    }
}
