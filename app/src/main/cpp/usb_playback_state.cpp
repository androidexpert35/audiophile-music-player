#include "usb_playback_state.h"

bool UsbPlaybackStateMachine::transition_to(UsbPlaybackState next) noexcept
{
    if (state_ == next) {
        return true;
    }

    bool allowed = false;
    switch (state_) {
        case UsbPlaybackState::Created:
            allowed = next == UsbPlaybackState::Configured ||
                      next == UsbPlaybackState::Stopping ||
                      next == UsbPlaybackState::Failed;
            break;
        case UsbPlaybackState::Configured:
            allowed = next == UsbPlaybackState::Priming ||
                      next == UsbPlaybackState::Stopping ||
                      next == UsbPlaybackState::Failed;
            break;
        case UsbPlaybackState::Priming:
            allowed = next == UsbPlaybackState::StreamingPcm ||
                      next == UsbPlaybackState::StreamingNativeDsd ||
                      next == UsbPlaybackState::StreamingDop ||
                      next == UsbPlaybackState::Stopping ||
                      next == UsbPlaybackState::Failed;
            break;
        case UsbPlaybackState::StreamingNativeDsd:
            allowed = next == UsbPlaybackState::SwitchingToDop ||
                      next == UsbPlaybackState::Stopping ||
                      next == UsbPlaybackState::Failed;
            break;
        case UsbPlaybackState::SwitchingToDop:
            allowed = next == UsbPlaybackState::Priming ||
                      next == UsbPlaybackState::Failed ||
                      next == UsbPlaybackState::Stopping;
            break;
        case UsbPlaybackState::StreamingPcm:
        case UsbPlaybackState::StreamingDop:
            allowed = next == UsbPlaybackState::Stopping ||
                      next == UsbPlaybackState::Failed;
            break;
        case UsbPlaybackState::Stopping:
            allowed = next == UsbPlaybackState::Stopped ||
                      next == UsbPlaybackState::Failed;
            break;
        case UsbPlaybackState::Stopped:
            allowed = false;
            break;
        case UsbPlaybackState::Failed:
            allowed = next == UsbPlaybackState::Stopping ||
                      next == UsbPlaybackState::Stopped;
            break;
    }

    if (allowed) {
        state_ = next;
    }
    return allowed;
}
