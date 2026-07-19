package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Shared top application bar for Audiophile Music Player.
 *
 * Provides a consistent, Material 3 expressive header across every main-flow
 * The bar adapts the container colour from `MaterialTheme.colorScheme.surface`
 * at rest to `MaterialTheme.colorScheme.surfaceContainer` once the user has scrolled
 * content underneath it, giving a subtle elevation cue without obscuring the dark
 * audiophile canvas.
 *
 * Screens backed by a `LazyColumn` can pass a [TopAppBarDefaults.enterAlwaysScrollBehavior]
 * so the bar collapses while the user browses deep into the list and resurfaces smoothly
 * on a reverse scroll — keeping key actions (back navigation, refresh) always reachable.
 *
 * @param title Primary title string displayed in the bar.
 * @param modifier Optional [Modifier] applied to the root [TopAppBar].
 * @param onNavigateBack When non-null, a leading arrow-back navigation icon is rendered
 *   and this lambda is invoked when the user taps it. Omit for root tab destinations.
 * @param actions Trailing slot for contextual [IconButton] actions such as refresh or settings.
 *   Defaults to an empty slot.
 * @param scrollBehavior Optional [TopAppBarScrollBehavior] that connects the bar to the
 *   nested-scroll host of a [androidx.compose.foundation.lazy.LazyColumn] or similar
 *   scrollable container. Supply [TopAppBarDefaults.enterAlwaysScrollBehavior] for lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_navigate_back)
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        scrollBehavior = scrollBehavior
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "AppTopBar — Root destination (dark)")
@Composable
private fun AppTopBarRootPreview() {
    AudiophileMusicPlayerTheme {
        AppTopBar(
            title = "Library",
            actions = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null
                    )
                }
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "AppTopBar — Detail screen with back (dark)")
@Composable
private fun AppTopBarDetailPreview() {
    AudiophileMusicPlayerTheme {
        AppTopBar(
            title = "Random Access Memories",
            onNavigateBack = {}
        )
    }
}

