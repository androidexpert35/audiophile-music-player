// ─────────────────────────────────────────────────────────────────────────────
// i_audio_decoder.h
//
// Step 15 — IAudioDecoder: clean abstraction layer between the audio decode
// engine (FFmpeg) and the USB isochronous output engine (libusb + SPSC ring).
//
// Design rationale
// ────────────────
// The legacy architecture had the FFmpeg JNI bridge write decoded PCM into a
// Kotlin-owned DirectByteBuffer, which the Kotlin layer then forwarded to
// AudioTrack.  This file severs that coupling:
//
//   OLD:  FFmpeg → JNI DirectByteBuffer → Kotlin → AudioTrack
//   NEW:  FFmpeg → IAudioDecoder → DecoderToRingBridge → SpscRingBuffer → libusb
//
// IAudioDecoder exposes only the primitive operations the ring-buffer pump
// (DecoderToRingBridge) needs:
//
//   1. Format inquiry — sample rate, bit depth, encoding, channel count.
//   2. PCM read      — fill a caller-owned buffer with decoded interleaved PCM.
//   3. DSD read      — fill a caller-owned buffer with raw bit-normalised DSD.
//   4. Seek          — reposition the decode cursor for transport control.
//   5. Release       — destroy the session and free all decoder resources.
//
// All rendering concerns (AudioTrack, ALSA, AAudio, USB endpoints) are
// explicitly EXCLUDED from this interface.  The interface is also independent
// of the JNI boundary — no JNI types appear here — so the pump thread can
// call read_pcm() without ever interacting with the JVM.
//
// Thread-safety contract:
//   All methods MUST be called from the same single thread (the pump thread).
//   The interface does not provide internal synchronisation — this matches the
//   existing Session single-owner model in ffmpeg_bridge.cpp.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cstddef>
#include <cstdint>

// ─────────────────────────────────────────────────────────────────────────────
// DecoderFormat
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Describes the PCM output shape produced by a decoder session.
 *
 * All fields are populated during session open and remain immutable for the
 * lifetime of the session.  The pump thread reads this once at startup to
 * configure the SPSC ring buffer and the USB transfer pool.
 *
 * @property sample_rate_hz       PCM samples per second (e.g. 44100, 96000,
 *                                88200 for DSD-fallback sessions).
 * @property channel_count        Number of interleaved audio channels (1 or 2
 *                                for the current scanner; never 0).
 * @property bit_depth            Source bit depth (1 = DSD raw, 16 = CD,
 *                                24/32 = hi-res lossless, 32 = float).
 * @property bytes_per_sample     Bytes occupied per channel per PCM sample in
 *                                the interleaved output stream.
 * @property android_pcm_encoding AudioFormat.ENCODING_PCM_* constant from the
 *                                Android SDK (2=16-bit, 4=float, 22=32-bit).
 *                                Zero for native-DSD sessions that bypass PCM.
 * @property is_dsd               True when the decoder is operating in
 *                                pass-through DSD mode.  In that case
 *                                read_dsd() must be used instead of read_pcm().
 * @property dsd_rate_multiplier  DSD rate as a multiple of 44.1 kHz (64, 128,
 *                                256).  Meaningful only when is_dsd == true.
 */
struct DecoderFormat {
    int  sample_rate_hz        = 0;
    int  channel_count         = 0;
    int  bit_depth             = 0;
    int  bytes_per_sample      = 0;
    int  android_pcm_encoding  = 0;
    bool is_dsd                = false;
    int  dsd_rate_multiplier   = 0;
};

// ─────────────────────────────────────────────────────────────────────────────
// Read-result sentinels
// ─────────────────────────────────────────────────────────────────────────────

/// Returned by read_pcm() / read_dsd() when the source stream is exhausted.
static constexpr int kDecoderEof               =  0;

/// Returned by read_pcm() / read_dsd() on a recoverable decode error.
/// The caller should retry on the next pump iteration.
static constexpr int kDecoderRecoverableError  = -1;

// ─────────────────────────────────────────────────────────────────────────────
// IAudioDecoder
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pure-virtual contract for an audio decode session that delivers raw PCM or
 * DSD bytes to a caller-owned output buffer.
 *
 * ### Responsibilities
 *
 * - Open and manage the lifecycle of a decode session for a single source file.
 * - Decode compressed audio frames into the chosen output PCM format.
 * - Normalise DSD bit-order to MSB-first when operating in DSD pass-through mode.
 * - Expose the resulting stream shape via format() so callers can configure
 *   downstream rendering without querying implementation details.
 *
 * ### What this interface explicitly does NOT do
 *
 * - ❌ Write to AudioTrack, AAudio, ALSA, or any OS audio output.
 * - ❌ Write to any USB endpoint or isochronous transfer buffer.
 * - ❌ Interact with the JVM or cross the JNI boundary during read_pcm/read_dsd.
 * - ❌ Apply DSP (resampling, EQ, limiting) beyond what is intrinsic to the
 *      codec itself (e.g. DSD-prep lavfi graph).
 *
 * @see FfmpegAudioDecoder   — FFmpeg-backed implementation.
 * @see DecoderToRingBridge  — pump that connects this to the SpscRingBuffer.
 */
class IAudioDecoder {
public:
    virtual ~IAudioDecoder() = default;

    // Non-copyable — ownership is transferred via unique_ptr.
    IAudioDecoder(const IAudioDecoder &)            = delete;
    IAudioDecoder &operator=(const IAudioDecoder &) = delete;

    // ── Format inquiry ────────────────────────────────────────────────────────

    /**
     * Returns the output format negotiated during session open.
     *
     * The returned reference is valid for the lifetime of the decoder object
     * and is safe to cache in the pump thread without further inquiry.
     *
     * @return Immutable DecoderFormat describing the PCM/DSD output shape.
     */
    virtual const DecoderFormat &format() const noexcept = 0;

    // ── PCM read (hot path) ───────────────────────────────────────────────────

    /**
     * Decode and write up to `dst_cap` bytes of interleaved PCM into `dst`.
     *
     * This is the hot-path function called repeatedly by the pump thread.
     * It must be:
     *   - Non-blocking (never waits on a lock or system call with unbounded delay)
     *   - Allocation-free after session open (no heap alloc on the decode path)
     *   - Callable without the JVM being attached
     *
     * Must NOT be called when format().is_dsd == true; use read_dsd() instead.
     *
     * @param dst      Caller-owned output buffer, must be at least dst_cap bytes.
     * @param dst_cap  Maximum bytes to write.  Must be > 0.
     * @return  > 0                   — bytes written; may be less than dst_cap.
     *          kDecoderEof (0)       — stream exhausted; no more data.
     *          kDecoderRecoverableError (-1) — transient failure; caller may retry.
     */
    virtual int read_pcm(uint8_t *dst, int dst_cap) noexcept = 0;

    // ── DSD read (pass-through path) ──────────────────────────────────────────

    /**
     * Read up to `dst_cap` MSB-first DSD bytes into `dst`.
     *
     * Raw DSD byte streams are normalised to MSB-first order regardless of the
     * source container (DSF stores LSBF; DSDIFF stores MSBF).  Callers receive
     * a canonical bit stream they can forward directly to a DoP formatter or a
     * native-DSD isochronous endpoint.
     *
     * Must only be called when format().is_dsd == true; on PCM sessions this
     * returns kDecoderRecoverableError immediately.
     *
     * @param dst      Caller-owned output buffer, must be at least dst_cap bytes.
     * @param dst_cap  Maximum bytes to write.  Must be > 0.
     * @return  > 0                   — bytes written.
     *          kDecoderEof (0)       — stream exhausted.
     *          kDecoderRecoverableError (-1) — transient failure; caller may retry.
     */
    virtual int read_dsd(uint8_t *dst, int dst_cap) noexcept = 0;

    // ── Transport control ─────────────────────────────────────────────────────

    /**
     * Reposition the decode cursor to `position_us` microseconds from the
     * beginning of the stream.
     *
     * After a successful seek the internal spill buffer is cleared so the next
     * read_pcm() / read_dsd() call returns frames from the new position.
     *
     * @param position_us  Target position in microseconds.  Values outside
     *                     [0, duration_us] are clamped by the implementation.
     * @return  true  — seek succeeded and the cursor is at the new position.
     *          false — seek failed; cursor position is undefined and the
     *                  caller should close the session.
     */
    virtual bool seek(int64_t position_us) noexcept = 0;

protected:
    IAudioDecoder() = default;
};

