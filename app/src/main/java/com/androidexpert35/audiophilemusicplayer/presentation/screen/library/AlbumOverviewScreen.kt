package com.androidexpert35.audiophilemusicplayer.presentation.screen.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.PlaylistPickerDialog
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.AlbumDetailActionRow
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.AlbumDetailDiscHeader
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.AlbumDetailHeroHeader
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.AlbumDetailTopBar
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.AlbumDetailTrackItem
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.LibraryEmptyState
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileNight
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.AlbumOverviewUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.AlbumOverviewUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.AlbumOverviewUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.AlbumOverviewViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Album detail screen entry point.
 *
 * Presents an immersive album page — a large square album artwork hero, the album
 * title with artist info and audio characteristics (codec, bit depth, sample rate)
 * below the art, a primary action bar (play, shuffle, like, album overflow), and an ordered
 * tracklist with now-playing highlighting. A transparent floating top bar overlays the content with a back
 * arrow; the album title fades into the bar as the user scrolls past the hero.
 *
 * The persistent mini-player and bottom navigation bar are provided by the app shell
 * in the parent composition; this screen only manages content inside the content area.
 *
 * @param albumId Stable album identifier passed from the navigation back-stack entry.
 * @param viewModel Hilt-provided album overview ViewModel instance.
 */
@Composable
fun AlbumOverviewScreen(
    albumId: Long,
    viewModel: AlbumOverviewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Trigger the initial data load whenever the album ID changes.
    LaunchedEffect(albumId) {
        viewModel.onEvent(AlbumOverviewUiEvent.Initialize(albumId))
    }

    // Collect one-shot queue, playlist, and playback feedback.
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is AlbumOverviewUiEffect.PlaybackError ->
                    snackbarHostState.showSnackbar(effect.message)

                is AlbumOverviewUiEffect.QueueUpdated ->
                    snackbarHostState.showSnackbar(effect.message)

                is AlbumOverviewUiEffect.TrackAddedToPlaylist -> {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.playlist_track_added_success,
                            effect.trackTitle,
                            effect.playlistName
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is AlbumOverviewUiEffect.AlbumAddedToPlaylist -> {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.playlist_album_added_success,
                            effect.albumTitle,
                            effect.playlistName
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // The transparent container lets the dark app background fill the status bar so
    // the floating back-button overlay blends seamlessly with the artwork below.
    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.OVERLAY,
        statusBarColor = Color.Transparent,
        containerColor = Color.Transparent,
        content = { model ->
            AlbumOverviewContent(
                model = model,
                snackbarHostState = snackbarHostState,
                onEvent = viewModel::onEvent
            )
        }
    )
}

/**
 * Stateless content composable for the album detail screen.
 *
 * Renders a [LazyColumn] whose items cover the full hero section, the action row,
 * optional disc headers, and the ordered track list. [AlbumDetailTopBar] floats
 * on top as a separate Box layer; its alpha is driven by a draw-phase-only lambda so
 * it never recomposes on scroll. Per-item `isCurrentlyPlaying` state is scoped with
 * [derivedStateOf] so only the two rows that swap playing state recompose on track change.
 *
 * @param model Current immutable album detail state.
 * @param snackbarHostState Host for transient playback error messages.
 * @param onEvent Callback emitting user intents to the ViewModel.
 */
@Composable
private fun AlbumOverviewContent(
    model: AlbumOverviewUiModel,
    snackbarHostState: SnackbarHostState,
    onEvent: (AlbumOverviewUiEvent) -> Unit
) {
    val shellBottomPadding = LocalShellBottomPadding.current
    val listState = rememberLazyListState()

    // Alpha ramps 0 → 1 across the first 350 px of scroll within the hero item,
    // then locks at 1 once any subsequent item reaches the top of the viewport.
    // derivedStateOf avoids redundant State object changes when the computed
    // Float has not actually changed between frames.
    val topBarAlphaState = remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex >= 1 -> 1f
                listState.firstVisibleItemScrollOffset > 0 ->
                    ((listState.firstVisibleItemScrollOffset - 100f) / 350f).coerceIn(0f, 1f)
                else -> 0f
            }
        }
    }

    // Stable lambda — created once, reads topBarAlphaState.value at call-site.
    // AlbumDetailTopBar passes this lambda into drawBehind / graphicsLayer blocks,
    // so those draw layers redraw on scroll without recomposing the top bar composable.
    val topBarAlphaProvider: () -> Float = remember { { topBarAlphaState.value } }

    // rememberUpdatedState keeps the same State<Long?> reference across recompositions,
    // so the stable playingTrackIdProvider lambda never changes identity. This lets
    // each item's derivedStateOf scope its isCurrentlyPlaying recomposition independently:
    // only the two rows that actually swap playing state recompose on track change.
    val currentPlayingTrackIdState = rememberUpdatedState(model.currentPlayingTrackId)
    val playingTrackIdProvider: () -> Long? = remember { { currentPlayingTrackIdState.value } }

    // Multi-disc grouping is expensive — memoize against tracklist identity so the
    // CPU only regroups when track data changes, not on scroll or playing-track changes.
    val tracksByDisc = remember(model.tracks) {
        model.tracks
            .groupBy { track -> track.discNumber.coerceAtLeast(1) }
            .entries
            .sortedBy { it.key }
    }

    // Subtle dark gradient keeping the back button legible over bright artwork.
    val topScrimBrush = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to AudiophileNight.copy(alpha = 0.85f),
                1.0f to Color.Transparent
            ),
            startY = 0f,
            endY = 180f
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
    ) {
        // ── Layer 1: Main scrollable content ───────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = shellBottomPadding + 24.dp)
        ) {
            // Hero — album artwork + title + artist info row
            item(key = "hero") {
                model.album?.let { album ->
                    AlbumDetailHeroHeader(
                        album = album,
                        totalDurationMs = model.totalDurationMs,
                        artistImageUrl = model.artistImageUrl,
                        onArtistClick = { onEvent(AlbumOverviewUiEvent.OpenArtist(album.artistName)) },
                        maxSampleRateHz = model.maxSampleRateHz,
                        maxBitDepth = model.maxBitDepth,
                        codecSummary = model.codecSummary,
                        isLossless = model.losslessTrackCount > 0,
                    )
                }
            }

            // Action row — like + album overflow (left), shuffle + play (right).
            item(key = "actions") {
                AlbumDetailActionRow(
                    isLiked = model.isLiked,
                    onPlayClick = { onEvent(AlbumOverviewUiEvent.PlayAlbum) },
                    onShuffleClick = { onEvent(AlbumOverviewUiEvent.ShuffleAlbum) },
                    onLikeClick = { onEvent(AlbumOverviewUiEvent.ToggleLike) },
                    onPlayNextClick = { onEvent(AlbumOverviewUiEvent.PlayAlbumNext) },
                    onAddToQueueClick = { onEvent(AlbumOverviewUiEvent.AddAlbumToQueue) },
                    onAddToPlaylistClick = {
                        onEvent(AlbumOverviewUiEvent.ShowAlbumPlaylistPicker)
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // ── Tracklist ──────────────────────────────────────────────────────
            if (model.tracks.isEmpty()) {
                item(key = "empty") {
                    LibraryEmptyState(
                        icon = Icons.Filled.Album,
                            title = stringResource(R.string.album_overview_empty_tracks_title),
                            message = stringResource(R.string.album_overview_empty_tracks_message),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp)
                    )
                }
            } else if (model.discCount > 1) {
                // Multi-disc: insert a labelled section header before each disc group.
                tracksByDisc.forEach { (disc, discTracks) ->
                    item(key = "disc_$disc") {
                        AlbumDetailDiscHeader(discNumber = disc)
                    }
                    itemsIndexed(
                        items = discTracks,
                        key = { _, track -> track.id }
                    ) { index, track ->
                        val isPlaying by remember(track.id) {
                            derivedStateOf { playingTrackIdProvider() == track.id }
                        }
                        AlbumDetailTrackItem(
                            track = track,
                            trackNumber = if (track.trackNumber > 0) track.trackNumber else index + 1,
                            isCurrentlyPlaying = isPlaying,
                            onClick = { onEvent(AlbumOverviewUiEvent.PlayTrack(track)) },
                            onPlayNextClick = { onEvent(AlbumOverviewUiEvent.PlayNext(track)) },
                            onAddToQueueClick = { onEvent(AlbumOverviewUiEvent.AddToQueue(track)) },
                            onAddToPlaylistClick = { onEvent(AlbumOverviewUiEvent.ShowTrackOptions(track)) }
                        )
                    }
                }
            } else {
                // Single-disc or un-tagged album: flat tracklist.
                itemsIndexed(
                    items = model.tracks,
                    key = { _, track -> track.id }
                ) { index, track ->
                    val isPlaying by remember(track.id) {
                        derivedStateOf { playingTrackIdProvider() == track.id }
                    }
                    AlbumDetailTrackItem(
                        track = track,
                        trackNumber = if (track.trackNumber > 0) track.trackNumber else index + 1,
                        isCurrentlyPlaying = isPlaying,
                        onClick = { onEvent(AlbumOverviewUiEvent.PlayTrack(track)) },
                        onPlayNextClick = { onEvent(AlbumOverviewUiEvent.PlayNext(track)) },
                        onAddToQueueClick = { onEvent(AlbumOverviewUiEvent.AddToQueue(track)) },
                        onAddToPlaylistClick = { onEvent(AlbumOverviewUiEvent.ShowTrackOptions(track)) },
                        onGoToArtistClick = { onEvent(AlbumOverviewUiEvent.OpenArtist(track.artistName)) }
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // ── Layer 2: Top gradient scrim ────────────────────────────────────────
        // Permanently drawn to keep the back button legible over bright artwork.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.TopStart)
                .background(topScrimBrush)
        )

        // ── Layer 3: Floating top bar ──────────────────────────────────────────
        // topBarAlphaProvider is read only inside draw-phase lambdas inside
        // AlbumDetailTopBar, so this composable never recomposes on scroll.
        AlbumDetailTopBar(
            albumTitle = model.album?.title.orEmpty(),
            topBarAlphaProvider = topBarAlphaProvider,
            onNavigateBack = { onEvent(AlbumOverviewUiEvent.NavigateBack) },
            modifier = Modifier.align(Alignment.TopStart)
        )

        if (model.playlistPickerTracks.isNotEmpty()) {
            PlaylistPickerDialog(
                playlists = model.playlists,
                onDismiss = { onEvent(AlbumOverviewUiEvent.DismissPlaylistPicker) },
                onPlaylistSelected = { playlistId ->
                    onEvent(AlbumOverviewUiEvent.AddTrackToPlaylist(playlistId))
                }
            )
        }

        // ── Layer 4: Snackbar ─────────────────────────────────────────────────
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = shellBottomPadding + 8.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "AlbumOverviewScreen — Content", showBackground = true, backgroundColor = 0xFF090B10)
@Composable
private fun AlbumOverviewContentPreview() {
    AudiophileMusicPlayerTheme {
        AlbumOverviewContent(
            model = previewAlbumOverviewUiModel(),
            snackbarHostState = remember { SnackbarHostState() },
            onEvent = {}
        )
    }
}

@Preview(name = "AlbumOverviewScreen — Liked", showBackground = true, backgroundColor = 0xFF090B10)
@Composable
private fun AlbumOverviewContentLikedPreview() {
    AudiophileMusicPlayerTheme {
        AlbumOverviewContent(
            model = previewAlbumOverviewUiModel().copy(isLiked = true, currentPlayingTrackId = 2L),
            snackbarHostState = remember { SnackbarHostState() },
            onEvent = {}
        )
    }
}

// ---------------------------------------------------------------------------
// Preview helpers
// ---------------------------------------------------------------------------

/**
 * Constructs a realistic sample model for the album overview screen previews.
 *
 * @return Preview-only [AlbumOverviewUiModel] populated with Kind of Blue data.
 */
private fun previewAlbumOverviewUiModel(): AlbumOverviewUiModel {
    val album = Album(
        id = 1L,
        title = "Kind of Blue",
        artistName = "Miles Davis",
        artUri = null,
        trackCount = 5,
        year = 1959
    )
    val tracks = listOf(
        previewTrack(1L, "So What", 1, 545_000L),
        previewTrack(2L, "Freddie Freeloader", 2, 578_000L),
        previewTrack(3L, "Blue in Green", 3, 327_000L),
        previewTrack(4L, "All Blues", 4, 692_000L),
        previewTrack(5L, "Flamenco Sketches", 5, 566_000L)
    )
    return AlbumOverviewUiModel(
        album = album,
        tracks = tracks,
        currentPlayingTrackId = null,
        isLiked = false,
        totalDurationMs = tracks.sumOf { it.durationMs },
        totalSizeBytes = 220_000_000L,
        discCount = 1,
        losslessTrackCount = 5,
        hiResTrackCount = 5,
        maxSampleRateHz = 96_000,
        maxBitDepth = 24,
        codecSummary = "FLAC"
    )
}

private fun previewTrack(id: Long, title: String, trackNum: Int, durationMs: Long): Track =
    Track(
        id = id,
        title = title,
        artistName = "Miles Davis",
        albumTitle = "Kind of Blue",
        albumId = 1L,
        durationMs = durationMs,
        uri = "content://tracks/$id",
        trackNumber = trackNum,
        discNumber = 1,
        audioFormat = AudioFormat(
            sampleRateHz = 96_000,
            bitDepth = 24,
            channelCount = 2,
            codec = AudioCodec.FLAC,
            isLossless = true
        ),
        fileSizeBytes = 44_000_000L,
        dateAdded = 1_700_000_000L - id * 10_000L
    )
