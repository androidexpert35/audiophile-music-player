package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EngineType
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-lived USB playback lifecycle coordinator.
 *
 * Bridges USB attach/detach/permission events to runtime engine selection while
 * keeping broadcast-receiver logic out of ViewModels and UI classes.
 *
 * Responsibilities:
 * - refresh the active audiophile pipeline when a DAC appears,
 * - refresh the active audiophile pipeline when USB direct-output availability changes,
 * - re-evaluate the preferred engine whenever USB availability changes.
 */
@Singleton
class UsbAudioLifecycleManager @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val usbDeviceScanner: UsbDeviceScanner,
    private val audioEngineManager: AudioEngineManager,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    @Volatile
    private var audiophileEngineEnabled: Boolean = false

    /** Starts USB lifecycle observation. Safe to call multiple times. */
    fun start() {
        if (!started.compareAndSet(false, true)) return

        appScope.launch {
            settingsRepository.observeAudiophileEngineEnabled()
                .distinctUntilChanged()
                .collect { enabled ->
                    audiophileEngineEnabled = enabled
                    requestUsbPermissionIfNeeded()
                    reconcilePreferredEngine()
                }
        }

        // USB attach / detach / permission-denied events. A Channel.BUFFERED buffer is
        // inserted between the SharedFlow source and this collector so that events are
        // not dropped when this coroutine is briefly suspended inside
        // refreshAudiophileOutputIfNeeded() (e.g. while holding swapMutex during an
        // engine switch). Without the buffer, SharedFlow's tryEmit can return false for
        // a slow consumer, silently discarding the event.
        //
        // PermissionGranted is intentionally not reloaded here. The deviceState
        // permission observer below is the authoritative reload trigger for that
        // transition. It watches the deviceState StateFlow — which is always updated by
        // UsbDeviceScanner's own events subscriber — and is therefore immune to the
        // drop-on-slow-consumer problem that affects direct SharedFlow subscriptions.
        appScope.launch {
            usbDeviceScanner.events
                .buffer(Channel.BUFFERED)
                .collect { event ->
                    when (event) {
                        is UsbAudioEvent.DeviceAttached -> {
                            Log.d(TAG, "USB DAC attached: ${event.device.deviceName}")
                            // The manifest's USB-attach intent filter makes Android show
                            // its app chooser when several DAC-capable apps are installed.
                            // Do not call UsbManager.requestPermission() here: its prompt
                            // can otherwise appear on top of that chooser. When the user
                            // selects Audiophile, Android grants USB host access as part of
                            // the attach flow; Settings retains an explicit manual grant
                            // action for every other permission path.
                            refreshAudiophileOutputIfNeeded()
                        }

                        is UsbAudioEvent.DeviceDetached -> {
                            Log.w(TAG, "USB DAC detached: deviceId=${event.deviceId}")
                            refreshAudiophileOutputIfNeeded()
                        }

                        is UsbAudioEvent.PermissionGranted -> {
                            // Reload is handled by the deviceState permission observer.
                            Log.d(TAG, "USB permission granted via event: ${event.device.deviceName}")
                        }

                        is UsbAudioEvent.PermissionDenied -> {
                            Log.w(TAG, "USB permission denied for ${event.device.deviceName}")
                            refreshAudiophileOutputIfNeeded()
                        }
                    }
                }
        }

        // Watches for the isPermissionGranted flag changing from false → true on the
        // deviceState StateFlow. UsbDeviceScanner's own subscriber keeps this StateFlow
        // current on every event emission, so this observer never misses a permission
        // grant even when the events collector above was suspended at emission time.
        //
        // drop(1) skips the initial snapshot so we only react to runtime transitions;
        // the current state is already handled by the DeviceAttached path above.
        appScope.launch {
            usbDeviceScanner.deviceState
                .map { it.isPermissionGranted }
                .distinctUntilChanged()
                .drop(1)
                .filter { granted -> granted }
                .collect {
                    Log.d(TAG, "USB permission state → granted (deviceState observer)")
                    refreshAudiophileOutputIfNeeded()
                }
        }
    }

    private suspend fun refreshAudiophileOutputIfNeeded() {
        if (!audiophileEngineEnabled) {
            reconcilePreferredEngine()
            return
        }

        if (audioEngineManager.activeEngineType.value == EngineType.AUDIOPHILE) {
            audioEngineManager.reloadCurrentTrack()
        } else {
            reconcilePreferredEngine()
        }
    }

    private fun requestUsbPermissionIfNeeded(deviceId: Int? = null) {
        if (!audiophileEngineEnabled) return

        val deviceState = usbDeviceScanner.deviceState.value
        val targetDeviceId = deviceId ?: deviceState.connectedDevice?.deviceId
        if (targetDeviceId == null) return
        if (deviceId == null && deviceState.isPermissionGranted) return

        usbDeviceScanner.requestPermission(targetDeviceId)
    }

    private suspend fun reconcilePreferredEngine() {
        runCatching {
            audioEngineManager.reconcilePreferredEngine(preferAudiophile = audiophileEngineEnabled)
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to reconcile preferred USB playback engine", throwable)
        }
    }

    private companion object {
        const val TAG = "UsbAudioLifecycle"
    }
}

