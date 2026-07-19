package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Candidate USB audio streaming target selected for direct playback.
 *
 * @property usbInterface Streaming interface that must be claimed.
 * @property endpoint OUT endpoint used for queued transfers.
 * @property alternateSetting Alternate setting chosen for the interface, when available.
 * @property burstBytes Effective payload bytes per transfer burst.
 * @property capacityBytesPerSecond Estimated endpoint throughput budget.
 */
internal data class UsbStreamingTarget(
    val usbInterface: UsbInterface,
    val endpoint: UsbEndpoint,
    val alternateSetting: Int?,
    val burstBytes: Int,
    val capacityBytesPerSecond: Long,
)

