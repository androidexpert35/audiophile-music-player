#pragma once

#include <cstdint>

/**
 * Represents the serialized lifecycle of one native direct-USB playback
 * session.
 */
enum class UsbPlaybackState : uint8_t {
    Created,
    Configured,
    Priming,
    StreamingPcm,
    StreamingNativeDsd,
    SwitchingToDop,
    StreamingDop,
    Stopping,
    Stopped,
    Failed,
};

/**
 * Validates lifecycle transitions independently from JNI and libusb so the
 * ownership protocol can be tested on the host.
 */
class UsbPlaybackStateMachine {
public:
    /**
     * Returns the current session state.
     */
    [[nodiscard]] constexpr UsbPlaybackState state() const noexcept
    {
        return state_;
    }

    /**
     * Applies a legal transition and returns false without changing state when
     * the requested edge is not part of the lifecycle contract.
     */
    [[nodiscard]] bool transition_to(UsbPlaybackState next) noexcept;

private:
    UsbPlaybackState state_ = UsbPlaybackState::Created;
};
