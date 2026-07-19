// ─────────────────────────────────────────────────────────────────────────────
// usb_driver_context.h
//
// Shared driver-context aggregate for the user-space USB Audio Class 2.0 driver.
//
// UsbDriverContext is the single root object that owns every C++ resource
// allocated during the lifetime of a USB DAC connection.  It is heap-allocated
// in nativeInitWithFileDescriptor() and returned to the Kotlin layer as a jlong
// opaque handle.  All subsequent JNI calls receive the jlong and cast it back to
// UsbDriverContext* to access the appropriate sub-system.
//
// Ownership model:
//   jlong handle  ──▶  UsbDriverContext*  (heap, owned by Kotlin via handle)
//                        ├─ libusb_context*           (libusb session)
//                        ├─ libusb_device_handle*     (device connection)
//                        ├─ int device_fd             (diagnostic only)
//                        ├─ int claimed_interface_number
//                        ├─ int active_alt_setting
//                        ├─ unique_ptr<IsoTransferPool>
//                        ├─ unique_ptr<SpscRingBuffer>
//                        └─ unique_ptr<LibusbEventThread>
//
// Destruction order is handled by usb_teardown.cpp:
//   Phases 1–3 : cancel + drain in-flight transfers (event thread still running)
//   Phase  4   : stop + join event thread
//   Phase  5   : destroy ring buffer
//   Phase  6   : destroy transfer pool (libusb_free_transfer + free)
//   Phase  7   : release USB interface claim
//   Phase  8   : libusb_close
//   Phase  9   : libusb_exit
//
// This header is included by:
//   • usb_audio_bridge.cpp  — JNI entry points, context allocation/deletion
//   • usb_teardown.cpp      — teardown implementation
//
// It must remain free of JNI types (<jni.h> not included here).
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <memory>

#include "usb_iso_transfer_pool.h"   // IsoTransferPool
#include "spsc_ring_buffer.h"        // SpscRingBuffer
#include "libusb_event_thread.h"     // LibusbEventThread
#include "native_dsd_formatter.h"    // DsdFrameFormatter, DsdEngineMode
#include "usb_pcm_wire_format.h"
#include "usb_playback_state.h"
#include "usb_producer_coordinator.h"

// Forward declarations — full types resolved via the headers above.
struct libusb_context;
struct libusb_device_handle;

// Forward declaration — full type in dsd_playback_manager.h.
// Avoids a circular include since DsdPlaybackManager includes usb_driver_context.h.
class DsdPlaybackManager;

// ─────────────────────────────────────────────────────────────────────────────
// UsbDriverContext
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Opaque driver context that aggregates every libusb resource owned by the
 * user-space audio driver for a single USB DAC connection.
 *
 * ### Lifecycle
 *
 * Allocated in `nativeInitWithFileDescriptor()` via `new (std::nothrow)`.
 * Released in `nativeRelease()` via `teardown_context()` + `delete`.
 *
 * Fields are added progressively across JNI steps and are populated only on
 * successful completion of the corresponding step:
 *
 * | Step | Field(s)                        | Populated by                   |
 * |------|---------------------------------|--------------------------------|
 * |  1   | `usb_ctx`, `device_handle`      | `nativeInitWithFileDescriptor` |
 * |  3   | `claimed_interface_number`,     | `nativeClaimInterface`         |
 * |      | `active_alt_setting`            |                                |
 * |  4   | `transfer_pool`                 | `nativeAllocateTransferPool`   |
 * |  6   | `ring_buffer`                   | `nativeStartPlayback`          |
 * |  9   | `event_thread`                  | `nativeStartPlayback`          |
 *
 * ### Thread safety
 *
 * The context is touched by only two threads in normal operation:
 *   - The Kotlin audio/init thread, which calls into JNI entry points.
 *   - The libusb event thread (`event_thread`), which accesses only the pool's
 *     `TransferSlot` ring atomics and the ring-buffer pointer.
 *
 * The JNI entry points are not re-entrant by design; the Kotlin layer is
 * required to serialise calls to `nativeInitWithFileDescriptor`,
 * `nativeStartPlayback`, and `nativeRelease`.
 *
 * @property usb_ctx                    Isolated libusb session context.
 * @property device_handle              Open handle to the connected USB DAC.
 * @property device_fd                  Raw FD from `UsbManager.openDevice()` —
 *                                      stored for diagnostics only; never closed
 *                                      by the C++ driver.
 * @property claimed_interface_number   `bInterfaceNumber` currently claimed by
 *                                      this process, or -1 if none.
 * @property active_alt_setting         `bAlternateSetting` currently active on
 *                                      the claimed interface, or -1 if none.
 * @property iso_endpoint_address       `bEndpointAddress` of the ISO OUT endpoint,
 *                                      mirrored from `IsoTransferPool::config()` at
 *                                      pool-allocation time so Phase 6.5 teardown can
 *                                      call `libusb_clear_halt()` after the pool is gone.
 * @property was_dsd_session            `true` when `nativeStartDsdPlayback()` completed
 *                                      successfully; triggers the XMOS Soft Reset in
 *                                      Phase 6.5 of `teardown_context()`.
 * @property pcm_valid_bit_depth         Valid PCM bits inside the selected UAC2
 *                                      subslot; used by the enhanced S32LE packer.
 * @property transfer_pool              RAII pool of iso OUT transfer descriptors
 *                                      Null until allocation succeeds.
 * @property ring_buffer                Lock-free SPSC audio ring buffer.
 *                                      Null until playback starts.
 * @property event_thread               Dedicated libusb event-processing thread
 *                                      Null until playback starts.
 */
struct UsbDriverContext {

    // ── libusb session and device ────────────────────────────────────────────

    /**
     * Isolated libusb session.
     * Initialised by `libusb_init()` in nativeInitWithFileDescriptor.
     * Always destroyed last (Phase 9 of teardown) to ensure no dangling
     * handles reference it.
     */
    libusb_context *usb_ctx = nullptr;

    /**
     * Open libusb device handle.
     * Obtained via `libusb_wrap_sys_device()` from the Java-granted FD.
     * The underlying FD is owned by `UsbDeviceConnection` (Java-side);
     * the driver must never call `close()` on the raw FD itself.
     */
    libusb_device_handle *device_handle = nullptr;

    /**
     * Raw inode file descriptor received from `UsbManager.openDevice()`.
     * Stored for diagnostic logging only.  Set to -1 after teardown.
     */
    int device_fd = -1;

    // ── Interface ownership ───────────────────────────────────────────────────

    /**
     * `bInterfaceNumber` of the AudioStreaming interface currently claimed by
     * this process, or -1 when no interface has been claimed yet (or after
     * release).  Stored here so `uac2_release_interface()` can be called
     * unconditionally from `teardown_context()`.
     */
    int claimed_interface_number = -1;

    /**
     * `bAlternateSetting` active on the claimed interface, or -1.
     * Used for logging during teardown (Phase 7).
     */
    int active_alt_setting = -1;

    /**
     * `bInterfaceNumber` of the Audio Control interface claimed by
     * `nativeSetUac2ClockSampleRate()` before the UAC2 SET_CUR control
     * transfer, or -1 when not claimed.
     *
     * ### Why the Control interface must be explicitly claimed
     *
     * Android's USB stack (libusb + kernel) rejects control transfers that
     * target an interface the process has not explicitly claimed.  The Audio
     * Control interface is separate from the AudioStreaming interface and is
     * almost always interface 0.  Without claiming it first,
     * `libusb_control_transfer` with `wIndex` lower-byte = 0 returns
     * `LIBUSB_ERROR_PIPE` because the host rejects the request.
     *
     * Teardown releases this claim independently of `claimed_interface_number`
     * so both interfaces are always properly released on shutdown.
     */
    int ctrl_interface_number = -1;

    /**
     * `bEndpointAddress` of the isochronous OUT endpoint, mirrored from the
     * `IsoTransferPool` configuration at pool-allocation time.
     *
     * Stored here so `teardown_context()` can pass the correct address to
     * `libusb_clear_halt()` during the DSD soft-reset sequence (Phase 6.5),
     * which executes after the `IsoTransferPool` is destroyed in Phase 6.
     *
     * Set by `nativeAllocateTransferPool()`; default 0 (unset).
     */
    uint8_t iso_endpoint_address = 0;

    /**
     * Latched to `true` by `nativeStartDsdPlayback()` immediately before it
     * returns success.
     *
     * `teardown_context()` reads this flag in Phase 6.5 to decide whether to
     * execute the XMOS Soft Reset sequence before releasing the streaming
     * interface.  The reset is necessary because the XMOS USB receiver retains
     * its DSD DSP lock across interface claim/release cycles — a subsequent PCM
     * session on the same physical USB connection would otherwise inherit the
     * DSD PLL state and display **"FSR ERROR"**.
     *
     * This flag is deliberately **not** reset to `false` when `dsd_manager` is
     * released by `nativeReleaseDsdManager()` — the DSD state the chip needs to
     * recover from persists until `teardown_context()` has run the soft reset.
     */
    bool was_dsd_session = false;

    /**
     * Valid PCM resolution advertised by the selected Type-I endpoint.
     *
     * Enhanced PCM always uses a four-byte subslot, but many DACs advertise
     * 24 valid bits inside that container. The JNI float writer clears the
     * unused least-significant bits according to this value so the payload is
     * correctly MSB-aligned rather than sending arbitrary padding bits.
     * Zero means no strict PCM endpoint has been selected yet.
     */
    uint8_t pcm_valid_bit_depth = 0;

    /**
     * Exact four-byte integer PCM representation selected from the UAC2
     * descriptors. Both the decoder pump and enhanced writer use this value so
     * source precision and endpoint padding cannot diverge.
     */
    UsbPcmWireFormat pcm_wire_format{};

    /**
     * Serialized lifecycle state for the resources owned by this context.
     */
    UsbPlaybackStateMachine playback_state;

    /**
     * Serializes the sole decoder producer with control-plane transitions such
     * as Native DSD to DoP fallback.
     */
    std::shared_ptr<UsbProducerCoordinator> producer_coordinator;

    /**
     * The `bClockID` successfully acknowledged by the DAC during
     * `nativeSetUac2ClockSampleRate()`.
     *
     * Persisted here so the DSD soft-reset sequence in Phase 6.5 of
     * `teardown_context()` can address the UAC2 SET_CUR control transfer to the
     * **exact** Clock Source entity used during the session, instead of cycling
     * through speculative candidate IDs.  Sending a SET_CUR to an invalid ID
     * triggers `LIBUSB_ERROR_PIPE (-9)`, which stalls the control endpoint and
     * causes the subsequent PCM session's clock programming to fail silently —
     * the root cause of "ring_free=0 KB" ISO deadlock on FiiO KA5.
     *
     * - `0`   — not yet determined; brute-force phase never ACK'd, or
     *            `nativeSetUac2ClockSampleRate()` was not called for this session.
     * - `> 0` — known-good bClockID; Phase 6.5 uses this value exclusively.
     *
     * Set by `nativeSetUac2ClockSampleRate()` on the first successful transfer.
     * Never reset between sessions — Phase 6.5 reads it during DSD teardown and
     * the next PCM session overwrites it at setup time.
     */
    uint8_t resolved_clock_source_id = 0;

    // ── Isochronous transfer pool ────────────────────────────────────────────

    /**
     * Owning pointer to the pre-allocated isochronous OUT transfer ring.
     * Null until `nativeAllocateTransferPool()` succeeds.
     * Destroyed in teardown Phase 6 after all callbacks have drained.
     */
    std::unique_ptr<IsoTransferPool> transfer_pool;

    // ── SPSC audio ring buffer ────────────────────────────────────────────────

    /**
     * Owning pointer to the lock-free SPSC ring buffer.
     * The FFmpeg decoder thread (producer) pushes PCM / DoP frames here;
     * the isochronous completion callback (consumer) pops per-transfer.
     * Null until `nativeStartPlayback()` succeeds.
     * Destroyed in teardown Phase 5 after the event thread is joined.
     */
    std::unique_ptr<SpscRingBuffer> ring_buffer;

    // ── Dedicated libusb event thread ─────────────────────────────────────────

    /**
     * Owning pointer to the dedicated libusb event-processing thread.
     * Required for isochronous callbacks to fire.
     * Null until `nativeStartPlayback()` succeeds.
     * Destroyed in teardown Phase 4 — AFTER all in-flight transfers are
     * cancelled and their CANCELLED callbacks have cleared `in_flight`.
     */
    std::unique_ptr<LibusbEventThread> event_thread;

    // ── DSD formatter and playback manager ────────────────────────────────────

    /**
     * Active DSD frame formatter used by the audio producer (FFmpeg bridge) to
     * convert raw DSD bytes from the decoder into the transport format pushed
     * into the SPSC ring buffer.
     *
     * Initialised to `DopFormatterFunctor` (safe default for any DAC).
     * Switched to `NativeDsdMsbfFormatter` or `NativeDsdLsbfFormatter` by
     * `nativeStartDsdPlayback()` before the first ISO transfers are submitted.
     * Reset back to `DopFormatterFunctor` by `DsdPlaybackManager::execute_fallback_to_dop()`
     * after a successful Native DSD → DoP fallback.
     *
     * Thread-safety: the formatter is written only during session setup or
     * fallback (single-threaded, with ring buffer cleared and no concurrent
     * producer active).  The producer reads it only while actively encoding.
     * The ring-buffer reset in the fallback sequence provides the memory fence.
     */
    DsdFrameFormatter dsd_formatter{DopFormatterFunctor{}};

    /**
     * Wire representation selected for the current DSD session.
     *
     * Read by the decoder bridge before its producer thread starts. Updated
     * during fallback only while the producer is quiesced.
     */
    DsdWireMode dsd_wire_mode = DsdWireMode::Native;

    /**
     * Owning pointer to the DSD playback manager.
     *
     * Non-null during a DSD playback session started via `nativeStartDsdPlayback()`.
     * Manages the 200 ms Native DSD observation window, monitors for stalls,
     * and executes the automatic DoP fallback sequence.
     *
     * ### Destruction order (CRITICAL)
     *
     * The manager MUST be destroyed BEFORE `teardown_context()` is called,
     * because its destructor joins the monitor thread, which accesses
     * `transfer_pool` and `ring_buffer`.  Destroying the context first would
     * leave the monitor thread with dangling pointers.
     *
     * `nativeRelease()` enforces this order itself. Kotlin may call
     * `nativeReleaseDsdManager(handle)` earlier to end observation promptly;
     * the later reset is then a safe no-op.
     *
     * Null when the session was started via `nativeStartPlayback()` (PCM-only).
     */
    std::unique_ptr<DsdPlaybackManager> dsd_manager;
};
