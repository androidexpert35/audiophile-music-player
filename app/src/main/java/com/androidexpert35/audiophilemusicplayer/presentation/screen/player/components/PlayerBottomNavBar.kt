@file:Suppress("unused")

package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

/**
 * **Deprecated** — The bottom navigation bar and [BottomNavDestination] enum
 * have been moved to the shared `common/components/` package and are now
 * provided globally by [AppShell].
 *
 * @see com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppBottomNavBar
 * @see com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.BottomNavDestination
 */

// Re-export for any remaining call-sites during migration.
@Deprecated(
    message = "Use AppBottomNavBar from common/components instead.",
    replaceWith = ReplaceWith(
        "AppBottomNavBar",
        "com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.AppBottomNavBar"
    )
)
typealias PlayerBottomNavBar = Unit

@Deprecated(
    message = "Use BottomNavDestination from common/components instead.",
    replaceWith = ReplaceWith(
        "BottomNavDestination",
        "com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.BottomNavDestination"
    )
)
typealias BottomNavDestination = com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.BottomNavDestination

