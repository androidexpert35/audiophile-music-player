#pragma once

#include <cstdint>

/**
 * Selects the USB framing applied to the canonical interleaved DSD byte stream.
 */
enum class DsdWireMode : uint8_t {
    Native,
    Dop,
};
