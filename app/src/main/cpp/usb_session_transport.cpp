#include "usb_session_transport.h"

#include <android/log.h>
#include <string>

#include "usb_driver_context.h"

static constexpr const char *TRANSPORT_TAG = "UsbAudioBridge";

#define TRLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TRANSPORT_TAG, __VA_ARGS__)
#define TRLOGI(...) __android_log_print(ANDROID_LOG_INFO,  TRANSPORT_TAG, __VA_ARGS__)
#define TRLOGE(...) __android_log_print(ANDROID_LOG_ERROR, TRANSPORT_TAG, __VA_ARGS__)

int usb_transport_prepare(UsbDriverContext *ctx, const char *log_tag) noexcept
{
    if (ctx == nullptr) return -2;

    if (!ctx->transfer_pool) {
        TRLOGE("%s: transfer pool not allocated — call nativeAllocateTransferPool first",
               log_tag);
        return -2;
    }

    // ── Ring buffer ──────────────────────────────────────────────────────────
    std::string ring_error;
    ctx->ring_buffer = SpscRingBuffer::create(kDefaultRingBufferBytes, &ring_error);
    if (!ctx->ring_buffer) {
        TRLOGE("%s: SpscRingBuffer::create failed — %s", log_tag, ring_error.c_str());
        return -3;
    }
    TRLOGI("%s: ring buffer created (%zu KB)",
           log_tag, ctx->ring_buffer->capacity() / 1024UL);

    // ── Attach ring to the transfer pool ─────────────────────────────────────
    // From this point the ISO callback pops audio bytes from the ring on every
    // completed transfer (or outputs silence when the ring is empty).
    ctx->transfer_pool->attach_ring_buffer(ctx->ring_buffer.get());
    TRLOGD("%s: ring buffer attached to transfer pool", log_tag);

    // ── libusb event thread ──────────────────────────────────────────────────
    // Must be running BEFORE any transfer is submitted — otherwise the first
    // completion callback would never fire and the ring would stall.
    std::string evt_error;
    ctx->event_thread = LibusbEventThread::create(ctx->usb_ctx, &evt_error);
    if (!ctx->event_thread) {
        TRLOGE("%s: LibusbEventThread::create failed — %s", log_tag, evt_error.c_str());
        ctx->transfer_pool->attach_ring_buffer(nullptr);
        ctx->ring_buffer.reset();
        return -4;
    }
    TRLOGI("%s: event thread started", log_tag);

    return 0;
}

void usb_transport_abort(UsbDriverContext *ctx) noexcept
{
    if (ctx == nullptr) return;
    ctx->event_thread.reset();
    if (ctx->transfer_pool) {
        ctx->transfer_pool->attach_ring_buffer(nullptr);
    }
    ctx->ring_buffer.reset();
}
