// ─────────────────────────────────────────────────────────────────────────────
// usb_session_transport.h
//
// Shared transport preparation for nativeStartPlayback (PCM) and
// nativeStartDsdPlayback (DSD): both entry points need the identical
// ring-buffer + libusb-event-thread bring-up with identical rollback, so the
// sequence lives here exactly once.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cstddef>

struct UsbDriverContext;

/// Ring buffer capacity (bytes) created by usb_transport_prepare().
/// 192 kHz / 32-bit stereo = 1 536 B per 1 ms transfer × 16 ms pool × 3× headroom
/// → 73 728 B → next power of two = 131 072 B (128 KB ≈ 43 ms of audio).
inline constexpr std::size_t kDefaultRingBufferBytes = 128UL * 1024UL;

/**
 * Prepares the isochronous transport for a playback session:
 *
 *   1. Creates the SPSC ring buffer (kDefaultRingBufferBytes).
 *   2. Attaches it to the transfer pool so the ISO callback can pop bytes.
 *   3. Starts the dedicated libusb event thread (must be running BEFORE any
 *      transfer is submitted, or the first completion callback never fires).
 *
 * Does NOT submit ISO transfers — submission timing differs per session type
 * (PCM defers until the ring holds real audio; DSD submits silence-primed
 * slots itself).
 *
 * On failure everything newly created here is rolled back; the transfer pool
 * is left untouched for nativeRelease().
 *
 * @param ctx      Driver context with a non-null transfer_pool.
 * @param log_tag  Caller name used as the log prefix.
 * @return 0 on success; -2 pool missing, -3 ring creation failed,
 *         -4 event thread failed (same codes both JNI callers return).
 */
int usb_transport_prepare(UsbDriverContext *ctx, const char *log_tag) noexcept;

/**
 * Rolls back a successful usb_transport_prepare(): stops the event thread,
 * detaches the ring from the pool, and destroys the ring.  Used by error
 * paths that fail after transport preparation (e.g. DSD manager creation).
 */
void usb_transport_abort(UsbDriverContext *ctx) noexcept;
