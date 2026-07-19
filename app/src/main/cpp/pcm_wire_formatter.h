#pragma once

#include <cstddef>
#include <cstdint>

#include "usb_pcm_wire_format.h"

/**
 * Identifies the decoder-side integer PCM representation supplied to the USB
 * wire formatter.
 */
enum class PcmSourceEncoding : uint8_t {
    S16Le,
    S32Le,
};

/**
 * Reports the result of formatting one decoded PCM chunk.
 */
struct PcmFormatResult {
    std::size_t bytes_written = 0;
    bool success = false;
};

/**
 * Converts decoded integer PCM into the negotiated MSB-aligned four-byte UAC2
 * subslot without allocating on the audio thread.
 *
 * Unity gain has an explicit integer-only path so a bit-perfect claim can be
 * verified byte-for-byte. Attenuated S16 input is promoted before gain is
 * applied, allowing endpoints with 24 or 32 valid bits to retain precision
 * that would otherwise be lost by re-quantising to S16 first.
 */
class PcmWireFormatter {
public:
    explicit constexpr PcmWireFormatter(UsbPcmWireFormat format) noexcept
        : format_(format)
    {}

    /**
     * Formats a complete interleaved PCM chunk.
     *
     * Input sizes that do not contain whole samples, unsupported endpoint
     * formats, non-finite gains, and insufficient output capacity fail without
     * writing a partial chunk.
     */
    [[nodiscard]] PcmFormatResult format(
            const uint8_t *input,
            std::size_t input_bytes,
            PcmSourceEncoding encoding,
            double gain,
            uint8_t *output,
            std::size_t output_capacity) const noexcept;

    /**
     * Converts normalized interleaved float32 samples to the same negotiated
     * integer USB representation used by the decoder pump.
     */
    [[nodiscard]] PcmFormatResult format_float32(
            const uint8_t *input,
            std::size_t input_bytes,
            double gain,
            uint8_t *output,
            std::size_t output_capacity) const noexcept;

    /**
     * Returns the immutable endpoint format used for every conversion.
     */
    [[nodiscard]] constexpr const UsbPcmWireFormat &wire_format() const noexcept
    {
        return format_;
    }

private:
    [[nodiscard]] int32_t quantize_to_valid_bits(int64_t sample) const noexcept;

    UsbPcmWireFormat format_;
};
