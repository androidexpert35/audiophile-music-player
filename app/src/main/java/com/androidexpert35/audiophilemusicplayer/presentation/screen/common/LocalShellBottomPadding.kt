package com.androidexpert35.audiophilemusicplayer.presentation.screen.common

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Provides the measured height of the floating app shell bottom panel
 * (mini-player + bottom navigation bar) to every screen in the composition tree.
 *
 * Screens that contain scrollable lists or fixed layouts should add this value
 * as bottom content padding so their content is never obscured by the panel.
 *
 * Usage:
 * ```
 * val shellPadding = LocalShellBottomPadding.current
 * contentPadding = PaddingValues(bottom = shellPadding)
 * ```
 *
 * The value is updated automatically by [AppShell] whenever the panel height
 * changes (e.g. when the mini-player slides in or out). The default is 0.dp,
 * which is used before the first layout pass completes.
 */
val LocalShellBottomPadding = compositionLocalOf<Dp> { 0.dp }

