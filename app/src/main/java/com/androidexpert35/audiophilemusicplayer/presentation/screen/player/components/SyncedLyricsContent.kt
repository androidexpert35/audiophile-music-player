package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.Lyrics
import kotlin.math.abs

/** Default item height fallback (dp) when the line has not yet been laid out. */
private val FALLBACK_ITEM_HEIGHT = 56.dp

/** Top/bottom fade region applied via DstIn blend on the LazyColumn viewport. */
private val FADE_HEIGHT = 88.dp

/** Vertical spacing between lyric lines. */
private val LINE_SPACING = 14.dp

/** Horizontal padding for lyric content. */
private val LINE_HORIZONTAL_PADDING = 24.dp

/** Scale applied to fully-distant (off-centre) lines. */
private const val DISTANT_SCALE = 0.75f

/** Alpha applied to fully-distant (off-centre) lines. */
private const val DISTANT_ALPHA = 0.30f

/** Extra alpha multiplier for instrumental placeholder lines. */
private const val INSTRUMENTAL_ALPHA_MULTIPLIER = 0.5f

/**
 * Tightly-scoped [LazyColumn] of time-stamped lyric lines that keeps the active
 * line centred in the visible viewport — matching the Spotify / Apple Music
 * convention.
 *
 * Centering is achieved by:
 * 1. Capturing the rendered viewport height via [Modifier.onSizeChanged].
 * 2. Setting `contentPadding` vertical to `height / 2` so the first and last lines
 *    can scroll to the midpoint.
 * 3. Using `scrollToItem` on first composition (no animation) and
 *    `animateScrollToItem` on subsequent index changes, with a `scrollOffset` of
 *    `itemHeight / 2` so the line's centre aligns with the viewport centre.
 *
 * Each visible line is scaled and faded based on its distance from the viewport
 * centre. A `BlendMode.DstIn` gradient mask softens the top and bottom edges.
 *
 * @param lyrics Resolved lyrics payload containing the time-stamped lines.
 * @param positionMs Lambda returning the current playback position in milliseconds.
 *   Captured as a lambda to avoid recomposing this composable on every tick.
 * @param modifier Modifier applied to the root [Box]; should provide a bounded size.
 */
@Composable
internal fun SyncedLyricsContent(
    lyrics: Lyrics,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    var listHeightPx by remember { mutableIntStateOf(0) }

    // Active line index derived from playback position. derivedStateOf keeps the
    // recomposition scoped — only this observer re-runs on each position tick.
    val currentIndex by remember(lyrics.lines) {
        derivedStateOf {
            val pos = positionMs()
            lyrics.lines.indexOfLast { it.timestampMs <= pos }
        }
    }

    val hasJumpedToInitialPosition = remember { mutableStateOf(false) }

    // First available viewport height: jump (no animation) to the correct line so
    // the sheet opens at the right position when started mid-song.
    LaunchedEffect(listHeightPx) {
        if (listHeightPx > 0 && !hasJumpedToInitialPosition.value) {
            val idx = currentIndex.coerceAtLeast(0)
            listState.scrollToItem(
                index = idx,
                scrollOffset = resolveItemHeightPx(listState, idx, density) / 2,
            )
            hasJumpedToInitialPosition.value = true
        }
    }

    // Animate to each new active line. Guarded so it does not race with the
    // initial jump on first composition.
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && listHeightPx > 0 && hasJumpedToInitialPosition.value) {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = resolveItemHeightPx(listState, currentIndex, density) / 2,
            )
        }
    }

    // Per-frame transforms keyed by list index. derivedStateOf ensures downstream
    // recomposition fires only when layoutInfo actually changes.
    val itemTransforms by remember {
        derivedStateOf {
            if (listHeightPx <= 0) return@derivedStateOf emptyMap<Int, LyricItemTransform>()
            val viewportCenter = listHeightPx / 2f
            listState.layoutInfo.visibleItemsInfo.associate { info ->
                val itemCenter = info.offset + info.size / 2f
                val t = (abs(itemCenter - viewportCenter) / viewportCenter).coerceIn(0f, 1f)
                info.index to LyricItemTransform(
                    scale = lerp(1f, DISTANT_SCALE, t),
                    alpha = lerp(1f, DISTANT_ALPHA, t),
                )
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { listHeightPx = it.height }
            // Offscreen compositing so DstIn gradient masks blend against this
            // node's own rendered pixels, not the screen background.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fadePx = FADE_HEIGHT.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = 0f,
                        endY = fadePx,
                    ),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = size.height - fadePx,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = LINE_HORIZONTAL_PADDING,
                vertical = with(density) { (listHeightPx / 2).toDp() },
            ),
            verticalArrangement = Arrangement.spacedBy(LINE_SPACING),
        ) {
            itemsIndexed(
                items = lyrics.lines,
                key = { index, _ -> index },
            ) { index, line ->
                val isActive = index == currentIndex
                val isInstrumentalLine = line.text.isBlank()
                val transform = if (isActive) {
                    LyricItemTransform.ACTIVE
                } else {
                    itemTransforms[index] ?: LyricItemTransform.DISTANT
                }

                if (isActive && !isInstrumentalLine) {
                    ActiveLyricLine(text = line.text, transform = transform)
                } else {
                    InactiveLyricLine(
                        text = if (isInstrumentalLine) {
                            stringResource(R.string.lyrics_music_placeholder)
                        } else {
                            line.text
                        },
                        isInstrumental = isInstrumentalLine,
                        transform = transform,
                    )
                }
            }
        }
    }
}

/**
 * Resolves the rendered height of the item at [index], or returns a sensible
 * fallback when the item has not been measured yet.
 */
private fun resolveItemHeightPx(
    listState: androidx.compose.foundation.lazy.LazyListState,
    index: Int,
    density: androidx.compose.ui.unit.Density,
): Int = listState.layoutInfo.visibleItemsInfo
    .firstOrNull { it.index == index }
    ?.size
    ?: with(density) { FALLBACK_ITEM_HEIGHT.roundToPx() }

/**
 * Active lyric line: bold white text wrapped in a frosted-glass pill.
 *
 * `graphicsLayer` is applied before the clip so the scale transform does not
 * affect the clip boundary shape.
 */
@Composable
private fun ActiveLyricLine(
    text: String,
    transform: LyricItemTransform,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = transform.scale
                scaleY = transform.scale
                alpha = transform.alpha
            }
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                lineHeight = 29.sp,
            ),
            color = Color.White,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Inactive lyric line or instrumental placeholder. The graphicsLayer alpha
 * handles distance-based dimming; instrumental passages get an additional 50 %
 * alpha reduction so they stay visually subordinate to real lyric lines.
 */
@Composable
private fun InactiveLyricLine(
    text: String,
    isInstrumental: Boolean,
    transform: LyricItemTransform,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = FontWeight.Normal,
            fontSize = if (isInstrumental) 14.sp else 16.sp,
            lineHeight = 24.sp,
        ),
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = transform.scale
                scaleY = transform.scale
                alpha = if (isInstrumental) {
                    transform.alpha * INSTRUMENTAL_ALPHA_MULTIPLIER
                } else {
                    transform.alpha
                }
            },
        textAlign = TextAlign.Center,
    )
}

