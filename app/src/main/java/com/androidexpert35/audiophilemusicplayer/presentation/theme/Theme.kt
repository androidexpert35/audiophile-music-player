package com.androidexpert35.audiophilemusicplayer.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Audiophile-tuned dark colour scheme.
 *
 * Mirrors the reference screenshot: deep black surfaces, teal/cyan primary
 * accent, and gold Hi-Res highlights handled at the component level.
 */
private val AudiophileDarkColorScheme = darkColorScheme(
    primary = AudiophilePrimary,
    onPrimary = AudiophileBlack,
    primaryContainer = AudiophilePrimaryContainer,
    onPrimaryContainer = AudiophileOnPrimaryContainer,
    secondary = AudiophileSecondary,
    onSecondary = AudiophileBlack,
    secondaryContainer = AudiophileSecondaryContainer,
    onSecondaryContainer = AudiophileOnSurface,
    tertiary = AudiophileTertiary,
    onTertiary = AudiophileBlack,
    tertiaryContainer = AudiophileTertiaryContainer,
    onTertiaryContainer = AudiophileOnSurface,
    background = AudiophileNight,
    onBackground = AudiophileOnSurface,
    surface = AudiophileSurface,
    onSurface = AudiophileOnSurface,
    onSurfaceVariant = AudiophileOnSurfaceVariant,
    surfaceContainer = AudiophileSurfaceContainer,
    surfaceContainerLow = AudiophileSurfaceContainerLow,
    surfaceContainerHigh = AudiophileSurfaceContainerHigh,
    surfaceBright = AudiophileSurfaceBright,
    outline = AudiophileOutline,
    outlineVariant = AudiophileOutline,
    scrim = AudiophileScrim,
    inversePrimary = AudiophilePrimaryBright
)

/**
 * Root theme composable for the Audiophile Music Player.
 *
 * Forces the dark colour scheme regardless of system preference —
 * audiophile players are dark by convention to reduce distraction and
 * let album art take centre stage.
 *
 * @param darkTheme Whether dark colours should be preferred when dynamic colour is enabled.
 * @param dynamicColor Whether the app should derive its palette from the system wallpaper on supported versions.
 * @param content The composable subtree to theme.
 */
@Composable
fun AudiophileMusicPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> AudiophileDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AudiophileShapes,
        content = content
    )
}