package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.media.AudioFormat
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.AudiophileOutputSink
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioTrackSink
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.DsdPipelineInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates configured [UsbAudioSink] instances for the current direct USB DAC.
 *
 * The factory validates the decoded source-native format against the selected
 * DAC's advertised capabilities before a direct USB sink is opened. When no
 * permitted DAC is available — or when direct USB negotiation fails — the
 * factory falls back to [AudioTrackSink] so the FFmpeg engine still runs locally.
 *
 * On Android 14+ (API 34) the factory also negotiates a bit-perfect
 * [android.media.AudioMixerAttributes] with [AudioManager] via
 * [UsbBitPerfectRouter] before every [AudioTrackSink] is constructed. This
 * instructs AudioFlinger to bypass its sample-rate converter for the media
 * stream so the decoded PCM reaches the USB DAC at exactly the source rate
 * and bit depth — even when the custom direct-USB path is unavailable.
 * The routing preference is cleared when the engine fully stops.
 */
@Singleton
class UsbAudioSinkFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val usbDeviceScanner: UsbDeviceScanner,
    private val usbManager: android.hardware.usb.UsbManager,
    private val usbBitPerfectRouter: UsbBitPerfectRouter,
    private val usbVolumeController: UsbVolumeController,
) {

    /** Returns the latest scanned USB-device snapshot used for output negotiation. */
    internal fun currentUsbDeviceState(): UsbAudioDeviceState = usbDeviceScanner.deviceState.value

    /**
     * Opens a new audiophile output sink for [format].
     *
     * @param format Decoded PCM format produced by the FFmpeg pipeline.
     * @param requireDirectOutput When `true`, the [AudioTrackSink] fallback will
     *   throw rather than silently degrade to the software mixer.  Pass `true`
     *   when delivering a DoP (DSD-over-PCM) bitstream that must not be routed
     *   through AudioFlinger's gain/resampling stage.
     * @return Ready-to-use sink targeting either a permitted USB DAC or the
     *   platform AudioTrack stack when direct USB playback is unavailable.
     */
    fun create(format: AudioFormatInfo, requireDirectOutput: Boolean = false): AudiophileOutputSink {
        val state = usbDeviceScanner.deviceState.value
        val device = usbDeviceScanner.getPermittedDevice()

        Log.i(
            TAG,
            "create: sampleRate=${format.sampleRateHz}Hz/${format.sourceBitDepth}b " +
                "requireDirectOutput=$requireDirectOutput " +
                "connectedDevice=${state.connectedDevice?.deviceId} " +
                "isLibusbReady=${state.isLibusbReady} " +
                "isDirectUsbReady=${state.isDirectUsbReady} " +
                "isPermissionGranted=${state.isPermissionGranted}"
        )
        if (state.isLibusbReady) {
            // create() should not be reached for USB output — callers should route
            // through createLibusbPcmSink (PCM) or createLibusbDsdSink (DSD).
            // Reaching here for a libusb-ready device means a code path is not
            // correctly gated at the engine level.
            Log.w(
                TAG,
                "create(): called while isLibusbReady=true — this should only happen for " +
                    "non-USB output (BT/speaker). If USB DAC is the target this is a routing bug."
            )
        }

        if (device == null) {
            // Distinguish between "nothing connected" and "connected but no permission".
            if (state.connectedDevice != null && !state.isPermissionGranted) {
                // A USB DAC is physically connected but the app has not yet been
                // granted host-level access via UsbManager.  Re-request the permission
                // dialog so the user can grant it.  On some ROMs the attach-time
                // request is suppressed or the dialog is auto-dismissed; requesting
                // here — at the start of actual playback — gives the user another
                // clear opportunity to approve access.
                Log.w(
                    TAG,
                    "USB DAC connected (deviceId=${state.connectedDevice.deviceId} " +
                        "name=${state.connectedDevice.deviceName}) but app-level USB host " +
                        "permission not yet granted — re-requesting permission dialog"
                )
                usbDeviceScanner.requestPermission(state.connectedDevice.deviceId)
            } else {
                Log.d(TAG, "No USB DAC connected; falling back to platform AudioTrack sink")
            }
            Log.i(
                PATH_TAG,
                "Audiophile sink selection: AudioTrack fallback " +
                    "(usbReady=false hasPermission=${state.isPermissionGranted} " +
                    "connectedDevice=${state.connectedDevice?.deviceName})"
            )
            return createPlatformFallback(format, requireDirectOutput)
        }

        if (!state.isDirectUsbReady) {
            // Permission was granted, but the transport layer could not be opened.
            // Most likely cause on OEM ROMs (OPPO/ColorOS, Xiaomi MIUI, etc.):
            // the kernel's UAC2 class driver has claimed the USB audio interface
            // exclusively, so UsbManager.openDevice() returns null or
            // UsbDeviceConnection.claimInterface() fails even with force=true.
            // On these ROMs native DSD and direct-USB PCM are not possible;
            // the platform AudioTrack path (which routes through the kernel driver)
            // is the only viable output.
            Log.w(
                TAG,
                "USB DAC permitted (deviceId=${device.deviceId}) but direct USB transport " +
                    "not available (isDirectUsbTransportSupported=${state.isDirectUsbTransportSupported}). " +
                    "Likely cause: OEM kernel UAC driver owns the interface exclusively. " +
                    "Falling back to platform AudioTrack sink."
            )
            Log.i(
                PATH_TAG,
                "Audiophile sink selection: AudioTrack fallback " +
                    "(usbReady=false transport=${state.isDirectUsbTransportSupported} " +
                    "device=${device.deviceId})"
            )
            return createPlatformFallback(format, requireDirectOutput)
        }

        val sourceProfile = UsbAudioOutputProfile(
            sampleRateHz = format.sampleRateHz,
            bitDepth = format.sourceBitDepth,
            channelCount = format.channelCount,
        )
        val selectedProfile = resolveDirectUsbProfile(
            sourceProfile = sourceProfile,
            supportedProfiles = state.supportedProfiles,
        )
        if (selectedProfile == null) {
            Log.w(
                TAG,
                "Direct USB profile negotiation is unavailable for deviceId=${device.deviceId} source=$sourceProfile supported=${state.supportedProfiles}; falling back to AudioTrack"
            )
            Log.w(
                PATH_TAG,
                "Audiophile sink selection: direct USB unsupported for source=$sourceProfile; falling back to AudioTrack"
            )
            return createPlatformFallback(format, requireDirectOutput)
        }
        Log.d(
            TAG,
            "Opening direct USB sink for deviceId=${device.deviceId} profile=$selectedProfile"
        )
        Log.i(
            PATH_TAG,
            "Audiophile sink selection: direct USB deviceId=${device.deviceId} profile=$selectedProfile"
        )
        return runCatching {
            UsbAudioSink(
                usbManager = usbManager,
                device = device,
                outputProfile = selectedProfile,
            )
        }.onFailure { throwable ->
            Log.w(
                TAG,
                "Direct USB sink setup failed for deviceId=${device.deviceId}; falling back to AudioTrack",
                throwable
            )
            Log.w(PATH_TAG, "Audiophile sink selection: USB failed, falling back to AudioTrack", throwable)
        }.getOrElse {
            createPlatformFallback(format, requireDirectOutput)
        }
    }

    /**
     * Builds the platform-managed [AudioTrackSink] fallback used when direct USB
     * output is unavailable or proves unstable at runtime.
     *
     * On Android 14+ (API 34), [UsbBitPerfectRouter.routeAudio] is called before
     * the [AudioTrackSink] is constructed so that `AudioManager` can configure a
     * bit-perfect [android.media.AudioMixerAttributes] profile before the
     * `AudioTrack` opens its hardware stream. When the DAC does not support the
     * exact format, or the API is unavailable, the call is a no-op and the sink
     * falls through to Android's standard mixer path.
     *
     * The standard Android routing stack can still target the connected USB DAC,
     * so this sink keeps the FFmpeg-based audiophile engine audible even when the
     * custom USB host path cannot sustain playback on a particular device.
     *
     * @param format Decoded PCM format produced by the FFmpeg pipeline.
     * @param requireDirectOutput When `true`, the [AudioTrackSink] will throw
     *   instead of falling through to the software mixer. Pass `true` for DoP
     *   (DSD-over-PCM) transports where mixer processing would corrupt the signal.
     * @return Platform-backed sink that delegates routing to Android audio.
     */
    internal fun createPlatformFallback(
        format: AudioFormatInfo,
        requireDirectOutput: Boolean = false,
    ): AudioTrackSink {
        // Negotiate bit-perfect mixer attributes with AudioFlinger before the
        // AudioTrack is opened. On API < 34 this is a safe no-op. On failure or
        // format mismatch the sink is still created — playback continues through
        // the standard resampling mixer path.
        val routingResult = usbBitPerfectRouter.routeAudio(
            sampleRate = format.sampleRateHz,
            bitDepth = format.sourceBitDepth,
            channelCount = format.channelCount,
        )
        Log.d(TAG, "Platform fallback bit-perfect routing: $routingResult")
        return AudioTrackSink(
            context = context,
            format = format,
            requireDirectOutput = requireDirectOutput,
        )
    }

    /**
     * Clears any active bit-perfect [android.media.AudioMixerAttributes]
     * preference for the connected USB DAC, returning mixer control to the
     * OS default policy.
     *
     * Must be called when the audiophile engine fully stops so AudioFlinger
     * is not left holding a stale bit-perfect preference after playback ends.
     * Safe to call when no preference is active or no DAC is connected.
     */
    internal fun releasePlatformBitPerfectRouting() = usbBitPerfectRouter.clearRouting()

    /**
     * Opens the direct USB sink in native DSD mode for [dsdRate].
     *
     * @param dsdRate One-bit DSD family to configure on the active DAC.
     * @return Direct [UsbAudioSink] already configured for native DSD streaming.
     * @throws IllegalStateException When no permitted direct-USB DAC is available.
     */
    internal fun createNativeDsdSink(dsdRate: DsdRate): UsbAudioSink {
        val state = usbDeviceScanner.deviceState.value
        val device = usbDeviceScanner.getPermittedDevice()
        check(device != null && state.isDirectUsbReady) {
            when {
                state.connectedDevice == null ->
                    "No USB DAC connected — native DSD output requires a direct USB connection"
                !state.isPermissionGranted ->
                    "USB DAC connected (id=${state.connectedDevice.deviceId}) but app-level " +
                        "USB host permission not granted — native DSD unavailable"
                !state.isDirectUsbTransportSupported ->
                    "USB DAC permitted (id=${state.connectedDevice.deviceId}) but direct USB " +
                        "transport not available — OEM kernel driver may have exclusive access; " +
                        "native DSD unavailable on this device"
                else ->
                    "USB DAC not ready for native DSD (state=$state)"
            }
        }
        return UsbAudioSink(
            usbManager = usbManager,
            device = device,
            nativeDsdRate = dsdRate,
        ).also { sink ->
            check(sink.configureDsd(dsdRate)) {
                "USB DAC rejected native DSD configuration for ${dsdRate.displayName}"
            }
        }
    }

    /**
     * Opens a [LibusbDsdAudioSink] that routes native DSD through the libusb
     * isochronous engine, bypassing Android's USB host `UsbRequest` stack.
     *
     * The full libusb initialisation sequence is executed synchronously:
     * init context → claim interface → allocate transfer pool → start DSD stream.
     * The native FFmpeg decoder is opened last and wired to the ring buffer by
     * [LibusbDsdAudioSink.play].
     *
     * Resources are released in the correct order by [LibusbDsdAudioSink.close].
     *
     * @param dsdRate One-bit DSD family of the source file.
     * @param path    Filesystem path used to open the native FFmpeg decoder.
     * @return Initialised [LibusbDsdAudioSink] ready for [LibusbDsdAudioSink.play].
     * @throws IllegalStateException When no permitted direct-USB DAC is available.
     * @throws IllegalStateException When any step of the libusb sequence fails.
     */
    internal fun createLibusbDsdSink(dsdRate: DsdRate, path: String): LibusbDsdAudioSink {
        val state = usbDeviceScanner.deviceState.value
        val device = usbDeviceScanner.getPermittedDevice()

        Log.i(
            TAG,
            "createLibusbDsdSink: dsdRate=${dsdRate.displayName} " +
                "connectedDevice=${state.connectedDevice?.deviceId} " +
                "isPermissionGranted=${state.isPermissionGranted} " +
                "isLibusbReady=${state.isLibusbReady} " +
                "isDirectUsbReady=${state.isDirectUsbReady} (ignored for libusb) " +
                "supportedDsdRates=${state.supportedDsdRates.map { it.displayName }} " +
                "supportedProfiles=${state.supportedProfiles.size} " +
                "path=$path"
        )

        // libusb only needs an open FD from UsbManager.openDevice() — no kernel
        // interface-claim required. isLibusbReady gates on permission only.
        check(device != null && state.isLibusbReady) {
            when {
                state.connectedDevice == null ->
                    "No USB DAC connected — libusb DSD requires a direct USB connection"
                !state.isPermissionGranted ->
                    "USB permission not granted for deviceId=${state.connectedDevice.deviceId}"
                else -> "USB DAC not ready for libusb DSD (state=$state)"
            }
        }

        // ── Resolve the native-DSD streaming endpoint ──────────────────────────
        // Use the libusb-safe selector that skips UsbRequest probing (probing
        // would fail on OEM-kernel-locked devices where claimInterface returns false).
        Log.d(TAG, "createLibusbDsdSink: calling selectStreamingTargetForLibusb for DSD ${dsdRate.displayName}")
        val dsdTarget = UsbStreamingTargetSelector.selectStreamingTargetForLibusb(
            device = device,
            outputProfile = null,
            nativeDsdRate = dsdRate,
        )
        Log.i(
            TAG,
            "createLibusbDsdSink: dsdTarget=${dsdTarget?.let { "iface=${it.usbInterface.id} alt=${it.alternateSetting} ep=0x${it.endpoint.address.toString(16)} cap=${it.capacityBytesPerSecond}B/s" } ?: "null"}"
        )

        // ── Resolve the DoP fallback PCM endpoint ──────────────────────────────
        // DsdPlaybackManager uses these alt-setting indices when it switches the
        // context from Native DSD to DoP after a STALL within the 200 ms window.
        val dopPcmRate = when (dsdRate) {
            DsdRate.DSD64  -> DOP_PCM_RATE_DSD64
            DsdRate.DSD128 -> DOP_PCM_RATE_DSD128
            DsdRate.DSD256 -> DOP_PCM_RATE_DSD256
        }
        Log.d(TAG, "createLibusbDsdSink: calling selectStreamingTargetForLibusb for DoP PCM ${dopPcmRate}Hz")
        val dopPcmTarget = UsbStreamingTargetSelector.selectStreamingTargetForLibusb(
            device = device,
            outputProfile = UsbAudioOutputProfile(
                sampleRateHz = dopPcmRate,
                bitDepth = DOP_PCM_BIT_DEPTH,
                channelCount = DSD_STEREO_CHANNELS,
            ),
            nativeDsdRate = null,
        )
        Log.i(
            TAG,
            "createLibusbDsdSink: dopPcmTarget=${dopPcmTarget?.let { "iface=${it.usbInterface.id} alt=${it.alternateSetting} ep=0x${it.endpoint.address.toString(16)} cap=${it.capacityBytesPerSecond}B/s" } ?: "null"}"
        )

        // DoP-only DACs start directly on their PCM endpoint. Deliberately
        // provoking a Native-DSD STALL would program the wrong carrier clock
        // and make startup depend on an error path.
        val startInDop = dsdTarget == null
        val effectiveDsdTarget = dsdTarget ?: dopPcmTarget
            ?: error(
                "No USB audio streaming endpoint found for DSD ${dsdRate.displayName} " +
                    "or DoP PCM on deviceId=${device.deviceId} — " +
                    "device may not expose AudioStreaming interfaces or descriptor parsing failed"
            )

        val dsdInterfaceNumber = effectiveDsdTarget.usbInterface.id
        // Alternate setting 0 has zero ISO bandwidth — enforce ≥ 1 for the ISO endpoint.
        val dsdAltSetting = (effectiveDsdTarget.alternateSetting ?: 1).coerceAtLeast(1)
        val dsdEndpointAddress = effectiveDsdTarget.endpoint.address

        val pcmInterfaceNumber = dopPcmTarget?.usbInterface?.id ?: dsdInterfaceNumber
        val pcmAltSetting = (dopPcmTarget?.alternateSetting ?: dsdAltSetting).coerceAtLeast(1)
        val pcmEndpointAddress = dopPcmTarget?.endpoint?.address ?: 0
        val pcmBandwidth = dopPcmTarget?.endpoint?.maxPacketSize ?: 0
        val supportsDopFallback = dsdTarget != null && dopPcmTarget != null

        // ── DSD_U32LE USB framing (32-bit subslot, matching native_dsd_formatter.cpp) ──
        // The native DSD formatter packs 32 DSD bits (4 bytes) per channel per USB frame.
        // UAC2 clock = dsdSampleRateHz / 32  (number of DSD_U32 frames per second).
        //
        //   DSD64  → 2,822,400 / 32 =  88,200 Hz clock, 8 B/frame → 705,600 B/s ✓
        //   DSD128 → 5,644,800 / 32 = 176,400 Hz clock, 8 B/frame → 1,411,200 B/s ✓
        //
        // DAC display check (bSubslotSize=4 DAC, e.g. FiiO KA5):
        //   88,200 Hz × 32 bits/subslot = 2,822,400 bit/s/ch = DSD64 ✓
        //   Old clock (352,800 Hz): 352,800 × 32 = 11,289,600 bit/s/ch = DSD256 ✗
        val dsdFrameRateHz = if (startInDop) {
            dopPcmRate
        } else {
            dsdRate.sampleRateHz / DSD_U32_SUBSLOT_BITS
        }
        val dsdBytesPerFrame = DSD_U32_BYTES_PER_CHANNEL * DSD_STEREO_CHANNELS // 4 × 2 = 8
        // bytes/µframe: ceil(frameRateHz / 8000) × bytesPerFrame
        val bytesPerUframe =
            ((dsdFrameRateHz + USB_HS_UFRAMES_PER_SEC - 1) / USB_HS_UFRAMES_PER_SEC) *
                dsdBytesPerFrame

        Log.i(
            TAG,
            "createLibusbDsdSink: resolved endpoints — " +
                "dsdInterface=$dsdInterfaceNumber alt=$dsdAltSetting ep=0x${dsdEndpointAddress.toString(16)} " +
                "dopInterface=$pcmInterfaceNumber dopAlt=$pcmAltSetting " +
                "dopEp=0x${pcmEndpointAddress.toString(16)} startInDop=$startInDop " +
                "dsdFrameRateHz=$dsdFrameRateHz dsdBytesPerFrame=$dsdBytesPerFrame bytesPerUframe=$bytesPerUframe"
        )

        // ── Open Android USB connection to obtain the FD for libusb ───────────
        // The connection MUST remain open for the lifetime of the sink.
        Log.d(TAG, "createLibusbDsdSink: calling UsbManager.openDevice for deviceId=${device.deviceId}")
        val connection = usbManager.openDevice(device)
            ?: error("UsbManager.openDevice returned null for deviceId=${device.deviceId} — check USB permission and cable")

        Log.d(TAG, "createLibusbDsdSink: UsbManager.openDevice OK fd=${connection.fileDescriptor}")

        // The LibusbDsdAudioSink constructor owns the complete 6-step USB init
        // sequence.  The factory is only responsible for opening the FFmpeg decoder
        // (requires the file path) and building the PipelinePathReport before
        // handing all parameters to the sink constructor.
        var decoderHandle = 0L
        return try {

            // ── Open native FFmpeg decoder (DSD mode: forcePcm = false) ────────
            Log.d(TAG, "createLibusbDsdSink: nativeOpenDecoderForUsb(path=$path forcePcm=false)")
            decoderHandle = EngineSwapBridge.nativeOpenDecoderForUsb(path, false)
            Log.i(TAG, "createLibusbDsdSink: nativeOpenDecoderForUsb → decoderHandle=$decoderHandle")
            check(decoderHandle != 0L) {
                "nativeOpenDecoderForUsb failed for path=$path — check file exists and is a valid DSD file"
            }

            val pathReport = PipelinePathReport(
                usedDirectFlag = true,
                usedFloatFallback = false,
                encoding = 0,
                sampleRateHz = if (startInDop) dopPcmRate else dsdRate.sampleRateHz,
                channelMask = DSD_STEREO_CHANNELS,
                bufferFrames = 0,
                nativeOutputSampleRateHz = dsdFrameRateHz,     // UAC2 clock rate (e.g. 88,200 Hz for DSD64)
                framesPerBuffer = ISO_PACKETS_PER_TRANSFER,
                routedDeviceType = UsbConstants.USB_CLASS_AUDIO,
                routedDeviceName = device.productName ?: device.deviceName,
                audioSessionId = 0,
                dsdPipelineInfo = DsdPipelineInfo(
                    sourceFormat = dsdRate.displayName,
                    sourceDsdRate = dsdRate,
                    outputMode = if (startInDop) {
                        DsdOutputMode.DoP(dopPcmRate)
                    } else {
                        DsdOutputMode.NativeDsd(dsdRate)
                    },
                    effectiveDsdRate = dsdRate,
                    dopPcmRate = dopPcmRate.takeIf { startInDop },
                ),
            )

            Log.i(
                PATH_TAG,
                "Audiophile sink selection: libusb native DSD ${dsdRate.displayName} " +
                    "deviceId=${device.deviceId} dsdFrameRateHz=$dsdFrameRateHz " +
                    "dsdBytesPerFrame=$dsdBytesPerFrame decoderHandle=$decoderHandle"
            )

            // The sink's init{} block performs the full USB setup sequence:
            //   1. nativeInitWithFileDescriptor
            //   2. nativeClaimInterface
            //   3. nativeSetUac2ClockSampleRate  ← CRITICAL: program DAC PLL before alt-set
            //   4. nativeActivateAltSetting       ← CRITICAL: open ISO endpoint
            //   5. nativeAllocateTransferPool
            //   6. nativeStartDsdPlayback
            // Any failure in init{} is caught below, and the sink rolls back its own
            // libusb context before re-throwing — only the decoder needs cleanup here.
            LibusbDsdAudioSink(
                dsdInterfaceNumber = dsdInterfaceNumber,
                dsdAltSetting = dsdAltSetting,
                dsdEndpointAddress = dsdEndpointAddress,
                bytesPerUframe = bytesPerUframe,
                pcmInterfaceNumber = pcmInterfaceNumber,
                pcmAltSetting = pcmAltSetting,
                pcmEndpointAddress = pcmEndpointAddress,
                pcmBandwidth = pcmBandwidth,
                startInDop = startInDop,
                supportsDopFallback = supportsDopFallback,
                decoderHandle = decoderHandle,
                dsdRate = dsdRate,
                initialVolume = usbVolumeController.volumePct.value / 100f,
                connection = connection,
                pathReport = pathReport,
            )
        } catch (e: Exception) {
            // The sink's init{} rolls back the libusb context (driverHandle) on failure.
            // The factory is only responsible for releasing the decoder and connection.
            Log.e(TAG, "createLibusbDsdSink FAILED — decoderHandle=$decoderHandle Error: ${e.message}", e)
            if (decoderHandle != 0L) EngineSwapBridge.nativeReleaseUsbDecoder(decoderHandle)
            runCatching { connection.close() }
            Log.e(TAG, "createLibusbDsdSink: cleanup complete for dsdRate=${dsdRate.displayName}", e)
            throw e
        }
    }

    /**
     * Opens a [LibusbPcmAudioSink] that routes PCM audio through the libusb
     * isochronous engine.
     *
     * Handles both native-PCM sources and DSD files decimated to 88 200 Hz
     * float32 by the lavfi+soxr pipeline (`forcePcm=true` is passed to the
     * native decoder in both cases).
     *
     * The native pump thread reads decoded PCM from the native decoder and
     * pushes it to the SPSC ring buffer consumed by the ISO OUT transfers.
     * The Kotlin write loop is bypassed (SUE cannot be applied on this path).
     *
     * ### DSD-to-PCM transition: cold-boot reset sequence
     *
     * After Native DSD playback the XMOS USB receiver chip is stuck in DSD mode
     * (alternate setting 4).  Any PCM clock `SET_CUR` issued while the chip is in
     * this state is silently rejected, causing **"FSR ERROR"** and silent output.
     *
     * This method enforces the following strict init order to clear that condition:
     *
     * ```
     * nativeClaimInterface(iface, targetAlt)      // 1. claim streaming interface
     * nativeActivateAltSetting(iface, 0)           // 2. Alt-0: zero-bandwidth idle — clears DSD lock
     * Thread.sleep(20)                             // 3. 20 ms for XMOS PLL to reach idle
     * nativeSetUac2ClockSampleRate(pcmRate)        // 4. program PCM clock (safe now)
     * nativeActivateAltSetting(iface, targetAlt)   // 5. activate PCM ISO endpoint
     * nativeAllocateTransferPool(...)              // 6. allocate ISO transfer pool
     * nativeStartPlayback(...)                     // 7. start ring + event thread
     * ```
     *
     * The Alt-0 drop step is **non-fatal**: a DAC already at Alt 0 (first boot or
     * post-PCM session) simply accepts a harmless redundant `SET_INTERFACE`.
     *
     * The [EngineSwapBridge.nativeOpenDecoderForUsb] call uses `forcePcm=true` so
     * the native decoder reports `is_dsd=false` — this re-enables the PCM
     * 32-bit padding and software volume scalar inside [DecoderToRingBridge].
     *
     * @param format PCM format that the native decoder will produce.
     * @param path   Filesystem path used to open the native FFmpeg decoder.
     * @return Initialised [LibusbPcmAudioSink] ready for [LibusbPcmAudioSink.play].
     * @throws IllegalStateException When no permitted libusb-ready USB DAC is available
     *   or any step of the init sequence fails.
     */
    internal fun createLibusbPcmSink(format: AudioFormatInfo, path: String): LibusbPcmAudioSink {
        val state = usbDeviceScanner.deviceState.value
        val device = usbDeviceScanner.getPermittedDevice()
        check(device != null && state.isLibusbReady) {
            when {
                state.connectedDevice == null ->
                    "No USB DAC connected — libusb PCM requires a direct USB connection"
                !state.isPermissionGranted ->
                    "USB permission not granted for deviceId=${state.connectedDevice.deviceId}"
                else -> "USB DAC not ready for libusb PCM (state=$state)"
            }
        }

        // Select the streaming endpoint for this PCM format (no UsbRequest probe).
        //
        // ── Universal 32-bit subslot alignment ─────────────────────────────────
        // ALL PCM output to the ring buffer is normalised to 32-bit (4-byte)
        // subslots, regardless of the original source bit depth.  This matches the
        // primary alternate settings on high-end UAC2 DACs (e.g. FiiO KA5, iFi DACs)
        // which advertise bSubslotSize=4 on their ISO endpoints.
        //
        // Sending a narrower sub-format (16-bit = 4 B/stereo frame) into a 32-bit
        // endpoint (8 B/stereo frame) shifts every sample boundary, producing severe
        // distortion and pitch alteration.
        //
        //  • 16-bit (S16LE, bytesPerSample=2):
        //      The C++ pump expands each sample to an MSB-aligned S32LE slot
        //      (bits 31:16 = audio, bits 15:0 = 0x0000) via
        //      PcmWireFormatter. Wire = 4 bytes/sample.
        //
        //  • 24-bit (FFmpeg S32LE, bytesPerSample=4):
        //      Already a 32-bit container (audio in bits 31:8, 0x00 in bits 7:0).
        //      No conversion in the pump.  Wire = 4 bytes/sample.
        //
        //  • float32 / native S32 (bytesPerSample=4):
        //      Already 4 bytes/sample.  Pump passthrough.
        //
        // profileBytesPerSample = 4 always so the ISO pool and ring drain rate
        // stay in sync with the pump's actual output rate.
        // effectiveBitDepth = 32 directs nativeSelectBestEndpointForFormat to the
        // four-pass cascade pass that prefers bSubslotSize=4 alt settings.
        val profileBytesPerSample = 4  // Universal 32-bit wire format
        val effectiveBitDepth     = 32 // Request bSubslotSize=4 from the descriptor scanner

        Log.i(
            TAG,
            "createLibusbPcmSink: format resolution — " +
                "sourceBitDepth=${format.sourceBitDepth}  ffmpegBytesPerSample=${format.bytesPerSample}  " +
                "universalPadding=32-bit  effectiveBitDepth=$effectiveBitDepth  " +
                "profileBytesPerSample=$profileBytesPerSample"
        )

        val pcmProfile = UsbAudioOutputProfile(
            sampleRateHz = format.sampleRateHz,
            bitDepth = effectiveBitDepth,
            channelCount = format.channelCount,
        )

        // Open the connection here so the file descriptor is available for
        // nativeInitWithFileDescriptor → nativeSelectBestEndpointForFormat.
        // Endpoint selection is deliberately deferred into the try block so
        // cleanup on any failure is handled by the single catch at the bottom.
        val connection = usbManager.openDevice(device)
            ?: error("UsbManager.openDevice returned null for deviceId=${device.deviceId}")

        var driverHandle = 0L
        var decoderHandle = 0L
        return try {
            // ── Step 1: wrap the FD into a libusb device handle ─────────────────
            driverHandle = UsbAudioBridge.nativeInitWithFileDescriptor(connection.fileDescriptor)
            Log.i(
                TAG,
                "createLibusbPcmSink: nativeInitWithFileDescriptor → " +
                    "driverHandle=0x${driverHandle.toULong().toString(16)} " +
                    "isValid=${UsbAudioBridge.isValidHandle(driverHandle)}"
            )
            // ARM64 MTE/HWASan tagged pointers have bit-63 set → appear negative as jlong.
            // NEVER check `driverHandle > 0L`; use isValidHandle() which checks against
            // the known small-negative error codes (-1L through -4L) only.
            check(UsbAudioBridge.isValidHandle(driverHandle)) {
                "nativeInitWithFileDescriptor failed for deviceId=${device.deviceId}: " +
                    UsbAudioBridge.describeInitError(driverHandle)
            }

            // ── Step 2: format-aware endpoint selection via C++ descriptor parser ─
            //
            // The native scanner walks the UAC2 config descriptor and parses
            // bSubslotSize + bBitResolution for every AudioStreaming alt setting.
            // effectiveBitDepth=32 targets bSubslotSize=4 (32-bit container) endpoints,
            // matching the universal 32-bit wire format used by the C++ pump for ALL
            // source bit depths (S16 expanded to MSB-aligned S32, 24-bit already in
            // S32 container, float32/native S32 passthrough).
            //
            // This call replaces the Android-USB-API-based selectStreamingTargetForLibusb,
            // which could not read bSubslotSize/bBitResolution because the Android
            // UsbInterface API does not expose class-specific descriptor fields.
            val endpointSelection = UsbPcmEndpointSelection.fromNative(
                UsbAudioBridge.nativeSelectBestEndpointForFormat(
                    handle = driverHandle,
                    sampleRateHz = format.sampleRateHz,
                    effectiveBitDepth = effectiveBitDepth,
                    channelCount = format.channelCount,
                    requireExactPcm = false,
                )
            ) ?: error(
                "nativeSelectBestEndpointForFormat: no valid UAC2 endpoint selection for " +
                    "$pcmProfile on deviceId=${device.deviceId}"
            )
            val interfaceNumber = endpointSelection.interfaceNumber
            val altSetting = endpointSelection.altSetting
            val endpointAddress = endpointSelection.endpointAddress
            val nativeMaxBpuf = endpointSelection.effectiveBytesPerUframe

            // bytesPerPcmFrame is based on profileBytesPerSample — the wire byte width
            // that actually enters the ring buffer after any packing.  This value drives
            // both bytesPerUframe (packet sizing) and nativeAllocateTransferPool
            // (ISO pool drain rate).  It MUST equal wire_bpf in the C++ pump loop;
            // any mismatch causes ring overflow (full_ring_sleeps) or underrun → noise.
            val bytesPerPcmFrame = profileBytesPerSample * format.channelCount
            val bytesPerUframe =
                ((format.sampleRateHz + USB_HS_UFRAMES_PER_SEC - 1) / USB_HS_UFRAMES_PER_SEC) *
                    bytesPerPcmFrame

            // ── UAC2 packet diagnostic ─────────────────────────────────────────────
            // Log a complete parameter table so every value that feeds nativeAllocateTransferPool
            // can be verified at a glance.  Critical columns:
            //   • "FFmpeg bytes/frame"  — what the decoder produces per stereo frame
            //   • "Wire bytes/frame"    — what the ring / ISO pool actually contains after expansion
            //   • "ISO bytes/frame"     — what nativeAllocateTransferPool will consume
            // All three MUST be equal.  A mismatch causes ring overflow or underrun → noise.
            val ffmpegBytesPerFrame = format.bytesPerSample * format.channelCount
            val expectedPumpKbps   = (format.sampleRateHz.toLong() * ffmpegBytesPerFrame) / 1024L
            val expectedWireKbps   = (format.sampleRateHz.toLong() * bytesPerPcmFrame) / 1024L
            val expectedIsoKbps    = (bytesPerUframe.toLong() * USB_HS_UFRAMES_PER_SEC) / 1024L

            val expansionNote = when {
                format.bytesPerSample == 2 ->
                    "S16LE→S32LE MSB expansion ACTIVE: pump produces ${ffmpegBytesPerFrame}B/frame " +
                        "→ ring receives ${bytesPerPcmFrame}B/frame (audio in bits 31:16, zeros in 15:0)"
                format.bytesPerSample == 4 && format.sourceBitDepth <= 24 ->
                    "24-bit S32LE passthrough: audio in bits 31:8, 0x00 in bits 7:0, " +
                        "${bytesPerPcmFrame}B/frame (already 32-bit container, no conversion)"
                else ->
                    "passthrough (float32 / native S32): pump produces and ring receives ${ffmpegBytesPerFrame}B/frame"
            }
            Log.i(TAG, "createLibusbPcmSink: ═══ UAC2 PACKET DIAGNOSTIC TABLE ════════════")
            Log.i(TAG, "  Source format  : ${format.sampleRateHz} Hz  ch=${format.channelCount}")
            Log.i(TAG, "  sourceBitDepth : ${format.sourceBitDepth}  androidPcmEncoding=${format.androidPcmEncoding}  (2=S16 3=S24 4=FLT 22=S32)")
            Log.i(TAG, "  FFmpeg output  : bytesPerSample=${format.bytesPerSample}  bytesPerFrame(FFmpeg)=$ffmpegBytesPerFrame")
            Log.i(TAG, "  Pump treatment : $expansionNote")
            Log.i(
                TAG,
                "  Wire / ISO cfg : container=${endpointSelection.subslotBytes * Byte.SIZE_BITS}bit  " +
                    "validBits=${endpointSelection.validBitDepth}  bytesPerFrame(wire)=$bytesPerPcmFrame  " +
                    "bytesPerUframe=$bytesPerUframe"
            )
            Log.i(TAG, "  Endpoint (UAC2 descriptor-matched, bSubslotSize=4 preferred):")
            Log.i(TAG, "    iface=$interfaceNumber  alt=$altSetting  ep=0x${endpointAddress.toString(16)}")
            Log.i(TAG, "    nativeMaxBpuf=$nativeMaxBpuf B/µframe  requiredBpuf=$bytesPerUframe B/µframe")
            Log.i(TAG, "  Throughput est : pump→ring $expectedPumpKbps KB/s (raw FFmpeg)  wire→dac $expectedWireKbps KB/s  iso $expectedIsoKbps KB/s")
            if (ffmpegBytesPerFrame != bytesPerPcmFrame && format.bytesPerSample != 2) {
                Log.w(
                    TAG,
                    "  ⚠ UNEXPECTED MISMATCH: FFmpeg bytes/frame=$ffmpegBytesPerFrame ≠ wire bytes/frame=$bytesPerPcmFrame " +
                        "and not a 16-bit source.  This format combination is unhandled — ring will " +
                        "${if (ffmpegBytesPerFrame > bytesPerPcmFrame) "OVERFLOW → full_ring_sleeps" else "UNDERRUN"}."
                )
            } else if (format.bytesPerSample == 2) {
                Log.i(TAG, "  ✓ S16→S32 MSB expansion: FFmpeg $ffmpegBytesPerFrame B/frame → wire $bytesPerPcmFrame B/frame (32-bit container)")
            } else {
                Log.i(TAG, "  ✓ bytes/frame match: FFmpeg=$ffmpegBytesPerFrame  wire=$bytesPerPcmFrame (32-bit passthrough)")
            }
            Log.i(TAG, "createLibusbPcmSink: ═════════════════════════════════════════")


            val claimResult = UsbAudioBridge.nativeClaimInterface(
                handle = driverHandle,
                interfaceNumber = interfaceNumber,
                altSetting = altSetting,
                endpointAddress = endpointAddress,
                effectiveBytesPerUframe = bytesPerUframe,
            )
            Log.i(TAG, "createLibusbPcmSink: nativeClaimInterface → result=$claimResult")
            check(claimResult == UsbAudioBridge.CLAIM_SUCCESS) {
                "nativeClaimInterface failed (status=$claimResult iface=$interfaceNumber alt=$altSetting)"
            }

            // ── Step 3b (Cold reset): drop interface to Alt 0 before clock negotiation ──
            //
            // If the previous session was Native DSD, the XMOS USB receiver chip is
            // "stuck" in its DSD streaming mode (usually Alt 4).  It continues to
            // interpret every UAC2 control transfer in a DSD context — so the PCM
            // Clock Source SET_CUR that follows will be silently ignored or mis-parsed,
            // causing "FSR ERROR" (Frame Sample Rate mismatch) and output silence when
            // PCM data starts flowing.
            //
            // Sending SET_INTERFACE with altSetting=0 (zero ISO bandwidth) signals the
            // chip to tear down its DSD state machine, release the isochronous pipe, and
            // reset its internal PLL to an idle/undetermined state.  Any subsequent
            // clock SET_CUR is then interpreted in a clean PCM context.
            //
            // This step is intentionally non-fatal: a DAC that was already in Alt 0
            // (first boot, or post-PCM session) will accept the redundant SET_INTERFACE
            // without complaint and the 20 ms delay is still beneficial for consistency.
            val idleAltResult = UsbAudioBridge.nativeActivateAltSetting(
                handle          = driverHandle,
                interfaceNumber = interfaceNumber,
                altSetting      = 0,   // zero-bandwidth idle; intentional cold-reset (not an error)
                endpointAddress = 0,   // no ISO endpoint active at alt=0; skip clear_halt
            )
            if (idleAltResult != UsbAudioBridge.CLAIM_SUCCESS) {
                Log.w(
                    TAG,
                    "createLibusbPcmSink: Alt-0 cold reset returned $idleAltResult — " +
                        "DAC may already be idle or firmware rejected zero-bandwidth SET_INTERFACE; " +
                        "continuing (non-fatal, DSD state may persist on some DACs)"
                )
            } else {
                Log.i(
                    TAG,
                    "createLibusbPcmSink: Alt-0 cold reset ✓ — DSD state cleared, " +
                        "iface=$interfaceNumber is now idle"
                )
            }

            // Allow the XMOS chip 20 ms to complete its PLL unlock/reset cycle before
            // the PCM clock negotiation begins.  During this window the chip transitions
            // from "DSD PLL locked" to "PLL idle" — issuing the clock SET_CUR too
            // quickly after Alt-0 can race with the reset and produce a second FSR ERROR.
            Thread.sleep(20)

            // ── Step 3c: Program the UAC2 Clock Source sample rate ──────────────
            // In UAC2, selecting an alternate setting only allocates isochronous
            // bus bandwidth.  The DAC's internal PLL must be explicitly programmed
            // to the stream's sample rate via a SET_CUR Class request to the
            // Clock Source entity (bDescriptorSubtype=0x0A in the Audio Control
            // interface descriptor).
            //
            // Skipping this step causes "FSR ERROR" on the DAC display (Frequency
            // Sample Rate mismatch) and hardware mute — the DAC keeps its
            // power-on default clock (often 44.1 kHz or 48 kHz) regardless of
            // the ISO stream rate.
            //
            // This call is now safe because Step 3b confirmed the chip has left DSD
            // mode and is ready to accept a new PCM clock value.
            //
            // clockSourceId = -1 → auto-detect from config descriptor in native code.
            // The native fallback is bClockID=1, correct for the FiiO KA5 and
            // the majority of UAC2 consumer DACs (XMOS/ESS reference designs).
            //
            // controlInterfaceNumber = 0 → the USB Audio standard mandates that the
            // Audio Control interface is always interface 0 for single-function DACs.
            // Multi-function devices may differ; audit with a USB descriptor dump if
            // the clock set fails even after the DAC accepts 44.1/48 kHz.
            val clockResult = UsbAudioBridge.nativeSetUac2ClockSampleRate(
                handle = driverHandle,
                controlInterfaceNumber = 0,
                clockSourceId = -1,                  // -1 = auto-detect from descriptor
                sampleRateHz = format.sampleRateHz,
            )
            if (clockResult < 0) {
                // Non-fatal: some DACs accept implicit clock negotiation or silently
                // ignore the control transfer.  Log a prominent warning so the user
                // can identify the cause if "FSR ERROR" still appears, but allow the
                // playback sequence to proceed — the ISO stream may still work.
                Log.w(
                    TAG,
                    "createLibusbPcmSink: nativeSetUac2ClockSampleRate FAILED " +
                        "(status=$clockResult, sampleRate=${format.sampleRateHz} Hz) — " +
                        "proceeding; if the DAC shows 'FSR ERROR' check the bClockID " +
                        "in the USB descriptor dump and pass it explicitly"
                )
            } else {
                Log.i(
                    TAG,
                    "createLibusbPcmSink: UAC2 clock source → ${format.sampleRateHz} Hz ✓ " +
                        "(status=$clockResult)"
                )
            }

            // ── Step 3d: Activate the PCM ISO alternate setting (AFTER clock setup) ──
            // On FiiO KA series and XMOS/Savitech chips the DAC's PLL must be
            // programmed (Step 3c above) BEFORE SET_INTERFACE activates the ISO
            // endpoint.  Doing it in the wrong order causes persistent "FSR ERROR".
            // endpointAddress is forwarded so the C++ layer can issue
            // libusb_clear_halt immediately after SET_INTERFACE, preventing the
            // XMOS USB-receiver HALT deadlock (ring_free=0 KB, full_sleeps rising).
            val altResult = UsbAudioBridge.nativeActivateAltSetting(
                handle          = driverHandle,
                interfaceNumber = interfaceNumber,
                altSetting      = altSetting,
                endpointAddress = endpointAddress,
            )
            Log.i(
                TAG,
                "createLibusbPcmSink: nativeActivateAltSetting → result=$altResult " +
                    "(iface=$interfaceNumber alt=$altSetting ep=0x${endpointAddress.toString(16).uppercase()})"
            )
            check(altResult == UsbAudioBridge.CLAIM_SUCCESS) {
                "nativeActivateAltSetting failed (status=$altResult iface=$interfaceNumber alt=$altSetting)"
            }

            val poolResult = UsbAudioBridge.nativeAllocateTransferPool(
                handle = driverHandle,
                sampleRateHz = format.sampleRateHz,
                bytesPerAudioFrame = bytesPerPcmFrame,
                endpointAddress = endpointAddress,
                poolSize = ISO_POOL_SIZE,
                packetsPerTransfer = ISO_PACKETS_PER_TRANSFER,
            )
            Log.i(TAG, "createLibusbPcmSink: nativeAllocateTransferPool → result=$poolResult")
            check(poolResult == 0) {
                "nativeAllocateTransferPool failed (code=$poolResult ${format.sampleRateHz}Hz)"
            }

            val startResult = UsbAudioBridge.nativeStartPlayback(handle = driverHandle)
            Log.i(TAG, "createLibusbPcmSink: nativeStartPlayback → result=$startResult")
            check(startResult == 0) {
                "nativeStartPlayback failed (code=$startResult)"
            }

            // ── Pre-flight volume ordering checkpoint ─────────────────────────────
            //
            // nativeStartPlayback has now created the SPSC ring buffer and started
            // the libusb event thread.  ISO transfers are live but the ring is still
            // empty — the DAC clock is ticking on silence.
            //
            // The software volume is already loaded from SharedPreferences inside
            // UsbVolumeController (it is read from prefs on construction). It will be
            // delivered to the native bridge in strict pre-pump order when the sink's
            // play() method is called:
            //
            //   1. LibusbPcmAudioSink.play():
            //        val initialVolume = volumeController.volumePct.value / 100f
            //   2. nativeAttachUsbEngine(driverHandle, decoderHandle, eof, initialVolume)
            //        ↳ C++: bridge->set_volume(initial_volume)   // quadratic taper applied
            //        ↳ C++: bridge->start()                      // pump thread launched
            //   3. UsbVolumeController.attachBridge(handle)       // safety re-apply
            //
            // This ordering guarantees the persisted level is honoured by the very
            // first pre-fill chunk — no startup blast, no brief silence gap.
            Log.i(
                TAG,
                "createLibusbPcmSink: ── pre-flight volume checkpoint ──────────────\n" +
                    "  Persisted volume : ${usbVolumeController.volumePct.value} %\n" +
                    "  Linear position  : ${"%.4f".format(usbVolumeController.volumePct.value / 100f)}\n" +
                    "  Quadratic gain   : ${"%.6f".format(
                        usbVolumeController.volumePct.value.let { pct ->
                            val p = pct / 100f; p * p
                        }
                    )}\n" +
                    "  Delivery point   : nativeAttachUsbEngine(initialVolume) → before bridge->start()\n" +
                    "──────────────────────────────────────────────────────────────────"
            )

            // Open native FFmpeg decoder: forcePcm=true so DSD sources are
            // decimated to float32 88 200 Hz, exactly matching the ISO pool config.
            decoderHandle = EngineSwapBridge.nativeOpenDecoderForUsb(path, true)
            Log.i(TAG, "createLibusbPcmSink: nativeOpenDecoderForUsb → decoderHandle=$decoderHandle")
            check(decoderHandle != 0L) {
                "nativeOpenDecoderForUsb failed for path=$path"
            }

            val pathReport = PipelinePathReport(
                usedDirectFlag = true,
                usedFloatFallback = false,
                encoding = format.androidPcmEncoding,
                sampleRateHz = format.sampleRateHz,
                channelMask = format.channelCount,
                bufferFrames = 0,
                nativeOutputSampleRateHz = format.sampleRateHz,
                framesPerBuffer = ISO_PACKETS_PER_TRANSFER,
                routedDeviceType = android.hardware.usb.UsbConstants.USB_CLASS_AUDIO,
                routedDeviceName = device.productName ?: device.deviceName,
                audioSessionId = 0,
                usbPcmSubslotBitDepth = endpointSelection.subslotBytes * Byte.SIZE_BITS,
                usbPcmValidBitDepth = endpointSelection.validBitDepth,
            )

            Log.i(
                PATH_TAG,
                "Audiophile sink selection: libusb PCM ${format.sampleRateHz}Hz/${effectiveBitDepth}bit " +
                    "deviceId=${device.deviceId} driverHandle=0x${driverHandle.toULong().toString(16)}"
            )

            LibusbPcmAudioSink(
                driverHandle = driverHandle,
                decoderHandle = decoderHandle,
                sampleRateHz = format.sampleRateHz,
                connection = connection,
                pathReport = pathReport,
                volumeController = usbVolumeController,
            )
        } catch (e: Exception) {
            if (decoderHandle != 0L) EngineSwapBridge.nativeReleaseUsbDecoder(decoderHandle)
            // isValidHandle() required — ARM64 tagged pointers are negative as jlong.
            if (UsbAudioBridge.isValidHandle(driverHandle)) UsbAudioBridge.nativeRelease(driverHandle)
            runCatching { connection.close() }
            Log.e(TAG, "createLibusbPcmSink failed for ${format.sampleRateHz}Hz: ${e.message}", e)
            throw e
        }
    }

    /**
     * Opens a [LibusbPcmEnhancedSink] that accepts Kotlin-processed PCM data
     * directly into the libusb ISO ring buffer.
     *
     * Use this factory method when an FFmpeg DSP pipeline (SUE / Hi-Res Dynamic
     * Remaster) is active alongside a libusb-ready USB DAC. The sink owns the
     * libusb driver context and ISO transfer pool but does **not** open a native
     * decoder or start the native pump thread — all decoding and DSP is handled by
     * the Kotlin write loop in [BitPerfectPlaybackEngine].
     *
     * [format] must be the **post-DSP** [AudioFormatInfo] from `pipelineOutputFormat`:
     * float32 PCM at the SUE / Hi-Res output sample rate (e.g. 88 200 Hz for a
     * 44 100 Hz source with active SUE). The USB clock source and ISO transfer pool
     * are both configured at this rate, matching the data rate the Kotlin write loop
     * will deliver.
     *
     * @param format Post-DSP PCM format: float32 encoding, SUE / Hi-Res output rate.
     * @return Initialised [LibusbPcmEnhancedSink] ready for Kotlin write-loop
     *   data delivery.
     * @throws IllegalStateException When no permitted libusb-ready USB DAC is available
     *   or any step of the init sequence fails.
     */
    internal fun createLibusbPcmEnhancedSink(format: AudioFormatInfo): LibusbPcmEnhancedSink {
        val state = usbDeviceScanner.deviceState.value
        val device = usbDeviceScanner.getPermittedDevice()
        check(device != null && state.isLibusbReady) {
            when {
                state.connectedDevice == null ->
                    "No USB DAC connected — libusb enhanced PCM requires a direct USB connection"
                !state.isPermissionGranted ->
                    "USB permission not granted for deviceId=${state.connectedDevice.deviceId}"
                else -> "USB DAC not ready for libusb enhanced PCM (state=$state)"
            }
        }

        // Post-DSP output is always float32 (ENCODING_PCM_FLOAT), which maps to
        // a 4-byte wire subslot. Use effectiveBitDepth=32 to target bSubslotSize=4
        // alternate settings in the UAC2 descriptor scanner — the same choice as
        // createLibusbPcmSink for all source depths.
        val profileBytesPerSample = 4
        val effectiveBitDepth     = 32

        Log.i(
            TAG,
            "createLibusbPcmEnhancedSink: outputRate=${format.sampleRateHz}Hz " +
                "encoding=${format.androidPcmEncoding} channels=${format.channelCount} " +
                "(float32 Kotlin-write sink; no native pump)"
        )

        val connection = usbManager.openDevice(device)
            ?: error("UsbManager.openDevice returned null for deviceId=${device.deviceId}")

        var driverHandle = 0L
        return try {

            // ── Step 1: wrap the FD into a libusb device handle ─────────────────
            driverHandle = UsbAudioBridge.nativeInitWithFileDescriptor(connection.fileDescriptor)
            Log.i(
                TAG,
                "createLibusbPcmEnhancedSink: nativeInitWithFileDescriptor → " +
                    "driverHandle=0x${driverHandle.toULong().toString(16)} " +
                    "isValid=${UsbAudioBridge.isValidHandle(driverHandle)}"
            )
            check(UsbAudioBridge.isValidHandle(driverHandle)) {
                "nativeInitWithFileDescriptor failed for deviceId=${device.deviceId}: " +
                    UsbAudioBridge.describeInitError(driverHandle)
            }

            // ── Step 2: select best UAC2 endpoint at the DSP output rate ────────
            val endpointSelection = UsbPcmEndpointSelection.fromNative(
                UsbAudioBridge.nativeSelectBestEndpointForFormat(
                    handle = driverHandle,
                    sampleRateHz = format.sampleRateHz,
                    effectiveBitDepth = effectiveBitDepth,
                    channelCount = format.channelCount,
                    requireExactPcm = true,
                )
            ) ?: error(
                "nativeSelectBestEndpointForFormat: no valid UAC2 endpoint selection for " +
                    "${format.sampleRateHz}Hz/${effectiveBitDepth}b/${format.channelCount}ch " +
                    "on deviceId=${device.deviceId}"
            )
            val interfaceNumber = endpointSelection.interfaceNumber
            val altSetting = endpointSelection.altSetting
            val endpointAddress = endpointSelection.endpointAddress

            val bytesPerPcmFrame = profileBytesPerSample * format.channelCount
            val bytesPerUframe   =
                ((format.sampleRateHz + USB_HS_UFRAMES_PER_SEC - 1) / USB_HS_UFRAMES_PER_SEC) *
                    bytesPerPcmFrame

            Log.i(
                TAG,
                "createLibusbPcmEnhancedSink: uac2 endpoint → " +
                    "iface=$interfaceNumber alt=$altSetting ep=0x${endpointAddress.toString(16)} " +
                    "container=${endpointSelection.subslotBytes * Byte.SIZE_BITS}bit " +
                    "validBits=${endpointSelection.validBitDepth} bytesPerUframe=$bytesPerUframe"
            )

            // ── Step 3a: claim the streaming interface ───────────────────────────
            val claimResult = UsbAudioBridge.nativeClaimInterface(
                handle                = driverHandle,
                interfaceNumber       = interfaceNumber,
                altSetting            = altSetting,
                endpointAddress       = endpointAddress,
                effectiveBytesPerUframe = bytesPerUframe,
            )
            Log.i(TAG, "createLibusbPcmEnhancedSink: nativeClaimInterface → result=$claimResult")
            check(claimResult == UsbAudioBridge.CLAIM_SUCCESS) {
                "nativeClaimInterface failed (status=$claimResult iface=$interfaceNumber alt=$altSetting)"
            }

            // ── Step 3b (Cold reset): drop interface to Alt 0 before clock negotiation ──
            //
            // Mirror of the same step in createLibusbPcmSink.  If a Native DSD session
            // just ended the XMOS receiver is still in DSD mode (Alt 4).  Sending
            // SET_INTERFACE to Alt 0 tears down the DSD state machine and resets the
            // chip's PLL to idle so the PCM clock SET_CUR in Step 3c is accepted in
            // a clean PCM context instead of being silently rejected.
            //
            // Non-fatal: a DAC already at Alt 0 (first boot or post-PCM session) will
            // handle this as a benign no-op.
            val idleAltResult = UsbAudioBridge.nativeActivateAltSetting(
                handle          = driverHandle,
                interfaceNumber = interfaceNumber,
                altSetting      = 0,   // zero-bandwidth idle; intentional cold-reset
                endpointAddress = 0,   // no ISO endpoint active at alt=0; skip clear_halt
            )
            if (idleAltResult != UsbAudioBridge.CLAIM_SUCCESS) {
                Log.w(
                    TAG,
                    "createLibusbPcmEnhancedSink: Alt-0 cold reset returned $idleAltResult — " +
                        "DAC may already be idle; continuing (non-fatal)"
                )
            } else {
                Log.i(
                    TAG,
                    "createLibusbPcmEnhancedSink: Alt-0 cold reset ✓ — DSD state cleared, " +
                        "iface=$interfaceNumber is now idle"
                )
            }

            // 20 ms for the XMOS chip's DSD PLL to reach idle before PCM clock setup.
            Thread.sleep(20)

            // ── Step 3c: program UAC2 clock source to the DSP output rate ───────
            // The clock must be set to the SUE/Hi-Res output rate, not the source
            // rate, because that is the rate at which the ISO ring buffer will be
            // filled by the Kotlin write loop.
            //
            // This is now safe because Step 3b confirms the chip left DSD mode.
            val clockResult = UsbAudioBridge.nativeSetUac2ClockSampleRate(
                handle                 = driverHandle,
                controlInterfaceNumber = 0,
                clockSourceId          = -1,            // auto-detect from descriptor
                sampleRateHz           = format.sampleRateHz,
            )
            if (clockResult < 0) {
                Log.w(
                    TAG,
                    "createLibusbPcmEnhancedSink: nativeSetUac2ClockSampleRate FAILED " +
                        "(status=$clockResult sampleRate=${format.sampleRateHz} Hz) — proceeding"
                )
            } else {
                Log.i(
                    TAG,
                    "createLibusbPcmEnhancedSink: UAC2 clock → ${format.sampleRateHz} Hz ✓"
                )
            }

            // ── Step 3d: activate ISO alt setting (after clock setup) ────────────
            // PLL programmed (Step 3c) BEFORE SET_INTERFACE — required by XMOS/Savitech.
            // endpointAddress forwarded so C++ can call libusb_clear_halt after
            // SET_INTERFACE to unblock the XMOS USB-receiver HALT condition.
            val altResult = UsbAudioBridge.nativeActivateAltSetting(
                handle          = driverHandle,
                interfaceNumber = interfaceNumber,
                altSetting      = altSetting,
                endpointAddress = endpointAddress,
            )
            Log.i(
                TAG,
                "createLibusbPcmEnhancedSink: nativeActivateAltSetting → result=$altResult " +
                    "(iface=$interfaceNumber alt=$altSetting ep=0x${endpointAddress.toString(16).uppercase()})"
            )
            check(altResult == UsbAudioBridge.CLAIM_SUCCESS) {
                "nativeActivateAltSetting failed (status=$altResult iface=$interfaceNumber alt=$altSetting)"
            }

            // ── Step 4: allocate ISO transfer pool at DSP output rate ────────────
            val poolResult = UsbAudioBridge.nativeAllocateTransferPool(
                handle              = driverHandle,
                sampleRateHz        = format.sampleRateHz,
                bytesPerAudioFrame  = bytesPerPcmFrame,
                endpointAddress     = endpointAddress,
                poolSize            = ISO_POOL_SIZE,
                packetsPerTransfer  = ISO_PACKETS_PER_TRANSFER,
            )
            Log.i(TAG, "createLibusbPcmEnhancedSink: nativeAllocateTransferPool → result=$poolResult")
            check(poolResult == 0) {
                "nativeAllocateTransferPool failed (code=$poolResult ${format.sampleRateHz}Hz)"
            }

            // ── Step 5: prepare the ISO transport (ring + event thread) ─────────
            // No native pump is attached here. The Kotlin write loop feeds the
            // ring via nativeWriteToRingBuffer, whose first real-audio push submits
            // the transfer pool.
            val startResult = UsbAudioBridge.nativeStartPlayback(handle = driverHandle)
            Log.i(TAG, "createLibusbPcmEnhancedSink: nativeStartPlayback → result=$startResult")
            check(startResult == 0) {
                "nativeStartPlayback failed (code=$startResult)"
            }

            val pathReport = PipelinePathReport(
                usedDirectFlag          = true,
                usedFloatFallback       = false,
                // Kotlin DSP emits float32, but the JNI boundary quantises it
                // to signed S32LE before the bytes enter the UAC2 ring.
                encoding                = AudioFormat.ENCODING_PCM_32BIT,
                sampleRateHz            = format.sampleRateHz,
                channelMask             = format.channelCount,
                bufferFrames            = 0,
                nativeOutputSampleRateHz = format.sampleRateHz,
                framesPerBuffer         = ISO_PACKETS_PER_TRANSFER,
                routedDeviceType        = android.hardware.usb.UsbConstants.USB_CLASS_AUDIO,
                routedDeviceName        = device.productName ?: device.deviceName,
                audioSessionId          = 0,
                usbPcmSubslotBitDepth   = endpointSelection.subslotBytes * Byte.SIZE_BITS,
                usbPcmValidBitDepth     = endpointSelection.validBitDepth,
            )

            Log.i(
                PATH_TAG,
                "Audiophile sink selection: libusb PCM (Kotlin-write/DSP) " +
                    "${format.sampleRateHz}Hz/float32 deviceId=${device.deviceId} " +
                    "driverHandle=0x${driverHandle.toULong().toString(16)}"
            )

            LibusbPcmEnhancedSink(
                driverHandle = driverHandle,
                sampleRateHz = format.sampleRateHz,
                connection   = connection,
                pathReport   = pathReport,
                volumeController = usbVolumeController,
            )
        } catch (e: Exception) {
            if (UsbAudioBridge.isValidHandle(driverHandle)) UsbAudioBridge.nativeRelease(driverHandle)
            runCatching { connection.close() }
            Log.e(TAG, "createLibusbPcmEnhancedSink failed for ${format.sampleRateHz}Hz: ${e.message}", e)
            throw e
        }
    }

    /**
     * Resolves the direct-USB output profile that can be opened for the current
     * track, or `null` when the app should fall back to the platform audio path.
     *
     * Returning `null` keeps playback audible when the connected DAC cannot do
     * source-native USB host playback for the current file.
     * The platform [AudioTrackSink] may still route successfully to the same DAC
     * through Android's mixer / direct-output stack.
     *
     * @param sourceProfile Decoded PCM shape produced by the FFmpeg pipeline.
     * @param supportedProfiles USB output formats advertised by the selected DAC.
     * @return Direct USB profile to open, or `null` when playback should degrade
     *   to [AudioTrackSink].
     */
    internal fun resolveDirectUsbProfile(
        sourceProfile: UsbAudioOutputProfile,
        supportedProfiles: List<UsbAudioOutputProfile>,
    ): UsbAudioOutputProfile? =
        sourceProfile.takeIf { profile ->
            supportedProfiles.isEmpty() || supportedProfiles.any { it.matches(profile) }
        }

    private fun UsbAudioOutputProfile.matches(other: UsbAudioOutputProfile): Boolean =
        sampleRateHz == other.sampleRateHz &&
            bitDepth == other.bitDepth &&
            channelCount == other.channelCount

    private companion object {
        const val TAG = "UsbAudioSinkFactory"
        const val PATH_TAG = "AudiophilePath"

        // ── libusb DSD transfer pool constants ────────────────────────────────
        /** Pre-allocated ISO OUT transfer slots. 16 × 1 ms = 16 ms of pipeline depth. */
        const val ISO_POOL_SIZE = 16

        /**
         * ISO packets (microframes) per transfer slot.
         * 8 µframes × 125 µs = 1 ms per [libusb_submit_transfer] call.
         */
        const val ISO_PACKETS_PER_TRANSFER = 8

        /** USB 2.0 High-Speed microframes per second — used for bytes/µframe ceiling. */
        const val USB_HS_UFRAMES_PER_SEC = 8_000

        /** Stereo DSD channel count: one byte per channel per USB audio frame. */
        const val DSD_STEREO_CHANNELS = 2

        /**
         * DSD_U32LE subslot bit width: 32 DSD bits packed into one 4-byte USB subslot.
         *
         * The UAC2 clock frequency for Native DSD is `dsdSampleRateHz / DSD_U32_SUBSLOT_BITS`.
         * For DSD64: 2,822,400 / 32 = 88,200 Hz.
         *
         * A DAC with `bSubslotSize=4` interprets the clock as:
         *   `clock × 32 bits/subslot = DSD bit-rate per channel`
         *   88,200 × 32 = 2,822,400 = DSD64 ✓
         */
        const val DSD_U32_SUBSLOT_BITS = 32

        /**
         * Bytes per channel per DSD_U32LE frame (= one 32-bit DSD subslot).
         *
         * For stereo: `DSD_U32_BYTES_PER_CHANNEL × DSD_STEREO_CHANNELS = 8` bytes per frame.
         * This must equal `wire_bpf` in `decoder_to_ring_bridge.cpp` for DSD sessions.
         */
        const val DSD_U32_BYTES_PER_CHANNEL = 4


        // DoP PCM carrier rates (DSD-over-PCM v1.1 spec, §4.2).
        const val DOP_PCM_RATE_DSD64  = 176_400   // 2× 88.2 kHz
        const val DOP_PCM_RATE_DSD128 = 352_800   // 4× 88.2 kHz
        const val DOP_PCM_RATE_DSD256 = 705_600   // 8× 88.2 kHz

        /** DoP uses 24-bit PCM frames to preserve the marker bytes in the MSB. */
        const val DOP_PCM_BIT_DEPTH = 24
    }
}
