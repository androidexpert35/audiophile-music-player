package com.androidexpert35.audiophilemusicplayer.presentation.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.ArtistDescriptionAlbumsSection
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.ArtistDescriptionAppearsOnSection
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.ArtistDescriptionHeroSection
import com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components.ArtistDescriptionPopularTracksSection
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingMedium
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.ArtistDescriptionUiEffect
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.ArtistDescriptionUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.ArtistDescriptionUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.ArtistDescriptionViewModel
import com.tony.coreui.presentation.components.basescreen.AppBaseScreen
import com.tony.coreui.presentation.components.basescreen.BaseLoadingType

/**
 * Artist description screen entry point.
 *
 * Presents a Spotify-style artist profile with an immersive full-width hero
 * photograph, a "Popular Songs" horizontal track row, a 2-column album grid, and
 * an "Appears On" horizontal row of featured-appearance albums sourced entirely
 * from the device's local MediaStore index.
 *
 * The hero extends behind the transparent Android status bar. White system icons
 * remain legible over the hero's dedicated dark top scrim, while a semi-transparent
 * back button provides navigation without an opaque top bar.
 *
 * @param artistName Artist name passed from the navigation back-stack entry.
 * @param viewModel Hilt-provided artist description ViewModel instance.
 */
@Composable
fun ArtistDescriptionScreen(
    artistName: String,
    viewModel: ArtistDescriptionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Trigger the initial data load whenever the artist name changes.
    LaunchedEffect(artistName) {
        viewModel.onEvent(ArtistDescriptionUiEvent.Initialize(artistName))
    }

    // Collect one-shot playback errors and surface them via the snackbar.
    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ArtistDescriptionUiEffect.PlaybackError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    // Spotify-style edge-to-edge presentation: the transparent status bar overlays
    // a darkened strip of the hero and explicitly uses white system icons.
    AppBaseScreen(
        uiState = uiState,
        onErrorDialogDismiss = viewModel::dismissErrorPopup,
        loadingType = BaseLoadingType.OVERLAY,
        statusBarColor = Color.Transparent,
        useLightStatusIcons = true,
        containerColor = Color.Transparent,
        content = { model ->
            ArtistDescriptionContent(
                model = model,
                snackbarHostState = snackbarHostState,
                onEvent = viewModel::onEvent
            )
        }
    )
}

/**
 * Stateless content composable for the artist description screen.
 *
 * Uses a [LazyColumn] so that all sections (hero, popular tracks, albums grid,
 * appears-on row) participate in a single unified scroll. The hero image is the
 * first item and extends beneath the transparent Android status bar.
 *
 * A semi-transparent circular back button is overlaid in the top-left corner
 * so it remains reachable regardless of the scroll position.
 *
 * @param model Current immutable artist description state.
 * @param snackbarHostState Host for transient playback error messages.
 * @param onEvent Callback emitting user intents to the ViewModel.
 */
@Composable
private fun ArtistDescriptionContent(
    model: ArtistDescriptionUiModel,
    snackbarHostState: SnackbarHostState,
    onEvent: (ArtistDescriptionUiEvent) -> Unit
) {
    val shellBottomPadding = LocalShellBottomPadding.current

    // Explicit dark background ensures the AMOLED canvas is always visible
    // even when AppBaseScreen renders its Surface as Color.Transparent for the
    // edge-to-edge hero effect, regardless of the system window's default color.
    Box(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)) {

        // ── Main scrollable body ───────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = shellBottomPadding + 16.dp)
        ) {
            // Hero image + action row — the photograph extends beneath the status bar.
            item(key = "hero") {
                ArtistDescriptionHeroSection(
                    artistName = model.artistName,
                    artistImageUrl = model.artistImageUrl,
                    trackCount = model.allTracks.size,
                    albumCount = model.albumCount,
                    isFollowed = model.isFollowed,
                    onPlayClick = { onEvent(ArtistDescriptionUiEvent.PlayArtist) },
                    onShuffleClick = { onEvent(ArtistDescriptionUiEvent.ShuffleArtist) },
                    onFollowClick = { onEvent(ArtistDescriptionUiEvent.ToggleFollow) }
                )
            }

            // ── Popular Songs ─────────────────────────────────────────────────
            if (model.popularTracks.isNotEmpty()) {
                item(key = "popular_header") {
                    ArtistDescriptionSectionHeader(
                        title = stringResource(R.string.artist_description_popular_tracks_title),
                        modifier = Modifier.padding(
                            start = paddingMedium,
                            end = paddingMedium,
                            top = 4.dp,
                            bottom = 10.dp
                        )
                    )
                }
                item(key = "popular_tracks") {
                    ArtistDescriptionPopularTracksSection(
                        tracks = model.popularTracks,
                        currentPlayingTrackId = model.currentPlayingTrackId,
                        onTrackClick = { track -> onEvent(ArtistDescriptionUiEvent.PlayTrack(track)) }
                    )
                }
            }

            // ── Albums ────────────────────────────────────────────────────────
            if (model.albums.isNotEmpty()) {
                item(key = "albums_header") {
                    ArtistDescriptionSectionHeader(
                        title = stringResource(R.string.artist_description_albums_title),
                        modifier = Modifier.padding(
                            start = paddingMedium,
                            end = paddingMedium,
                            top = 20.dp,
                            bottom = 10.dp
                        )
                    )
                }
                item(key = "albums_grid") {
                    ArtistDescriptionAlbumsSection(
                        albums = model.albums,
                        onAlbumClick = { albumId -> onEvent(ArtistDescriptionUiEvent.OpenAlbum(albumId)) }
                    )
                }
            }

            // ── Appears On ────────────────────────────────────────────────────
            if (model.appearsOnAlbums.isNotEmpty()) {
                item(key = "appears_on_header") {
                    ArtistDescriptionSectionHeader(
                        title = stringResource(R.string.artist_description_appears_on_title),
                        modifier = Modifier.padding(
                            start = paddingMedium,
                            end = paddingMedium,
                            top = 20.dp,
                            bottom = 10.dp
                        )
                    )
                }
                item(key = "appears_on") {
                    ArtistDescriptionAppearsOnSection(
                        albums = model.appearsOnAlbums,
                        onAlbumClick = { albumId -> onEvent(ArtistDescriptionUiEvent.OpenAlbum(albumId)) }
                    )
                }
            }

            // Bottom breathing room already provided by contentPadding above.
            item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // ── Translucent back button overlay ───────────────────────────────────
        // Stays at the top-left regardless of the scroll position, matching the
        // Spotify-style approach of floating the navigation action over the hero.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Surface(
                onClick = { onEvent(ArtistDescriptionUiEvent.NavigateBack) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.artist_description_back_content_description),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── Snackbar ──────────────────────────────────────────────────────────
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = shellBottomPadding + 8.dp)
        )
    }
}

/**
 * Bold section header used to separate content categories on the artist
 * description screen (Popular Songs, Albums, Appears On).
 *
 * @param title Section label to display.
 * @param modifier Optional [Modifier] for the root [Text].
 */
@Composable
private fun ArtistDescriptionSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxWidth()
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(
    name = "ArtistDescriptionScreen — Playing",
    showBackground = true,
    backgroundColor = 0xFF090B10
)
@Composable
private fun ArtistDescriptionScreenPlayingPreview() {
    AudiophileMusicPlayerTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        ArtistDescriptionContent(
            model = previewArtistDescriptionUiModel(),
            snackbarHostState = snackbarHostState,
            onEvent = {}
        )
    }
}

@Preview(
    name = "ArtistDescriptionScreen — No Image",
    showBackground = true,
    backgroundColor = 0xFF090B10
)
@Composable
private fun ArtistDescriptionScreenNoImagePreview() {
    AudiophileMusicPlayerTheme {
        val snackbarHostState = remember { SnackbarHostState() }
        ArtistDescriptionContent(
            model = previewArtistDescriptionUiModel().copy(artistImageUrl = null),
            snackbarHostState = snackbarHostState,
            onEvent = {}
        )
    }
}

/**
 * Builds a realistic sample model for the artist description screen previews.
 *
 * Uses Miles Davis as the subject with a curated subset of his real catalogue
 * so that the popular tracks row, albums grid, and appears-on row all render.
 *
 * @return Preview-only [ArtistDescriptionUiModel].
 */
private fun previewArtistDescriptionUiModel(): ArtistDescriptionUiModel {
    val albumKOB = Album(id = 1L, title = "Kind of Blue", artistName = "Miles Davis", artUri = null, trackCount = 5, year = 1959)
    val albumBB = Album(id = 2L, title = "Bitches Brew", artistName = "Miles Davis", artUri = null, trackCount = 4, year = 1970)
    val albumFeat = Album(id = 3L, title = "Giant Steps", artistName = "John Coltrane", artUri = null, trackCount = 8, year = 1960)

    val tracks = listOf(
        previewTrack(1L, "So What", albumKOB),
        previewTrack(2L, "Freddie Freeloader", albumKOB),
        previewTrack(3L, "Blue in Green", albumKOB),
        previewTrack(4L, "Pharaoh's Dance", albumBB),
        previewTrack(5L, "Miles Runs the Voodoo Down", albumBB)
    )

    return ArtistDescriptionUiModel(
        artistName = "Miles Davis",
        artistImageUrl = null,
        popularTracks = tracks,
        allTracks = tracks,
        albums = listOf(albumKOB, albumBB),
        appearsOnAlbums = listOf(albumFeat),
        currentPlayingTrackId = 1L,
        isFollowed = false,
        albumCount = 2,
        totalDurationMs = tracks.sumOf { it.durationMs },
        losslessTrackCount = 5,
        hiResTrackCount = 3,
        maxSampleRateHz = 96_000,
        maxBitDepth = 24,
        codecSummary = "FLAC"
    )
}

private fun previewTrack(id: Long, title: String, album: Album): Track = Track(
    id = id,
    title = title,
    artistName = "Miles Davis",
    albumTitle = album.title,
    albumId = album.id,
    durationMs = 300_000L + id * 30_000L,
    uri = "content://tracks/$id",
    trackNumber = id.toInt(),
    discNumber = 1,
    audioFormat = AudioFormat(
        sampleRateHz = 96_000,
        bitDepth = 24,
        channelCount = 2,
        codec = AudioCodec.FLAC,
        isLossless = true
    ),
    fileSizeBytes = 50_000_000L,
    dateAdded = 1_700_000_000L - id * 10_000L
)
