#include "usb_producer_coordinator.h"

#include "decoder_to_ring_bridge.h"

bool UsbProducerCoordinator::attach(DecoderToRingBridge *bridge) noexcept
{
    if (bridge == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (bridge_ != nullptr) {
        return false;
    }
    bridge_ = bridge;
    return true;
}

void UsbProducerCoordinator::detach(DecoderToRingBridge *bridge) noexcept
{
    std::lock_guard<std::mutex> lock(mutex_);
    if (bridge_ != bridge) {
        return;
    }
    bridge_->stop();
    bridge_ = nullptr;
}

void UsbProducerCoordinator::quiesce() noexcept
{
    std::lock_guard<std::mutex> lock(mutex_);
    if (bridge_ != nullptr) {
        bridge_->stop();
    }
}

bool UsbProducerCoordinator::quiesce_for_dsd_switch(DsdWireMode mode) noexcept
{
    std::lock_guard<std::mutex> lock(mutex_);
    if (bridge_ == nullptr) {
        return false;
    }
    bridge_->stop();
    bridge_->set_dsd_wire_mode(mode);
    return true;
}

bool UsbProducerCoordinator::resume() noexcept
{
    std::lock_guard<std::mutex> lock(mutex_);
    return bridge_ != nullptr && bridge_->start();
}

bool UsbProducerCoordinator::has_producer() const noexcept
{
    std::lock_guard<std::mutex> lock(mutex_);
    return bridge_ != nullptr;
}
