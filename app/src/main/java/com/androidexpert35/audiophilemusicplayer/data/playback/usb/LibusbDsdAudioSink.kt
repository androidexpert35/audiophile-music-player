package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbDsdAudioSink.Companion.DSD_STEREO_CHANNELS
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbDsdAudioSink.Companion.DSD_U32_BYTES_PER_CHANNEL
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * USB audio output sink backed by the libusb isochronous transfer engine.
 *
 * Unlike [UsbAudioSink], this sink delegates **all** data throughput to the
 * native [DecoderToRingBridge] pump thread wired to the libusb `SpscRingBuffer`.
 * The Kotlin write loop in [BitPerfectPlaybackEngine] must **not** push data to
 * this sink; it detects the type via `is LibusbDsdAudioSink` and switches to a
 * lightweight EOF-polling mode instead.
 *
 * ### Initialization ownership
 *
 * This class owns its **complete** USB initialization lifecycle. The `init {}`
 * block performs the full mandatory UAC2 sequence in strict order:
 *
 * ```
 * 1. nativeInitWithFileDescriptor(fd)       ← wrap Android FD into libusb
 * 2. nativeClaimInterface(...)              ← claim streaming iface (no alt yet)
 * 3. nativeSetUac2ClockSampleRate(...)      ← SET_CUR: program DAC PLL to DSD_U32LE frame rate
 *    [50 ms delay — PLL settling before SET_INTERFACE]
 * 4. nativeActivateAltSetting(...)          ← SET_INTERFACE: Alt0→AltN hard-reset then ISO
 * 5. nativeAllocateTransferPool(...)        ← pre-allocate ISO descriptors
 * 6. nativeStartDsdPlayback(...)            ← start event thread + DSD/DoP manager
 * 7. nativeAttachUsbEngine(...)             ← called later in play()
 * ```
 *
 * ### Clock and Frame-Size Alignment (DSD_U32LE)
 *
 * The native formatter (`native_dsd_formatter.cpp`) outputs **DSD_U32LE**: 4 bytes (32 DSD bits)
 * per channel per USB audio frame.  The UAC2 clock, the IsoTransferPool, and the ring-buffer pump
 * must all agree on this format:
 *
 * | Parameter | Formula | DSD64 example |
 * |-----------|---------|---------------|
 * | UAC2 clock (SET_CUR) | `sampleRateHz / 32` | 88,200 Hz |
 * | IsoPool sampleRateHz | `sampleRateHz / 32` | 88,200 Hz |
 * | IsoPool bytesPerFrame | `4 × channels`      | 8 bytes   |
 * | Total byte rate       | `88,200 × 8`        | 705,600 B/s |
 * | DAC display check     | `88,200 × 32 bits`  | 2,822,400 bps/ch = DSD64 ✓ |
 *
 * Using `sampleRateHz / 8` (byte rate, 352,800 Hz for DSD64) as the clock was the previous
 * mistake: with a 32-bit subslot DAC the display showed DSD256 (352,800 × 32 = 11,289,600).
 *
 * ### Lifecycle
 *
 * ```
 * LibusbDsdAudioSink(...)   // init{} runs the 6-step USB setup; ISO idles on silence
 *   play()                  // pump thread started; DSD data flows
 *   pause()                 // pump stopped; ring drains naturally
 *   play()                  // pump restarted from current decoder position
 *   seekTo(positionMs)      // pump stopped, decoder seeked, pump restarted
 *   close()                 // full teardown in correct destruction order
 * ```
 *
 * @property dsdInterfaceNumber  `bInterfaceNumber` of the DSD streaming interface.
 * @property dsdAltSetting       `bAlternateSetting` of the DSD ISO endpoint (must be ≥ 1).
 * @property dsdEndpointAddress  `bEndpointAddress` of the DSD ISO OUT endpoint.
 * @property bytesPerUframe      Bytes per USB microframe computed from the DSD byte-rate.
 * @property pcmInterfaceNumber  `bInterfaceNumber` of the DoP PCM fallback interface.
 * @property pcmAltSetting       `bAlternateSetting` of the DoP PCM fallback endpoint.
 * @property pcmEndpointAddress  `bEndpointAddress` of the DoP PCM fallback endpoint.
 * @property pcmBandwidth        Maximum bytes per USB microframe on the DoP endpoint.
 * @property startInDop          Starts directly in DoP when Native DSD is unavailable.
 * @property supportsDopFallback Whether Native DSD can switch to a validated DoP target.
 * @property decoderHandle       Opaque native decoder handle from
 *   [EngineSwapBridge.nativeOpenDecoderForUsb]. Released by [close].
 * @property dsdRate             DSD family of the source file; drives clock-rate
 *   computation and wall-clock position tracking.
 * @property initialVolume       Linear volume scalar (0.0–1.0) applied to the C++
 *   bridge before the pump thread starts — eliminates startup volume blast.
 * @property connection          [UsbDeviceConnection] whose FD libusb wraps.
 *   Must remain open for the lifetime of this sink; closed by [close].
 * @property pathReport          Diagnostic routing descriptor for telemetry surfaces.
 */
internal class LibusbDsdAudioSink(
    private val dsdInterfaceNumber: Int,
    private val dsdAltSetting: Int,
    private val dsdEndpointAddress: Int,
    private val bytesPerUframe: Int,
    private val pcmInterfaceNumber: Int,
    private val pcmAltSetting: Int,
    private val pcmEndpointAddress: Int,
    private val pcmBandwidth: Int,
    private val startInDop: Boolean,
    private val supportsDopFallback: Boolean,
    private val decoderHandle: Long,
    private val dsdRate: DsdRate,
    private val initialVolume: Float,
    private val connection: UsbDeviceConnection,
    pathReport: PipelinePathReport,
) : LibusbOutputSink {

    private val initialPathReport = pathReport
    private val activeDsdMode = AtomicInteger(
        if (startInDop) MODE_DOP else MODE_NATIVE_DSD
    )

    override val pathReport: PipelinePathReport
        get() {
            if (activeDsdMode.get() == MODE_NATIVE_DSD) return initialPathReport
            val dopCarrierRate = dsdRate.sampleRateHz / DOP_DSD_BITS_PER_PCM_FRAME
            return initialPathReport.copy(
                sampleRateHz = dopCarrierRate,
                dsdPipelineInfo = initialPathReport.dsdPipelineInfo?.copy(
                    outputMode = DsdOutputMode.DoP(dopCarrierRate),
                    dopPcmRate = dopCarrierRate,
                ),
            )
        }

    /**
     * Direct DSD playhead values count one-bit samples, independently of
     * whether the native transport currently emits Native DSD or DoP.
     */
    override val playbackHeadClockRateHz: Int = dsdRate.sampleRateHz

    // ── Native driver-context handle ──────────────────────────────────────────
    // Set by init{}; 0L means init has not run or was rolled back after failure.
    // Use UsbAudioBridge.isValidHandle() — NEVER `driverHandle > 0L` (ARM64 MTE).

    @Volatile private var driverHandle: Long = 0L

    // ── DSD mode-change listener ──────────────────────────────────────────────
    // Declared BEFORE init{} so it is already initialised when nativeStartDsdPlayback
    // is invoked inside the init block.  @Keep prevents R8 from stripping the JNI
    // callback method from the release build.

    /**
     * Receives the DSD mode-change callback from the native monitor thread.
     *
     * The JNI layer resolves `this.onEngineModeChanged(I)V` at call time; the
     * `@Keep` annotation prevents R8 from renaming or removing it.
     *
     * This is a nested (not inner) class so it can be instantiated before the
     * enclosing [LibusbDsdAudioSink] instance is fully constructed, which is
     * required because [UsbAudioBridge.nativeStartDsdPlayback] needs a listener
     * reference during [LibusbDsdAudioSink]'s own `init {}` block.
     */
    @Keep
    class DsdModeChangeListener(
        private val onModeChanged: (Int) -> Unit,
    ) {
        /**
         * Called by the native [DsdPlaybackManager] monitor thread exactly once when
         * Native DSD STALL is detected and the engine switches to DoP.
         *
         * @param mode 0 = Native DSD (initial state), 1 = DoP (fallback).
         */
        @Keep
        fun onEngineModeChanged(mode: Int) {
            onModeChanged(mode)
            val modeName = if (mode == 0) "NativeDsd" else "DoP"
            Log.i(TAG, "DSD mode changed → $modeName (raw=$mode) — libusb DoP fallback active")
        }
    }

    // Strong reference so the GC cannot collect it while the native DsdPlaybackManager
    // holds a JNI global ref to the same object across the session.
    private val dsdModeListener = DsdModeChangeListener(activeDsdMode::set)

    // ── Full USB initialization (steps 1–6) ───────────────────────────────────

    init {
        // ── DSD_U32LE USB framing constants ───────────────────────────────────────
        //
        // The native DSD formatter (native_dsd_formatter.cpp) packs DSD bits as
        // DSD_U32LE: 32 DSD bits (4 bytes) per channel, 8 bytes per stereo frame.
        // Every USB "audio frame" therefore carries one 32-bit DSD word per channel.
        //
        // UAC2 clock frequency (frames/sec) = DSD_bit_rate / bits_per_subslot:
        //   DSD64  → 2,822,400 / 32 =  88,200 Hz  → clock SET_CUR =  88,200 Hz
        //   DSD128 → 5,644,800 / 32 = 176,400 Hz  → clock SET_CUR = 176,400 Hz
        //   DSD256 → 11,289,600 / 32 = 352,800 Hz → clock SET_CUR = 352,800 Hz
        //
        // With clock= 88,200 Hz the FiiO KA5 computes: 88,200 × 32 = 2,822,400 bps/ch = DSD64 ✓
        // The OLD value of 352,800 Hz (= sampleRateHz / 8) caused the display to show
        // DSD256 because the DAC interpreted: 352,800 × 32 = 11,289,600 bps/ch = DSD256.
        //
        // NOTE — "16-bit subslot" alternative (if the DAC uses bSubslotSize=2):
        //   clock = sampleRateHz / 16 = 176,400 Hz  bytesPerFrame = 4
        //   Total byte rate = 176,400 × 4 = 705,600 B/s (same throughput, different framing)
        //   Use that pair ONLY if the DAC still shows the wrong rate with 88,200 Hz —
        //   it would mean the DAC's Alt4 specifies 16-bit subslots, not 32-bit.
        val dsdFrameRateHz = if (startInDop) {
            dsdRate.sampleRateHz / DOP_DSD_BITS_PER_PCM_FRAME
        } else {
            dsdRate.sampleRateHz / DSD_BITS_PER_U32_SUBSLOT
        }
        val dsdBytesPerFrame = DSD_U32_BYTES_PER_CHANNEL * DSD_STEREO_CHANNELS // 4 × 2 = 8


        try {
            // ── Step 1: Wrap Android FD into a libusb context ─────────────────
            Log.d(TAG, "init: nativeInitWithFileDescriptor(fd=${connection.fileDescriptor})")
            driverHandle = UsbAudioBridge.nativeInitWithFileDescriptor(connection.fileDescriptor)
            Log.i(
                TAG, "init: nativeInitWithFileDescriptor → " +
                    "driverHandle=0x${driverHandle.toULong().toString(16)} " +
                    "isValid=${UsbAudioBridge.isValidHandle(driverHandle)}"
            )
            check(UsbAudioBridge.isValidHandle(driverHandle)) {
                // ARM64 MTE/HWASan: valid heap pointers have bit 63 set (appear negative).
                "nativeInitWithFileDescriptor failed: ${UsbAudioBridge.describeInitError(driverHandle)}"
            }

            // ── Step 2: Claim the streaming interface (deferred alt-setting) ───
            // Claims the USB interface for exclusive host use and registers the
            // endpoint.  The ISO alt setting is NOT activated yet — it must wait
            // until after the clock is programmed (step 3).
            Log.d(
                TAG, "init: nativeClaimInterface(" +
                    "iface=$dsdInterfaceNumber alt=$dsdAltSetting " +
                    "ep=0x${dsdEndpointAddress.toString(16)} bpuf=$bytesPerUframe)"
            )
            val claimResult = UsbAudioBridge.nativeClaimInterface(
                handle = driverHandle,
                interfaceNumber = dsdInterfaceNumber,
                altSetting = dsdAltSetting,
                endpointAddress = dsdEndpointAddress,
                effectiveBytesPerUframe = bytesPerUframe,
            )
            Log.i(TAG, "init: nativeClaimInterface → result=$claimResult")
            check(claimResult == UsbAudioBridge.CLAIM_SUCCESS) {
                "nativeClaimInterface failed (status=$claimResult " +
                    "iface=$dsdInterfaceNumber alt=$dsdAltSetting)"
            }

            // ── Step 3: Program the UAC2 Clock Source to the DSD_U32LE frame rate ──
            // The DAC's internal PLL must be explicitly programmed via a UAC2
            // SET_CUR control transfer BEFORE the ISO alt setting is activated.
            // Without this the DAC keeps its power-on default clock (often
            // 44.1 or 48 kHz), leaving the high-bandwidth ISO endpoint unable
            // to open, which causes LIBUSB_ERROR_BUSY (-6) on transfer submit.
            //
            // DSD_U32LE frame rate (= UAC2 clock programmed by this step):
            //   DSD64  →  88 200 Hz  (2 822 400 ÷ 32)
            //   DSD128 → 176 400 Hz  (5 644 800 ÷ 32)
            //   DSD256 → 352 800 Hz  (11 289 600 ÷ 32)
            //
            // DAC display interpretation (bSubslotSize=4 = 32-bit DSD subslot):
            //   clock × 32 bits/subslot = DSD bit-rate per channel
            //   88 200 × 32 = 2 822 400 bit/s/ch → DSD64 ✓
            //
            // clockSourceId = -1 → auto-detect from config descriptor (fallback bClockID=1).
            // controlInterfaceNumber = 0 → UAC2 Audio Control interface.
            val clockResult = UsbAudioBridge.nativeSetUac2ClockSampleRate(
                handle = driverHandle,
                controlInterfaceNumber = 0,
                clockSourceId = -1,
                sampleRateHz = dsdFrameRateHz,
            )
            if (clockResult < 0) {
                // Non-fatal: some DACs self-negotiate the clock via the DSD transport.
                Log.w(
                    TAG, "init: nativeSetUac2ClockSampleRate FAILED " +
                        "(status=$clockResult dsdFrameRateHz=$dsdFrameRateHz ${dsdRate.displayName}) — " +
                        "proceeding; if the DAC still shows wrong DSD rate, verify Alt4 bSubslotSize " +
                        "with a USB descriptor dump. Expected: bSubslotSize=4 (32-bit DSD_U32LE)."
                )
            } else {
                Log.i(
                    TAG, "init: UAC2 clock source → $dsdFrameRateHz Hz " +
                        "(${dsdRate.displayName} DSD_U32LE frame rate: ${dsdRate.sampleRateHz / 1_000_000}.${(dsdRate.sampleRateHz % 1_000_000) / 100_000} MHz ÷ 32 bits/subslot) " +
                        "✓ (status=$clockResult)"
                )
            }

            // ── Inter-command delay: clock → alt-setting ──────────────────────
            // After the UAC2 SET_CUR clock transfer completes, the DAC's PLL needs
            // a short settling window before it can accept SET_INTERFACE cleanly.
            // On the FiiO KA5 (XMOS core) and similar Savitech chips, issuing
            // SET_INTERFACE without a pause causes the firmware to latch the
            // alt-setting request before its internal clock domain has committed to
            // the new rate.  The resulting race condition manifests as
            // LIBUSB_ERROR_BUSY (-6) on the first transfer submission.
            // 50 ms is sufficient for the XMOS PLL to stabilise on all known DSD
            // rates (DSD64/128/256) while adding negligible apparent latency.
            Log.d(TAG, "init: sleeping 50 ms between UAC2 clock SET_CUR and SET_INTERFACE (PLL settling)")
            Thread.sleep(50)

            // ── Step 4: Activate the ISO alternate setting (AFTER clock setup) ──
            // SET_INTERFACE opens the ISO endpoint and allocates USB bus bandwidth.
            // Must follow the clock SET_CUR so the DAC PLL has already locked to
            // the target rate; some XMOS/Savitech firmware ignores subsequent clock
            // updates once the alt setting is active (wrong-order → FSR ERROR).
            val altResult = UsbAudioBridge.nativeActivateAltSetting(
                handle          = driverHandle,
                interfaceNumber = dsdInterfaceNumber,
                altSetting      = dsdAltSetting,
                endpointAddress = dsdEndpointAddress,
            )
            Log.i(
                TAG, "init: nativeActivateAltSetting → result=$altResult " +
                    "(iface=$dsdInterfaceNumber alt=$dsdAltSetting)"
            )
            check(altResult == UsbAudioBridge.CLAIM_SUCCESS) {
                "nativeActivateAltSetting failed (status=$altResult " +
                    "iface=$dsdInterfaceNumber alt=$dsdAltSetting)"
            }

            // ── Step 5: Allocate the ISO transfer pool ────────────────────────
            // sampleRateHz  = DSD_U32LE frame rate (UAC2 clock freq, e.g. 88,200 Hz for DSD64).
            //                  This drives the Bresenham accumulator that computes per-µframe
            //                  packet sizes.  Must be consistent with the SET_CUR clock above.
            // bytesPerAudioFrame = 8 (L_U32 4B + R_U32 4B = one DSD_U32LE stereo frame).
            //                  Must match wire_bpf in decoder_to_ring_bridge.cpp pump_loop.
            //                  For DSD: wire_bpf = channel_count × bytes_per_sample = 2 × 4 = 8.
            //
            // Throughput check: 88,200 Hz × 8 B/frame = 705,600 B/s = DSD64 stereo ✓
            //                   (2,822,400 DSD bits/s per channel as expected)
            val poolResult = UsbAudioBridge.nativeAllocateTransferPool(
                handle = driverHandle,
                sampleRateHz = dsdFrameRateHz,
                bytesPerAudioFrame = dsdBytesPerFrame,
                endpointAddress = dsdEndpointAddress,
                poolSize = ISO_POOL_SIZE,
                packetsPerTransfer = ISO_PACKETS_PER_TRANSFER,
            )
            Log.i(TAG, "init: nativeAllocateTransferPool → result=$poolResult")
            check(poolResult == 0) {
                "nativeAllocateTransferPool failed (code=$poolResult ${dsdRate.displayName})"
            }

            // ── Step 6: Start DSD stream with automatic DoP fallback ──────────
            // Creates the SPSC ring buffer, starts the libusb event thread, submits
            // all ISO slots with startup silence. Stall observation is armed after
            // play() attaches the real producer.
            val dsdResult = UsbAudioBridge.nativeStartDsdPlayback(
                handle = driverHandle,
                nativeDsdInterface = dsdInterfaceNumber,
                nativeDsdAltSetting = dsdAltSetting,
                pcmInterface = pcmInterfaceNumber,
                pcmAltSetting = pcmAltSetting,
                pcmEndpoint = pcmEndpointAddress,
                pcmBandwidth = pcmBandwidth,
                startInDop = startInDop,
                supportsDopFallback = supportsDopFallback,
                useLsbf = false,       // MSBF is standard for all modern USB DSD DACs
                listener = dsdModeListener,
            )
            Log.i(
                TAG, "init: nativeStartDsdPlayback → result=$dsdResult " +
                    "(${dsdRate.displayName} " +
                    "driverHandle=0x${driverHandle.toULong().toString(16)})"
            )
            check(dsdResult == 0) {
                "nativeStartDsdPlayback failed (code=$dsdResult ${dsdRate.displayName})"
            }

            Log.i(TAG, "init: USB DSD setup complete ✓ — ISO transfers running (silence)")
        } catch (e: Exception) {
            // Roll back any partial state so the caller does not leak a libusb context.
            Log.e(TAG, "init: DSD setup FAILED — rolling back. Error: ${e.message}", e)
            if (UsbAudioBridge.isValidHandle(driverHandle)) {
                // nativeReleaseDsdManager is a no-op when nativeStartDsdPlayback was
                // never reached; calling it unconditionally is safe.
                UsbAudioBridge.nativeReleaseDsdManager(driverHandle)
                UsbAudioBridge.nativeRelease(driverHandle)
            }
            driverHandle = 0L
            throw e   // propagate so the factory can clean up decoder + connection
        }
    }

    // ── Native bridge handle ──────────────────────────────────────────────────

    /** Handle returned by [EngineSwapBridge.nativeAttachUsbEngine]; 0 when stopped. */
    private var bridgeHandle: Long = 0L

    // ── Position tracking ─────────────────────────────────────────────────────

    /** Wall-clock epoch (SystemClock.elapsedRealtime) captured when the pump starts. */
    private var playStartWallClockMs: Long = 0L

    /** Accumulated DSD frame position at the last pause/seek boundary. */
    private var pausedAtFrames: Long = 0L

    /**
     * Seek target requested while the pump was not attached (`bridgeHandle == 0`),
     * in microseconds from stream start. [play] must apply it to the native
     * decoder right after attaching — otherwise the pump starts decoding from the
     * beginning of the stream while [pausedAtFrames] (and therefore the reported
     * playback position) already reflects the seeked-to point.
     */
    private var pendingSeekUs: Long? = null

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var isPlaying: Boolean = false
    @Volatile private var closed: Boolean = false

    /**
     * Set to `true` by the native pump thread (via JNI) when end-of-stream is
     * reached. Polled by [BitPerfectPlaybackEngine]'s write-loop override.
     *
     * @see eofListener
     */
    override val eofFlag: AtomicBoolean = AtomicBoolean(false)

    // ── EOF listener ─────────────────────────────────────────────────────────

    /**
     * Receives the end-of-stream notification from the native pump thread.
     * The JNI layer calls `onDecoderEof()V` once the pump exhausts the decoder.
     */
    @Keep
    private inner class EofListener {
        /** Called from the native pump thread when [decoderHandle] reports EOF. */
        @Keep
        fun onDecoderEof() {
            eofFlag.set(true)
            Log.d(TAG, "onDecoderEof: native pump reached end of stream")
        }
    }

    private val eofListener = EofListener()

    // ── AudiophileOutputSink ──────────────────────────────────────────────────

    /**
     * Starts the pump thread and wires [decoderHandle] to the libusb ring buffer.
     *
     * Includes a 100 ms hardware stabilization sleep before submitting ISO transfers
     * so that the DAC's PLL — activated by [UsbAudioBridge.nativeActivateAltSetting]
     * during construction — has sufficient time to lock onto the DSD byte-rate clock
     * before the first isochronous packet is sent. Without this delay, XMOS/Savitech
     * firmware (FiiO KA5 and similar) may still be re-locking when the first transfer
     * arrives, producing [LIBUSB_ERROR_BUSY (-6)][java.io.IOException].
     *
     * DSD audio begins flowing through the ISO transfers after this call.
     * Calling [play] while already playing is a documented no-op.
     *
     * @throws IOException when [EngineSwapBridge.nativeAttachUsbEngine] fails.
     */
    override fun play() {
        checkNotClosed()
        if (isPlaying) return

        // ── Hardware PLL stabilization guard ──────────────────────────────────
        // After nativeActivateAltSetting (SET_INTERFACE) in init{}, the DAC's
        // internal PLL needs time to lock onto the new clock frequency before
        // isochronous transfers are submitted.  Without this delay, the FiiO KA5
        // (and other XMOS/Savitech firmware) may still be re-locking when the
        // first ISO OUT packet arrives → LIBUSB_ERROR_BUSY (-6) or STALL.
        // 100 ms is well within the 200 ms DSD observation window, so the DAC
        // will have settled before the DsdPlaybackManager starts watching for stalls.
        Log.d(TAG, "play: sleeping 100 ms for DAC PLL lock before attaching engine")
        Thread.sleep(100)

        // Apply any seek that was requested while the pump was detached BEFORE
        // attaching the engine: nativeAttachUsbEngine pre-fills the ring from
        // the decoder's current position and the ring is never cleared, so a
        // post-attach seek leaves start-of-track DSD in the ring and the DAC
        // audibly replays the beginning before jumping to the target position.
        val preAttachSeekUs = pendingSeekUs
        if (preAttachSeekUs != null) {
            pendingSeekUs = null
            val seekOk = EngineSwapBridge.nativeSeekUsbDecoder(decoderHandle, preAttachSeekUs)
            Log.d(TAG, "play: applied pre-attach seek positionUs=$preAttachSeekUs seekOk=$seekOk")
        }

        val handle = EngineSwapBridge.nativeAttachUsbEngine(
            driverHandle, decoderHandle, eofListener, initialVolume
        )
        // ARM64 MTE/HWASan: heap pointers have the top-byte set → appear negative as jlong.
        // NEVER check `handle > 0L` — use isValidBridgeHandle() to avoid false rejects.
        if (!EngineSwapBridge.isValidBridgeHandle(handle)) {
            throw IOException(
                "LibusbDsdAudioSink.play: nativeAttachUsbEngine failed (code=$handle)"
            )
        }
        bridgeHandle = handle

        playStartWallClockMs = SystemClock.elapsedRealtime()
        isPlaying = true
        Log.d(TAG, "play: pump attached — bridgeHandle=$bridgeHandle dsdRate=${dsdRate.displayName}")
    }

    /**
     * Stops the pump thread and snapshots the current position for seamless resume.
     * Calling [pause] while already paused is a no-op.
     */
    override fun pause() {
        if (!isPlaying) return
        pausedAtFrames = getPlaybackHeadPositionFrames()
        stopNativePump()
        isPlaying = false
        Log.d(TAG, "pause: pump detached — pausedAtFrames=$pausedAtFrames")
    }

    /** Stops the pump thread; equivalent to [pause] on the libusb path. */
    override fun stop() = pause()

    /**
     * No-op flush — the libusb ring buffer is not directly flushable from Kotlin.
     * Use [seekTo] to discard buffered audio.
     */
    override fun flush() = Unit

    /**
     * No-op write — data is pushed natively by the pump thread.
     * Returns [size] so the engine's write loop treats this as success.
     *
     * @param buffer Ignored.
     * @param size   Returned unchanged.
     */
    override fun write(buffer: ByteBuffer, size: Int): Int = size

    /**
     * Returns the estimated playback head in one-bit DSD frames, derived from
     * wall-clock elapsed time since [play] was last called.
     *
     * When paused or not yet played, returns [pausedAtFrames].
     */
    override fun getPlaybackHeadPositionFrames(): Long {
        if (!isPlaying || playStartWallClockMs == 0L) return pausedAtFrames
        val elapsedMs = SystemClock.elapsedRealtime() - playStartWallClockMs
        return pausedAtFrames + elapsedMs * dsdRate.sampleRateHz / 1_000L
    }

    /**
     * Performs the full libusb DSD teardown in the correct destruction order:
     *
     * 1. Stop pump thread ([EngineSwapBridge.nativeDetachUsbEngine]).
     * 2. Free native decoder ([EngineSwapBridge.nativeReleaseUsbDecoder]).
     * 3. Destroy DSD monitor thread ([UsbAudioBridge.nativeReleaseDsdManager]).
     * 4. Release libusb context ([UsbAudioBridge.nativeRelease]).
     * 5. Close Android USB device connection.
     *
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    override fun close() {
        if (closed) return
        closed = true
        Log.d(TAG, "close: tearing down libusb DSD resources")
        stopNativePump()
        EngineSwapBridge.nativeReleaseUsbDecoder(decoderHandle)
        if (UsbAudioBridge.isValidHandle(driverHandle)) {
            UsbAudioBridge.nativeReleaseDsdManager(driverHandle)
            UsbAudioBridge.nativeRelease(driverHandle)
        }
        runCatching { connection.close() }
            .onFailure { e -> Log.w(TAG, "UsbDeviceConnection.close failed", e) }
        Log.d(TAG, "close: done")
    }

    // ── LibusbOutputSink extended API ─────────────────────────────────────────

    /**
     * Seeks the native pump and decoder to [positionMs] milliseconds from stream
     * start and resets the wall-clock position epoch.
     *
     * @param positionMs Target seek position in milliseconds from stream start.
     * @return `true` when the native seek succeeded.
     */
    override fun seekTo(positionMs: Long): Boolean {
        checkNotClosed()
        val positionUs = positionMs * 1_000L

        val seekOk = if (bridgeHandle != 0L) {
            pendingSeekUs = null
            EngineSwapBridge.nativePumpBridgeSeek(bridgeHandle, decoderHandle, positionUs)
        } else {
            // Pump stopped (e.g. paused, or not yet attached after a fresh load).
            // Stash the target so play() can apply it right after attaching.
            pendingSeekUs = positionUs
            Log.d(TAG, "seekTo: pump not running — position pending for next play()")
            true
        }

        pausedAtFrames = positionMs * dsdRate.sampleRateHz / 1_000L
        if (isPlaying) playStartWallClockMs = SystemClock.elapsedRealtime()

        Log.d(TAG, "seekTo: positionMs=$positionMs seekOk=$seekOk")
        return seekOk
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun stopNativePump() {
        val handle = bridgeHandle
        if (handle != 0L) {
            EngineSwapBridge.nativeDetachUsbEngine(handle)
            bridgeHandle = 0L
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "LibusbDsdAudioSink is already closed" }
    }

    private companion object {
        const val TAG = "LibusbDsdSink"

        /** Pre-allocated ISO OUT transfer slots. 16 × 1 ms = 16 ms of pipeline depth. */
        const val ISO_POOL_SIZE = 16

        /**
         * ISO packets (microframes) per transfer slot.
         * 8 µframes × 125 µs = 1 ms per transfer submit.
         */
        const val ISO_PACKETS_PER_TRANSFER = 8

        /** Stereo DSD: two channels (Left + Right) per USB audio frame. */
        const val DSD_STEREO_CHANNELS = 2

        /** DSD bits carried by each channel in one DoP PCM frame. */
        const val DOP_DSD_BITS_PER_PCM_FRAME = 16

        /** Native mode ordinal mirrored by the JNI callback contract. */
        const val MODE_NATIVE_DSD = 0
        const val MODE_DOP = 1

        /**
         * DSD bits per USB subslot per channel in the DSD_U32LE format.
         *
         * The native DSD formatter ([native_dsd_formatter.cpp]) packs 4 bytes (32 DSD bits)
         * of audio into each subslot per channel.  The UAC2 clock frequency is therefore:
         * ```
         *   clock_hz = dsdSampleRateHz / DSD_BITS_PER_U32_SUBSLOT
         * ```
         * e.g. DSD64: 2,822,400 / 32 = 88,200 Hz.
         *
         * With this clock the FiiO KA5 (and other 32-bit subslot DACs) compute:
         *   88,200 × 32 = 2,822,400 DSD bits/sec per channel = DSD64 ✓
         *
         * The prior value of `sampleRateHz / 8` (8-bit mapping) caused the DAC to
         * misread the clock as: 352,800 × 32 = 11,289,600 bit/s/ch = **DSD256**.
         */
        const val DSD_BITS_PER_U32_SUBSLOT = 32

        /**
         * Bytes per channel per USB audio frame for the DSD_U32LE subformat.
         *
         * One DSD_U32 word = 4 bytes = 32 DSD bits per channel.
         * For stereo: [DSD_U32_BYTES_PER_CHANNEL] × [DSD_STEREO_CHANNELS] = 8 bytes / frame.
         * This MUST match `wire_bpf` in `decoder_to_ring_bridge.cpp`
         * (`wire_bpf = channel_count × bytes_per_sample = 2 × 4 = 8` for DSD).
         */
        const val DSD_U32_BYTES_PER_CHANNEL = 4

    }
}
