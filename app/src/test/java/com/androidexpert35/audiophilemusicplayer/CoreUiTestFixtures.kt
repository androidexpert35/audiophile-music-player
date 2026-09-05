package com.androidexpert35.audiophilemusicplayer

import com.androidexpert35.audiophilemusicplayer.presentation.error.AudiophileUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay.PlayerOverlayManager
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.presentation.navigation.NavigationCommand
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.navigation.NavigationOptions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Deterministic string resolver used by JVM tests that have no Android resource context.
 */
internal object TestStringResolver : StringResolver {
    override fun get(id: Int, vararg formatArgs: Any): String =
        buildString {
            append(id)
            if (formatArgs.isNotEmpty()) {
                append(":")
                append(formatArgs.joinToString())
            }
        }

    override fun getPlural(id: Int, quantity: Int, vararg formatArgs: Any): String =
        get(id, quantity, *formatArgs)
}

/** Shared Audiophile error mapper configured for local JVM tests. */
internal val TestUiErrorMapper = AudiophileUiErrorMapper(TestStringResolver, io.mockk.mockk(relaxed = true))

/**
 * Navigation test double that records the most recent forward route.
 */
internal class FakeNavigationManager : NavigationManager {
    override val navigationCommands = MutableSharedFlow<NavigationCommand>()
    override val currentRoute = MutableStateFlow<String?>(null)

    var lastRoute: String? = null
        private set

    override fun navigate(route: String, options: NavigationOptions) {
        lastRoute = route
    }

    override fun navigateUp() = Unit

    override fun popBackStack(route: String?, inclusive: Boolean) = Unit

    override fun navigateAndClearBackStack(
        route: String,
        popUpToRoute: String?,
        inclusive: Boolean
    ) {
        lastRoute = route
    }

    override fun onRouteChanged(route: String?) {
        currentRoute.value = route
    }
}

/**
 * Player-overlay test double that records open requests and exposes the production flow contract.
 */
internal class FakePlayerOverlayManager : PlayerOverlayManager {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val openRequests = requests

    var opened: Boolean = false
        private set

    override fun open() {
        opened = true
        requests.tryEmit(Unit)
    }
}
