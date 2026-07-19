package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Process
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.AudiophileOutputSink
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import java.io.IOException
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.max

/**
 * Direct USB PCM sink backed by the Android USB host API.
 *
 * The sink claims the DAC's audio-streaming interface, identifies an OUT
 * isochronous endpoint, and writes interleaved little-endian PCM using a
 * double-buffered [UsbRequest] queue. The owning playback engine calls [write]
 * from its dedicated `THREAD_PRIORITY_AUDIO` worker, so the USB transfer loop
 * never runs on the main thread.
 *
 * @property usbManager System USB host manager used to open the DAC.
 * @property device Permitted USB DAC target.
 * @property outputProfile Concrete PCM shape selected for the stream.
 */
class UsbAudioSink(
    usbManager: UsbManager,
    private val device: UsbDevice,
    private val outputProfile: UsbAudioOutputProfile? = null,
    private val nativeDsdRate: DsdRate? = null,
) : AudiophileOutputSink {

    init {
        require(outputProfile != null || nativeDsdRate != null) {
            "UsbAudioSink requires either a PCM output profile or a native DSD rate"
        }
    }

    private val streamingTarget: UsbStreamingTarget = UsbStreamingTargetSelector.selectStreamingTarget(
        usbManager = usbManager,
        device = device,
        outputProfile = outputProfile,
        nativeDsdRate = nativeDsdRate,
    )
        ?: throw IOException(
            "USB DAC does not expose an audio-streaming endpoint for ${transportDescription()}"
        )
    private val audioStreamingInterface: UsbInterface = streamingTarget.usbInterface
    private val endpoint: UsbEndpoint = streamingTarget.endpoint
    private val connection: UsbDeviceConnection = usbManager.openDevice(device)
        ?: throw IOException("UsbManager.openDevice returned null for deviceId=${device.deviceId}")

    private val requestBuffers: List<ByteBuffer>
    private val requests: List<UsbRequest>
    private val inFlightBytes = IdentityHashMap<UsbRequest, Int>()

    private var completedFrames: Long = 0L
    private var frameRemainder: Int = 0
    private var dsdByteRemainderNumerator: Int = 0
    private var closed: Boolean = false

    /** Diagnostic report describing the claimed USB output path. */
    override var pathReport: PipelinePathReport
        private set

    init {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            check(connection.claimInterface(audioStreamingInterface, true)) {
                "Failed to claim USB audio interface ${audioStreamingInterface.id}"
            }

            val selectedAlternateSetting = streamingTarget.alternateSetting
            val setInterfaceSucceeded = runCatching { connection.setInterface(audioStreamingInterface) }
                .onFailure { throwable ->
                    Log.w(
                        TAG,
                        "setInterface failed for deviceId=${device.deviceId} interface=${audioStreamingInterface.id} alt=${selectedAlternateSetting ?: "unknown"}",
                        throwable,
                    )
                }
                .getOrDefault(false)
            check(setInterfaceSucceeded || selectedAlternateSetting == null || selectedAlternateSetting == 0) {
                "Failed to switch USB audio interface ${audioStreamingInterface.id} to alt=${selectedAlternateSetting}"
            }

            val endpointBurstBytes = UsbStreamingTargetSelector.endpointBurstBytes(endpoint)
            val endpointCapacityBytesPerSecond = UsbStreamingTargetSelector.endpointCapacityBytesPerSecond(endpoint)
            val bufferBytes = max(computeMaxPacketBytes(), endpointBurstBytes)
            requests = List(REQUEST_RING_SIZE) { index ->
                UsbRequest().also { request ->
                    check(request.initialize(connection, endpoint)) {
                        "UsbRequest.initialize failed for requestIndex=$index"
                    }
                }
            }
            requestBuffers = List(REQUEST_RING_SIZE) {
                ByteBuffer.allocateDirect(bufferBytes)
            }

            val activeSampleRateHz = nativeDsdRate?.sampleRateHz ?: outputProfile?.sampleRateHz ?: 0
            val activeChannelCount = outputProfile?.channelCount ?: DSD_CHANNEL_COUNT
            pathReport = PipelinePathReport(
                usedDirectFlag = true,
                usedFloatFallback = false,
                encoding = 0,
                sampleRateHz = activeSampleRateHz,
                channelMask = activeChannelCount,
                bufferFrames = activeSampleRateHz,
                nativeOutputSampleRateHz = activeSampleRateHz,
                framesPerBuffer = 1,
                routedDeviceType = UsbConstants.USB_CLASS_AUDIO,
                routedDeviceName = device.productName ?: device.deviceName,
                audioSessionId = 0,
            )

            Log.w(
                TAG,
                "Direct USB sink is bypassing Android volume shaping; reduce DAC volume before playback starts. " +
                    "deviceId=${device.deviceId} transport=${transportDescription()} interfaceId=${audioStreamingInterface.id} " +
                    "alt=${selectedAlternateSetting ?: "unknown"} endpointAddress=${endpoint.address} " +
                    "endpointType=${endpoint.type} endpointMaxPacket=${endpoint.maxPacketSize} interval=${endpoint.interval} " +
                    "endpointBurstBytes=${endpointBurstBytes} endpointCapacityBytesPerSecond=${endpointCapacityBytesPerSecond}"
            )
        } catch (throwable: Throwable) {
            runCatching { connection.close() }
            throw throwable
        }
    }

    /** Starts the sink. USB transfers themselves occur when [write] is called. */
    override fun play() {
        checkOpen()
    }

    /** Pauses the sink by draining queued packets and waiting for completion. */
    override fun pause() {
        drainInFlightRequests()
    }

    /** Clears queued packets and resets the 1 ms packet-size accumulator. */
    override fun flush() {
        drainInFlightRequests()
        frameRemainder = 0
        dsdByteRemainderNumerator = 0
    }

    /** Stops the sink and drains any in-flight USB transfers. */
    override fun stop() {
        drainInFlightRequests()
    }

    /**
     * Writes [size] bytes of little-endian interleaved PCM to the DAC.
     *
     * @param buffer Direct source buffer containing decoded PCM data.
     * @param size Number of valid bytes available from the current buffer window.
     * @return Total bytes transferred successfully, or `-1` on a USB failure.
     */
    override fun write(buffer: ByteBuffer, size: Int): Int {
        checkOpen()
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        if (size <= 0) return 0

        val source = buffer.duplicate().apply {
            position(0)
            limit(size)
        }

        var written = 0
        while (written < size) {
            if (inFlightBytes.size >= requests.size) {
                waitForCompletedRequest() ?: return ERROR_WRITE_FAILED
            }

            val freeIndex = requests.indexOfFirst { request -> !inFlightBytes.containsKey(request) }
            if (freeIndex < 0) {
                return ERROR_WRITE_FAILED
            }

            val packetBytes = computePacketBytes(size - written)
            val chunkSize = minOf(packetBytes, size - written)
            if (chunkSize <= 0) {
                return written
            }

            val request = requests[freeIndex]
            val requestBuffer = requestBuffers[freeIndex]
            requestBuffer.clear()
            val sourceSlice = source.duplicate().apply {
                position(written)
                limit(written + chunkSize)
            }
            requestBuffer.put(sourceSlice)
            requestBuffer.flip()

            @Suppress("DEPRECATION")
            val queued = runCatching { request.queue(requestBuffer, chunkSize) }
                .onFailure { throwable ->
                    Log.e(TAG, "UsbRequest.queue failed for deviceId=${device.deviceId}", throwable)
                }
                .getOrDefault(false)
            if (!queued) {
                return if (written > 0) written else ERROR_WRITE_FAILED
            }
            inFlightBytes[request] = chunkSize
            written += chunkSize
        }

        while (inFlightBytes.isNotEmpty()) {
            waitForCompletedRequest() ?: return ERROR_WRITE_FAILED
        }
        return written
    }

    /** @return Completed playback head position in PCM frames. */
    override fun getPlaybackHeadPositionFrames(): Long = completedFrames

    /**
     * Enables native one-bit DSD packet accounting for [rate].
     *
     * The sink already selected an isochronous endpoint during construction; this
     * method switches the packet-size model from PCM frame sizing to native DSD
     * byte budgeting so the USB write loop matches the requested one-bit rate.
     *
     * @param rate DSD family that should be streamed natively.
     * @return `true` when the sink can carry [rate], otherwise `false`.
     */
    fun configureDsd(rate: DsdRate): Boolean {
        if (nativeDsdRate == null || nativeDsdRate != rate) {
            return false
        }
        dsdByteRemainderNumerator = 0
        completedFrames = 0L
        pathReport = pathReport.copy(sampleRateHz = rate.sampleRateHz, nativeOutputSampleRateHz = rate.sampleRateHz)
        return true
    }

    /** Releases the claimed interface, request queue, and USB device connection. */
    override fun close() {
        if (closed) return
        closed = true

        requests.forEach { request ->
            runCatching { request.cancel() }
            runCatching { request.close() }
        }
        runCatching { connection.releaseInterface(audioStreamingInterface) }
            .onFailure { throwable ->
                Log.w(TAG, "releaseInterface failed for deviceId=${device.deviceId}", throwable)
            }
        runCatching { connection.close() }
            .onFailure { throwable ->
                Log.w(TAG, "UsbDeviceConnection.close failed for deviceId=${device.deviceId}", throwable)
            }
    }

    private fun drainInFlightRequests() {
        while (!closed && inFlightBytes.isNotEmpty()) {
            if (waitForCompletedRequest() == null) {
                break
            }
        }
    }

    private fun waitForCompletedRequest(): UsbRequest? {
        checkOpen()
        val completed = runCatching { connection.requestWait() }
            .onFailure { throwable ->
                Log.e(TAG, "requestWait failed for deviceId=${device.deviceId}", throwable)
            }
            .getOrNull()
        if (completed == null) {
            Log.e(TAG, "requestWait returned null for deviceId=${device.deviceId}; the DAC may have been unplugged")
            return null
        }

        val transferredBytes = inFlightBytes.remove(completed) ?: 0
        completedFrames += if (nativeDsdRate != null) {
            // Native DSD packets are budgeted in whole bytes, but playback time is
            // tracked in one-bit source frames. Each stereo byte-pair carries eight
            // one-bit samples per channel, so bytes are converted back to source
            // frame units here before the engine computes position in milliseconds.
            transferredBytes.toLong() * DSD_BITS_PER_BYTE / DSD_CHANNEL_COUNT
        } else {
            transferredBytes / outputProfile!!.bytesPerFrame.toLong().coerceAtLeast(1L)
        }
        return completed
    }

    private fun computePacketBytes(remainingBytes: Int): Int {
        val packetBytes = if (nativeDsdRate != null) {
            val bytesTimesMs = dsdByteRemainderNumerator + (nativeDsdRate.sampleRateHz * DSD_CHANNEL_COUNT)
            val bytesThisPacket = (bytesTimesMs / (DSD_BITS_PER_BYTE * USB_FRAME_RATE_HZ)).coerceAtLeast(1)
            dsdByteRemainderNumerator = bytesTimesMs % (DSD_BITS_PER_BYTE * USB_FRAME_RATE_HZ)
            bytesThisPacket
        } else {
            val framesTimesMs = frameRemainder + outputProfile!!.sampleRateHz
            val framesThisPacket = (framesTimesMs / USB_FRAME_RATE_HZ).coerceAtLeast(1)
            frameRemainder = framesTimesMs % USB_FRAME_RATE_HZ
            framesThisPacket * outputProfile.bytesPerFrame
        }
        return minOf(packetBytes, remainingBytes)
    }

    private fun computeMaxPacketBytes(): Int {
        return if (nativeDsdRate != null) {
            ceil(nativeDsdRate.sampleRateHz * DSD_CHANNEL_COUNT / (DSD_BITS_PER_BYTE * USB_FRAME_RATE_HZ.toDouble())).toInt()
        } else {
            val framesPerMs = ceil(outputProfile!!.sampleRateHz / USB_FRAME_RATE_HZ.toDouble()).toInt()
            framesPerMs * outputProfile.bytesPerFrame
        }
    }

    private fun transportDescription(): String = nativeDsdRate?.displayName
        ?: outputProfile?.let { "${it.sampleRateHz} Hz / ${it.bitDepth}-bit / ${it.channelCount}ch" }
        ?: "unknown USB transport"

    private fun checkOpen() {
        check(!closed) { "USB audio sink is already closed" }
    }

    companion object {
        const val TAG = "UsbAudioSink"
        const val REQUEST_RING_SIZE = 2
        const val USB_FRAME_RATE_HZ = 1_000
        const val ERROR_WRITE_FAILED = -1

        private const val DSD_CHANNEL_COUNT = 2
        private const val DSD_BITS_PER_BYTE = 8

        /**
         * Returns whether Android can initialize at least one queued USB output
         * endpoint for the provided DAC.
         *
         * Delegates to [UsbStreamingTargetSelector] so routing decisions and
         * capability probing remain in a single location. The Settings screen and
         * playback engine use this signal to avoid repeatedly attempting a direct
         * USB path that is known to be unsupported on the current device / ROM.
         */
        internal fun supportsQueuedStreaming(device: UsbDevice, usbManager: UsbManager): Boolean =
            UsbStreamingTargetSelector.supportsQueuedStreaming(device, usbManager)
    }
}

