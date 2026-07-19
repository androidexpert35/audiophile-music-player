package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.TelemetryStatus
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary

/**
 * Key-value telemetry row with optional pill styling for the value.
 *
 * Labels use a 60 % white tint for secondary hierarchy on the glass background;
 * plain values use 92 % white. Badge values receive a tinted glass-fill behind
 * the accent border to reinforce the liquid-glass aesthetic.
 *
 * @param label Left-aligned parameter name.
 * @param value Right-aligned parameter value.
 * @param badge When `true`, the value is rendered as a tinted glass pill.
 * @param badgeColor Fill, border, and text tint applied when [badge] is enabled.
 */
@Composable
internal fun TelemetryValueRow(
    label: String,
    value: String,
    badge: Boolean = false,
    badgeColor: Color = AudiophilePrimary,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Secondary label — 60 % white matches the glass text hierarchy used
        // across the sheet (drag handle, supporting text, etc.).
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.60f),
        )
        if (badge) {
            // Tinted glass pill: translucent accent background + accent border
            // gives the badge a glowing, frosted look consistent with the sheet.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .background(
                        color = badgeColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(50),
                    )
                    .border(
                        width = 1.dp,
                        color = badgeColor.copy(alpha = 0.40f),
                        shape = RoundedCornerShape(50),
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = badgeColor,
                )
            }
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                // Primary value text at 92 % white — crisp but softer than pure white.
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

/**
 * Boolean telemetry status row with a glass-compatible status indicator.
 *
 * Active states render a glow-halo ring behind the core dot to create a
 * liquid-glass "lit from within" effect. Inactive and unavailable states
 * show a dim dot without the halo.
 *
 * @param label Left-aligned parameter name.
 * @param activeStatus Current activity level of the hardware feature.
 */
@Composable
internal fun TelemetryStatusRow(
    label: String,
    activeStatus: TelemetryStatus,
) {
    val isActive = activeStatus == TelemetryStatus.ACTIVE ||
        activeStatus == TelemetryStatus.ACTIVE_UNCONFIRMED

    // Active: teal primary. Inactive / unavailable: dim white dot.
    val statusColor = if (isActive) AudiophilePrimary else Color.White.copy(alpha = 0.30f)

    val statusText = when (activeStatus) {
        TelemetryStatus.ACTIVE -> stringResource(R.string.telemetry_active)
        TelemetryStatus.ACTIVE_UNCONFIRMED -> stringResource(R.string.telemetry_active_unconfirmed)
        TelemetryStatus.INACTIVE -> stringResource(R.string.telemetry_inactive)
        TelemetryStatus.UNAVAILABLE -> stringResource(R.string.telemetry_diagnostic_unavailable)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.60f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 14 dp container anchors both glow ring and core dot at the same centre
            // so the layout is stable regardless of whether the halo is visible.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(14.dp),
            ) {
                if (isActive) {
                    // Outer glow ring — semi-transparent halo mimics the backlit
                    // glow seen on active indicators in liquid-glass UI systems.
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.22f))
                    )
                }
                // Core dot — solid fill on top of the optional glow ring.
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (isActive) AudiophilePrimary else Color.White.copy(alpha = 0.40f),
            )
        }
    }
}

