package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import kotlin.math.log10

/**
 * Converts a volume percentage to its decibel equivalent under the quartic
 * taper applied by the C++ pump layer (`decoder_to_ring_bridge.cpp`).
 *
 * Formula: `gain_dB = 20 × log10(position⁴) = 80 × log10(pct / 100)`
 *
 * This mirrors the `linear_to_gain_scalar()` function in C++ (`gain = position⁴`)
 * so the dB label is always consistent with what the native pump applies.
 *
 * | Slider % | Quartic gain | dB         |
 * |---------|-------------|------------|
 * | 100 %   | 1.000 000   |   0.0 dB   |
 * |  75 %   | 0.316 406   | −10.0 dB   |
 * |  50 %   | 0.062 500   | −24.1 dB   |
 * |  25 %   | 0.003 906   | −48.2 dB   |
 * |   0 %   | 0.000 000   | −∞ (Mute)  |
 *
 * @param pct Volume percentage in `[0, 100]`.
 * @return Human-readable dB string, e.g. `"−24.1 dB"`, or `"Mute"` when `pct == 0`.
 */
private fun volumePctToDbString(pct: Int): String {
    if (pct <= 0) return "Mute"
    if (pct >= 100) return "0.0 dB"
    // Quartic dB = 20·log10(position⁴) = 80·log10(position)
    val gainDb = 80f * log10(pct / 100f)
    return "%.1f dB".format(gainDb).replace("-", "−")
}

/**
 * Converts a volume percentage to its linear decibel equivalent for the
 * standard AudioFlinger / AudioTrack path.
 *
 * Formula: `gain_dB = 20 × log10(pct / 100)`
 *
 * This uses a simple linear position → dB mapping, matching the logarithmic
 * perception curve of the Android AudioManager stream volume.
 *
 * @param pct Volume percentage in `[0, 100]`.
 * @return Human-readable dB string, e.g. `"−6.0 dB"`, or `"Mute"` when `pct == 0`.
 */
private fun systemVolumePctToDbString(pct: Int): String {
    if (pct <= 0) return "Mute"
    if (pct >= 100) return "0.0 dB"
    val gainDb = 20f * log10(pct / 100f)
    return "%.1f dB".format(gainDb).replace("-", "−")
}

/**
 * Transient overlay that shows the current USB volume level when the user presses
 * a physical volume key while the libusb audio path is active.
 *
 * The overlay slides in from the top of the screen and displays:
 * - A volume icon that adapts to the current level
 * - A label showing the percentage alongside the quartic-tapered dB equivalent
 * - A [Slider] the user can drag with a finger to set the level directly
 *
 * The card surface matches the app's floating bottom panel glassmorphism style:
 * `surfaceContainer` fill, `AudiophileGlassHighlight` 1 dp border, 32 dp corner
 * radius, and a 12 dp shadow elevation.
 *
 * Dismissal timing is managed by the caller (typically `MainActivity` via a
 * coroutine job that auto-cancels after a short idle period).
 *
 * The icon adapts to the current volume level:
 * - `0 %`      → `VolumeOff`
 * - `1..33 %`  → `VolumeMute`
 * - `34..66 %` → `VolumeDown`
 * - `67..100 %`→ `VolumeUp`
 *
 * ### Usage
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {
 *     AppNavigator(...)
 *     UsbVolumeOverlay(
 *         isVisible = showOverlay,
 *         volumePct = currentPct,
 *         onVolumeChange = { viewModel.onEvent(SetVolume(it)) },
 *     )
 * }
 * ```
 *
 * @param isVisible      When `true` the overlay slides in; when `false` it slides back out.
 * @param volumePct      Current volume in `[0, 100]`.
 * @param onVolumeChange Callback emitted with the new integer percentage `[0, 100]`
 *   when the user drags the slider. The caller is responsible for forwarding this
 *   to [com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbVolumeController]
 *   (USB mode) or to [android.media.AudioManager] (system mode).
 * @param modifier       Optional [Modifier] applied to the outermost layout node.
 * @param isDsdLocked    When `true` the overlay shows the Native DSD volume-lock
 *   warning instead of the interactive slider. The slider becomes non-interactive
 *   and a localised message explains that volume must be controlled on the DAC.
 *   Defaults to `false`.
 * @param isUsbMode      When `true` the overlay labels itself "USB Volume" and uses
 *   the quartic dB formula that matches the C++ gain stage. When `false` (system
 *   AudioFlinger path) the label reads "Volume" and the standard linear dB
 *   formula is used. Defaults to `true`.
 */
@Composable
fun UsbVolumeOverlay(
    isVisible: Boolean,
    volumePct: Int,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isDsdLocked: Boolean = false,
    isUsbMode: Boolean = true,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(MotionTokens.DurationShort)) +
                slideInVertically(
                    animationSpec = tween(MotionTokens.DurationMedium),
                    initialOffsetY = { -it },
                ),
            exit = fadeOut(tween(MotionTokens.DurationShort)) +
                slideOutVertically(
                    animationSpec = tween(MotionTokens.DurationShort),
                    targetOffsetY = { -it },
                ),
        ) {
            VolumeCard(
                volumePct = volumePct,
                onVolumeChange = onVolumeChange,
                isDsdLocked = isDsdLocked,
                isUsbMode = isUsbMode,
            )
        }
    }
}

/**
 * Stateless glassmorphism card rendered inside [UsbVolumeOverlay].
 *
 * Matches the [ShellBottomPanel] surface recipe:
 * - Fill  : `MaterialTheme.colorScheme.surfaceContainer`
 * - Border: 1 dp [AudiophileGlassHighlight] (translucent white edge)
 * - Shape : `RoundedCornerShape(32.dp)`
 * - Shadow: `12.dp` elevation
 *
 * When [isDsdLocked] is `true` the card switches to a "volume locked" mode:
 * the header icon changes to a padlock, the title and dB label are replaced by
 * the Native DSD advisory message, and the slider is rendered but disabled so
 * the user cannot interact with it. The card auto-dismisses on the same timer
 * as the normal mode.
 *
 * When [isUsbMode] is `false` (system AudioFlinger path) the title reads
 * "Volume" instead of "USB Volume" and the secondary dB label uses the standard
 * linear formula instead of the quartic taper.
 *
 * @param volumePct      Current volume in `[0, 100]`.
 * @param onVolumeChange Callback with the new integer percentage when the slider is dragged.
 * @param isDsdLocked    When `true` renders the Native DSD volume-lock state.
 * @param isUsbMode      When `true` uses USB labels and the quartic dB formula.
 */
@Composable
private fun VolumeCard(
    volumePct: Int,
    onVolumeChange: (Int) -> Unit,
    isDsdLocked: Boolean = false,
    isUsbMode: Boolean = true,
) {
    val cardShape = RoundedCornerShape(24.dp)

    // Icon selection: padlock when DSD is active (volume software bypass),
    // otherwise pick the standard level-adaptive volume icon.
    val icon = when {
        isDsdLocked     -> Icons.Rounded.Lock
        volumePct == 0  -> Icons.AutoMirrored.Rounded.VolumeOff
        volumePct <= 33 -> Icons.AutoMirrored.Rounded.VolumeMute
        volumePct <= 66 -> Icons.AutoMirrored.Rounded.VolumeDown
        else            -> Icons.AutoMirrored.Rounded.VolumeUp
    }

    // Dimmed tint signals that neither the icon nor the slider accepts input.
    val iconTint = if (isDsdLocked) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    // Title: "USB Volume" on the libusb path; plain "Volume" on the system path.
    val titleText = when {
        isDsdLocked -> "DSD Nativo"
        isUsbMode   -> "USB Volume  $volumePct%"
        else        -> "Volume  $volumePct%"
    }

    // Secondary dB label — quartic for libusb, linear for system AudioFlinger.
    // Pre-compute once so the composition function stays pure.
    val dbLabel = when {
        isDsdLocked -> null
        isUsbMode   -> volumePctToDbString(volumePct)
        else        -> systemVolumePctToDbString(volumePct)
    }

    Surface(
        modifier = Modifier
            .padding(horizontal = 32.dp, vertical = 16.dp)
            .fillMaxWidth()
            .border(width = 1.dp, color = AudiophileGlassHighlight, shape = cardShape),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Header row: icon + label ──────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (isDsdLocked) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            if (isDsdLocked) {
                // ── DSD lock advisory message ─────────────────────────────────
                // The software volume path is bypassed by the native DSD engine;
                // instruct the user to use the physical DAC volume control instead.
                Text(
                    text = "Volume non controllabile in DSD nativo, usa il controllo volume del DAC",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                // ── dB secondary label ────────────────────────────────────────
                Text(
                    text = dbLabel ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Interactive slider (disabled in DSD-locked mode) ──────────────
            // In normal mode the user can drag this directly; key-press volume
            // steps also update it through the parent's volumePct state.
            // In DSD-locked mode the slider is visible but non-interactive so
            // the UI remains consistent while clearly communicating the locked state.
            Slider(
                value = volumePct.toFloat(),
                onValueChange = { if (!isDsdLocked) onVolumeChange(it.toInt()) },
                valueRange = 0f..100f,
                enabled = !isDsdLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                colors = if (isDsdLocked) {
                    // Fully greyed-out track to reinforce the locked / bypassed state.
                    SliderDefaults.colors(
                        disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    )
                } else {
                    SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
                    )
                },
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "Volume Overlay – 75% (light)", showBackground = true)
@Composable
private fun PreviewLight() {
    AudiophileMusicPlayerTheme(darkTheme = false) {
        UsbVolumeOverlay(isVisible = true, volumePct = 75, onVolumeChange = {})
    }
}

@Preview(name = "Volume Overlay – 75% (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewDark() {
    AudiophileMusicPlayerTheme(darkTheme = true) {
        UsbVolumeOverlay(isVisible = true, volumePct = 75, onVolumeChange = {})
    }
}

@Preview(name = "Volume Overlay – Muted (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewMuted() {
    AudiophileMusicPlayerTheme(darkTheme = true) {
        UsbVolumeOverlay(isVisible = true, volumePct = 0, onVolumeChange = {})
    }
}

@Preview(name = "Volume Overlay – 50% quartic (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewMid() {
    AudiophileMusicPlayerTheme(darkTheme = true) {
        UsbVolumeOverlay(isVisible = true, volumePct = 50, onVolumeChange = {})
    }
}

@Preview(name = "Volume Overlay – DSD Locked (light)", showBackground = true)
@Composable
private fun PreviewDsdLockedLight() {
    AudiophileMusicPlayerTheme(darkTheme = false) {
        UsbVolumeOverlay(isVisible = true, volumePct = 75, onVolumeChange = {}, isDsdLocked = true)
    }
}

@Preview(name = "Volume Overlay – DSD Locked (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewDsdLockedDark() {
    AudiophileMusicPlayerTheme(darkTheme = true) {
        UsbVolumeOverlay(isVisible = true, volumePct = 75, onVolumeChange = {}, isDsdLocked = true)
    }
}

@Preview(name = "Volume Overlay – System (light)", showBackground = true)
@Composable
private fun PreviewSystemLight() {
    AudiophileMusicPlayerTheme(darkTheme = false) {
        UsbVolumeOverlay(isVisible = true, volumePct = 60, onVolumeChange = {}, isUsbMode = false)
    }
}

@Preview(name = "Volume Overlay – System (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewSystemDark() {
    AudiophileMusicPlayerTheme(darkTheme = true) {
        UsbVolumeOverlay(isVisible = true, volumePct = 60, onVolumeChange = {}, isUsbMode = false)
    }
}

