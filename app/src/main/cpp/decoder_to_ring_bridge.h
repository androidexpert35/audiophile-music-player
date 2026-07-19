// ─────────────────────────────────────────────────────────────────────────────
// decoder_to_ring_bridge.h
//
// Step 49 — DecoderToRingBridge: pump thread that routes decoded audio bytes
// from an IAudioDecoder into the libusb engine's SpscRingBuffer.
//
// This class severs the final dependency between the legacy AudioTrack output
// path and the new libusb output path.  It owns a dedicated std::thread that
// continuously:
//   1. Checks SpscRingBuffer::free_space() for room.
//   2. Calls IAudioDecoder::read_pcm() (or read_dsd()) into a stack buffer.
//   3. Calls SpscRingBuffer::push() to deliver the bytes to the ISO callback.
//
// ### Architecture position
//
//   FfmpegAudioDecoder
//       │ read_pcm() / read_dsd()
//       ▼
//   DecoderToRingBridge (pump thread)
//       │ push()
//       ▼
//   SpscRingBuffer ──── pop() ──── IsoTransferPool callback ──── USB DAC
//
// ### Producer/consumer contract
//
// DecoderToRingBridge is the **sole producer** of the SpscRingBuffer.
// It must be started AFTER SpscRingBuffer::create() and BEFORE the ISO
// transfers are submitted (nativeStartPlayback / nativeStartDsdPlayback),
// so the ring has data to serve the first few ISO callbacks.
//
// ### Lifecycle
//
//   start()   — launch the pump thread; decoder must be non-null.
//   stop()    — signal the thread and block until it exits.
//   ~Bridge   — calls stop() if still running; safe to call from any thread.
//
// ### Thread-safety
//
//   start() and stop() must be called from the same external thread (the Kotlin
//   audio init thread / UsbAudioBridge side).  The pump thread itself must not
//   call start() or stop() on itself.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <thread>

// Android AudioFormat.ENCODING_PCM_* constants mirrored from ffmpeg_bridge.cpp
// so the pump loop can branch on output sample type without a separate query.
static constexpr int kAndroidEncodingPcm16Bit = 2;
static constexpr int kAndroidEncodingPcmFloat  = 4;
static constexpr int kAndroidEncodingPcm32Bit  = 22;

#include "i_audio_decoder.h"
#include "native_dsd_formatter.h"   // NativeDsdBitOrder, bit_reverse_byte, format_native_dsd_*
#include "pcm_wire_formatter.h"
#include "dsd_wire_mode.h"

// Forward declaration — full type in spsc_ring_buffer.h.
class SpscRingBuffer;
class UsbProducerCoordinator;

// ─────────────────────────────────────────────────────────────────────────────
// DecoderToRingBridge
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pump thread that continuously pulls decoded PCM / DSD bytes from an
 * IAudioDecoder and pushes them into a SpscRingBuffer for consumption by the
 * libusb isochronous transfer callback.
 *
 * ### Design guarantees
 *
 *   - Zero AudioTrack / AAudio / ALSA / OS audio output code is present.
 *   - The pump thread allocates no heap memory after construction.
 *     The chunk buffer is stack-allocated (kChunkBytes bytes per iteration).
 *   - The pump thread sleeps for kPollIntervalUs microseconds when the ring
 *     is full, preventing busy-polling and avoiding battery waste.
 *   - A clean EOF from the decoder sets eof_ = true; the Kotlin layer can
 *     poll is_eof() to detect the end of track.
 *   - Decode errors increment a recoverable_error_count_ for telemetry; the
 *     error count is visible via recoverable_error_count().
 *
 * ### USB attach / hot-plug handling
 *
 * The bridge is designed to survive an engine swap (ACTION_USB_DEVICE_ATTACHED):
 *   1. stop() — halt the pump thread while sharing the decoder.
 *   2. Replace the SpscRingBuffer pointer with the new ring from the fresh
 *      UsbDriverContext (via reset_ring_buffer()).
 *   3. start() — resume pumping into the new ring.
 * The decoder session itself is unaffected; no re-open is required.
 *
 * @see IAudioDecoder   Decoder interface.
 * @see SpscRingBuffer  Lock-free ring buffer consumed by the ISO callback.
 */
class DecoderToRingBridge {
public:
    /// Stack-allocated input/output chunk per pump iteration.
    /// 8 192 bytes ≈ 1 ms of audio at 192 kHz / 32-bit stereo — small enough
    /// to stay on the thread stack (Android audio threads get ≥ 32 KB).
    static constexpr std::size_t kChunkBytes = 8192u;

    /// Microseconds to sleep when the ring buffer is full.
    /// 500 µs matches the libusb event thread's callback interval so the pump
    /// wakes often enough to keep the ring pre-filled without spinning.
    static constexpr int kPollIntervalUs = 500;

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Create a bridge connecting `decoder` to `ring`.
     *
     * Does NOT start the pump thread — call start() separately after
     * the UsbDriverContext and transfer pool are both ready.
     *
     * Both raw pointers must remain valid for the lifetime of the bridge.
     * The bridge does not take ownership of either — the caller is responsible
     * for destroying decoder and ring after stop() returns.
     *
     * @param decoder        Non-null IAudioDecoder.
     * @param ring           Non-null SpscRingBuffer owned by the UsbDriverContext.
     * @param dsd_bit_order  Within-byte DSD bit ordering the connected DAC expects.
     *                       `NativeDsdBitOrder::Msbf` (default) suits all FiiO
     *                       XMOS-based DACs (KA5, K9 Pro, Q7, BTR7) and most
     *                       modern RME / Topping / Matrix DACs.
     *                       `NativeDsdBitOrder::Lsbf` is required only for a
     *                       minority of older Sony / Denon / iFi devices that
     *                       expect LSBF within each DSD byte.
     *                       Ignored for PCM sessions (is_dsd == false).
     * @return               Owning unique_ptr or nullptr on OOM.
     */
    static std::unique_ptr<DecoderToRingBridge> create(
            IAudioDecoder    *decoder,
            SpscRingBuffer   *ring,
            UsbPcmWireFormat  pcm_wire_format,
            std::shared_ptr<UsbProducerCoordinator> producer_coordinator,
            NativeDsdBitOrder dsd_bit_order = NativeDsdBitOrder::Msbf) noexcept;

    /**
     * Destructor.  Calls stop() if the pump thread is still running.
     */
    ~DecoderToRingBridge();

    // Non-copyable, non-movable — holds an owning std::thread.
    DecoderToRingBridge(const DecoderToRingBridge &)            = delete;
    DecoderToRingBridge &operator=(const DecoderToRingBridge &) = delete;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Launch the pump thread.
     *
     * May only be called once before the first stop().  Calling start() while
     * already running is a no-op (logs a warning).
     */
    [[nodiscard]] bool start() noexcept;

    /**
     * Signal the pump thread to stop and block until it exits.
     *
     * Safe to call from any thread except the pump thread itself.  After stop()
     * returns, the ring buffer will still contain whatever data was pushed
     * before the thread exited — ISO callbacks drain it normally.
     *
     * Calling stop() when the thread is not running is a safe no-op.
     */
    void stop() noexcept;

    /**
     * Replace the destination ring buffer without restarting the decoder.
     *
     * Must be called ONLY when the pump thread is stopped (after stop() returns
     * and before the next start() call).  Used during USB engine hot-swap
     * (ACTION_USB_DEVICE_ATTACHED) to point the pump at the fresh ring that
     * the new UsbDriverContext owns.
     *
     * @param new_ring  Non-null replacement SpscRingBuffer pointer.
     */
    void reset_ring_buffer(SpscRingBuffer *new_ring) noexcept;

    /**
     * Selects Native DSD or DoP framing while the pump is stopped.
     */
    void set_dsd_wire_mode(DsdWireMode mode) noexcept;

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Returns true once the decoder has signalled EOF.
     *
     * The Kotlin layer should poll this (or use the on_eof callback) to detect
     * end-of-track and enqueue the next item.
     *
     * @return  true after read_pcm/read_dsd returns kDecoderEof.
     */
    bool is_eof() const noexcept
    {
        return eof_.load(std::memory_order_acquire);
    }

    /**
     * Returns the cumulative count of recoverable decode errors since start().
     *
     * A non-zero count indicates transient issues (corrupt frames, priming
     * cycles) that did not stop the pump thread.
     *
     * @return  Error count since start().
     */
    uint32_t recoverable_error_count() const noexcept
    {
        return recoverable_error_count_.load(std::memory_order_acquire);
    }

    /**
     * Register an optional callback invoked from the pump thread when EOF is
     * reached.  Set before calling start().
     *
     * @param cb  Callable accepting no arguments.  May be null to clear a
     *            previously registered callback.
     */
    void set_on_eof(std::function<void()> cb) noexcept
    {
        on_eof_callback_ = std::move(cb);
    }

    // ── Software volume control ───────────────────────────────────────────────

    /**
     * Set the software volume from a raw UI slider position.
     *
     * The libusb engine bypasses Android AudioFlinger entirely, so the device volume
     * rocker has no effect.  This method is the sole amplitude control for the
     * direct-USB path.
     *
     * ### Quadratic taper (applied here)
     *
     * The incoming linear position is converted to an amplitude gain scalar via a
     * **quadratic (x²) power curve** inside this function — identical to the taper
     * `EngineSwapBridge::nativeWriteToRingBuffer` applies for the enhanced (DSP)
     * libusb write path, so the same UI position sounds equally loud regardless of
     * which sink is active. The pre-computed scalar is stored in the atomic so
     * `pump_loop()` reads the final multiplier directly without additional
     * computation on the hot path:
     *
     *   `gain = position ^ 2`
     *
     *   position │ stored gain │ equiv. dB
     *   ─────────┼─────────────┼──────────
     *   0.00     │ 0.000 000   │ −∞ (mute)
     *   0.25     │ 0.062 500   │ −24.1 dB
     *   0.50     │ 0.250 000   │ −12.0 dB
     *   0.75     │ 0.562 500   │  −5.0 dB
     *   1.00     │ 1.000 000   │   0.0 dB
     *
     * ### Startup mute guard
     *
     * The atomic is initialised to `0.0f` (silence) and the Kotlin layer passes
     * the current volume directly to `nativeAttachUsbEngine()` as `initial_volume`
     * so the pump thread starts at the correct amplitude.  `UsbVolumeController`
     * also calls this method again inside `attachBridge()` immediately after receiving
     * the bridge handle, providing a redundant safety re-apply.
     *
     * ### Precision
     *
     * All per-sample multiplications use double (64-bit) precision so that
     * quantisation artefacts from the gain multiply are below the thermal-noise
     * floor of the DAC at any gain setting.
     *
     * @param volume  Raw UI slider position in [0.0, 1.0].
     *                0.0 = absolute mute.  1.0 = full scale (0 dB).
     *                Values outside this range are clamped before the taper is applied.
     */
    void set_volume(float volume) noexcept;

    /**
     * Returns the current software volume scalar.
     *
     * Thread-safe: reads the atomic with relaxed ordering.
     *
     * @return  Linear amplitude scalar in [0.0, 1.0].
     */
    float volume() const noexcept;

private:
    explicit DecoderToRingBridge(
            IAudioDecoder    *decoder,
            SpscRingBuffer   *ring,
            UsbPcmWireFormat  pcm_wire_format,
            std::shared_ptr<UsbProducerCoordinator> producer_coordinator,
            NativeDsdBitOrder dsd_bit_order) noexcept;

    /// Main pump loop executed on the pump thread.
    void pump_loop() noexcept;

    IAudioDecoder              *decoder_   = nullptr;
    SpscRingBuffer             *ring_      = nullptr;
    PcmWireFormatter            pcm_formatter_;
    std::shared_ptr<UsbProducerCoordinator> producer_coordinator_;

    /**
     * Within-byte DSD bit ordering forwarded to the DSD_U32LE formatter.
     *
     * Selects between `format_native_dsd_from_interleaved_msbf()` (MSBF, the
     * default for FiiO/XMOS/RME/Topping DACs) and
     * `format_native_dsd_from_interleaved_lsbf()` (LSBF, required for a small
     * subset of older Sony / Denon DACs).
     *
     * Set once in the constructor via the `create()` factory parameter and
     * never mutated; the pump thread reads it without synchronisation.
     */
    NativeDsdBitOrder           dsd_bit_order_ = NativeDsdBitOrder::Msbf;
    DsdWireMode                 dsd_wire_mode_ = DsdWireMode::Native;
    uint8_t                     dop_marker_ = 0x05U;

    std::thread                 pump_thread_;
    std::atomic<bool>           should_stop_{false};
    std::atomic<bool>           eof_{false};
    std::atomic<uint32_t>       recoverable_error_count_{0};
    std::function<void()>       on_eof_callback_;

    /**
     * Pre-computed amplitude gain scalar in [0.0, 1.0].
     *
     * ### What is stored
     *
     * This field stores the **quadratic-tapered gain scalar** — NOT the raw linear
     * slider position.  `set_volume(position)` applies `gain = position²` and
     * stores the result so `pump_loop()` reads the final multiplier directly
     * from the atomic with zero additional computation on the hot path.
     *
     * ### Startup mute guard
     *
     * Initialised to **0.0f** (absolute silence) so the pump thread outputs
     * strict silence from its very first iteration.  The Kotlin layer passes
     * the current volume as `initial_volume` to `nativeAttachUsbEngine()` which
     * calls `set_volume()` BEFORE `bridge->start()`, ensuring the first pre-fill
     * chunk is at the correct amplitude — no audible silence gap or volume blast.
     *
     * Default: 0.0f (muted until `set_volume()` is called).
     */
    std::atomic<float>          volume_scalar_{0.0f};
};
