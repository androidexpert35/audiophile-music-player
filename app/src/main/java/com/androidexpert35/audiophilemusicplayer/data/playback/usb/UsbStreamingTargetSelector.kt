package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbStreamingTargetSelector.selectStreamingTarget
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import java.io.IOException
import kotlin.math.abs

/**
 * Selects and probes USB streaming targets for direct playback.
 *
 * Encapsulates endpoint discovery, throughput estimation, and `UsbRequest`
 * compatibility probing so [UsbAudioSink] can focus on transfer ownership.
 */
internal object UsbStreamingTargetSelector {
    private const val TAG = "UsbAudioSink"
    private const val USB_AUDIO_STREAMING_SUBCLASS = 0x02
    private const val ISO_PACKET_PAYLOAD_MASK = 0x07FF
    private const val ISO_TRANSACTIONS_SHIFT = 11
    private const val ISO_TRANSACTIONS_MASK = 0x3
    private const val USB_FRAME_RATE_HZ = 1_000L
    private const val USB_MICROFRAMES_PER_SECOND = 8_000L
    private const val MAX_HIGH_SPEED_INTERVAL_SHIFT = 15
    private const val DSD_CHANNEL_COUNT = 2
    private const val DSD_BITS_PER_BYTE = 8

    /**
     * Selects the best stream target for either PCM or native DSD output.
     *
     * @param usbManager System USB host manager.
     * @param device Target USB DAC.
     * @param outputProfile Requested PCM output shape, when using PCM transport.
     * @param nativeDsdRate Requested DSD rate, when using native DSD transport.
     * @return Selected [UsbStreamingTarget], or `null` when none is available.
     */
    fun selectStreamingTarget(
        usbManager: UsbManager,
        device: UsbDevice,
        outputProfile: UsbAudioOutputProfile?,
        nativeDsdRate: DsdRate?,
    ): UsbStreamingTarget? {
        val requiredBytesPerSecond = if (nativeDsdRate != null) {
            nativeDsdRate.sampleRateHz.toLong() * DSD_CHANNEL_COUNT / DSD_BITS_PER_BYTE
        } else {
            val pcmProfile = outputProfile ?: return null
            pcmProfile.sampleRateHz.toLong() * pcmProfile.bytesPerFrame.toLong()
        }

        val candidates = (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter(::isAudioStreamingInterface)
            .flatMap { usbInterface ->
                selectOutEndpoints(usbInterface).map { endpoint ->
                    UsbStreamingTarget(
                        usbInterface = usbInterface,
                        endpoint = endpoint,
                        alternateSetting = resolveAlternateSetting(usbInterface),
                        burstBytes = endpointBurstBytes(endpoint),
                        capacityBytesPerSecond = endpointCapacityBytesPerSecond(endpoint),
                    )
                }
            }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No USB audio streaming endpoints found for deviceId=${device.deviceId}")
            return null
        }

        val rankedCandidates = candidates.sortedWith(
            compareBy<UsbStreamingTarget>(
                { if (it.capacityBytesPerSecond >= requiredBytesPerSecond) 0 else 1 },
                { endpointPreferenceOrder(it.endpoint) },
                { if ((it.alternateSetting ?: 0) > 0) 0 else 1 },
                { abs(it.capacityBytesPerSecond - requiredBytesPerSecond) },
                // Prefer higher alt-setting number on equal capacity: UAC2 DACs typically order
                // alt settings by ascending bit depth, so SubslotSize=4/BitRes=24 (lower number)
                // and SubslotSize=4/BitRes=32 (higher number) have the same bytes/frame and thus
                // the same capacity.  Selecting the higher alt avoids sending left-aligned S32
                // to a DAC that expects right-aligned 24-bit data (BitRes=24) → noise.
                { -(it.alternateSetting ?: 0) },
                { it.capacityBytesPerSecond },
            )
        )

        val probeConnection = usbManager.openDevice(device)
        if (probeConnection == null) {
            Log.w(TAG, "UsbManager.openDevice returned null while probing endpoints for deviceId=${device.deviceId}")
            return null
        }

        val selected = try {
            rankedCandidates.firstOrNull { candidate ->
                supportsQueuedRequests(
                    connection = probeConnection,
                    device = device,
                    candidate = candidate,
                )
            }
        } finally {
            runCatching { probeConnection.close() }
        }

        if (selected == null) {
            throw IOException(
                "No UsbRequest-compatible USB streaming endpoint could be initialized for " +
                    (nativeDsdRate?.displayName ?: outputProfile.toString())
            )
        }

        Log.i(
            TAG,
            "Selected USB streaming target for deviceId=${device.deviceId} transport=${nativeDsdRate?.displayName ?: outputProfile} " +
                "requiredBytesPerSecond=${requiredBytesPerSecond} alt=${selected.alternateSetting ?: "unknown"} " +
                "interfaceId=${selected.usbInterface.id} endpointAddress=${selected.endpoint.address} " +
                "endpointType=${selected.endpoint.type} endpointInterval=${selected.endpoint.interval} " +
                "endpointBurstBytes=${selected.burstBytes} endpointCapacityBytesPerSecond=${selected.capacityBytesPerSecond}",
        )

        if (selected.capacityBytesPerSecond < requiredBytesPerSecond) {
            throw IOException(
                "USB DAC endpoint throughput ${selected.capacityBytesPerSecond} B/s is below required ${requiredBytesPerSecond} B/s " +
                    "for ${nativeDsdRate?.displayName ?: outputProfile.toString()}"
            )
        }

        return selected
    }

    /**
     * Selects the best stream target for the libusb isochronous engine.
     *
     * Identical to [selectStreamingTarget] in ranking logic but **skips** the
     * [UsbRequest] probe (`supportsQueuedRequests`). libusb submits ISO transfers
     * directly via `USBDEVFS_SUBMITURB`, so the kernel UAC driver's exclusive
     * interface ownership does not block endpoint selection here.
     *
     * @param device Target USB DAC.
     * @param outputProfile Requested PCM output shape, or `null` when using DSD.
     * @param nativeDsdRate Requested DSD rate, or `null` when using PCM.
     * @return Selected [UsbStreamingTarget], or `null` when no matching endpoint
     *   is found or the endpoint bandwidth is insufficient.
     */
    fun selectStreamingTargetForLibusb(
        device: UsbDevice,
        outputProfile: UsbAudioOutputProfile?,
        nativeDsdRate: DsdRate?,
    ): UsbStreamingTarget? {
        val requiredBytesPerSecond = if (nativeDsdRate != null) {
            nativeDsdRate.sampleRateHz.toLong() * DSD_CHANNEL_COUNT / DSD_BITS_PER_BYTE
        } else {
            val pcmProfile = outputProfile ?: return null
            pcmProfile.sampleRateHz.toLong() * pcmProfile.bytesPerFrame.toLong()
        }

        val transport = nativeDsdRate?.displayName ?: outputProfile.toString()
        Log.d(
            TAG,
            "selectStreamingTargetForLibusb: deviceId=${device.deviceId} transport=$transport " +
                "requiredBytesPerSecond=$requiredBytesPerSecond " +
                "totalInterfaces=${device.interfaceCount}"
        )

        // Enumerate ALL audio streaming interfaces for diagnostics.
        val allInterfaces = (0 until device.interfaceCount).map(device::getInterface)
        allInterfaces.forEach { iface ->
            Log.d(
                TAG,
                "  interface id=${iface.id} class=${iface.interfaceClass} " +
                    "subclass=${iface.interfaceSubclass} endpointCount=${iface.endpointCount} " +
                    "isAudioStreaming=${isAudioStreamingInterface(iface)}"
            )
            (0 until iface.endpointCount).map(iface::getEndpoint).forEach { ep ->
                Log.d(
                    TAG,
                    "    endpoint addr=0x${ep.address.toString(16)} type=${ep.type} " +
                        "dir=${ep.direction} maxPacket=${ep.maxPacketSize} interval=${ep.interval} " +
                        "capacity=${endpointCapacityBytesPerSecond(ep)}B/s"
                )
            }
        }

        val candidates = allInterfaces
            .filter(::isAudioStreamingInterface)
            .flatMap { usbInterface ->
                selectOutEndpoints(usbInterface).map { endpoint ->
                    UsbStreamingTarget(
                        usbInterface = usbInterface,
                        endpoint = endpoint,
                        alternateSetting = resolveAlternateSetting(usbInterface),
                        burstBytes = endpointBurstBytes(endpoint),
                        capacityBytesPerSecond = endpointCapacityBytesPerSecond(endpoint),
                    )
                }
            }

        if (candidates.isEmpty()) {
            Log.w(
                TAG,
                "libusb: NO USB audio streaming endpoints found for deviceId=${device.deviceId} " +
                    "transport=$transport — device may not expose AudioStreaming interfaces at this point in the scan"
            )
            return null
        }

        Log.d(TAG, "libusb: found ${candidates.size} audio-streaming OUT endpoint(s) for $transport:")
        candidates.forEach { c ->
            Log.d(
                TAG,
                "  candidate iface=${c.usbInterface.id} alt=${c.alternateSetting} " +
                    "ep=0x${c.endpoint.address.toString(16)} type=${c.endpoint.type} " +
                    "capacity=${c.capacityBytesPerSecond}B/s burst=${c.burstBytes} " +
                    "meetsRequired=${c.capacityBytesPerSecond >= requiredBytesPerSecond}"
            )
        }

        // Rank: capacity-meets-required first, then ISO > BULK, then has alt-setting,
        // then closest capacity match, then HIGHER alt-setting number on equal capacity.
        //
        // The final tie-breaker (‑alternateSetting) is critical for 24-bit PCM over USB:
        // SubslotSize=4/BitResolution=24 and SubslotSize=4/BitResolution=32 alt settings
        // both carry 8 bytes/frame and therefore have identical endpoint capacity.
        // UAC2 DACs order alt settings from lowest to highest bit depth, so the 24-bit
        // alt (lower number, right-aligned data expected) would otherwise be selected
        // ahead of the 32-bit alt (full 32-bit range).  FFmpeg outputs 24-bit content as
        // left-aligned S32 which is correct for BitResolution=32 but corrupt when the
        // DAC's BitResolution=24 mode reads only the lower 24 bits → loud noise.
        // Preferring the higher alt-setting number ensures the 32-bit alt is selected
        // so left-aligned S32 data is interpreted correctly by the DAC.
        val selected = candidates.sortedWith(
            compareBy<UsbStreamingTarget>(
                { if (it.capacityBytesPerSecond >= requiredBytesPerSecond) 0 else 1 },
                { endpointPreferenceOrder(it.endpoint) },
                { if ((it.alternateSetting ?: 0) > 0) 0 else 1 },
                { abs(it.capacityBytesPerSecond - requiredBytesPerSecond) },
                { -(it.alternateSetting ?: 0) },   // tie-breaker: prefer higher alt (= higher bit depth)
            )
        ).firstOrNull()

        if (selected == null) {
            Log.w(TAG, "libusb: no ranked endpoint for $transport deviceId=${device.deviceId}")
            return null
        }

        if (selected.capacityBytesPerSecond < requiredBytesPerSecond) {
            Log.w(
                TAG,
                "libusb: best endpoint capacity ${selected.capacityBytesPerSecond} B/s is below " +
                    "required $requiredBytesPerSecond B/s for $transport deviceId=${device.deviceId} — " +
                    "selecting anyway and letting the native ISO engine handle the mismatch"
            )
            // Do NOT return null here — return the best available endpoint.
            // The C++ DsdPlaybackManager will STALL and auto-switch to DoP if the
            // selected rate is wrong; returning null here would cause a premature
            // fallthrough to PCM tier-3 before even attempting the transfer.
        }

        Log.i(
            TAG,
            "libusb: SELECTED target deviceId=${device.deviceId} transport=$transport " +
                "iface=${selected.usbInterface.id} alt=${selected.alternateSetting ?: "unknown"} " +
                "ep=0x${selected.endpoint.address.toString(16)} type=${selected.endpoint.type} " +
                "capacity=${selected.capacityBytesPerSecond}B/s required=$requiredBytesPerSecond"
        )
        return selected
    }

    /**
     * Returns whether Android can initialize any queued streaming target.
     *
     * @param device Target USB DAC.
     * @param usbManager System USB host manager.
     * @return `true` when at least one output endpoint can be driven via [UsbRequest].
     */
    fun supportsQueuedStreaming(device: UsbDevice, usbManager: UsbManager): Boolean {
        val candidates = (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter(::isAudioStreamingInterface)
            .flatMap { usbInterface ->
                selectOutEndpoints(usbInterface).map { endpoint ->
                    UsbStreamingTarget(
                        usbInterface = usbInterface,
                        endpoint = endpoint,
                        alternateSetting = resolveAlternateSetting(usbInterface),
                        burstBytes = endpointBurstBytes(endpoint),
                        capacityBytesPerSecond = endpointCapacityBytesPerSecond(endpoint),
                    )
                }
            }
            .sortedWith(
                compareBy<UsbStreamingTarget>(
                    { endpointPreferenceOrder(it.endpoint) },
                    { if ((it.alternateSetting ?: 0) > 0) 0 else 1 },
                    { -it.capacityBytesPerSecond },
                )
            )
        if (candidates.isEmpty()) {
            return false
        }

        val probeConnection = usbManager.openDevice(device) ?: return false
        return try {
            candidates.any { candidate ->
                supportsQueuedRequests(
                    connection = probeConnection,
                    device = device,
                    candidate = candidate,
                )
            }
        } finally {
            runCatching { probeConnection.close() }
        }
    }

    /** Returns the effective burst size for [endpoint]. */
    fun endpointBurstBytes(endpoint: UsbEndpoint): Int {
        val rawMaxPacketSize = endpoint.maxPacketSize.coerceAtLeast(1)
        if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            return rawMaxPacketSize
        }

        val packetPayloadBytes = rawMaxPacketSize and ISO_PACKET_PAYLOAD_MASK
        val transactionsPerMicroframe = ((rawMaxPacketSize shr ISO_TRANSACTIONS_SHIFT) and ISO_TRANSACTIONS_MASK) + 1
        return (packetPayloadBytes * transactionsPerMicroframe).coerceAtLeast(packetPayloadBytes.coerceAtLeast(1))
    }

    /** Returns the estimated throughput budget for [endpoint]. */
    fun endpointCapacityBytesPerSecond(endpoint: UsbEndpoint): Long {
        val burstBytes = endpointBurstBytes(endpoint).toLong().coerceAtLeast(1L)
        if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            return burstBytes * USB_FRAME_RATE_HZ
        }

        val safeInterval = endpoint.interval.coerceAtLeast(1)
        val fullSpeedTransfersPerSecond = (USB_FRAME_RATE_HZ / safeInterval).coerceAtLeast(1)
        val fullSpeedBytesPerSecond = burstBytes * fullSpeedTransfersPerSecond

        val highSpeedMicroframesPerTransfer = 1 shl (safeInterval - 1).coerceAtMost(MAX_HIGH_SPEED_INTERVAL_SHIFT)
        val highSpeedTransfersPerSecond = (USB_MICROFRAMES_PER_SECOND / highSpeedMicroframesPerTransfer)
            .coerceAtLeast(1)
        val highSpeedBytesPerSecond = burstBytes * highSpeedTransfersPerSecond

        return maxOf(fullSpeedBytesPerSecond, highSpeedBytesPerSecond)
    }

    private fun isAudioStreamingInterface(usbInterface: UsbInterface): Boolean =
        usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
            usbInterface.interfaceSubclass == USB_AUDIO_STREAMING_SUBCLASS

    private fun selectOutEndpoints(usbInterface: UsbInterface): List<UsbEndpoint> =
        (0 until usbInterface.endpointCount)
            .map(usbInterface::getEndpoint)
            .filter { endpoint -> endpoint.direction == UsbConstants.USB_DIR_OUT }
            .sortedBy(::endpointPreferenceOrder)

    private fun supportsQueuedRequests(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        candidate: UsbStreamingTarget,
    ): Boolean {
        val claimed = runCatching {
            connection.claimInterface(candidate.usbInterface, true)
        }.onFailure { throwable ->
            Log.w(
                TAG,
                "Probe claimInterface failed for deviceId=${device.deviceId} interface=${candidate.usbInterface.id}",
                throwable,
            )
        }.getOrDefault(false)
        if (!claimed) {
            return false
        }

        try {
            val setInterfaceSucceeded = runCatching {
                connection.setInterface(candidate.usbInterface)
            }.onFailure { throwable ->
                Log.w(
                    TAG,
                    "Probe setInterface failed for deviceId=${device.deviceId} interface=${candidate.usbInterface.id} alt=${candidate.alternateSetting ?: "unknown"}",
                    throwable,
                )
            }.getOrDefault(false)
            if (!setInterfaceSucceeded && candidate.alternateSetting != null && candidate.alternateSetting != 0) {
                return false
            }

            val probeRequest = UsbRequest()
            return try {
                @Suppress("DEPRECATION")
                runCatching {
                    probeRequest.initialize(connection, candidate.endpoint)
                }.onFailure { throwable ->
                    Log.w(
                        TAG,
                        "Probe UsbRequest.initialize failed for deviceId=${device.deviceId} endpointAddress=${candidate.endpoint.address} type=${candidate.endpoint.type}",
                        throwable,
                    )
                }.getOrDefault(false)
            } finally {
                runCatching { probeRequest.close() }
            }
        } finally {
            runCatching { connection.releaseInterface(candidate.usbInterface) }
        }
    }

    /**
     * Ranking preference for USB endpoint types in audio streaming contexts.
     *
     * USB Audio class devices stream audio exclusively through **isochronous** (ISO)
     * endpoints. ISO transactions provide guaranteed bounded latency and a dedicated
     * USB bus allocation — the correct transport for real-time audio.
     *
     * Bulk endpoints are for file/data transfer (e.g. USB storage, firmware update)
     * and are never the correct choice for audio playback. Preferring bulk here was
     * a long-standing bug that caused the selector to rank a non-audio endpoint above
     * the ISO audio streaming endpoint, resulting in incorrect interface/alt-setting
     * selection and silent DSD transport failures.
     *
     * Order: ISO (0) → BULK (1) → other (2). Lower value = more preferred.
     */
    private fun endpointPreferenceOrder(endpoint: UsbEndpoint): Int = when (endpoint.type) {
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> 0   // Correct for USB Audio streaming
        UsbConstants.USB_ENDPOINT_XFER_BULK -> 1   // Data transfer — not for audio
        else -> 2
    }

    private fun resolveAlternateSetting(usbInterface: UsbInterface): Int? = runCatching {
        UsbInterface::class.java.getMethod("getAlternateSetting").invoke(usbInterface) as? Int
    }.getOrNull()
}

