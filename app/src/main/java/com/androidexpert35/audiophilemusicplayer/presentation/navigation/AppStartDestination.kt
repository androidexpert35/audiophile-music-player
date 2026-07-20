package com.androidexpert35.audiophilemusicplayer.presentation.navigation

/**
 * Resolved decision for which navigation graph the app should compose on launch.
 *
 * Computed once per cold start from the runtime media permission and the persisted
 * indexing flag. Choosing the destination **before** the [androidx.navigation.compose.NavHost]
 * is composed lets the app skip mounting (and immediately animating away) the onboarding
 * screen when there is genuinely nothing to onboard, eliminating the first-frame
 * composition + navigation-transition burst that otherwise overlaps the library's own
 * initial composition.
 */
sealed interface AppStartDestination {

    /**
     * The decision is still being computed (the persisted indexing flag is read
     * asynchronously). The host renders a plain themed background during this brief
     * window so no onboarding UI flashes before the real start destination is known.
     */
    data object Deciding : AppStartDestination

    /**
     * Start on the onboarding graph — either the media permission has not been granted
     * yet, or the library has never been indexed and the first scan must run.
     */
    data object Onboarding : AppStartDestination

    /**
     * Start directly on the main library flow — permission is granted and a cached
     * Room index already exists, so onboarding can be skipped entirely.
     */
    data object Main : AppStartDestination
}
