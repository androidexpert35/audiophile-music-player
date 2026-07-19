// ─────────────────────────────────────────────────────────────────────────────
// usb_handle_validation.h
//
// Shared validation for jlong-encoded native handles.
//
// ### ARM64 MTE / HWASan tagged-pointer contract
//
// On Android ARM64 devices with the Memory Tagging Extension (MTE) or
// Hardware-Assisted AddressSanitizer (HWASan) enabled, the allocator embeds a
// memory tag in the top byte of every heap pointer (e.g. 0xb4000075…).  When
// such a pointer is cast to jlong (signed 64-bit) the tag byte sets bit 63,
// making the value appear **negative**.  A guard of the form `handle <= 0`
// therefore incorrectly rejects fully valid context pointers on these devices.
//
// The correct rule, mirrored by `UsbAudioBridge.isValidHandle()` on the Kotlin
// side, is: reject only the null sentinel (0) and the known small-negative
// error codes of the entry point that produced the handle; accept every other
// value — positive or negative — as a valid pointer.
//
// This header is JNI-free (int64_t instead of jlong) so it can be unit-tested
// on the host.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cstdint>

/**
 * Lowest (most negative) error sentinel returned by
 * `nativeInitWithFileDescriptor()` in usb_audio_bridge.cpp (ERR_OUT_OF_MEMORY).
 * A static_assert next to the ERR_* definitions keeps this in sync.
 */
inline constexpr int64_t kUsbDriverLowestErrorSentinel = -4;

/**
 * Lowest (most negative) error sentinel returned by
 * `nativeAttachUsbEngine()` in engine_swap_bridge.cpp (SWAP_ERR_SUBMIT_FAILED).
 * A static_assert next to the SWAP_ERR_* definitions keeps this in sync.
 */
inline constexpr int64_t kEngineSwapLowestErrorSentinel = -6;

/**
 * Returns true when `handle` is safe to cast back to the producing entry
 * point's context pointer type.
 *
 * @param handle                 Value previously returned across JNI.
 * @param lowest_error_sentinel  Most negative error code the producing entry
 *                               point can return (e.g. kUsbDriverLowestErrorSentinel).
 */
[[nodiscard]] constexpr bool is_valid_native_handle(
        int64_t handle,
        int64_t lowest_error_sentinel) noexcept
{
    if (handle == 0) return false;                                    // null / OOM
    if (handle >= lowest_error_sentinel && handle <= -1) return false; // error code
    return true;                                                       // pointer (may be MTE-tagged)
}
