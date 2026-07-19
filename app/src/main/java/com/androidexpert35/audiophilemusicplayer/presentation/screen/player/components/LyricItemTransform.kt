package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

/**
 * Immutable holder for per-line visual transform values derived from a lyric line's
 * distance from the viewport centre.
 *
 * Computed once per scroll frame inside a `derivedStateOf` block and applied to
 * each visible item via [androidx.compose.ui.graphics.graphicsLayer].
 *
 * @property scale Uniform scale factor (X and Y) applied to the line composable.
 * @property alpha Opacity applied to the line composable.
 */
internal data class LyricItemTransform(
    val scale: Float,
    val alpha: Float,
) {
    companion object {
        /** Full-size, fully-opaque transform for the active lyric line. */
        val ACTIVE = LyricItemTransform(scale = 1f, alpha = 1f)

        /**
         * Fallback transform for off-screen lines that have no visible layout info.
         * Matches the far end of the lerp range so they are visually suppressed.
         */
        val DISTANT = LyricItemTransform(scale = 0.75f, alpha = 0.30f)
    }
}

