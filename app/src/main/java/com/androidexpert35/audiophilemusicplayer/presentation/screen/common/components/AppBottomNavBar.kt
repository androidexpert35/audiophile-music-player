package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes

/**
 * Bottom navigation section of the floating composite panel.
 *
 * Renders three minimalist nav items on the left — each with a white icon and an
 * Italian label — and the [AudioOutputControl] pill on the right. The entire bar
 * sits below the mini-player section inside the shared glassmorphism surface, so it
 * inherits the panel's background without its own container color.
 *
 * @param currentRoute The currently active navigation route, used to highlight the
 *   selected destination.
 * @param isPlayerOpen Whether the now-playing overlay is currently visible. Used to
 *   highlight the [BottomNavDestination.LIBRARY] (Riproduttore) item while the
 *   player is open.
 * @param onDestinationSelected Callback emitted when the user taps a destination item.
 * @param modifier Optional [Modifier] applied to the root row.
 */
@Composable
fun AppBottomNavBar(
    currentRoute: String?,
    isPlayerOpen: Boolean,
    onDestinationSelected: (BottomNavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BottomNavDestination.entries.forEach { destination ->
            val selected = when (destination) {
                // LIBRARY is selected when browsing catalogue, album/artist pages,
                // or while the player overlay is open (player sits on top of library).
                BottomNavDestination.LIBRARY -> {
                    isPlayerOpen ||
                        currentRoute == destination.route ||
                        currentRoute?.startsWith(
                            AppRoutes.AlbumOverview.routeDefinition.baseRoute
                        ) == true ||
                        currentRoute?.startsWith(
                            AppRoutes.ArtistDescription.routeDefinition.baseRoute
                        ) == true ||
                        currentRoute?.startsWith(
                            AppRoutes.PlaylistOverview.routeDefinition.baseRoute
                        ) == true
                }
                else -> currentRoute == destination.route
            }

            // weight(1f) distributes each item equally across the available width
            // so SpaceBetween produces a perfectly balanced three-column layout.
            BottomNavItem(
                destination = destination,
                selected = selected,
                onClick = { onDestinationSelected(destination) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Single minimalist navigation item shown inside [AppBottomNavBar].
 *
 * Renders the destination's icon above its Italian label. The icon and label
 * tint shifts from [MaterialTheme.colorScheme.onSurfaceVariant] to
 * [MaterialTheme.colorScheme.primary] when selected.
 *
 * @param destination The destination this item represents.
 * @param selected Whether this item corresponds to the active route.
 * @param onClick Callback emitted when the item is tapped.
 * @param modifier Optional [Modifier] applied to the column.
 */
@Composable
private fun BottomNavItem(
    destination: BottomNavDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = stringResource(destination.labelRes),
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp)
        )
    }
}
