#pragma once

#include <mutex>

#include "dsd_wire_mode.h"

class DecoderToRingBridge;

/**
 * Serializes access to the sole producer of a direct-USB session.
 *
 * The coordinator is owned by the USB driver context and lets control-plane
 * operations quiesce the decoder before resetting the SPSC ring or changing
 * endpoint format. It never owns the bridge; attach and detach define the
 * validity of the guarded pointer.
 */
class UsbProducerCoordinator {
public:
    /**
     * Registers the sole producer for the current session.
     */
    [[nodiscard]] bool attach(DecoderToRingBridge *bridge) noexcept;

    /**
     * Stops and unregisters the producer when it still matches the caller.
     */
    void detach(DecoderToRingBridge *bridge) noexcept;

    /**
     * Stops the attached producer without unregistering it.
     */
    void quiesce() noexcept;

    /**
     * Stops the producer and switches its DSD wire formatter while holding the
     * ownership lock.
     */
    [[nodiscard]] bool quiesce_for_dsd_switch(DsdWireMode mode) noexcept;

    /**
     * Restarts the currently attached producer after a serialized transition.
     */
    [[nodiscard]] bool resume() noexcept;

    /**
     * Returns whether a producer is currently attached.
     */
    [[nodiscard]] bool has_producer() const noexcept;

private:
    mutable std::mutex mutex_;
    DecoderToRingBridge *bridge_ = nullptr;
};
