package com.androidexpert35.audiophilemusicplayer.presentation.theme

import androidx.compose.ui.graphics.Color

// --- Core Audiophile Palette ---

/** Deep pitch-black surface for maximum contrast with album art. */
val AudiophileBlack = Color(0xFF090B10)

/** Deep blue-black canvas tone used behind expressive layered surfaces. */
val AudiophileNight = Color(0xFF10141D)

/** Slightly lighter surface for cards and elevated elements. */
val AudiophileSurface = Color(0xFF171B24)

/** Subtle container background for bottom bars and overlays. */
val AudiophileSurfaceContainer = Color(0xFF1E2430)

/** Low-emphasis container for sheet / pill backgrounds. */
val AudiophileSurfaceContainerLow = Color(0xFF252C39)

/** High-emphasis surface (e.g., active navigation item). */
val AudiophileSurfaceContainerHigh = Color(0xFF303848)

/** Bright surface used for highly elevated glassmorphism-inspired layers. */
val AudiophileSurfaceBright = Color(0xFF3A4356)

// --- Text / Icon Colours ---

/** Primary on-surface colour — crisp white for headings and active icons. */
val AudiophileOnSurface = Color(0xFFF4F7FF)

/** De-emphasized secondary text and inactive icons. */
val AudiophileOnSurfaceVariant = Color(0xFFBCC6D8)

/** Subtle outline for dividers and borders. */
val AudiophileOutline = Color(0xFF465062)

// --- Accent Colours ---

/** Teal/cyan accent matching the screenshot's heart & seek-bar active colour. */
val AudiophilePrimary = Color(0xFF6AE7D4)

/** Bright primary accent used for expressive highlights and glow treatments. */
val AudiophilePrimaryBright = Color(0xFFA2FFEE)

/** Lighter tint for primary containers. */
val AudiophilePrimaryContainer = Color(0xFF183E3A)

/** On-primary-container text. */
val AudiophileOnPrimaryContainer = Color(0xFFB9FFF4)

/** Secondary accent for chips, cards, and supporting emphasis. */
val AudiophileSecondary = Color(0xFFB5C4FF)

/** Secondary container used for expressive info treatments. */
val AudiophileSecondaryContainer = Color(0xFF27304A)

/** Supporting magenta-violet accent for hero areas and metadata. */
val AudiophileTertiary = Color(0xFFE0B7FF)

/** Tertiary container for hero chips and decorative accents. */
val AudiophileTertiaryContainer = Color(0xFF3A2945)

/** Gold / amber accent for Hi-Res audio tag. */
val HiResGold = Color(0xFFF0C96C)

/** Dark gold tint for Hi-Res tag container background. */
val HiResGoldContainer = Color(0xFF4A3B16)

/** Muted grey for non-Hi-Res / lossy tag. */
val LossyGrey = Color(0xFF9EA7B8)

/** Dark muted container for non-Hi-Res tag. */
val LossyGreyContainer = Color(0xFF2B3240)

/** Overlay scrim used on top of blurred artwork backgrounds. */
val AudiophileScrim = Color(0xCC091019)

/**
 * Semi-transparent dark warm-brown pill background for audio-quality chips.
 *
 * Derived from the Tidal badge reference: the on-screen composite over black
 * measured as `#3B2E25`. At 80 % (0xCC) opacity the chip remains consistent
 * across artwork colours while still letting the blurred background bleed
 * through enough for the [AudiophileGlassHighlight] border to read as glass.
 */
val AudioChipBackground = Color(0xCC3B2E25)

/** Soft glass highlight used by elevated expressive panels. */
val AudiophileGlassHighlight = Color(0x1FFFFFFF)

// --- Legacy (kept for compatibility) ---
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)