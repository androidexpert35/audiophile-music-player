// ─────────────────────────────────────────────────────────────────────────────
// fd_trampoline_path.h
//
// Recognises the `/proc/self/fd/<n>` paths the Kotlin layer produces for
// `content://` tracks (see BitPerfectUriResolver.kt).
//
// Handing such a path to FFmpeg's file protocol makes the kernel *re-open* the
// underlying file under this app's uid, and MediaProvider's FUSE layer then
// re-checks access from scratch: READ_MEDIA_AUDIO does not cover `.dsf` /
// `.dff` (the platform has no DSD MIME entry), so every SAF-granted DSD
// document was rejected with EACCES while MediaStore-indexed FLAC/WAV on the
// same volume opened fine. The already-open descriptor carries the grant, so
// the decoder reads through it instead — this header is the shared, host
// testable recogniser that decides which of the two routes a path takes.
// ─────────────────────────────────────────────────────────────────────────────
#ifndef AUDIOPHILE_FD_TRAMPOLINE_PATH_H
#define AUDIOPHILE_FD_TRAMPOLINE_PATH_H

#include <cstdint>
#include <cstring>

/** Prefix of the descriptor trampoline paths produced for `content://` URIs. */
inline constexpr const char *kFdTrampolinePrefix = "/proc/self/fd/";

/**
 * Extracts the descriptor number from a `/proc/self/fd/<n>` trampoline path.
 *
 * Only a bare, fully numeric suffix is accepted: anything else (an ordinary
 * file path, a trailing component such as `/proc/self/fd/12/x`, a negative or
 * overflowing number) is reported as "not a trampoline" so the caller keeps
 * using the normal path-based open.
 *
 * @param path Null-terminated source path handed to the decoder, may be null.
 * @return The descriptor number, or `-1` when [path] is not a trampoline path.
 */
inline int parse_fd_trampoline_path(const char *path)
{
    if (path == nullptr) return -1;

    const size_t prefix_len = std::strlen(kFdTrampolinePrefix);
    if (std::strncmp(path, kFdTrampolinePrefix, prefix_len) != 0) return -1;

    const char *digits = path + prefix_len;
    if (*digits == '\0') return -1;

    int64_t value = 0;
    for (const char *p = digits; *p != '\0'; ++p) {
        if (*p < '0' || *p > '9') return -1;
        value = value * 10 + (*p - '0');
        if (value > INT32_MAX) return -1;
    }
    return static_cast<int>(value);
}

#endif  // AUDIOPHILE_FD_TRAMPOLINE_PATH_H
