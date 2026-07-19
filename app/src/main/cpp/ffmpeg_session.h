#pragma once

#include <cstdint>
#include <string>

#include "i_audio_decoder.h"

struct FfmpegSession;

extern "C" {

/**
 * Opens the single FFmpeg session implementation shared by JNI and the
 * direct-USB decoder adapter.
 */
FfmpegSession *ffmpeg_session_open(
        const char *path,
        bool force_pcm,
        std::string *error_out) noexcept;

/**
 * Reads interleaved PCM from an open session.
 */
int ffmpeg_session_read_pcm(
        FfmpegSession *session,
        uint8_t *destination,
        int destination_capacity) noexcept;

/**
 * Reads canonical MSB-first DSD bytes from an open session.
 */
int ffmpeg_session_read_dsd(
        FfmpegSession *session,
        uint8_t *destination,
        int destination_capacity) noexcept;

/**
 * Repositions and flushes an open session.
 */
bool ffmpeg_session_seek(
        FfmpegSession *session,
        int64_t position_us) noexcept;

/**
 * Copies the immutable negotiated output format.
 */
void ffmpeg_session_get_format(
        const FfmpegSession *session,
        DecoderFormat *format_out) noexcept;

/**
 * Releases every resource owned by a session.
 */
void ffmpeg_session_close(FfmpegSession *session) noexcept;

}
