package com.androidexpert35.audiophilemusicplayer.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 expressive shape scale for the Audiophile UI.
 *
 * Uses softer, larger radii for hero cards and shell surfaces so the dark
 * interface feels premium and tactile without becoming visually heavy.
 */
// Corner radii are tightened relative to the standard M3 expressive scale so
// cards and surfaces stay premium-looking without appearing oversized on
// high-density OEM displays that already apply a "Zoomed in" scaling factor.
val AudiophileShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

