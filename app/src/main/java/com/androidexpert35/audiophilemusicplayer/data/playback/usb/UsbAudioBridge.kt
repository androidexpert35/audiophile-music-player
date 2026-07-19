package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.CLAIM_SUCCESS
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.ERR_LIBUSB_INIT
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.ERR_NO_DISCOVERY_OPT
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.ERR_OUT_OF_MEMORY
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.ERR_WRAP_SYS_DEVICE
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.isValidHandle
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.nativeActivateAltSetting
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.nativeClaimInterface
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.nativeInitWithFileDescriptor
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.nativeRelease
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.nativeReleaseDsdManager
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.nativeSetUac2ClockSampleRate
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioBridge.nativeStartPlayback


/**
 * JNI bridge to the libusb-based native USB audio driver (`usb_audio_bridge.cpp`).
 *
 * Provides the step-by-step initialization sequence for the libusb isochronous
 * audio engine:
 *
 * ```
 * nativeInitWithFileDescriptor(fd)          // Steps 1–4: context, no-discovery, wrap FD
 *   → nativeClaimInterface(...)             // Step 3a: claim streaming iface (no alt yet)
 *   → nativeActivateAltSetting(..., 0)      // Step 3b: SET_INTERFACE Alt-0 — cold-reset DSD lock
 *   → nativeSetUac2ClockSampleRate(...)     // Step 3c: claim ctrl iface 0 + SET_CUR clock
 *   → nativeActivateAltSetting(..., alt)    // Step 3d: SET_INTERFACE — activate ISO alt
 *   → nativeAllocateTransferPool(...)       // Step 4: pre-allocate ISO descriptors
 *   → nativeStartPlayback(handle)           // Step 5: PCM — ring + event thread
 *   OR
 *   → nativeStartDsdPlayback(...)           // Step 13: DSD — ring + event thread + DSD manager
 *   → nativeReleaseDsdManager(handle)       // optional early monitor join
 *   → nativeRelease(handle)                 // full teardown + delete context
 * ```
 *
 * All returned handles are opaque [Long] pointers to native heap objects. A handle
 * becomes invalid after the matching release call and must not be reused.
 */
internal object UsbAudioBridge {

    // ── Native error codes returned by nativeInitWithFileDescriptor ──────────────
    // Mirror the ERR_* constants defined in usb_audio_bridge.cpp.
    const val ERR_LIBUSB_INIT: Long      = -1L
    const val ERR_NO_DISCOVERY_OPT: Long = -2L
    const val ERR_WRAP_SYS_DEVICE: Long  = -3L
    const val ERR_OUT_OF_MEMORY: Long    = -4L

    /** nativeClaimInterface success code (mirrors UsbControlStatus::Success). */
    const val CLAIM_SUCCESS = 0

    /**
     * Returns `true` when [handle] is a valid driver context handle.
     *
     * **Do NOT use `handle > 0L` for this check.** On ARM64 with Android's
     * Memory Tagging Extension (MTE) or Hardware-Assisted AddressSanitizer
     * (HWASan), heap allocations carry a memory tag in the top byte of the
     * pointer (e.g. `0xb4000075…`). When the native `UsbDriverContext*` pointer
     * is cast to a `jlong`, addresses with the top bit set appear **negative**
     * from Java's perspective, causing a `> 0L` guard to falsely reject a
     * perfectly valid handle.
     *
     * The only truly invalid return values are:
     * - `0L`   — null pointer (OOM or allocation failure before any context is allocated)
     * - `-1L`  — [ERR_LIBUSB_INIT]       (`libusb_init()` failed)
     * - `-2L`  — [ERR_NO_DISCOVERY_OPT]  (`set_option` failed)
     * - `-3L`  — [ERR_WRAP_SYS_DEVICE]   (`libusb_wrap_sys_device()` failed)
     * - `-4L`  — [ERR_OUT_OF_MEMORY]     (heap allocation failed)
     *
     * Any other value — positive *or* negative — is a valid tagged ARM64 heap
     * pointer and must be passed back to [nativeRelease] when done.
     *
     * @param handle Return value from [nativeInitWithFileDescriptor].
     * @return `true` when [handle] is safe to use with subsequent native calls.
     */
    fun isValidHandle(handle: Long): Boolean =
        handle != 0L &&
        handle != ERR_LIBUSB_INIT &&
        handle != ERR_NO_DISCOVERY_OPT &&
        handle != ERR_WRAP_SYS_DEVICE &&
        handle != ERR_OUT_OF_MEMORY

    /**
     * Returns a human-readable description of an init error code, or an
     * unexpected-code diagnostic for any value not in the known error range.
     *
     * @param code Return value from [nativeInitWithFileDescriptor] that failed [isValidHandle].
     */
    fun describeInitError(code: Long): String = when (code) {
        0L                  -> "null handle — heap allocation failed before context was set up"
        ERR_LIBUSB_INIT     -> "ERR_LIBUSB_INIT (-1): libusb_init() failed (SELinux /dev/bus/usb scan; ensure global NO_DEVICE_DISCOVERY pre-set)"
        ERR_NO_DISCOVERY_OPT -> "ERR_NO_DISCOVERY_OPT (-2): libusb_set_option(NO_DEVICE_DISCOVERY) rejected by this libusb build"
        ERR_WRAP_SYS_DEVICE -> "ERR_WRAP_SYS_DEVICE (-3): libusb_wrap_sys_device() failed — FD may be stale, the device was unplugged, or the OEM kernel revoked it"
        ERR_OUT_OF_MEMORY   -> "ERR_OUT_OF_MEMORY (-4): heap allocation for UsbDriverContext failed"
        else                -> "unexpected code=$code (0x${code.toULong().toString(16)}) — " +
                               "this may be a valid ARM64 tagged pointer being misclassified; review the isValidHandle() logic"
    }

    // ── Initialization ────────────────────────────────────────────────────────────

    /**
     * Step 1–4 — Initialise the libusb context from an Android-granted USB file
     * descriptor.
     *
     * Creates a heap-allocated [UsbDriverContext], calls `libusb_init` on an
     * isolated context, sets `LIBUSB_OPTION_NO_DEVICE_DISCOVERY` (mandatory on
     * Android to avoid SELinux-blocked `/dev/bus/usb` scans), and wraps [fd] via
     * `libusb_wrap_sys_device`.
     *
     * @param fd File descriptor from [android.hardware.usb.UsbDeviceConnection.fileDescriptor].
     * @return Opaque driver context handle on success (passes [isValidHandle]; may appear
     *         negative on ARM64 MTE/HWASan due to pointer tagging — never use `> 0L`),
     *         or a negative [ERR_*] sentinel in the range `[-4, -1]` on failure.
     */
    @JvmStatic
    external fun nativeInitWithFileDescriptor(fd: Int): Long

    // ── Interface claim ───────────────────────────────────────────────────────────

    /**
     * Step 2 — Scan UAC2 descriptors and select the isochronous OUT alternate
     * setting whose `bSubslotSize` / `bBitResolution` fields best match the
     * source audio's wire bit depth.
     *
     * Must be called **after** [nativeInitWithFileDescriptor] returns a valid handle,
     * because the libusb device handle created in Step 1 is required to retrieve
     * the active configuration descriptor for parsing.
     *
     * ### Selection algorithm
     *
     * All candidate endpoints are enumerated from the USB config descriptor and logged
     * with their parsed `bSubslotSize` and `bBitResolution`.  A four-pass cascade then
     * selects the best match:
     *
     * | Pass | Criteria |
     * |------|----------|
     * | 1 | exact `bSubslotSize` + exact `bBitResolution` + sufficient bandwidth |
     * | 2 | exact `bSubslotSize` + sufficient bandwidth |
     * | 3 | `bSubslotSize ≥ target` + sufficient bandwidth (overprovisioned) |
     * | 4 | sufficient bandwidth only (last resort) |
     *
     * `effectiveBitDepth` must reflect the **wire** bit depth after any packing:
     * - 16-bit PCM               → `16`
     * - 24-bit PCM (packed S24LE after S32→S24 pump conversion) → `24`
     * - 32-bit PCM               → `32`
     *
     * This prevents sending 16-bit frames to a 32-bit alt-setting endpoint (causing
     * the DAC's hardware parser to desync and display **"FSR ERROR"**).
     *
     * @param handle            Context handle from [nativeInitWithFileDescriptor].
     * @param sampleRateHz      Source sample rate in Hz (e.g. `44100`, `192000`).
     * @param effectiveBitDepth Wire bit depth after any packing: `16`, `24`, or `32`.
     * @param channelCount      Number of audio channels (e.g. `2` for stereo).
     * @param requireExactPcm   When `true`, accepts only a Type-I linear-PCM
     *   endpoint with the exact requested subslot width, a valid advertised bit
     *   resolution, and compatible channels. Enhanced float-to-S32 uses this mode.
     * @return 6-element [IntArray] `[interfaceNumber, altSetting, endpointAddress,
     *         effectiveBytesPerUframe, subslotBytes, validBitDepth]` on success,
     *         or `null` when no suitable endpoint was found or the handle is invalid.
     *         The final two fields are the selected alternate setting's
     *         `bSubslotSize` and effective `bBitResolution`.
     */
    @JvmStatic
    external fun nativeSelectBestEndpointForFormat(
        handle: Long,
        sampleRateHz: Int,
        effectiveBitDepth: Int,
        channelCount: Int,
        requireExactPcm: Boolean,
    ): IntArray?

    /**
     * Step 3 — Claim the USB audio streaming interface and activate its
     * isochronous alternate setting.
     *
     * Must be called after [nativeInitWithFileDescriptor] returns a handle that
     * passes [isValidHandle]. On ARM64 MTE/HWASan devices valid handles may appear
     * negative as a signed [Long] — never guard with `handle > 0L`; always use
     * [isValidHandle] instead.
     *
     * @param handle                 Context handle from [nativeInitWithFileDescriptor].
     * @param interfaceNumber        `bInterfaceNumber` of the streaming interface.
     * @param altSetting             `bAlternateSetting` to activate (must be ≥ 1 for
     *                               ISO endpoints; alt 0 carries zero bandwidth).
     * @param endpointAddress        `bEndpointAddress` of the ISO OUT endpoint.
     * @param effectiveBytesPerUframe Bytes per USB microframe, used for diagnostic
     *                               logging only; does not affect the libusb claim.
     * @return [CLAIM_SUCCESS] (0) on success; a positive `UsbControlStatus` code
     *         on failure (see `usb_audio_bridge.cpp` for the full status table).
     */
    @JvmStatic
    external fun nativeClaimInterface(
        handle: Long,
        interfaceNumber: Int,
        altSetting: Int,
        endpointAddress: Int,
        effectiveBytesPerUframe: Int,
    ): Int

    // ── UAC2 clock source ─────────────────────────────────────────────────────────

    /**
     * Step 3b — Program the UAC2 Clock Source entity to the stream's sample rate.
     *
     * Must be called **after** [nativeClaimInterface] returns [CLAIM_SUCCESS] and
     * **before** [nativeActivateAltSetting].
     *
     * ### Why this is required
     *
     * In USB Audio Class 2.0 a `SET_INTERFACE` only reserves isochronous bus bandwidth.
     * The DAC's internal sample-rate oscillator is a separate Clock Source entity
     * that must be explicitly programmed via a UAC2 SET_CUR control transfer.  Without
     * this step the DAC keeps its power-on default rate and displays **"FSR ERROR"**
     * (Frequency Sample Rate mismatch), then mutes its output regardless of what data
     * the host streams.
     *
     * ### Control interface auto-claim
     *
     * This function automatically calls `libusb_claim_interface(0)` on the Audio
     * Control interface before the control transfer.  Without that claim, Android's
     * USB stack returns `LIBUSB_ERROR_PIPE` because the host rejects control
     * transfers targeting unclaimed interfaces.  The claim is released during
     * [nativeRelease] via teardown_context().
     *
     * ### Clock Source ID auto-detection
     *
     * Pass [clockSourceId] ≤ 0 to trigger auto-detection from the USB config
     * descriptor.  The native layer falls back to `bClockID=1` if parsing fails —
     * the correct value for the FiiO KA5 and most XMOS/ESS-based consumer UAC2 DACs.
     *
     * @param handle                   Context handle from [nativeInitWithFileDescriptor].
     * @param controlInterfaceNumber   `bInterfaceNumber` of the Audio Control interface
     *                                 (almost always `0` for UAC2 devices).
     * @param clockSourceId            UAC2 `bClockID` of the Clock Source entity, or ≤ 0
     *                                 to auto-detect from the config descriptor.
     * @param sampleRateHz             Target sample rate in Hz (e.g., `192000`).
     * @return                         ≥ 0 (bytes transferred) on success;
     *                                 negative libusb error code on failure.
     */
    @JvmStatic
    external fun nativeSetUac2ClockSampleRate(
        handle: Long,
        controlInterfaceNumber: Int,
        clockSourceId: Int,
        sampleRateHz: Int,
    ): Int

    /**
     * Activates an alternate setting on the USB audio streaming interface
     * (`SET_INTERFACE` control transfer).
     *
     * This function is called in two distinct roles during PCM initialisation:
     *
     * #### Role A — Cold-reset (DSD-to-PCM transition, altSetting = 0)
     *
     * After Native DSD playback the XMOS USB receiver chip is stuck in its DSD
     * streaming mode (usually Alt 4).  Passing `altSetting = 0` drops the
     * interface to zero isochronous bandwidth, which tears down the chip's DSD
     * state machine and resets the PLL to idle.  Without this step the PCM clock
     * `SET_CUR` sent in the following [nativeSetUac2ClockSampleRate] call is
     * silently ignored by a chip still expecting DSD framing, causing
     * **"FSR ERROR"** on the DAC display and silent output.
     *
     * Call sequence when transitioning from DSD to PCM:
     * ```
     * nativeClaimInterface()           // claim streaming iface
     * nativeActivateAltSetting(0)      // Alt-0: clear DSD lock — this role
     * Thread.sleep(20)                 // allow XMOS PLL to reach idle
     * nativeSetUac2ClockSampleRate()   // SET_CUR: program PCM clock
     * nativeActivateAltSetting(alt)    // Alt-N: open ISO bandwidth — Role B
     * nativeAllocateTransferPool()
     * ```
     *
     * #### Role B — ISO activation (altSetting ≥ 1)
     *
     * After the DAC PLL has been programmed via [nativeSetUac2ClockSampleRate],
     * this call opens isochronous bus bandwidth and arms the DAC's ISO receiver.
     * FiiO KA, XMOS, and Savitech chips require the clock `SET_CUR` to be sent
     * **before** this `SET_INTERFACE` request; reversing the order causes some
     * firmware versions to ignore subsequent clock updates because the PLL
     * already locked to its power-on default.
     *
      * @param handle            Context handle from [nativeInitWithFileDescriptor].
      * @param interfaceNumber   `bInterfaceNumber` of the streaming interface.
      * @param altSetting        `0` for Role A (DSD cold-reset); ≥ 1 for Role B
      *                          (ISO endpoint activation).
      * @param endpointAddress   `bEndpointAddress` of the isochronous OUT endpoint
      *                          (e.g., `0x01`).  Used by the C++ layer to issue
      *                          `libusb_clear_halt` after SET_INTERFACE for alt ≥ 1,
      *                          preventing the XMOS USB-receiver HALT deadlock.
      *                          Pass `0` for Role-A cold-reset calls where no ISO
      *                          endpoint is active and `clear_halt` is not needed.
      * @return [CLAIM_SUCCESS] (0) on success; a positive `UsbControlStatus` code
      *         on failure (same codes as [nativeClaimInterface]).
      *         A non-zero result from a Role A (alt = 0) call is **non-fatal** —
      *         the DAC may already be idle; log and continue.
      */
    @JvmStatic
    external fun nativeActivateAltSetting(
        handle: Long,
        interfaceNumber: Int,
        altSetting: Int,
        endpointAddress: Int,
    ): Int

    // ── Transfer pool ─────────────────────────────────────────────────────────────

    /**
     * Step 4 — Pre-allocate the isochronous OUT transfer pool.
     *
     * Must be called after [nativeClaimInterface] returns [CLAIM_SUCCESS]. Allocates
     * [poolSize] libusb transfer descriptors and their DMA-aligned payload buffers.
     * The pool is destroyed automatically by [nativeRelease].
     *
     * **DSD byte-rate note**: DSD stores 8 one-bit samples per byte per channel. Pass
     * `sampleRateHz = dsdRate.sampleRateHz / 8` (the byte rate) and
     * `bytesPerAudioFrame = 2` (stereo) so `IsoTransferPool` computes the correct
     * per-µframe buffer size.
     *
     * @param handle               Context handle.
     * @param sampleRateHz         Audio sample rate in Hz. For DSD: `bitRate / 8`.
     * @param bytesPerAudioFrame   Bytes per interleaved audio frame (e.g., 2 for stereo DSD).
     * @param endpointAddress      ISO OUT endpoint address (same as [nativeClaimInterface]).
     * @param poolSize             Number of pre-allocated slots (e.g., 16).
     * @param packetsPerTransfer   ISO packets per transfer (8 = 1 ms per submit at 8 000 µframes/s).
     * @return 0 on success; negative on failure.
     */
    @JvmStatic
    external fun nativeAllocateTransferPool(
        handle: Long,
        sampleRateHz: Int,
        bytesPerAudioFrame: Int,
        endpointAddress: Int,
        poolSize: Int,
        packetsPerTransfer: Int,
    ): Int

    // ── Stream start ──────────────────────────────────────────────────────────────

    /**
     * Step 5 (PCM) — Start the isochronous PCM stream.
     *
     * Creates the SPSC ring buffer (128 KB), attaches it to the transfer pool,
     * starts the libusb event thread, and submits all pool slots. Transfers carry
     * silence until the decoder pump thread fills the ring.
     *
     * The matching [EngineSwapBridge.nativeAttachUsbEngine] call must be made after
     * this function returns successfully to wire the decoder to the ring buffer.
     *
     * @param handle Context handle from [nativeInitWithFileDescriptor].
     * @return 0 on success; negative on failure.
     */
    @JvmStatic
    external fun nativeStartPlayback(handle: Long): Int

    /**
     * Step 13 (DSD) — Start the isochronous DSD stream with automatic DoP fallback.
     *
     * Extends [nativeStartPlayback] with a [DsdPlaybackManager] that arms a 200 ms
     * native-DSD stall observation window. If a `LIBUSB_TRANSFER_STALL` is detected
     * within the window, the manager switches the context's `dsd_formatter` to DoP
     * and notifies [listener] via `onEngineModeChanged(1)`.
     *
     * ### Listener contract
     * [listener] must be a Kotlin/Java object with the method:
     * ```kotlin
     * fun onEngineModeChanged(mode: Int)  // 0 = NativeDsd, 1 = DoP
     * ```
     * Hold a strong reference to [listener] for the lifetime of the DSD session to
     * prevent premature GC.
     *
     * ### Destruction order (critical)
     * [nativeRelease] joins the monitor as a final safeguard; an earlier
     * [nativeReleaseDsdManager] call remains useful during explicit DSD shutdown.
     *
     * @param handle                 Context handle.
     * @param nativeDsdInterface     `bInterfaceNumber` of the native-DSD alt setting.
     * @param nativeDsdAltSetting    `bAlternateSetting` of the native-DSD ISO endpoint.
     * @param pcmInterface           `bInterfaceNumber` of the DoP PCM alt setting.
     * @param pcmAltSetting          `bAlternateSetting` of the DoP PCM ISO endpoint.
     * @param pcmEndpoint            `bEndpointAddress` of the DoP PCM ISO endpoint.
     * @param pcmBandwidth           Maximum bytes per USB microframe for that endpoint.
     * @param startInDop             Starts directly in DoP for a DoP-only DAC.
     * @param supportsDopFallback    Whether a validated fallback endpoint exists.
     * @param useLsbf                `true` for LSBF byte ordering (rare legacy DACs);
     *                               `false` for MSBF (standard for all modern DACs).
     * @param listener               Mode-change notification target.
     * @return 0 on success; negative on failure.
     */
    @JvmStatic
    external fun nativeStartDsdPlayback(
        handle: Long,
        nativeDsdInterface: Int,
        nativeDsdAltSetting: Int,
        pcmInterface: Int,
        pcmAltSetting: Int,
        pcmEndpoint: Int,
        pcmBandwidth: Int,
        startInDop: Boolean,
        supportsDopFallback: Boolean,
        useLsbf: Boolean,
        listener: Any,
    ): Int

    // ── Release ───────────────────────────────────────────────────────────────────

    /**
     * Releases all libusb resources and deletes the driver context.
     *
     * Calls `teardown_context` (cancels in-flight transfers, joins the event thread,
     * releases the claimed interface, frees the transfer pool) then `delete ctx`.
     *
     * Safe to call with `handle == 0L` or a known error sentinel (`-1L` … `-4L`) — no-op.
     * After this call [handle] is invalid.
     *
     * @param handle Context handle from [nativeInitWithFileDescriptor].
     */
    @JvmStatic
    external fun nativeRelease(handle: Long)

    /**
     * Destroys the [DsdPlaybackManager] and joins its monitor thread.
     *
     * May be called before [nativeRelease] to end observation promptly.
     * [nativeRelease] also performs this join as an ownership safeguard.
     * Calling when no DSD manager exists is a safe no-op.
     *
     * @param handle Context handle from [nativeInitWithFileDescriptor].
     */
    @JvmStatic
    external fun nativeReleaseDsdManager(handle: Long)
}
