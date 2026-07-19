package com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.UsbAudioStatus

/**
 * Card showing the currently connected USB DAC and permission state.
 *
 * @param status Current direct USB audio readiness snapshot.
 * @param isUsbPlaybackActive Whether playback telemetry confirms that the
 *   audiophile pipeline is actively sending audio to USB, whether processed or
 *   unprocessed.
 * @param activePlaybackDeviceName Runtime USB device name reported by
 *   telemetry, used when discovery state is stale.
 * @param isRefreshInProgress Whether a manual USB rescan is currently running.
 * @param onRefresh Invoked when the user wants to retry USB DAC discovery.
 * @param onRequestPermission Invoked when the user wants to show the USB host
 *   permission prompt for the active DAC.
 */
@Composable
fun UsbDeviceInfoCard(
    status: UsbAudioStatus,
    isUsbPlaybackActive: Boolean,
    activePlaybackDeviceName: String?,
    isRefreshInProgress: Boolean,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val title = activePlaybackDeviceName
        ?.takeIf { isUsbPlaybackActive }
        ?: status.activeDeviceName
        ?: stringResource(
            if (isUsbPlaybackActive) {
                R.string.settings_usb_device_active
            } else {
                R.string.settings_usb_device_none
            }
        )
    val supportingText = when {
        isUsbPlaybackActive -> stringResource(R.string.settings_usb_device_playback_active)
        status.isDirectOutputReady -> stringResource(R.string.settings_usb_device_ready)
        status.isDeviceConnected && status.isPermissionGranted && !status.isDirectUsbTransportSupported ->
            stringResource(R.string.settings_usb_device_not_supported)
        status.isDeviceConnected && !status.isPermissionGranted ->
            stringResource(R.string.settings_usb_device_permission_required)
        status.isDeviceConnected -> stringResource(R.string.settings_usb_device_connected)
        else -> stringResource(R.string.settings_usb_device_disconnected)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_usb_device_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!status.isDeviceConnected && !isUsbPlaybackActive) {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !isRefreshInProgress,
                    ) {
                        Text(
                            text = stringResource(
                                if (isRefreshInProgress) {
                                    R.string.settings_usb_refreshing
                                } else {
                                    R.string.settings_usb_refresh
                                }
                            )
                        )
                    }
                }

                if (status.isDeviceConnected && !status.isPermissionGranted) {
                    OutlinedButton(onClick = onRequestPermission) {
                        Text(text = stringResource(R.string.settings_usb_request_permission))
                    }
                }
            }
        }
    }
}
