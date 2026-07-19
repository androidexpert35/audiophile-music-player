package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight

/**
 * Grouped telemetry section with a themed header and a liquid-glass card container.
 *
 * The card uses a pure white-tinted glass background so the blurred album-art
 * behind the sheet shows through, creating the frosted-glass depth effect. The
 * leading accent bar fades from full-opacity at the top to a subtle hint at the
 * bottom, giving each section a directional light feel consistent with glass
 * material design.
 *
 * @param icon Material icon shown before the section title.
 * @param title Section heading text.
 * @param accentColor Tint used for the icon, title, and gradient accent bar.
 * @param content Rows displayed inside the section body.
 */
@Composable
internal fun TelemetrySection(
    icon: ImageVector,
    title: String,
    accentColor: Color,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        SectionHeader(
            icon = icon,
            title = title,
            accentColor = accentColor,
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    // Standard glass-highlight border shared across all glass panels.
                    color = AudiophileGlassHighlight,
                    shape = RoundedCornerShape(20.dp),
                ),
            shape = RoundedCornerShape(20.dp),
            // Pure white-tinted glass: lets blurred album art bleed through for the
            // true frosted-glass depth effect instead of an opaque dark card.
            color = Color.White.copy(alpha = 0.08f),
            tonalElevation = 0.dp,
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Gradient accent bar fades from full-opacity at the top to a subtle
                // 25 % hint at the bottom, mimicking directional glass lighting.
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .padding(vertical = 10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.90f),
                                    accentColor.copy(alpha = 0.25f),
                                ),
                            )
                        )
                        .align(Alignment.CenterVertically)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Compact section header used by telemetry surfaces.
 *
 * @param modifier Optional [Modifier] applied after the default padding.
 * @param icon Material icon shown before the title.
 * @param title Section title.
 * @param accentColor Accent tint for the icon and title.
 */
@Composable
internal fun SectionHeader(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    accentColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 8.dp, start = 4.dp)
            .then(modifier)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = accentColor,
            fontWeight = FontWeight.Bold,
            letterSpacing = MaterialTheme.typography.labelLarge.letterSpacing,
        )
    }
}
