package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.SueStatus
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens

/**
 * Settings card for the Sonic Upscaling Enhancer (SUE).
 *
 * The subtitle reflects the real runtime state: disabled, actively enhancing a
 * lossy source, or intentionally inactive because the current track is lossless.
 *
 * @param enabled Current persisted toggle value.
 * @param sueStatus Real-time SUE status emitted by telemetry, or `null` when no
 *   track is loaded or the standard engine is active.
 * @param onInfoClick Invoked when the user requests the technical explanation dialog.
 * @param onToggle Invoked with the new preference value when the user flips the switch.
 */
@Composable
fun SueEnhancerCard(
    enabled: Boolean,
    sueStatus: SueStatus?,
    onInfoClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val descriptionText = when {
        !enabled -> stringResource(R.string.settings_sue_disabled_description)
        sueStatus == null -> stringResource(R.string.settings_sue_enabled_idle_description)
        !sueStatus.isLossy -> stringResource(R.string.settings_sue_enabled_lossless_description)
        sueStatus.isActive -> stringResource(R.string.settings_sue_enabled_lossy_description)
        !sueStatus.isProvisioned -> stringResource(R.string.settings_sue_unavailable_description)
        sueStatus.intensityProfile.equals("BYPASS", ignoreCase = true) ->
            stringResource(R.string.settings_sue_enabled_bypass_description)
        else -> stringResource(R.string.settings_sue_enabled_idle_description)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_sue_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = descriptionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onInfoClick) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.cd_open_lossy_restorer_info),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Box(
                        modifier = Modifier.size(52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Switch(
                            checked = enabled,
                            onCheckedChange = onToggle,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = enabled && sueStatus != null,
                enter = fadeIn(animationSpec = tweenFloat(MotionTokens.DurationMedium)) +
                    expandVertically(animationSpec = tweenIntSize(MotionTokens.DurationMedium)),
                exit = fadeOut(animationSpec = tweenFloat(MotionTokens.DurationShort)) +
                    shrinkVertically(animationSpec = tweenIntSize(MotionTokens.DurationShort)),
            ) {
                if (sueStatus != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_sue_status_codec,
                                sueStatus.codecDisplayName.ifBlank { stringResource(R.string.common_unknown) },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_sue_status_profile,
                                sueStatus.intensityProfile.ifBlank { stringResource(R.string.common_unknown) },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sueStatus.failureReason?.takeIf { it.isNotBlank() }?.let { failureReason ->
                            Text(
                                text = failureReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tweenFloat(durationMs: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis = durationMs)

private fun tweenIntSize(durationMs: Int) =
    androidx.compose.animation.core.tween<IntSize>(durationMillis = durationMs)

private class SueCardPreviewProvider : PreviewParameterProvider<Pair<Boolean, SueStatus?>> {
    override val values = sequenceOf(
        true to SueStatus(
            isEnabled = true,
            isActive = true,
            isLossy = true,
            isProvisioned = true,
            intensityProfile = "MODERATE",
            codecDisplayName = "AAC",
        ),
        true to SueStatus(
            isEnabled = true,
            isActive = false,
            isLossy = false,
            isProvisioned = true,
            intensityProfile = "BYPASS",
            codecDisplayName = "FLAC",
        ),
        true to SueStatus(
            isEnabled = true,
            isActive = false,
            isLossy = true,
            isProvisioned = false,
            intensityProfile = "MODERATE",
            codecDisplayName = "MP3",
        ),
        false to null,
    )
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SueEnhancerCardPreview(
    @PreviewParameter(SueCardPreviewProvider::class) state: Pair<Boolean, SueStatus?>,
) {
    AudiophileMusicPlayerTheme {
        SueEnhancerCard(
            enabled = state.first,
            sueStatus = state.second,
            onInfoClick = {},
            onToggle = {},
        )
    }
}

