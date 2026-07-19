package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects the presence of any USB DAC on the current device, considering both
 * the direct libusb path and the standard Android USB Audio HAL path.
 *
 * ### USB DAC "present" definition
 * A USB DAC is considered present when **any** of the following holds:
 * 1. The in-process libusb audio session is active (a permitted USB device has been
 *    claimed by the app's isochronous transport layer).
 * 2. [AudioManager.getDevices] reports at least one output device whose type is
 *    [AudioDeviceInfo.TYPE_USB_DEVICE], [AudioDeviceInfo.TYPE_USB_HEADSET], or
 *    [AudioDeviceInfo.TYPE_USB_ACCESSORY] — covering DACs routed via the Android
 *    standard USB Audio HAL without direct libusb access.
 *
 * ### Rate policy implication
 * When `false` is returned, all audio hardware routes through AudioFlinger's
 * primary mixer, which is fixed at [OutputRatePolicy.FIXED_NON_USB_RATE_HZ]
 * (48 kHz). Any source at a different rate will be silently resampled by
 * AudioFlinger unless the app performs the resample itself via libsoxr VHQ.
 *
 * ### Reactive observation
 * [observe] wraps [AudioManager.registerAudioDeviceCallback] in a `callbackFlow`.
 * The `awaitClose` block guarantees unregistration when the collector's scope
 * cancels — no manual lifecycle wiring required.
 *
 * @property context Application context for [AudioManager] resolution.
 * @property usbAudioSinkFactory Factory used to query the current libusb session state.
 */
@Singleton
class UsbDacPresenceDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val usbAudioSinkFactory: UsbAudioSinkFactory,
) {

    private val audioManager: AudioManager? =
        context.getSystemService(AudioManager::class.java)

    /**
     * Returns `true` when a USB DAC is currently reachable via either the
     * libusb path or the Android USB Audio HAL.
     *
     * This is a synchronous snapshot; for reactive observation use [observe].
     *
     * @return `true` when any USB audio output device is active.
     */
    fun isUsbDacPresent(): Boolean =
        isLibusbOutputActive() || isAndroidUsbHalOutputActive()

    /**
     * Returns `true` when the in-process libusb playback route is currently
     * available for a direct USB DAC session.
     */
    fun isLibusbOutputActive(): Boolean = isLibusbSessionActive()

    /**
     * Returns `true` when Android currently reports a USB audio output device on
     * the platform USB Audio HAL path.
     */
    fun isAndroidUsbHalOutputActive(): Boolean = hasAndroidUsbAudioOutput()

    /**
     * Emits `true` whenever a USB DAC is present and `false` when none is
     * connected. Emits the current state immediately on subscription.
     *
     * Uses [callbackFlow] to wrap [AudioManager.registerAudioDeviceCallback]
     * — the callback is automatically unregistered when the collector's scope
     * cancels via `awaitClose`, eliminating manual lifecycle wiring.
     *
     * [distinctUntilChanged] ensures no spurious re-emissions when multiple
     * devices are added or removed simultaneously but the overall "is any USB
     * DAC present" answer remains the same.
     *
     * @return [Flow] emitting the current and subsequent USB-DAC-presence states.
     */
    fun observe(): Flow<Boolean> = callbackFlow {
        // Emit the current state before registering so the first collector
        // never waits for a device-change event for its initial value.
        trySend(isUsbDacPresent())

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                trySend(isUsbDacPresent())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                trySend(isUsbDacPresent())
            }
        }

        // Register on the main-thread handler so callback invocations are
        // serialised with other main-thread audio routing notifications.
        audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

        // Automatically unregisters when the collecting scope is cancelled.
        awaitClose {
            audioManager?.unregisterAudioDeviceCallback(callback)
        }
    }.distinctUntilChanged()

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns `true` when the in-process libusb audio session has claimed
     * a USB DAC interface and is ready for isochronous transfers.
     */
    private fun isLibusbSessionActive(): Boolean =
        usbAudioSinkFactory.currentUsbDeviceState().isLibusbReady

    /**
     * Queries the Android [AudioManager] for any USB output device type.
     *
     * Covers DACs attached via the standard Android USB Audio HAL — those that
     * do NOT require libusb direct access (e.g. manufacturer-permitted DACs
     * exposed through the normal Android AudioRecord / AudioTrack routing).
     *
     * @return `true` when at least one USB-type output device is reported.
     */
    private fun hasAndroidUsbAudioOutput(): Boolean =
        audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { device ->
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            } == true
}

