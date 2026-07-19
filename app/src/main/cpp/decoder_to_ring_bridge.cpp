// ─────────────────────────────────────────────────────────────────────────────
// decoder_to_ring_bridge.cpp
//
// DecoderToRingBridge implementation.
//
// The pump thread is the single producer for the SpscRingBuffer.  It calls
// IAudioDecoder::read_pcm() (or read_dsd() for native-DSD sessions) in a tight
// loop and forwards each decoded chunk directly into the ring via push().
//
// ── Pump loop flow ────────────────────────────────────────────────────────────
//
//   while (!should_stop_) {
//       if ring is full: sleep kPollIntervalUs µs and continue
//       call decoder.read_pcm(chunk, kChunkBytes)
//       if EOF:  set eof_, call on_eof_callback_, exit loop
//       if err:  increment error counter, continue (recoverable)
//       ring.push(chunk, bytes_decoded)   // may push fewer if ring fills mid-chunk
//   }
//
// ── Underrun behaviour ────────────────────────────────────────────────────────
// If the ring becomes full faster than the ISO callback drains it — common
// during the first-connect window while the DAC clock stabilises — the pump
// sleeps 500 µs per iteration.  This is power-safe and avoids spinning.
//
// If the ISO callback drains faster than the decoder feeds (transient underrun),
// the callback's existing underrun path emits silence (zero-fill) for that
// transfer, keeping the DAC's isochronous clock alive.  The pump resumes
// filling the ring on the next decode iteration.
//
// ── DSD vs PCM dispatch ───────────────────────────────────────────────────────
// The session's is_dsd flag is checked once in pump_loop() and the appropriate
// read function pointer is selected.  The hot path executes exactly one
// virtual dispatch per chunk, not one per sample.
// ─────────────────────────────────────────────────────────────────────────────

#include "decoder_to_ring_bridge.h"

#include <algorithm>    // std::min, std::clamp
#include <array>
#include <android/log.h>
#include <cmath>        // std::pow, std::log10
#include <cstring>
#include <limits>       // std::numeric_limits
#include <new>
#include <system_error>
#include <thread>
#include <unistd.h>   // usleep
#include <vector>       // std::vector — pre-allocated S16→S32 expansion heap buffer

#include "audio_gain.h"   // shared quadratic volume taper
#include "spsc_ring_buffer.h"
#include "usb_producer_coordinator.h"
#include "dop_formatter.h"

// ── Logging ───────────────────────────────────────────────────────────────────

#define DRB_TAG   "DecoderToRingBridge"
#define DRBLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, DRB_TAG, __VA_ARGS__)
#define DRBLOGI(...) __android_log_print(ANDROID_LOG_INFO,  DRB_TAG, __VA_ARGS__)
#define DRBLOGW(...) __android_log_print(ANDROID_LOG_WARN,  DRB_TAG, __VA_ARGS__)
#define DRBLOGE(...) __android_log_print(ANDROID_LOG_ERROR, DRB_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Private constructor
// ─────────────────────────────────────────────────────────────────────────────

DecoderToRingBridge::DecoderToRingBridge(
        IAudioDecoder    *decoder,
        SpscRingBuffer   *ring,
        UsbPcmWireFormat  pcm_wire_format,
        std::shared_ptr<UsbProducerCoordinator> producer_coordinator,
        NativeDsdBitOrder dsd_bit_order) noexcept
    : decoder_(decoder),
      ring_(ring),
      pcm_formatter_(pcm_wire_format),
      producer_coordinator_(std::move(producer_coordinator)),
      dsd_bit_order_(dsd_bit_order)
{}

// ─────────────────────────────────────────────────────────────────────────────
// Factory
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Allocate and return a bridge object for the given decoder/ring pair.
 *
 * Does NOT start the pump thread — call start() after the transfer pool and
 * ring buffer are live to avoid priming the ring before ISO transfers begin.
 *
 * @param decoder        Non-null owning decoder; must outlive this bridge.
 * @param ring           Non-null ring buffer attached to the IsoTransferPool.
 * @param dsd_bit_order  Within-byte DSD bit ordering the connected DAC expects.
 *                       `NativeDsdBitOrder::Msbf` (default) suits FiiO, RME, and
 *                       Topping XMOS/ESS DACs.  `Lsbf` is only needed for a
 *                       small number of older Sony / Denon products.
 *                       Ignored for PCM sessions (is_dsd == false).
 * @return               Owning unique_ptr or nullptr on OOM.
 */
std::unique_ptr<DecoderToRingBridge> DecoderToRingBridge::create(
        IAudioDecoder    *decoder,
        SpscRingBuffer   *ring,
        UsbPcmWireFormat  pcm_wire_format,
        std::shared_ptr<UsbProducerCoordinator> producer_coordinator,
        NativeDsdBitOrder dsd_bit_order) noexcept
{
    if (!decoder || !ring) {
        DRBLOGE("create: decoder and ring must be non-null");
        return nullptr;
    }

    auto *raw = new (std::nothrow) DecoderToRingBridge(
            decoder,
            ring,
            pcm_wire_format,
            std::move(producer_coordinator),
            dsd_bit_order);
    if (!raw) {
        DRBLOGE("create: out of memory");
        return nullptr;
    }

    DRBLOGD("create: bridge ready — decoder=%p ring=%p dsd_bit_order=%s",
            static_cast<void *>(decoder),
            static_cast<void *>(ring),
            dsd_bit_order == NativeDsdBitOrder::Msbf ? "MSBF" : "LSBF");

    return std::unique_ptr<DecoderToRingBridge>(raw);
}

// ─────────────────────────────────────────────────────────────────────────────
// Destructor
// ─────────────────────────────────────────────────────────────────────────────

DecoderToRingBridge::~DecoderToRingBridge()
{
    if (producer_coordinator_ != nullptr) {
        producer_coordinator_->detach(this);
    } else {
        stop();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// start / stop
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Launch the pump thread.
 *
 * The thread starts immediately and begins filling the ring.  Pre-filling
 * before the first ISO transfer is submitted ensures the DAC is never starved
 * from the very first callback.
 */
bool DecoderToRingBridge::start() noexcept
{
    if (pump_thread_.joinable()) {
        DRBLOGW("start: pump thread is already running — ignoring duplicate start()");
        return true;
    }

    should_stop_.store(false, std::memory_order_release);
    eof_.store(false, std::memory_order_release);
    recoverable_error_count_.store(0, std::memory_order_release);

    try {
        pump_thread_ = std::thread(&DecoderToRingBridge::pump_loop, this);
    } catch (const std::system_error &error) {
        should_stop_.store(true, std::memory_order_release);
        DRBLOGE("start: std::thread creation failed: %s", error.what());
        return false;
    } catch (...) {
        should_stop_.store(true, std::memory_order_release);
        DRBLOGE("start: unknown thread creation failure");
        return false;
    }

    DRBLOGI("start: pump thread launched");
    return true;
}

/**
 * Signal the pump thread to exit and block until it is joined.
 *
 * After this returns:
 *   - The ring buffer still contains whatever audio was buffered before stop.
 *   - The ISO callback continues draining the ring normally until it empties,
 *     then outputs silence (underrun path) — a brief silence follows track end.
 *   - It is safe to destroy the bridge, re-create it with a new ring, or
 *     re-use the decoder for a new track.
 */
void DecoderToRingBridge::stop() noexcept
{
    should_stop_.store(true, std::memory_order_release);

    if (pump_thread_.joinable()) {
        pump_thread_.join();
        DRBLOGI("stop: pump thread joined");
    }
}

/**
 * Replace the destination ring buffer without touching the decoder.
 *
 * Must only be called between stop() and start() (pump thread not running).
 *
 * @param new_ring  Non-null replacement ring owned by the fresh UsbDriverContext.
 */
void DecoderToRingBridge::reset_ring_buffer(SpscRingBuffer *new_ring) noexcept
{
    if (!new_ring) {
        DRBLOGE("reset_ring_buffer: new_ring must not be null");
        return;
    }
    if (pump_thread_.joinable()) {
        DRBLOGE("reset_ring_buffer: must not be called while pump thread is running");
        return;
    }
    ring_ = new_ring;
    DRBLOGD("reset_ring_buffer: ring replaced — new ring=%p", static_cast<void *>(new_ring));
}

void DecoderToRingBridge::set_dsd_wire_mode(DsdWireMode mode) noexcept
{
    if (pump_thread_.joinable()) {
        DRBLOGE("set_dsd_wire_mode: pump must be stopped before changing mode");
        return;
    }
    dsd_wire_mode_ = mode;
    dop_marker_ = kDopMarkerA;
    DRBLOGI("set_dsd_wire_mode: mode=%s",
            mode == DsdWireMode::Native ? "Native" : "DoP");
}

// ─────────────────────────────────────────────────────────────────────────────
// pump_loop — core pump logic (runs on the pump thread)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Main pump loop.
 *
 * Responsible for bridging the decoder output to the ring buffer with no
 * AudioTrack, ALSA, or OS-audio involvement.  The loop:
 *
 *   1. Checks should_stop_ on every iteration so stop() has O(kPollIntervalUs)
 *      worst-case latency.
 *   2. Backs off for kPollIntervalUs microseconds when the ring is full to
 *      avoid spinning.  The backoff means the pump is idle during that window,
 *      burning no CPU and allowing other threads to run unimpeded.
 *   3. Reads up to kChunkBytes of decoded PCM or DSD bytes by calling
 *      read_pcm() / read_dsd() — chosen once based on format().is_dsd.
 *   4. Pushes one complete formatted chunk into the ring. The pre-decode space
 *      gate reserves the worst-case expansion size, so a chunk is never
 *      intentionally truncated or partially committed.
 *   5. On EOF: sets the eof_ flag, invokes on_eof_callback_ if registered,
 *      and exits the loop cleanly.
 */
// ─────────────────────────────────────────────────────────────────────────────
// Volume taper — quadratic curve
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Converts a raw UI volume position to an amplitude scalar using a
 * **quadratic (x²) power curve**.
 *
 * ### Why quadratic?
 *
 * A linear scalar (y = x) allocates equal slider travel to equal amplitude
 * change, but human hearing is logarithmic — moving from 0 → 0.1 amplitude is
 * a massive perceived change while 0.9 → 1.0 is barely noticeable.  A squared
 * curve is the standard lightweight approximation for perceptual loudness:
 *
 *   - The bottom half of the slider covers the quiet listening range with fine
 *     per-step resolution (−12 dB at 50 %, vs. −6 dB for a linear slider).
 *   - The upper half gives more usable travel at normal listening levels than
 *     the steeper quartic (x⁴) curve, which was found to attenuate too
 *     aggressively (e.g. UI=0.59 → gain=0.12 at x⁴ vs. gain=0.35 at x²).
 *   - It needs no transcendental maths — a single squaring is enough,
 *     keeping the per-chunk cost negligible.
 *
 * ### Mapping table
 *
 *   position │ gain (pos²) │ equiv. dB
 *   ─────────┼─────────────┼──────────
 *   0.00     │  0.000 000  │  −∞  (mute)
 *   0.25     │  0.062 500  │  −24.1 dB
 *   0.50     │  0.250 000  │  −12.0 dB
 *   0.59     │  0.348 100  │   −9.2 dB  ← was 0.121 (−18.3 dB) with x⁴
 *   0.75     │  0.562 500  │   −5.0 dB
 *   0.90     │  0.810 000  │   −1.8 dB
 *   1.00     │  1.000 000  │    0.0 dB
 *
 * ### Special cases
 *
 *   - position ≤ 0.0 : returns 0.0 exactly — absolute silence, no multiply cost.
 *   - position ≥ 1.0 : returns 1.0 exactly — avoids pow() rounding above 1.0.
 *
 * ### Startup mute guard
 *
 * `volume_scalar_` is initialised to **0.0f** so that the pump thread outputs
 * strict silence from its very first iteration.  The Kotlin layer calls
 * `set_volume()` immediately after the bridge is attached (`attachBridge()`),
 * restoring the persisted level within one JNI round-trip (< 1 ms).  This
 * eliminates the "volume blast at playback start" that the old 1.0f default
 * caused when the pump raced ahead of the first JNI volume delivery.
 *
 * @param linear  Raw UI volume position in [0.0, 1.0] from set_volume().
 * @return        Amplitude scalar in [0.0, 1.0] ready for per-sample multiply.
 */
static float linear_to_gain_scalar(float linear) noexcept
{
    // Shared quadratic taper — single source of truth in audio_gain.h, also
    // used by EngineSwapBridge nativeWriteToRingBuffer for the enhanced path.
    return ui_position_to_gain(linear);
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Set the software volume from a raw UI position.
 *
 * Applies the **quadratic taper** (`gain = position²`) immediately and stores
 * the resulting amplitude gain scalar in [0.0, 1.0] into [volume_scalar_].
 * `pump_loop()` reads the pre-computed scalar directly — no additional
 * computation on the hot path.
 *
 * ### Startup mute guard
 *
 * Initialised to 0.0f (absolute silence).  The Kotlin layer calls
 * `set_volume()` via the [initial_volume] parameter of
 * `nativeAttachUsbEngine()` BEFORE the pump thread is started, so the
 * very first pre-fill chunk is already at the correct amplitude.  The
 * `UsbVolumeController.attachBridge()` post-attach call provides an
 * additional safety re-apply after the bridge handle is returned to Kotlin.
 *
 * @param volume  Raw UI slider position in [0.0, 1.0].
 *                0.0 = absolute mute.  1.0 = full scale (0 dB, no attenuation).
 *                Values outside this range are clamped before taper is applied.
 */
void DecoderToRingBridge::set_volume(float volume) noexcept
{
    const float clamped = std::clamp(volume, 0.0f, 1.0f);

    // Apply the quadratic taper here so pump_loop() reads the final gain scalar
    // directly from the atomic without a function call on the hot path.
    // A single squaring matches powf(clamped, 2.0f) exactly for any IEEE-754
    // float in [0, 1] and is cheaper than std::pow.
    const float gain = linear_to_gain_scalar(clamped);   // clamped²
    volume_scalar_.store(gain, std::memory_order_relaxed);

    // Log the raw UI position alongside the resolved gain for easier debugging.
    // dB equivalent = 20·log10(gain) = 40·log10(position).
    const float gain_db = (gain > 0.0f)
        ? (20.0f * std::log10(gain))
        : -std::numeric_limits<float>::infinity();
    DRBLOGD("set_volume: ui=%.3f  quadratic_gain=%.6f  gain_db=%.1f dB",
            clamped, gain, gain_db);
}

/**
 * Returns the current software volume scalar.
 *
 * Thread-safe: relaxed atomic load.
 *
 * @return  Linear amplitude scalar in [0.0, 1.0].
 */
float DecoderToRingBridge::volume() const noexcept
{
    return volume_scalar_.load(std::memory_order_relaxed);
}

// ─────────────────────────────────────────────────────────────────────────────

void DecoderToRingBridge::pump_loop() noexcept
{
    const DecoderFormat &fmt = decoder_->format();

    // ── Universal 32-bit expansion decision ──────────────────────────────────
    //
    // ALL PCM output to the ring buffer is normalised to 32-bit (4-byte)
    // subslots.  This matches the primary alternate settings on high-end UAC2
    // DACs (e.g. FiiO KA5, iFi DACs) which advertise bSubslotSize=4.
    //
    //  • S16LE (bytes_per_sample=2):
    //      Applies to ALL sources whose SwrContext output is AV_SAMPLE_FMT_S16,
    //      including:
    //          – Lossless 16-bit (FLAC/WAV 16-bit): bit_depth=16
    //          – Lossy compressed (MP3/AAC/Vorbis):  bit_depth=16 after the fix
    //            in ffmpeg_session_open — FFmpeg decodes FLTP internally but
    //            SwrContext packs it into S16LE; out_bytes_per_sample = 2.
    //      PcmWireFormatter widens each 16-bit sample in the 32-bit domain.
    //      At unity the source lands in bits 31:16; under attenuation the low
    //      container bits retain the otherwise-lost precision. Wire bytes double:
    //      4 bytes/sample × channels.
    //
    //      IMPORTANT: the guard relies solely on bytes_per_sample == 2, NOT on
    //      bit_depth == 16.  Before the ffmpeg_session_open fix, lossy codecs
    //      (FLTP internally) reported source_bit_depth = 32 while producing S16LE
    //      output — the old `bit_depth == 16` check evaluated false and bypassed
    //      expansion, sending 4 B/stereo-frame into a ring that the ISO pool
    //      drained at 8 B/stereo-frame, causing 2× speed (chipmunk effect).
    //      Using bytes_per_sample as the sole discriminator is correct and robust:
    //      it reflects the ACTUAL width of every sample that enters the ring.
    //
    //  • 24-bit lossless (bytes_per_sample=4, bit_depth=24):
    //      FFmpeg already produces S32LE with audio in bits 31:8 and padding
    //      0x00 in bits 7:0 — already a 32-bit MSB-aligned container.
    //      No conversion; passthrough at 4 bytes/sample × channels.
    //
    //  • float32 / native S32 (bytes_per_sample=4):
    //      Already 4 bytes/sample.  Passthrough.
    //
    // In all non-DSD cases wire_bpf = channel_count × 4 (32-bit subslot).
    const bool needs_s16_expansion =
        !fmt.is_dsd && fmt.bytes_per_sample == 2;  // S16LE output → expand to S32LE MSB-aligned

    // Effective bytes per stereo frame that actually enter the ring.
    // Must match the bytesPerAudioFrame passed to nativeAllocateTransferPool.
    // For all non-DSD formats this resolves to channel_count × 4 = 8 (stereo).
    const int wire_bpf = needs_s16_expansion
        ? (fmt.channel_count * 4)                          // S16 expanded to S32
        : (fmt.channel_count * fmt.bytes_per_sample);      // S32LE / float32 passthrough

    // ── Decoder format banner ─────────────────────────────────────────────────
    {
        const double kbps    = (fmt.sample_rate_hz * wire_bpf) / 1024.0;

        DRBLOGI("pump_loop: ═══ DECODER FORMAT BANNER ════════════════════════");
        DRBLOGI("pump_loop:   is_dsd           = %s",  fmt.is_dsd ? "yes" : "no");
        DRBLOGI("pump_loop:   sample_rate_hz   = %d",  fmt.sample_rate_hz);
        DRBLOGI("pump_loop:   channel_count    = %d",  fmt.channel_count);
        DRBLOGI("pump_loop:   bit_depth        = %d",  fmt.bit_depth);
        DRBLOGI("pump_loop:   bytes_per_sample = %d  ← FFmpeg container width",
                fmt.bytes_per_sample);
        DRBLOGI("pump_loop:   raw bytes/frame  = %d  ← FFmpeg output before expansion",
                fmt.bytes_per_sample * fmt.channel_count);
        DRBLOGI("pump_loop:   android_pcm_enc  = %d  (2=S16 3=S24 4=FLT 22=S32)",
                fmt.android_pcm_encoding);
        DRBLOGI("pump_loop:   expansion_active = %s",
                needs_s16_expansion
                    ? "YES — S16LE→S32LE MSB-aligned (bytes_per_sample=2; covers FLAC-16 AND MP3/AAC)"
                    : (fmt.is_dsd
                        ? "NO  — DSD RAW PASSTHROUGH (is_dsd=true; bytes pushed as-is, no PCM conversion)"
                        : "NO  — passthrough (bytes_per_sample=4: 24-bit S32LE / float32 / native S32)"));
        DRBLOGI("pump_loop:   wire bytes/frame = %d  ← what enters the ring / ISO pool",
                wire_bpf);

        // ── DSD-specific passthrough confirmation ─────────────────────────────
        // Explicitly log that the S16→S32 expansion loop is INACTIVE for DSD.
        // DSD data is raw one-bit audio packed as bytes (MSB-first after normalisation
        // by read_dsd()).  Applying the PCM padding loop would corrupt the DSD marker
        // patterns and cause the DAC to reject every packet with LIBUSB_TRANSFER_STALL.
        // Note: `is_dsd` is declared below after this banner block; use `fmt.is_dsd`
        // here to avoid a use-before-declaration.
        if (fmt.is_dsd) {
            DRBLOGI("pump_loop: ─── DSD PASSTHROUGH ACTIVE ──────────────────────────────");
            DRBLOGI("pump_loop:   S16→S32 expansion loop: DISABLED  (is_dsd=true)");
            DRBLOGI("pump_loop:   Volume multiply:        DISABLED  (DSD — amplitude must not touch 1-bit stream)");
            DRBLOGI("pump_loop:   DSD_U32LE formatter:    ENABLED   (bit_order=%s)",
                    dsd_bit_order_ == NativeDsdBitOrder::Msbf ? "MSBF" : "LSBF");
            DRBLOGI("pump_loop:   Data path: read_dsd() → DSD_U32LE repack → ring_->push() → ISO OUT");
            DRBLOGI("pump_loop:   Input:  [L₀][R₀][L₁][R₁][L₂][R₂][L₃][R₃]  (byte-interleaved MSBF from FFmpeg)");
            DRBLOGI("pump_loop:   Output: [L₀][L₁][L₂][L₃][R₀][R₁][R₂][R₃]  (DSD_U32LE — 4-byte LE words, L then R)");
            DRBLOGI("pump_loop:   bytes_per_sample=%d  channel_count=%d  wire_bpf=%d",
                    fmt.bytes_per_sample, fmt.channel_count, wire_bpf);
            DRBLOGI("pump_loop:   If ring_size=0 at IsoCallback, check read_dsd() return value");
            DRBLOGI("pump_loop: ─────────────────────────────────────────────────────────");
        }
        DRBLOGI("pump_loop:   expected_push    = %.1f KB/s (%.3f MB/s)",
                kbps, kbps / 1024.0);
        DRBLOGI("pump_loop:   ring capacity    = %zu KB",
                ring_->capacity() / 1024UL);
        DRBLOGI("pump_loop:   chunk size       = %zu B  (~%.2f ms at wire rate)",
                kChunkBytes,
                (wire_bpf > 0 && fmt.sample_rate_hz > 0)
                    ? (kChunkBytes * 1000.0) / (fmt.sample_rate_hz * wire_bpf)
                    : 0.0);

        if (needs_s16_expansion) {
            DRBLOGI("pump_loop:   S16→S32 expand: each 2-byte S16LE sample → 4-byte S32LE MSB-aligned.");
            DRBLOGI("pump_loop:   S16LE[B0,B1] → S32LE[0x00,0x00,B0,B1] (audio in bits 31:16, zeros in 15:0).");
            DRBLOGI("pump_loop:   ISO pool must be configured with bytesPerAudioFrame=%d.", wire_bpf);
            DRBLOGI("pump_loop:   Verify UsbAudioSinkFactory passed %d to nativeAllocateTransferPool.", wire_bpf);
        } else {
            DRBLOGI("pump_loop:   No expansion: 24-bit S32LE (audio bits 31:8, pad 0x00 in bits 7:0),");
            DRBLOGI("pump_loop:   float32, or native S32 — all 4 bytes/sample, 32-bit container.");
        }

        DRBLOGI("pump_loop:   IMPORTANT — wire bytes/frame above must equal   ");
        DRBLOGI("pump_loop:   IsoTransferPool bytesPerFrame from nativeAllocateTransferPool.");
        DRBLOGI("pump_loop:   Mismatch = ring overflow (pump > pool) or underrun (pool > pump).");
        DRBLOGI("pump_loop: ═══════════════════════════════════════════════════");
    }

    // Choose the read function pointer once per session to eliminate a
    // virtual dispatch + branch prediction miss on every hot-path iteration.
    const bool is_dsd = fmt.is_dsd;
    const bool use_dop = is_dsd && dsd_wire_mode_ == DsdWireMode::Dop;


    // Stack-allocated raw chunk from the decoder — no heap allocation on the hot path.
    std::array<uint8_t, kChunkBytes + 8U> chunk{};
    std::size_t dsd_carry_bytes = 0U;

    // Dual-purpose staging buffer — pre-allocated on the heap once before the
    // pump loop so there are zero allocations on the hot path.
    //
    // Capacity is always kChunkBytes * 2 bytes, which is the maximum output size
    // produced by either of the two mutually exclusive expansion paths:
    //
    //   Path 1 — DSD_U32LE formatting (is_dsd == true):
    //     Output size == input size (both decode_ret bytes); kChunkBytes capacity
    //     is sufficient, so kChunkBytes * 2 provides headroom.
    //
    //   Path 2 — S16LE → S32LE expansion (needs_s16_expansion == true):
    //     PcmWireFormatter writes exactly (decode_ret * 2) bytes —
    //     each 2-byte S16 sample becomes a 4-byte S32LE subslot.
    //     Maximum output = kChunkBytes * 2 = 16 384 bytes.
    //
    //     padded_buffer_size = decode_ret * 2 ≤ kChunkBytes * 2 always holds
    //     because the decoder never returns more than kChunkBytes raw bytes per
    //     call.  The capacity check below asserts this at runtime.
    //
    // Using std::vector rather than a stack array avoids blowing the thread's
    // stack budget when kChunkBytes is large and eliminates the ambiguity in the
    // diagnostic log where "chunk_cap" was misread as the expansion buffer size.
    const std::size_t kExpandBufBytes = kChunkBytes * 2u;
    std::vector<uint8_t> expanded_chunk(kExpandBufBytes);

    uint64_t total_bytes_pushed = 0;
    uint32_t full_ring_sleeps   = 0;
    uint32_t consecutive_decode_errors = 0;

    // Diagnostic counters for the first-read sniff and periodic heartbeat.
    uint32_t first_read_logged = 0;   // log decode_ret for the first 3 successful reads
    uint64_t loop_iteration    = 0;   // iteration counter used for heartbeat throttle
    uint64_t last_hb_bytes     = 0;   // bytes pushed at the previous heartbeat tick

    // Logged once per DSD session when the first chunk enters the transparent path,
    // so the log fires exactly once regardless of how many iterations run.
    bool dsd_transparent_logged = false;

    while (!should_stop_.load(std::memory_order_acquire)) {
        ++loop_iteration;

        // ── Periodic heartbeat log (~every 2 s) ──────────────────────────────
        // At kPollIntervalUs = 500 µs / sleep, 4000 iterations ≈ 2 s when the
        // ring is constantly full.  Adjust the modulus if the pump is rarely
        // sleeping (fast-drain case) — it will still fire; just less often.
        if ((loop_iteration % 4000) == 0 && loop_iteration > 0) {
            const uint64_t delta_kb = (total_bytes_pushed - last_hb_bytes) / 1024UL;
            last_hb_bytes = total_bytes_pushed;
            DRBLOGI("pump_loop: [heartbeat iter=%llu] "
                    "total_pushed=%llu KB  ring_free=%zu KB / %zu KB  "
                    "full_sleeps=%u  errors=%u  delta=%llu KB/~2s",
                    static_cast<unsigned long long>(loop_iteration),
                    total_bytes_pushed / 1024ULL,
                    ring_->free_space() / 1024UL,
                    ring_->capacity() / 1024UL,
                    full_ring_sleeps,
                    recoverable_error_count_.load(std::memory_order_relaxed),
                    static_cast<unsigned long long>(delta_kb));
        }

        // ── Back-off if the ring has no room ──────────────────────────────────
        // A full ring means the ISO callback is draining slower than we produce
        // (e.g. DAC rate < expected, or we are initialising faster than streaming).
        // Sleep briefly rather than spin to avoid wasting a CPU core.
        //
        // IMPORTANT — threshold must account for S16→S32 expansion:
        //
        //   For S16 input, PcmWireFormatter writes
        //   kChunkBytes * 2 bytes into the ring (each 2-byte S16 sample becomes a
        //   4-byte S32 slot).  Using kChunkBytes alone as the guard means we can
        //   decode and expand when only HALF the required space is available; the
        //   std::min() clamp inside the push block then silently truncates the top
        //   half of the expanded buffer, dropping audio frames and causing the ring
        //   to drain out of sync with the ISO clock — the "chipmunk" pitch shift.
        //
        //   Example (kChunkBytes = 8192):
        //     free = 9 000 B  ≥ 8 192 → old code proceeds
        //     expanded_bytes = 16 384 B
        //     to_push = min(16 384, 9 000) = 9 000   ← 7 384 B (≈923 frames) dropped
        //
        //   Using kChunkBytes * 2 as the threshold for S16 expansion ensures we
        //   only decode when there is guaranteed space for the full expanded output.
        const std::size_t needed_ring_space = needs_s16_expansion
            ? (kChunkBytes * 2u)
            : (is_dsd
                ? (use_dop ? kChunkBytes * 2U : kChunkBytes + 8U)
                : kChunkBytes);
        const std::size_t free = ring_->free_space();
        if (free < needed_ring_space) {
            ++full_ring_sleeps;
            usleep(static_cast<unsigned>(kPollIntervalUs));
            continue;
        }

        // ── Decode the next chunk ─────────────────────────────────────────────
        int decode_ret = is_dsd
            ? decoder_->read_dsd(
                    chunk.data() + dsd_carry_bytes,
                    static_cast<int>(kChunkBytes))
            : decoder_->read_pcm(
                    chunk.data(),
                    static_cast<int>(kChunkBytes));

        if (decode_ret == kDecoderEof) {            // Stream exhausted — set the flag and notify the Kotlin layer via
            // the optional callback.  The remaining bytes in the ring will be
            // drained by the ISO callback naturally.
            //
            // DSD starvation diagnostic: if we reach EOF very early (few bytes
            // pushed) this indicates FFmpeg could not decode the DSF/DSDIFF file —
            // either the file is corrupt, the codec is not compiled in, or the
            // force_pcm flag was set incorrectly for a native-DSD session.
            if (is_dsd) {
                if (dsd_carry_bytes > 0U) {
                    DRBLOGW("pump_loop: DSD EOF with %zu incomplete byte(s); "
                            "source ended between complete stereo frames",
                            dsd_carry_bytes);
                }
                if (total_bytes_pushed == 0) {
                    DRBLOGE("pump_loop: DSD PUMP STARVED: Decoder returned 0 bytes — "
                            "read_dsd() returned EOF immediately (total_bytes_pushed=0). "
                            "FFmpeg DSD decoder did not produce any output. "
                            "Check: (1) DSF/DFF file integrity, (2) FFmpeg DSD codec enabled, "
                            "(3) nativeOpenDecoderForUsb called with forcePcm=false for DSD.");
                } else {
                    DRBLOGI("pump_loop: DSD PUMP: read_dsd() returned EOF "
                            "after %llu bytes — DSD stream exhausted cleanly.",
                            static_cast<unsigned long long>(total_bytes_pushed));
                }
            }
            eof_.store(true, std::memory_order_release);
            DRBLOGI("pump_loop: EOF reached — total_bytes_pushed=%llu",
                    static_cast<unsigned long long>(total_bytes_pushed));
            if (on_eof_callback_) {
                on_eof_callback_();
            }
            break;
        }

        if (decode_ret == kDecoderRecoverableError) {
            // Transient codec error — count and retry on the next iteration.
            // The lavfi soxr FIR kernel priming period produces EAGAIN for
            // several frames; those are normal and not counted as errors here.
            const uint32_t err_count =
                recoverable_error_count_.fetch_add(1, std::memory_order_relaxed) + 1;
            // DSD-specific diagnostic: log the first 5 errors explicitly so
            // repeated read_dsd() failures are immediately visible in logcat.
            // A sustained stream of errors with ring_size=0 in the ISO callback
            // indicates the FFmpeg DSD decoder is not producing output.
            if (is_dsd && err_count <= 5) {
                DRBLOGW("pump_loop: DSD recoverable decode error #%u — "
                        "read_dsd() returned -1. "
                        "If this repeats, FFmpeg DSD pipeline may have a format error.",
                        err_count);
            } else if (!is_dsd) {
                // PCM path: already tracked by the counter; no extra log needed.
            }
            ++consecutive_decode_errors;
            if (consecutive_decode_errors >= 2000U) {
                DRBLOGE("pump_loop: aborting after %u consecutive decoder errors",
                        consecutive_decode_errors);
                break;
            }
            usleep(static_cast<unsigned>(kPollIntervalUs));
            continue;
        }
        consecutive_decode_errors = 0U;

        if (is_dsd) {
            decode_ret += static_cast<int>(dsd_carry_bytes);
            dsd_carry_bytes = 0U;
        }

        // ── First-read sniff: log decoded vs wire bytes ───────────────────────
        // Prints the raw FFmpeg byte count alongside the wire (post-expansion) count
        // for the first 3 reads so mismatches between the decoder and ISO pool are
        // immediately visible without filtering through heartbeat logs.
        if (first_read_logged < 3) {
            ++first_read_logged;
            const bool doubles_on_wire = needs_s16_expansion || use_dop;
            const int wire_bytes = doubles_on_wire
                ? (decode_ret * 2)   // S16→S32: doubles byte count
                : decode_ret;        // passthrough (already 4 bytes/sample)
            // padded_buffer_size is the number of bytes that will be written into
            // expanded_chunk for this chunk: decode_ret * 2 for S16 expansion,
            // decode_ret for passthrough.  kExpandBufBytes is the pre-allocated
            // capacity of the vector — must always be ≥ padded_buffer_size.
            const std::size_t padded_buffer_size =
                doubles_on_wire
                    ? static_cast<std::size_t>(decode_ret) * 2u
                    : static_cast<std::size_t>(decode_ret);
            DRBLOGI("pump_loop: [first_read #%u] decode_ret=%d B (raw)  "
                    "wire_bytes=%d B  s16_expansion=%s  "
                    "padded_buffer_size=%zu B  expand_buf_cap=%zu B",
                    first_read_logged,
                    decode_ret,
                    wire_bytes,
                    needs_s16_expansion ? "YES" : "NO",
                    padded_buffer_size,
                    kExpandBufBytes);
            if (padded_buffer_size > kExpandBufBytes) {
                // This should never happen: decoder returns ≤ kChunkBytes, so
                // padded ≤ kChunkBytes * 2 == kExpandBufBytes.
                DRBLOGE("pump_loop: BUG — padded_buffer_size %zu > expand_buf_cap %zu "
                        "— buffer overflow imminent; halting pump",
                        padded_buffer_size, kExpandBufBytes);
                break;
            }
        }

        // ── Route decoded bytes based on stream type ─────────────────────────
        //
        //   1. DSD          → DSD_U32LE repack (is_dsd == true)
        //   2. PCM (unified) → 64-bit volume-scaled S32LE output via expanded_chunk
        //                      (covers both S16LE expansion and S32LE passthrough)

        if (is_dsd) {
            // ── Path 1: DSD_U32LE Conversion and Push ─────────────────────────
            //
            // read_dsd() returns byte-interleaved MSB-first DSD bytes:
            //   [L₀][R₀][L₁][R₁][L₂][R₂][L₃][R₃] … (8 bytes = 1 stereo 32-bit frame)
            //
            // The XMOS/FiiO KA5 and similar UAC2 DACs require DSD_U32LE format:
            //   [L₀][L₁][L₂][L₃][R₀][R₁][R₂][R₃] … (two adjacent 32-bit LE words,
            //                                          L-channel word first, R-channel word second)
            //
            // format_native_dsd_from_interleaved_msbf() performs the de-interleave:
            //   src[8i+0] → L word bits [ 7: 0]   src[8i+1] → R word bits [ 7: 0]
            //   src[8i+2] → L word bits [15: 8]   src[8i+3] → R word bits [15: 8]
            //   src[8i+4] → L word bits [23:16]   src[8i+5] → R word bits [23:16]
            //   src[8i+6] → L word bits [31:24]   src[8i+7] → R word bits [31:24]
            //
            // The LSBF variant additionally bit-reverses each byte before packing
            // for DACs whose firmware expects bit 0 = first DSD clock pulse.
            //
            // Output size equals input size: both are `decode_ret` bytes per chunk.
            // `expanded_chunk` (capacity = kChunkBytes * 2 = 16 KB) is reused as
            // the DSD_U32LE staging buffer; the DSD and S16 paths are mutually
            // exclusive so the buffer is never in use by both simultaneously.
            //
            // VOLUME is NOT applied to DSD: DSD is a 1-bit stream — multiplying
            // individual bytes would corrupt the 1-bit DSD samples and produce
            // white noise.  Volume control for DSD-capable DACs is performed in
            // the hardware domain (DAC volume register) or via analog attenuation.

            // Floor decode_ret to the nearest multiple of 8 — each DSD_U32LE
            // stereo frame consumes exactly 8 interleaved source bytes.  The
            // spill buffer writes L/R byte pairs so decode_ret is typically a
            // multiple of 8; floor ensures we never pass a fractional frame count
            // to the formatter.
            const std::size_t input_bytes_per_frame = use_dop ? 4U : 8U;
            const std::size_t dsd_frames =
                    static_cast<std::size_t>(decode_ret) / input_bytes_per_frame;
            const std::size_t dsd_input_bytes =
                    dsd_frames * input_bytes_per_frame;
            const std::size_t dsd_format_bytes = dsd_frames * 8U;
            const std::size_t remainder =
                    static_cast<std::size_t>(decode_ret) - dsd_input_bytes;

            if (dsd_frames == 0) {
                dsd_carry_bytes = static_cast<std::size_t>(decode_ret);
                DRBLOGD("pump_loop: retained %zu DSD byte(s) for the next frame",
                        dsd_carry_bytes);
                continue;
            }

            // Log once per session when the first DSD chunk enters this path.
            if (!dsd_transparent_logged) {
                dsd_transparent_logged = true;
                DRBLOGI("DSD_PUMP: %s conversion active — bit_order=%s",
                        use_dop ? "DoP" : "DSD_U32LE",
                        dsd_bit_order_ == NativeDsdBitOrder::Msbf ? "MSBF" : "LSBF");
                DRBLOGI("DSD_PUMP: decode_ret=%d B  dsd_frames=%zu  dsd_format_bytes=%zu B"
                        "  ring_free=%zu B  ring_cap=%zu B",
                        decode_ret, dsd_frames, dsd_format_bytes,
                        ring_->free_space(), ring_->capacity());
            }

            if (use_dop) {
                dop_marker_ = format_dop_from_interleaved_dsd(
                        chunk.data(),
                        expanded_chunk.data(),
                        dsd_frames,
                        dop_marker_);
            } else if (dsd_bit_order_ == NativeDsdBitOrder::Msbf) {
                format_native_dsd_from_interleaved_msbf(
                    chunk.data(), expanded_chunk.data(), dsd_frames);
            } else {
                format_native_dsd_from_interleaved_lsbf(
                    chunk.data(), expanded_chunk.data(), dsd_frames);
            }

            if (remainder > 0U) {
                std::memmove(
                        chunk.data(),
                        chunk.data() + dsd_input_bytes,
                        remainder);
                dsd_carry_bytes = remainder;
            }

            if (dsd_format_bytes > 0U) {
                if (!ring_->push(expanded_chunk.data(), dsd_format_bytes)) {
                    DRBLOGW("pump_loop: DSD ring push failed for %zu bytes — retrying",
                            dsd_format_bytes);
                } else {
                    total_bytes_pushed += dsd_format_bytes;
                }
            }

        } else {
            const auto encoding = fmt.bytes_per_sample == 2
                ? PcmSourceEncoding::S16Le
                : PcmSourceEncoding::S32Le;
            const double gain = static_cast<double>(
                    volume_scalar_.load(std::memory_order_relaxed));
            const auto formatted = pcm_formatter_.format(
                    chunk.data(),
                    static_cast<std::size_t>(decode_ret),
                    encoding,
                    gain,
                    expanded_chunk.data(),
                    expanded_chunk.size());
            if (!formatted.success) {
                DRBLOGE("pump_loop: PCM formatter rejected chunk bytes=%d "
                        "sourceBytes=%d gain=%.6f wireValidBits=%u",
                        decode_ret,
                        fmt.bytes_per_sample,
                        gain,
                        static_cast<unsigned>(
                                pcm_formatter_.wire_format().valid_bits));
                recoverable_error_count_.fetch_add(1, std::memory_order_relaxed);
                continue;
            }

            if (!ring_->push(expanded_chunk.data(), formatted.bytes_written)) {
                DRBLOGW("pump_loop: PCM ring push failed for %zu B",
                        formatted.bytes_written);
            } else {
                total_bytes_pushed += formatted.bytes_written;
            }
        }
    }

    DRBLOGI("pump_loop: exited — total_bytes_pushed=%llu full_ring_sleeps=%u errors=%u",
            static_cast<unsigned long long>(total_bytes_pushed),
            full_ring_sleeps,
            recoverable_error_count_.load(std::memory_order_relaxed));
}
