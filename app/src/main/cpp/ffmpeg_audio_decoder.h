// ─────────────────────────────────────────────────────────────────────────────
// ffmpeg_audio_decoder.h
//
// Step 15 — FfmpegAudioDecoder: FFmpeg-backed implementation of IAudioDecoder.
//
// This class provides the IAudioDecoder contract (PCM / DSD byte delivery)
// backed by the existing Session decode pipeline from ffmpeg_bridge.cpp,
// with ALL audio rendering concerns removed:
//
//   REMOVED FROM THIS LAYER:
//     • AudioTrack writes
//     • DirectByteBuffer JNI references
//     • Any threading or scheduling logic
//
//   RETAINED FROM ffmpeg_bridge.cpp:
//     • avformat / avcodec decode pipeline
//     • SwrContext format conversion
//     • DSD-prep lavfi filter graph (volume / LPF / alimiter / soxr VHQ)
//     • Spill buffer for partial-frame accumulation
//     • Source-depth-preserving output format selection
//     • CPU affinity hint via bind_current_thread_for_decode_load()
//
// ### Creation
//
// Use FfmpegAudioDecoder::open() — it wraps avformat_open_input and returns
// nullptr on failure with a descriptive error string instead of throwing across
// the JNI boundary.
//
// ### Thread model
//
// The same single-owner constraint from Session applies: every method must be
// called from the same thread (the DecoderToRingBridge pump thread).
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "i_audio_decoder.h"
#include "ffmpeg_session.h"

// ─────────────────────────────────────────────────────────────────────────────
// FfmpegAudioDecoder
// ─────────────────────────────────────────────────────────────────────────────

/**
 * FFmpeg-backed audio decoder implementing IAudioDecoder.
 *
 * Decodes any format supported by the vendored FFmpeg libraries (FLAC, ALAC,
 * WAV, MP3, AAC, OPUS, DSF, DSDIFF, …) and presents the output as a simple
 * pull-style byte stream with no AudioTrack or OS-output dependency.
 *
 * ### PCM output contract
 *
 *   - Output is always interleaved (packed), never planar.
 *   - Bit depth mirrors the source material (16-bit, 24-bit zero-padded to
 *     32-bit, 32-bit float) — consistent with the existing ffmpeg_bridge rules.
 *   - DSD sessions deliver raw MSB-first DSD bytes via read_dsd(); the lavfi
 *     decimation / PCM-fallback path is activated by passing @p force_pcm.
 *
 * ### Memory ownership
 *
 * Constructed exclusively via open().  The Session* is owned by this object;
 * do not share the pointer.
 *
 * @see IAudioDecoder
 * @see DecoderToRingBridge
 */
class FfmpegAudioDecoder final : public IAudioDecoder {
public:
    /**
     * Open and initialise an FFmpeg decode session for the given file path.
     *
     * Runs avformat_open_input, stream selection, codec open, SwrContext
     * allocation for format conversion, and DSD-prep lavfi graph construction
     * (when the source is DSD and force_pcm is true).  CPU affinity for the
     * calling thread is set based on decode load classification.
     *
     * This is the only valid way to construct a FfmpegAudioDecoder.
     *
     * @param path        Absolute filesystem path to the audio file.
     * @param force_pcm   When true and the source is DSD, the decoder operates
     *                    as a PCM-fallback session (Tier-3 path: lavfi+soxr VHQ
     *                    decimation to 88 200 Hz FLT).  When false, DSD sessions
     *                    emit raw DSD bytes for DoP / native-DSD delivery.
     * @param error_out   Optional output string filled with a human-readable
     *                    error description when open fails.
     * @return            Owning unique_ptr to a ready decoder, or nullptr on
     *                    any open/codec/format error.
     */
    static std::unique_ptr<FfmpegAudioDecoder> open(
            const char  *path,
            bool         force_pcm,
            std::string *error_out = nullptr) noexcept;

    ~FfmpegAudioDecoder() override;

    // ── IAudioDecoder ─────────────────────────────────────────────────────────

    /**
     * @copydoc IAudioDecoder::format
     */
    const DecoderFormat &format() const noexcept override;

    /**
     * @copydoc IAudioDecoder::read_pcm
     *
     * Internally drains the session spill buffer first, then pulls AVPackets
     * and decoded AVFrames until `dst_cap` bytes are available or the stream
     * is exhausted.  Delegates through the DSD-prep lavfi graph when the
     * session is a force-PCM DSD source.
     */
    int read_pcm(uint8_t *dst, int dst_cap) noexcept override;

    /**
     * @copydoc IAudioDecoder::read_dsd
     *
     * Reads raw AVPacket payloads, normalises bits to MSB-first, and fills
     * `dst`.  Only valid when the session was opened with force_pcm = false
     * and the source is a DSD codec (DSF / DSDIFF / WavPack-DSD).
     */
    int read_dsd(uint8_t *dst, int dst_cap) noexcept override;

    /**
     * @copydoc IAudioDecoder::seek
     *
     * Calls av_seek_frame() to the nearest packet at or before position_us,
     * flushes codec and lavfi buffers, and clears the spill buffer. Raw DSD
     * sessions additionally trim the leading bytes of the first post-seek
     * packet so playback begins at sample precision rather than at the earlier
     * DSF/DSDIFF block boundary.
     */
    bool seek(int64_t position_us) noexcept override;

private:
    // Constructed only by open().
    explicit FfmpegAudioDecoder(FfmpegSession *session) noexcept;

    /// Owning pointer to the opaque decode session.
    FfmpegSession *session_ = nullptr;

    /// Cached output format set once during open().
    DecoderFormat format_{};
};
