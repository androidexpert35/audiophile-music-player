// ─────────────────────────────────────────────────────────────────────────────
// usb_iso_transfer_pool.cpp
//
// RAII pool of pre-allocated libusb isochronous OUT transfer descriptors.
//
// See usb_iso_transfer_pool.h for design rationale, microframe arithmetic,
// pool sizing, memory alignment, and thread-safety contract.
// ─────────────────────────────────────────────────────────────────────────────

#include "usb_iso_transfer_pool.h"
#include "spsc_ring_buffer.h"

#include <algorithm>   // std::min — used in underrun partial-fill path
#include <android/log.h>
#include <cerrno>
#include <chrono>      // steady_clock — DSD stall observation window timing
#include <cstdlib>     // posix_memalign, free
#include <cstring>     // memset, strerror
#include <sstream>     // std::ostringstream

#include "libusb/libusb.h"

// ─── Logging macros ───────────────────────────────────────────────────────────

static constexpr const char *POOL_TAG = "IsoTransferPool";

#define PLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, POOL_TAG, __VA_ARGS__)
#define PLOGI(...) __android_log_print(ANDROID_LOG_INFO,  POOL_TAG, __VA_ARGS__)
#define PLOGW(...) __android_log_print(ANDROID_LOG_WARN,  POOL_TAG, __VA_ARGS__)
#define PLOGE(...) __android_log_print(ANDROID_LOG_ERROR, POOL_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Compile-time and run-time constants
// ─────────────────────────────────────────────────────────────────────────────

/// USB 2.0 High-Speed microframes per second.
/// 8 microframes/ms × 1000 ms/s = 8000 µframes/sec.
static constexpr uint32_t USB_HS_UFRAMES_PER_SEC = 8'000U;

/// Payload buffer alignment in bytes.
/// 64 bytes matches the ARM64 L1 cache line size and is a conservative lower
/// bound for DMA alignment requirements on Android SoCs.
static constexpr std::size_t BUFFER_ALIGNMENT = 64U;

/// Isochronous transfer timeout value.
/// 0 = no timeout.  Real-time audio requires that the host never cancel a
/// transfer due to a timeout — missed deliveries appear as LIBUSB_TRANSFER_ERROR
/// in the callback, which Step 5 handles by resubmitting without stalling.
static constexpr unsigned int ISO_TRANSFER_TIMEOUT_MS = 0U;

// ─────────────────────────────────────────────────────────────────────────────
// Microframe byte calculation (exported inline-equivalent for tests)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Compute the per-µframe payload size using ceiling integer division.
 *
 * ### Why ceiling division?
 *
 * For sample rates not evenly divisible by 8000 (e.g., 44.1, 88.2, 176.4,
 * 352.8 kHz), the exact bytes-per-µframe is fractional.  Allocating the
 * ceiling ensures the buffer is always large enough to hold the maximum
 * number of samples any given microframe might carry.
 *
 * In reality the hardware sends ceil(sample_rate/8000) samples in most
 * microframes and floor() in the remainder; the precise distribution is
 * governed by a feedback endpoint (handled in Step 5).  At this allocation
 * stage we always provide max capacity.
 *
 * Derivation for ceiling division without floating point:
 *   ceil(a / b) = (a + b - 1) / b  (integer arithmetic)
 *
 * @param sample_rate_hz        Audio sample rate in Hz.
 * @param bytes_per_audio_frame Bytes per interleaved audio sample-frame (e.g., 8).
 * @return                      Maximum bytes this pool must accommodate per µframe.
 */
static uint32_t compute_bytes_per_uframe(uint32_t sample_rate_hz,
                                          uint32_t bytes_per_audio_frame) noexcept
{
    // samples_per_uframe_ceil = ceil(sample_rate_hz / USB_HS_UFRAMES_PER_SEC)
    // Integer ceiling: (a + b - 1) / b
    const uint32_t samples_ceil =
        (sample_rate_hz + USB_HS_UFRAMES_PER_SEC - 1U) / USB_HS_UFRAMES_PER_SEC;

    return samples_ceil * bytes_per_audio_frame;
}

// ─────────────────────────────────────────────────────────────────────────────
// IsoTransferPool::compute_next_transfer_lengths — Bresenham packet sizer
// ─────────────────────────────────────────────────────────────────────────────

/// Maximum packets_per_transfer value accepted by compute_next_transfer_lengths.
/// Consumer USB audio uses P=8 (1 ms/transfer at HS).  64 provides headroom
/// for DSD/hi-rate configurations without heap allocation in the callback.
static constexpr uint32_t kMaxBresPackets = 64U;

/**
 * Compute per-packet byte counts for `packets_per_transfer` microframes using
 * the Bresenham (integer-DDA) phase accumulator.
 *
 * ### Why Bresenham is necessary for 44.1 kHz and DSD families
 *
 * For sample rates f that satisfy (f % 8000 == 0) — 48, 96, 192, 384 kHz —
 * each microframe carries exactly f/8000 samples and no accumulator is needed.
 *
 * For f not divisible by 8000 — 44100, 88200, 176400, 352800 Hz and the DSD64/
 * DSD128 byte rates — the ceiling-only approach allocates ceil(f/8000) frames
 * every microframe:
 *   44 100 Hz: ceil(5.5125) = 6 → 6 × 8000 = 48 000 Hz delivered ≡ +8.84% speed
 *
 * Bresenham distributes floor/ceil counts so the long-term average is exact:
 *   acc += sr;  frames = acc / 8000;  acc %= 8000;
 *
 * Over (8000 / gcd(sr, 8000)) microframes the byte sum equals exactly
 * sr × bytes_per_audio_frame / 8000 per µframe — pitch-perfect.
 *
 * ### Thread-safety
 *
 * `fractional_accumulator_` is modified here and nowhere else.  This function
 * is called from:
 *   - `allocate()` — on the JNI init thread, before any transfer is submitted.
 *   - `iso_transfer_callback` — on the libusb event thread (single thread).
 * Both call sites are serialised; no atomic protection is needed.
 *
 * @param packet_lengths_out  Output array of at least packets_per_transfer uint32_t.
 * @return                    Total byte count (= new libusb_transfer::length).
 */
uint32_t IsoTransferPool::compute_next_transfer_lengths(
        uint32_t *packet_lengths_out) noexcept
{
    const uint32_t P   = cfg_.packets_per_transfer;
    const uint32_t sr  = cfg_.sample_rate_hz;
    const uint32_t bpf = cfg_.bytes_per_audio_frame;

    // Defensive guard: if P somehow exceeds our stack-array max, fall back to
    // the uniform ceiling value rather than overrunning the caller's array.
    // In practice P == 8 for all supported configurations.
    if (__builtin_expect(P > kMaxBresPackets, 0)) {
        const uint32_t uniform = bytes_per_uframe_;
        for (uint32_t p = 0; p < P && p < kMaxBresPackets; ++p) {
            packet_lengths_out[p] = uniform;
        }
        return uniform * P;
    }

    uint32_t total = 0U;
    for (uint32_t p = 0; p < P; ++p) {
        fractional_accumulator_ += sr;
        const uint32_t frames = fractional_accumulator_ / USB_HS_UFRAMES_PER_SEC;
        fractional_accumulator_ %= USB_HS_UFRAMES_PER_SEC;
        const uint32_t bytes   = frames * bpf;
        packet_lengths_out[p]  = bytes;
        total                 += bytes;
    }
    return total;
}

// ─────────────────────────────────────────────────────────────────────────────
// Production isochronous completion callback
// ─────────────────────────────────────────────────────────────────────────────
//
// DESIGN MANDATE: This function is the innermost hot loop of the entire audio
// driver.  At 192 kHz / 8 packets per transfer it fires every 1 ms.  At
// 384 kHz it fires every 1 ms carrying twice the data.  Every microsecond
// spent here is a microsecond not available to the USB host controller's
// isochronous scheduler.
//
// Rules enforced in this function — any violation produces glitches or crashes:
//
//   ✅ Only lock-free atomics.  std::atomic<bool> store/load with explicit
//      memory_order.  No std::mutex, no spinlock, no OS futex.
//   ✅ memset() for buffer zero-fill — ARM64 NEON makes this ~1 ns/64 B.
//   ✅ libusb_submit_transfer() — safe from callback on threaded backend.
//      Android's libusb always uses the threaded (POSIX) backend.
//   ✅ Conditional __android_log_print ONLY on exceptional (non-hot) paths.
//      The COMPLETED branch emits zero log output.
//   ❌ NO heap allocation.  malloc/new/std::string are all forbidden.
//   ❌ NO printf / sprintf / std::to_string — all touch the heap internally.
//   ❌ NO blocking calls: no sleep, no mutex, no condition_variable::wait.
//   ❌ NO libusb_cancel_transfer() from inside the callback — it can deadlock
//      libusb's internal transfer list spinlock on some Android kernels.
//
// Transfer-level error triage:
//
//   Status               | Action        | Rationale
//   ─────────────────────┼───────────────┼────────────────────────────────────
//   COMPLETED            | walk + resubmit| Normal path — ~99.9% of callbacks
//   ERROR                | walk + resubmit| Transient CRC/babble; DAC PLL holds
//   TIMED_OUT            | walk + resubmit| Impossible (timeout=0), treat as transient
//   STALL                | shutdown       | Endpoint stalled, needs clear_halt
//   NO_DEVICE            | shutdown       | DAC physically removed
//   OVERFLOW             | shutdown       | Impossible for OUT, treat as fatal
//   CANCELLED            | drain only     | Ordered by teardown, never resubmit
//
// Consecutive-error guard:
//   Transient errors (ERROR, TIMED_OUT) increment slot->consecutive_errors.
//   COMPLETED resets it to 0.  At MAX_CONSECUTIVE_ERRORS the slot shuts down.
//   Without this guard a sustained bus fault would re-submit in a tight ~1 ms
//   loop consuming 100% of the libusb event thread.

/**
 * Triage result: tells the callback which action to take after status dispatch.
 */
enum class Triage : uint8_t {
    WalkAndResubmit,   ///< iterate packets, zero-fill buffer, call libusb_submit_transfer
    DrainOnly,         ///< clear in_flight, do not resubmit (CANCELLED path)
    Shutdown,          ///< set slot->shutdown, clear in_flight, do not resubmit
};

/**
 * Map a libusb_transfer_status to the Triage action.
 * Called once per callback; evaluated at compile time on O2/O3.
 */
static inline Triage triage_transfer_status(libusb_transfer_status s) noexcept {
    switch (s) {
        case LIBUSB_TRANSFER_COMPLETED:
        case LIBUSB_TRANSFER_ERROR:
        case LIBUSB_TRANSFER_TIMED_OUT:
            return Triage::WalkAndResubmit;

        case LIBUSB_TRANSFER_CANCELLED:
            return Triage::DrainOnly;

        case LIBUSB_TRANSFER_STALL:
        case LIBUSB_TRANSFER_NO_DEVICE:
        case LIBUSB_TRANSFER_OVERFLOW:
        default:
            return Triage::Shutdown;
    }
}

/**
 * Walk the iso_packet_desc[] array for a completed/error transfer.
 *
 * For each packet:
 *   • LIBUSB_TRANSFER_COMPLETED with actual_length == descriptor length → perfect.
 *   • LIBUSB_TRANSFER_COMPLETED with actual_length < descriptor length  → short
 *     packet.  Normal for fractional-rate sample clocks (44.1 kHz etc.) where
 *     the host controller sends one fewer sample in some microframes.
 *   • Any error status on an individual packet → counted in error_packets.
 *
 * Returns the number of packets that reported an error status.
 * This value is used only for the consecutive-error counter; it is NOT logged
 * on the hot path.
 *
 * @param transfer     Completed transfer with populated iso_packet_desc[].
 * @param buf_end      One-past-the-end of the buffer (for bounds assertions).
 * @return             Count of packets whose individual status != COMPLETED.
 */
static inline uint32_t walk_iso_packets(const libusb_transfer *transfer,
                                        const uint8_t         *buf_end) noexcept
{
    uint32_t       error_packets = 0;
    const uint8_t *pkt_base      = transfer->buffer;

    for (int p = 0; p < transfer->num_iso_packets; ++p) {
        const libusb_iso_packet_descriptor &desc = transfer->iso_packet_desc[p];

        if (desc.status != LIBUSB_TRANSFER_COMPLETED) {
            ++error_packets;
        }
        // Short-packet check: actual_length < desc.length is valid for
        // fractional-rate audio; no action needed here — the buffer is about
        // to be zero-filled regardless.

        // Advance past this packet's allocated slice.
        // pkt_base + desc.length must not exceed buf_end.
        // (Defensive: the pool guarantees continuous layout, but guard against
        // a malformed descriptor reported by a buggy USB host controller.)
        if (pkt_base + static_cast<ptrdiff_t>(desc.length) <= buf_end) {
            pkt_base += desc.length;
        }
    }

    return error_packets;
}

/**
 * iso_transfer_callback — production isochronous OUT completion handler.
 *
 * See usb_iso_transfer_pool.h for the full contract, threading rules, and
 * status dispatch table.
 *
 * @param transfer  Completed libusb_transfer.  transfer->user_data is
 *                  a non-null TransferSlot* set during pool construction.
 */
void iso_transfer_callback(libusb_transfer *transfer)
{
    // ── 0. Null-guard ─────────────────────────────────────────────────────────
    // user_data is set unconditionally during pool construction, so null here
    // means the transfer was submitted outside the pool — a programming error.
    if (__builtin_expect(transfer->user_data == nullptr, 0)) {
        // Cannot log slot index — use a minimal fixed string that avoids heap.
        __android_log_write(ANDROID_LOG_ERROR, POOL_TAG,
                            "iso_transfer_callback: null user_data");
        return;
    }

    auto *slot = static_cast<TransferSlot *>(transfer->user_data);

    // ── 0b. First-5-callbacks trace ───────────────────────────────────────────
    // Confirms that the ISO pump is alive and transfers are reaching the
    // completion callback.  After the 5th fire this block executes zero
    // instructions (the conditional is predicted-not-taken from fire #6 onward).
    //
    // Uses the pool-level counter rather than per-slot so we see the very first
    // five callbacks across the entire pool in arrival order.
    //
    // ❌ Do NOT call libusb_error_name() on the hot path; it lives here only
    //    under the count<5 guard which fires at most 5 times per session.
    {
        const uint32_t fire_no =
            slot->pool->increment_callback_fire_count();   // 0-based
        if (__builtin_expect(fire_no < 5U, 0)) {
            __android_log_print(ANDROID_LOG_INFO, POOL_TAG,
                                "IsoCallback: fired #%u  slot=%u  status=%d (%s)  "
                                "ring_size=%zu B",
                                fire_no + 1U,
                                slot->index,
                                static_cast<int>(transfer->status),
                                libusb_error_name(transfer->status),
                                slot->pool->ring_buffer()
                                    ? slot->pool->ring_buffer()->size()
                                    : 0U);
        }
    }

    // ── 1. Shutdown guard ─────────────────────────────────────────────────────
    // The producer thread / teardown path sets slot->shutdown = true before
    // calling libusb_cancel_transfer().  CANCELLED callbacks will arrive here
    // after that.  For all statuses: if shutdown is already set, drain and exit.
    //
    // memory_order_acquire: pairs with the release store in the teardown path,
    // ensuring we see all memory writes the teardown thread made before setting
    // shutdown (e.g., the pool's destructor marking transfers as invalid).
    if (slot->shutdown.load(std::memory_order_acquire)) {
        // release: ensures the producer sees the cleared flag after we return.
        slot->in_flight.store(false, std::memory_order_release);
        return;
    }

    // ── 2. Transfer-level status triage ──────────────────────────────────────
    const Triage action = triage_transfer_status(transfer->status);

    if (action == Triage::DrainOnly) {
        // Planned cancellation during teardown — expected, not an error.
        // Log at INFO; this fires at most N times (pool_size) during shutdown.
        __android_log_print(ANDROID_LOG_INFO, POOL_TAG,
                            "slot[%u] CANCELLED", slot->index);
        slot->in_flight.store(false, std::memory_order_release);
        return;
    }

    if (action == Triage::Shutdown) {
        // Fatal condition: STALL, NO_DEVICE, or OVERFLOW.
        // Log at ERROR; fires at most once per slot before shutdown silences it.
        __android_log_print(ANDROID_LOG_ERROR, POOL_TAG,
                            "slot[%u] fatal status=%d — shutting down slot",
                            slot->index, static_cast<int>(transfer->status));

        // ── DSD stall observation window (Step 13) ────────────────────────────
        // If a LIBUSB_TRANSFER_STALL fires while the 200 ms Native DSD observation
        // window is armed, record it so the DsdPlaybackManager monitor thread can
        // trigger the DoP fallback.  The timing check is lock-free:
        //   • dsd_window_active_ is loaded with acquire, pairing with the release-
        //     store in begin_dsd_observation_window(), which guarantees
        //     dsd_window_start_ns_ is visible to this thread.
        //   • set_dsd_stall_in_window() uses release-store so the monitor's
        //     acquire-load sees all state that existed before the stall.
        if (transfer->status == LIBUSB_TRANSFER_STALL &&
            slot->pool->is_dsd_observation_active())
        {
            const int64_t now_ns =
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch())
                .count();

            const int64_t elapsed_ns = now_ns - slot->pool->dsd_window_start_ns();

            if (elapsed_ns >= 0 &&
                elapsed_ns < IsoTransferPool::kDsdObservationWindowNs)
            {
                slot->pool->set_dsd_stall_in_window();
                __android_log_print(ANDROID_LOG_WARN, POOL_TAG,
                                    "slot[%u] STALL within DSD observation window "
                                    "(elapsed=%.1f ms) — fallback flag set",
                                    slot->index,
                                    static_cast<double>(elapsed_ns) / 1'000'000.0);
            }
        }

        // memory_order_release: teardown observer sees shutdown after in_flight.
        slot->shutdown.store(true,  std::memory_order_release);
        slot->in_flight.store(false, std::memory_order_release);
        return;
    }

    // action == Triage::WalkAndResubmit for COMPLETED, ERROR, or TIMED_OUT.

    // ── 3. iso_packet_desc walk ───────────────────────────────────────────────
    // Walk every packet's individual status and accumulate per-packet errors.
    // On the hot path (all packets COMPLETED) this is a tight branch-free loop
    // that ARM64 executes in ~8 ns for 8 packets (one cache line of descriptors).
    //
    // buf_end is computed once to bound the packet-base advancement in
    // walk_iso_packets().
    const uint8_t *buf_end =
        transfer->buffer + static_cast<ptrdiff_t>(transfer->length);

    const uint32_t packet_errors = walk_iso_packets(transfer, buf_end);

    // ── 4. Consecutive error accounting ──────────────────────────────────────
    // COMPLETED with zero packet errors resets the counter (healthy stream).
    // Any non-zero error count or a transfer-level ERROR/TIMED_OUT increments it.
    //
    // This is executed ONLY on the event thread for this slot — no contention.
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && packet_errors == 0) {
        slot->consecutive_errors = 0;
    } else {
        ++slot->consecutive_errors;

        if (slot->consecutive_errors >= TransferSlot::MAX_CONSECUTIVE_ERRORS) {
            // Sustained bus fault — stop re-submitting to prevent a tight loop.
            // Log once; subsequent callbacks on this slot will hit the shutdown
            // guard at step 1 and return silently.
            __android_log_print(ANDROID_LOG_ERROR, POOL_TAG,
                                "slot[%u] %u consecutive errors — shutting down slot "
                                "(transfer status=%d  packet_errors=%u)",
                                slot->index,
                                slot->consecutive_errors,
                                static_cast<int>(transfer->status),
                                packet_errors);
            slot->shutdown.store(true,  std::memory_order_release);
            slot->in_flight.store(false, std::memory_order_release);
            return;
        }
    }

    // ── 5. Compute Bresenham packet lengths for the NEXT transfer ────────────
    //
    // Compute per-packet byte counts BEFORE the ring pop so we know exactly
    // how many bytes to drain from the ring — which must equal exactly what the
    // next submission will send to the DAC.
    //
    // The accumulator advances by packets_per_transfer steps here, producing the
    // per-µframe floor/ceil distribution that averages to the exact nominal rate.
    // The result `new_total` replaces both `transfer->length` and the individual
    // `iso_packet_desc[k].length` values for the resubmission.
    //
    // Example — 44 100 Hz stereo 32-bit (8 B/frame):
    //   nominal: 44100/8000 = 5.5125 frames/µframe
    //   Bresenham over 8 packets (one transfer):
    //     µframe 0: acc=44100 → 5 frames → 40 B, acc=4100
    //     µframe 1: acc=48200 → 6 frames → 48 B, acc=200
    //     µframe 2: acc=44300 → 5 frames → 40 B, acc=4300
    //     µframe 3: acc=48400 → 6 frames → 48 B, acc=400
    //     µframe 4-7: same 5/6 pattern → 40/48/40/48 B
    //   total = 4×40 + 4×48 = 352 B  (44 100 × 8 / 8000 × 8 = 352 B ✓)
    //
    // Previous (ceiling-only) approach gave 6×8×8 = 384 B → 48 000 Hz delivered
    // → +8.84% speed, audible pitch shift.
    uint32_t bres_pkt_lens[kMaxBresPackets];
    const uint32_t new_total =
        slot->pool->compute_next_transfer_lengths(bres_pkt_lens);

    // ── 6. Fill transfer buffer from ring buffer (or silence on underrun) ────
    //
    // ### Why transfer->length must never shrink
    //
    //   The DAC recovers its USB clock from the isochronous schedule's timing,
    //   not from the payload content.  Submitting a shorter transfer would create
    //   a gap in the 125 µs microframe grid, causing the DAC's PLL to lose lock
    //   and producing a loud click or endpoint STALL.  We always fill exactly
    //   `new_total` bytes — silence (zeros) when live data is unavailable.
    //
    // ### Normal path (ring has enough data)
    //
    //   ring->pop() performs at most two memcpy calls (one if no wrap-around)
    //   and a single release-store of read_idx_.  This is the hot path and
    //   executes in ~200 ns for a 1 536-byte 192 kHz transfer on ARM64 NEON.
    //
    // ### Underrun path (ring is partially or completely empty)
    //
    //   1. Pop as many bytes as are currently available (partial fill).
    //   2. Zero-pad the remainder to maintain the correct transfer length.
    //   3. Increment the underrun counter for out-of-band monitoring.

    // Reinterpret libusb's unsigned char* as uint8_t* for pop().
    // uint8_t is guaranteed to be unsigned char on all Android/ARM targets.
    auto *const buf8     = reinterpret_cast<uint8_t *>(transfer->buffer);
    const auto  required = static_cast<std::size_t>(new_total);  // Bresenham-exact

    SpscRingBuffer *const ring = slot->pool->ring_buffer();

    if (__builtin_expect(ring != nullptr, 1)) {
        // ── Fast path: ring has a full transfer's worth of decoded audio ──────
        if (!ring->pop(buf8, required)) {
            // ── Underrun path ─────────────────────────────────────────────────
            // The ring could not satisfy a full `required`-byte pop.  Determine
            // how many bytes are actually available right now.
            //
            // Cap at `required`: ring->size() may return > required if the
            // producer pushed data between the failed pop() and this size() call.
            // Writing more than `required` bytes into the transfer buffer is a
            // memory safety violation; the cap makes the partial pop safe.
            const std::size_t partial = std::min(ring->size(), required);

            if (partial > 0) {
                // Partial fill: copy available bytes to the front of the buffer.
                ring->pop(buf8, partial);
                // Zero-pad the unfilled tail so the DAC receives a valid-length
                // transfer carrying silence for the missing samples.
                std::memset(buf8 + partial, 0, required - partial);
            } else {
                // Ring is completely empty — output a full silence frame.
                std::memset(buf8, 0, required);
            }

            // Signal the monitoring layer.  relaxed ordering is correct: this
            // counter has no happens-before relationship with audio data.
            slot->pool->increment_underrun_count();

            // Diagnostic: log underruns at a reduced rate (every 500th) to
            // avoid log spam on the hot callback path.
            const uint64_t underrun_cnt = slot->pool->underrun_count();
            if ((underrun_cnt % 500) == 1) {
                __android_log_print(ANDROID_LOG_WARN, POOL_TAG,
                                    "iso_callback: underrun #%llu — "
                                    "ring empty or short; %zu B silence-padded "
                                    "(slot=%u, required=%zu B)",
                                    static_cast<unsigned long long>(underrun_cnt),
                                    required - (partial > 0 ? partial : 0),
                                    slot->index,
                                    required);
            }
        }
        // On successful pop(): buf8 holds exactly `required` bytes of
        // decoded PCM / DoP audio, DMA-ready for the USB host controller.
    } else {
        // Ring not yet attached (pre-playback or post-teardown state).
        // Output silence so the DAC clock keeps running cleanly.
        std::memset(buf8, 0, required);
    }

    // ── 7. Apply Bresenham packet lengths to the transfer descriptor ──────────
    //
    // Set each iso_packet_desc[k].length to the Bresenham-computed value and
    // update transfer->length to the new total.  Both must be consistent before
    // calling libusb_submit_transfer() — libusb uses transfer->length as the
    // total DMA size and each iso_packet_desc[k].length as the per-µframe budget.
    //
    // The buffer was allocated for buffer_size_per_xfer_ = ceil(sr/8000)×bpf×P
    // bytes (ceiling), which is always ≥ new_total (Bresenham proof: each
    // packet ≤ ceil(sr/8000)×bpf), so no buffer overflow is possible.
    transfer->length = static_cast<int>(new_total);
    for (int p = 0; p < transfer->num_iso_packets; ++p) {
        transfer->iso_packet_desc[p].length =
            bres_pkt_lens[static_cast<uint32_t>(p)];
    }

    // ── 8. Re-submit ─────────────────────────────────────────────────────────
    // libusb_submit_transfer() is safe to call from inside a callback on
    // libusb's threaded event backend (which Android always uses).  It appends
    // the transfer to the host controller's isochronous OUT schedule for the
    // next available microframe and returns immediately (non-blocking).
    //
    // The transfer is considered in-flight again from this point.  We do NOT
    // clear in_flight here; it remains true.
    const int submit_ret = libusb_submit_transfer(transfer);

    if (__builtin_expect(submit_ret != LIBUSB_SUCCESS, 0)) {
        // Submit failure here means the device was removed between the triage
        // check and the submit call (race window ~200 ns), or the libusb
        // context is being shut down.  Either way, shut down this slot.
        __android_log_print(ANDROID_LOG_ERROR, POOL_TAG,
                            "slot[%u] libusb_submit_transfer failed: %s (%d) — "
                            "shutting down slot",
                            slot->index,
                            libusb_error_name(submit_ret),
                            submit_ret);
        slot->shutdown.store(true,  std::memory_order_release);
        slot->in_flight.store(false, std::memory_order_release);
    }
    // On submit success: in_flight remains true (set by the producer at submit
    // time); the next callback invocation will clear it again.
}


// ─────────────────────────────────────────────────────────────────────────────
// IsoTransferPool — private constructor
// ─────────────────────────────────────────────────────────────────────────────

IsoTransferPool::IsoTransferPool(const IsoTransferPoolConfig &cfg) noexcept
    : cfg_(cfg)
{
    // Pre-compute derived constants.  No fallible operations here — the factory
    // validates cfg before calling the constructor.
    bytes_per_uframe_    = compute_bytes_per_uframe(cfg_.sample_rate_hz,
                                                    cfg_.bytes_per_audio_frame);
    buffer_size_per_xfer_ = bytes_per_uframe_ * cfg_.packets_per_transfer;
}

// ─────────────────────────────────────────────────────────────────────────────
// IsoTransferPool — factory
// ─────────────────────────────────────────────────────────────────────────────

std::unique_ptr<IsoTransferPool> IsoTransferPool::create(
        libusb_device_handle        *handle,
        const IsoTransferPoolConfig &cfg,
        std::string                 *error_out)
{
    // ── Validate inputs ───────────────────────────────────────────────────────
    auto fail = [&](const char *msg) -> std::unique_ptr<IsoTransferPool> {
        PLOGE("IsoTransferPool::create: %s", msg);
        if (error_out) *error_out = msg;
        return nullptr;
    };

    if (handle == nullptr)
        return fail("device handle is null");
    if (cfg.sample_rate_hz == 0)
        return fail("sample_rate_hz must be > 0");
    if (cfg.bytes_per_audio_frame == 0)
        return fail("bytes_per_audio_frame must be > 0");
    if (cfg.endpoint_address == 0)
        return fail("endpoint_address must be non-zero");
    if (cfg.pool_size == 0)
        return fail("pool_size must be > 0");
    if (cfg.packets_per_transfer == 0)
        return fail("packets_per_transfer must be > 0");

    // ── Construct object (no allocation yet) ──────────────────────────────────
    // Use new(std::nothrow) so factory can return nullptr on OOM instead of
    // throwing across the JNI boundary.
    auto *raw = new (std::nothrow) IsoTransferPool(cfg);
    if (raw == nullptr)
        return fail("out of memory allocating IsoTransferPool");

    std::unique_ptr<IsoTransferPool> pool(raw);

    // ── Log the configuration summary ─────────────────────────────────────────
    PLOGI("IsoTransferPool::create: "
          "sampleRate=%u Hz  bytesPerFrame=%u  endpoint=0x%02X  "
          "poolSize=%u  packetsPerTransfer=%u  "
          "bytesPerUframe=%u  bufferPerTransfer=%u B  totalBuffer=%u B",
          cfg.sample_rate_hz,
          cfg.bytes_per_audio_frame,
          static_cast<unsigned int>(cfg.endpoint_address),
          cfg.pool_size,
          cfg.packets_per_transfer,
          pool->bytes_per_uframe_,
          pool->buffer_size_per_xfer_,
          pool->buffer_size_per_xfer_ * cfg.pool_size);

    // ── UAC2 throughput sanity check ──────────────────────────────────────────
    // Ceiling-based drain_rate_max = bytes_per_uframe * 8000, which can exceed
    // the nominal pump_rate for fractional sample rates (44.1 kHz family, DSD).
    // The Bresenham accumulator in compute_next_transfer_lengths() guarantees
    // the long-term average equals pump_rate exactly for fractional rates, so
    // the mismatch is expected and correct — it is NOT an error.
    {
        const uint32_t drain_rate_max =
            pool->bytes_per_uframe_ * USB_HS_UFRAMES_PER_SEC;
        const uint32_t pump_rate =
            cfg.sample_rate_hz * cfg.bytes_per_audio_frame;
        const bool is_fractional =
            (cfg.sample_rate_hz % USB_HS_UFRAMES_PER_SEC) != 0U;

        if (drain_rate_max == pump_rate) {
            PLOGI("IsoTransferPool::create:  ✓ throughput exact — "
                  "pump_rate=drain_rate=%u B/s  (rate is a multiple of 8 kHz)",
                  pump_rate);
        } else if (is_fractional) {
            // For 44.1 kHz family and DSD, the ceiling drain always exceeds the
            // pump rate — this is expected.  Bresenham corrects it at runtime.
            //
            // NOTE ON bytesPerUframe_ceil LOG VALUE
            // ──────────────────────────────────────
            // bytes_per_uframe_ceil reported here (e.g. 96 for DSD64 at 88.2kHz×8B)
            // is the BUFFER ALLOCATION ceiling only — it is NOT the actual bytes
            // sent per microframe.  The Bresenham accumulator distributes floor/ceil
            // counts so the long-term average matches the nominal rate exactly:
            //
            //   DSD64 example (sampleRateHz=88200, bytesPerFrame=8):
            //     bytes_per_uframe_ceil = ceil(88200/8000) × 8 = 12 × 8 = 96 B  ← buffer size
            //     Bresenham per-µframe  = 11 frames (88 B) most µframes, 12 (96 B) every 40th
            //     Long-term average     = 88.2 B/µframe  (= 88200/8000 × 8 ✓)
            //     Total bytes/transfer  = Bresenham_avg × 8 packets = ~705.6 B
            //
            // The 96 B ceiling buffer guarantees no overflow when the 12-frame
            // microframe fires; the Bresenham prevents the +8.84% speed error that
            // ceiling-only allocation would produce.
            PLOGI("IsoTransferPool::create:  ✓ throughput managed by Bresenham — "
                  "nominal=%u B/s  ceiling_max=%u B/s  "
                  "bytes_per_uframe_ceil=%u (BUFFER ALLOCATION ONLY — "
                  "actual µframe sizes vary floor/ceil via Bresenham to average %.2f B/µframe)",
                  pump_rate, drain_rate_max,
                  pool->bytes_per_uframe_,
                  static_cast<double>(pump_rate) / USB_HS_UFRAMES_PER_SEC);
        } else {
            // Non-fractional rate but drain != pump → genuine misconfiguration.
            PLOGW("IsoTransferPool::create:  ⚠ THROUGHPUT MISMATCH  "
                  "pump_rate=%u B/s  drain_rate_max=%u B/s  ratio=%.3f  "
                  "→ ring will %s",
                  pump_rate,
                  drain_rate_max,
                  (drain_rate_max > 0)
                      ? static_cast<double>(pump_rate) / drain_rate_max
                      : 0.0,
                  (pump_rate > drain_rate_max)
                      ? "OVERFLOW (full_ring_sleeps expected)"
                      : "underrun (silence padding expected)");
        }
        PLOGI("IsoTransferPool::create:  bytes_per_uframe_ceil=%u (CEILING/ALLOC)  "
              "packets_per_xfer=%u  buffer_cap=%u B  "
              "Bresenham_avg=%u B/transfer  "
              "(ring->pop() consumes exactly Bresenham bytes per packet — no padding waste)",
              pool->bytes_per_uframe_,
              cfg.packets_per_transfer,
              pool->buffer_size_per_xfer_,
              pump_rate * cfg.packets_per_transfer / USB_HS_UFRAMES_PER_SEC);
    }

    // ── Perform all fallible allocations ──────────────────────────────────────
    if (!pool->allocate(handle, error_out)) {
        // allocate() already called release_all() for partial cleanup.
        return nullptr;
    }

    PLOGI("IsoTransferPool::create: SUCCESS — %u transfer slots ready, all buffers zeroed",
          cfg.pool_size);
    return pool;
}

// ─────────────────────────────────────────────────────────────────────────────
// IsoTransferPool::allocate — all fallible resource acquisition
// ─────────────────────────────────────────────────────────────────────────────

bool IsoTransferPool::allocate(libusb_device_handle *handle, std::string *error_out)
{
    const uint32_t N = cfg_.pool_size;
    const int      P = static_cast<int>(cfg_.packets_per_transfer);

    // Pre-size the vectors to avoid partial-push_back state during the loop.
    // We reserve N capacity and default-construct the slot vector; transfer and
    // buffer vectors start empty and are grown one entry at a time so that
    // release_all() knows exactly how many valid entries exist.
    transfers_.reserve(N);
    buffers_.reserve(N);
    slots_.resize(N);   // default-initialised TransferSlot (pool=null, index=0)

    for (uint32_t i = 0; i < N; ++i) {

        // ── 1. Initialise the slot back-pointer ───────────────────────────────
        slots_[i].pool      = this;
        slots_[i].index     = i;
        slots_[i].in_flight = false;

        // ── 2. Allocate the aligned payload buffer ────────────────────────────
        //
        // posix_memalign() requirements:
        //   • alignment must be a power of two and a multiple of sizeof(void*).
        //   • BUFFER_ALIGNMENT = 64 satisfies both (64 is 2^6; ≥ sizeof(void*) on all targets).
        //   • Returns memory freeable with free().
        //   • Returns ENOMEM on allocation failure (not errno — the return value IS the error).
        //
        // We do NOT use new / aligned_alloc without the C11 _Alignas extension
        // because Android's NDK only guarantees posix_memalign universally.
        void *buf = nullptr;
        {
            const int pm_ret = posix_memalign(&buf,
                                              BUFFER_ALIGNMENT,
                                              static_cast<std::size_t>(buffer_size_per_xfer_));
            if (pm_ret != 0 || buf == nullptr) {
                std::ostringstream oss;
                oss << "posix_memalign failed for slot " << i
                    << " (size=" << buffer_size_per_xfer_
                    << " align=" << BUFFER_ALIGNMENT
                    << "): " << strerror(pm_ret);
                const std::string msg = oss.str();
                PLOGE("%s", msg.c_str());
                if (error_out) *error_out = msg;
                release_all();
                return false;
            }
            buffers_.push_back(buf);
        }

        // ── 3. Zero-fill the entire buffer (absolute silence) ─────────────────
        //
        // For PCM audio, all-zeros is bit-perfect digital silence for all
        // common formats (S16LE, S24LE, S32LE, F32LE).
        //
        // For DoP (DSD-over-PCM) the silence pattern is also 0x00...0x00:
        // a DoP frame needs the DoP marker bytes (0x05 / 0xFA) in the MSBs of
        // each sample, but since we are not yet playing DSD, zeroing the buffer
        // is safe — Step 5 will overwrite with properly framed DoP data before
        // the first submission.
        //
        // Note: zeroing after posix_memalign is mandatory.  The allocator does
        // NOT clear the memory (unlike calloc), so uninitialised bytes could
        // contain arbitrary data from a previous allocation.  Sending random
        // bytes as audio would produce a loud pop through the DAC.
        memset(buf, 0, static_cast<std::size_t>(buffer_size_per_xfer_));

        PLOGD("  slot[%u]: buffer=%p size=%u B aligned=%zu B (zeroed)",
              i, buf, buffer_size_per_xfer_,
              reinterpret_cast<uintptr_t>(buf) % BUFFER_ALIGNMENT == 0
                  ? BUFFER_ALIGNMENT : 0U);

        // ── 4. Allocate the libusb_transfer struct ────────────────────────────
        //
        // libusb_alloc_transfer(P) allocates a struct with room for P
        // iso_packet_desc[] entries in a flexible array at the end.
        // Memory layout (libusb internal):
        //   [ libusb_transfer fixed fields ][ iso_packet_desc[0..P-1] ]
        //
        // The struct is owned by us and freed with libusb_free_transfer().
        // Passing iso_packets=0 would make this a non-isochronous transfer;
        // P must match packets_per_transfer.
        libusb_transfer *xfr = libusb_alloc_transfer(P);
        if (xfr == nullptr) {
            std::ostringstream oss;
            oss << "libusb_alloc_transfer(" << P << ") returned null for slot " << i
                << " — out of memory";
            const std::string msg = oss.str();
            PLOGE("%s", msg.c_str());
            if (error_out) *error_out = msg;
            // buffers_[i] was just pushed; release_all() will free it along with
            // all previously allocated transfers and buffers.
            release_all();
            return false;
        }
        transfers_.push_back(xfr);

        // ── 5. Configure the transfer via libusb_fill_iso_transfer ────────────
        //
        // libusb_fill_iso_transfer() populates:
        //   • dev_handle   → the open device handle (used internally by libusb)
        //   • endpoint     → isochronous OUT endpoint address from Step 2
        //   • buffer       → our aligned zero-filled payload buffer
        //   • length       → total transfer length (P × bytes_per_uframe)
        //   • num_iso_packets → P
        //   • callback     → iso_transfer_callback (production Step 5 implementation)
        //   • user_data    → &slots_[i]  (pool back-pointer + slot index)
        //   • timeout      → 0 (no timeout — mandatory for real-time audio)
        //
        // It does NOT set the individual iso_packet_desc[].length values —
        // that requires a separate libusb_set_iso_packet_lengths() call.
        libusb_fill_iso_transfer(
            xfr,
            handle,
            cfg_.endpoint_address,
            static_cast<unsigned char *>(buf),
            static_cast<int>(buffer_size_per_xfer_),  // total length = P × bytes_per_uframe
            P,
            iso_transfer_callback,                    // production completion handler
            &slots_[i],                               // non-null user_data
            ISO_TRANSFER_TIMEOUT_MS);

        // ── 6. Compute initial Bresenham per-packet lengths ───────────────────
        //
        // Unlike libusb_set_iso_packet_lengths() which assigns the same ceiling
        // value to every packet, the Bresenham accumulator assigns floor/ceil
        // values that average to the exact nominal sample rate.  This ensures the
        // VERY FIRST batch of transfers already has the correct per-µframe sizing,
        // so there is no "ceiling burst" at the start of playback.
        //
        // The buffer was allocated for buffer_size_per_xfer_ bytes (ceiling × P),
        // which is always ≥ Bresenham total, so no overflow can occur.
        {
            uint32_t pkt_lens[kMaxBresPackets];
            const uint32_t bres_total = compute_next_transfer_lengths(pkt_lens);

            // Override transfer->length with Bresenham total (libusb_fill_iso_transfer
            // set it to buffer_size_per_xfer_; we replace it now).
            xfr->length = static_cast<int>(bres_total);

            // Set individual per-packet descriptor lengths.
            for (int p = 0; p < P; ++p) {
                xfr->iso_packet_desc[p].length =
                    pkt_lens[static_cast<uint32_t>(p)];
            }

            PLOGD("  slot[%u]: Bresenham initial total=%u B  "
                  "(ceiling=%u B, saved %d B/transfer)",
                  i, bres_total, buffer_size_per_xfer_,
                  static_cast<int>(buffer_size_per_xfer_) - static_cast<int>(bres_total));
        }

        // ── 7. Transfer flags ─────────────────────────────────────────────────
        //
        // LIBUSB_TRANSFER_FREE_BUFFER is intentionally NOT set.
        // The pool owns `buf` (allocated with posix_memalign) and must call
        // free(buf) explicitly in release_all().  Letting libusb free it would
        // call free() a second time in release_all(), causing a heap-use-after-free.
        //
        // LIBUSB_TRANSFER_FREE_TRANSFER is also NOT set.
        // libusb_free_transfer() is called explicitly in release_all().
        xfr->flags = 0;

        PLOGD("  slot[%u]: transfer=%p  ep=0x%02X  packets=%d  "
              "totalLen=%d B  bufCap=%u B  timeout=%u  callback=%p",
              i,
              static_cast<void *>(xfr),
              static_cast<unsigned int>(xfr->endpoint),
              xfr->num_iso_packets,
              xfr->length,
              buffer_size_per_xfer_,
              xfr->timeout,
              reinterpret_cast<void *>(xfr->callback));
    }

    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// IsoTransferPool::submit_all_transfers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Submit all pre-configured transfer slots to the USB host controller.
 *
 * Sets `in_flight = true` with release ordering BEFORE calling
 * libusb_submit_transfer() so that if the callback fires before we finish
 * this loop (possible on a busy host controller) it always sees `in_flight`
 * as true for every slot it touches.
 *
 * On any submit failure: clears `in_flight` for the failed slot, marks all
 * remaining slots as shutdown (so teardown_context() does not attempt to
 * cancel transfers that were never submitted), then returns -1.
 *
 * @return  Number of successfully submitted slots, or -1 on the first failure.
 */
int IsoTransferPool::submit_all_transfers() noexcept
{
    int submitted = 0;
    const auto n = transfers_.size();

    for (std::size_t i = 0; i < n; ++i) {
        // Arm the slot BEFORE submission — the callback may fire before the
        // next iteration of this loop.
        slots_[i].in_flight.store(true, std::memory_order_release);

        const int rc = libusb_submit_transfer(transfers_[i]);
        if (rc != LIBUSB_SUCCESS) {
            PLOGE("submit_all_transfers: slot[%zu] libusb_submit_transfer failed: "
                  "%s (%d) — aborting",
                  i, libusb_error_name(rc), rc);

            // The submit failed; undo the in_flight flag for this slot.
            slots_[i].in_flight.store(false, std::memory_order_release);

            // Mark all remaining (never-submitted) slots as shutdown so that
            // the teardown path does not try to cancel them.
            for (std::size_t j = i; j < n; ++j) {
                slots_[j].shutdown.store(true, std::memory_order_release);
            }
            return -1;
        }
        ++submitted;
    }

    PLOGI("submit_all_transfers: %d slot(s) submitted and in flight — "
          "isochronous schedule armed",
          submitted);
    return submitted;
}

// ─────────────────────────────────────────────────────────────────────────────
// IsoTransferPool::reset_for_retry
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Reset all transfer slots to their pre-submission state so that
 * `submit_all_transfers()` can be safely invoked a second time after a
 * recoverable LIBUSB_ERROR_BUSY failure.
 *
 * When `submit_all_transfers()` fails on slot 0 (no transfers were ever
 * successfully submitted), the slot array is left in a partially-poisoned
 * state: `in_flight = false, shutdown = true` for all slots ≥ the failure
 * point.  This function evicts that poison so the pool looks newly allocated
 * and a fresh submission pass can proceed.
 *
 * ### Thread safety
 * Must be called from the same thread that will call `submit_all_transfers()`
 * immediately after.  The event thread must NOT be running concurrently —
 * the intended call site is the JNI init thread before the event loop starts,
 * or after `libusb_handle_events` has returned and the transfer pipeline is
 * known to be idle.
 */
void IsoTransferPool::reset_for_retry() noexcept
{
    const auto n = slots_.size();
    PLOGI("reset_for_retry: resetting %zu slot(s) to pre-submission state "
          "for BUSY-retry re-submission",
          n);

    for (std::size_t i = 0; i < n; ++i) {
        // Release-store so the subsequent acquire-load in submit_all_transfers()
        // sees a fully cleared slot (no stale in_flight / shutdown state).
        slots_[i].in_flight.store(false, std::memory_order_release);
        slots_[i].shutdown.store(false, std::memory_order_release);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// IsoTransferPool::release_all — symmetric cleanup
// ─────────────────────────────────────────────────────────────────────────────

void IsoTransferPool::release_all() noexcept
{
    // Process in reverse-construction order (transfers_ then buffers_) so that
    // any internal libusb structure pointing at the buffer is freed first.
    //
    // PRECONDITION: No transfer in transfers_[] may be in-flight.
    // Freeing an in-flight libusb_transfer is undefined behaviour — libusb's
    // event handler will dereference the freed memory in the callback.
    // The caller (Step 5 teardown) must cancel and await all in-flight
    // transfers before calling the destructor or the factory error path.

    for (auto *xfr : transfers_) {
        if (xfr != nullptr) {
            // nullify the buffer pointer before freeing to prevent double-free
            // if libusb ever inspects the buffer after libusb_free_transfer
            // (it shouldn't, but defensive programming is warranted here).
            xfr->buffer = nullptr;
            libusb_free_transfer(xfr);
        }
    }
    transfers_.clear();

    for (auto *buf : buffers_) {
        if (buf != nullptr) {
            free(buf);   // posix_memalign memory is free()-compatible
        }
    }
    buffers_.clear();

    // slots_ holds no heap allocations; clear() drops the vector's size without
    // triggering any destructor side-effects.
    slots_.clear();

    PLOGD("release_all: all %u transfer structs and payload buffers freed",
          cfg_.pool_size);
}

// ─────────────────────────────────────────────────────────────────────────────
// DSD stall observation window — Step 13
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Arm the 200 ms DSD stall observation window.
 *
 * Captures the current steady_clock timestamp as a nanosecond integer for
 * lock-free access from the callback, then sets dsd_window_active_ with
 * memory_order_release so the callback's acquire-load guarantees the epoch
 * value is visible before any stall flag is evaluated.
 */
void IsoTransferPool::begin_dsd_observation_window() noexcept
{
    dsd_stall_in_window_.store(false, std::memory_order_relaxed);

    dsd_window_start_ns_ =
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
        .count();

    // Release-store: pairs with the acquire-load in iso_transfer_callback,
    // ensuring dsd_window_start_ns_ is visible before the window flag is seen.
    dsd_window_active_.store(true, std::memory_order_release);

    PLOGI("IsoTransferPool: DSD observation window armed (200 ms)");
}

/**
 * Disarm the DSD stall observation window.
 *
 * Clears both the active flag and the stall flag so the callback stops
 * recording stalls and the monitor thread stops polling.
 */
void IsoTransferPool::clear_dsd_observation_window() noexcept
{
    // Disable window first so callback stops recording new stalls.
    dsd_window_active_.store(false, std::memory_order_release);
    dsd_stall_in_window_.store(false, std::memory_order_relaxed);

    PLOGI("IsoTransferPool: DSD observation window cleared");
}

// ─────────────────────────────────────────────────────────────────────────────
// IsoTransferPool — destructor
// ─────────────────────────────────────────────────────────────────────────────

IsoTransferPool::~IsoTransferPool() noexcept
{
    // Log a warning if transfers are still populated at destruction time.
    // This indicates a missing teardown step in Step 5 and would be a bug
    // because any in-flight transfers' callbacks would dereference freed memory.
    if (!transfers_.empty()) {
        PLOGW("~IsoTransferPool: %zu transfer slot(s) still present at destruction — "
              "ensure all in-flight transfers are cancelled before destroying the pool",
              transfers_.size());
    }

    release_all();
}

