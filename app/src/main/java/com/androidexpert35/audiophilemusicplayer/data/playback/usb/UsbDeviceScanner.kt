package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.dsd.DsdCapabilityDetector
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers USB audio DACs, dispatches permission requests, and exposes device
 * lifecycle updates as a shared [Flow].
 *
 * The underlying attach/detach and permission broadcasts are wrapped in a
 * `callbackFlow`, satisfying the app's mandatory callback-to-Flow policy while
 * ensuring receiver cleanup happens automatically through `awaitClose`.
 *
 * @property context Application context used to register the broadcast receiver.
 * @property usbManager System USB host manager.
 * @property descriptorParser Best-effort USB Audio Class descriptor parser.
 * @property appScope Long-lived scope keeping the shared event stream active.
 */
@Singleton
class UsbDeviceScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val usbManager: UsbManager,
    private val descriptorParser: UsbAudioDescriptorParser,
    private val dsdCapabilityDetector: DsdCapabilityDetector,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val _deviceState = MutableStateFlow(scanCurrentDeviceState())

    /** Current app-selected USB DAC state snapshot. */
    val deviceState: StateFlow<UsbAudioDeviceState> = _deviceState.asStateFlow()

    /** Shared USB attach/detach/permission event stream. */
    val events: Flow<UsbAudioEvent> = observeUsbEvents()
        .shareIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            replay = 0,
        )

    init {
        events
            .onEach {
                _deviceState.value = scanCurrentDeviceState()
            }
            .launchIn(appScope)
    }

    /**
     * Scans the currently connected USB devices and returns audio-class DACs.
     */
    fun scanAudioDevices(): List<UsbAudioDeviceDescriptor> = usbManager.deviceList.values
        .mapNotNull { device ->
            device.takeIf(::isUsbAudioDevice)?.toDescriptor()
        }
        .sortedBy { it.deviceName.lowercase(Locale.getDefault()) }

    /**
     * Requests USB host permission for the selected DAC.
     *
     * @param deviceId Optional device ID to target. When omitted, the scanner
     *   picks the currently selected DAC candidate.
     * @return `true` when a permission dialog or immediate grant path was
     *   triggered, otherwise `false`.
     */
    fun requestPermission(deviceId: Int? = null): Boolean {
        val device = resolveTargetDevice(deviceId) ?: run {
            Log.w(TAG, "requestPermission skipped: no USB audio DAC available")
            return false
        }
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB permission already granted for deviceId=${device.deviceId}")
            _deviceState.value = scanCurrentDeviceState()
            return true
        }

        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        // MUTABLE is required here: UsbManager.requestPermission() fills the returned
        // intent in with EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED when the user responds
        // to the system dialog. An IMMUTABLE PendingIntent silently drops that fill-in, so
        // the ACTION_USB_PERMISSION broadcast arrives with no extras and the permission
        // grant is never observed by this scanner (isPermissionGranted stays stuck false).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        Log.d(TAG, "Requesting USB permission for ${device.logLabel()}")
        return runCatching {
            usbManager.requestPermission(device, pendingIntent)
            true
        }.onFailure { throwable ->
            Log.e(TAG, "USB permission request failed for ${device.logLabel()}", throwable)
        }.getOrDefault(false)
    }

    /** Returns the currently selected, permitted DAC candidate if one exists. */
    fun getPermittedDevice(): UsbDevice? = resolvePreferredAudioDevice(permittedOnly = true)

    /**
     * Re-runs the current USB DAC scan and publishes the refreshed state.
     *
     * This gives the Settings screen an explicit retry path when Android did not
     * deliver an attach broadcast early enough for the first UI snapshot.
     *
     * @return Latest selected USB DAC state after the scan completes.
     */
    fun refreshDeviceState(): UsbAudioDeviceState = scanCurrentDeviceState().also { refreshedState ->
        _deviceState.value = refreshedState
    }

    private fun observeUsbEvents(): Flow<UsbAudioEvent> = callbackFlow {
        fun ProducerScope<UsbAudioEvent>.emitAttached(device: UsbDevice) {
            val descriptor = device.toDescriptor()
            Log.d(TAG, "USB DAC attached: ${device.logLabel()}")
            trySend(UsbAudioEvent.DeviceAttached(descriptor))
        }

        fun ProducerScope<UsbAudioEvent>.emitPermissionState(
            device: UsbDevice,
            granted: Boolean = usbManager.hasPermission(device),
        ) {
            val descriptor = device.toDescriptor()
            if (granted) {
                val parsedProfiles = parseSupportedProfiles(device)
                Log.d(
                    TAG,
                    "USB permission granted for ${device.logLabel()} profiles=${parsedProfiles.profiles} estimated=${parsedProfiles.areProfilesEstimated}"
                )
                trySend(
                    UsbAudioEvent.PermissionGranted(
                        device = descriptor,
                        supportedProfiles = parsedProfiles.profiles,
                    )
                )
            } else {
                Log.w(TAG, "USB permission denied for ${device.logLabel()}")
                trySend(UsbAudioEvent.PermissionDenied(descriptor))
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.parcelableAudioDeviceOrNull() ?: return
                        emitAttached(device)
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = intent.parcelableAudioDeviceOrNull() ?: return
                        Log.d(TAG, "USB DAC detached: ${device.logLabel()}")
                        trySend(UsbAudioEvent.DeviceDetached(device.deviceId))
                    }

                    ACTION_USB_PERMISSION -> {
                        val extraDevice = intent.parcelableAudioDeviceOrNull()
                        if (extraDevice != null) {
                            emitPermissionState(
                                device = extraDevice,
                                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                            )
                        } else {
                            // Defense in depth: some OEM broadcast dispatchers still fail to
                            // fill in EXTRA_DEVICE even with a mutable PendingIntent. Fall
                            // back to the currently-selected audio device and query
                            // UsbManager directly rather than trusting a missing boolean
                            // extra (which would default to false and read as a denial).
                            val fallbackDevice = resolvePreferredAudioDevice(permittedOnly = false)
                            if (fallbackDevice != null) {
                                Log.w(TAG, "ACTION_USB_PERMISSION missing EXTRA_DEVICE — falling back to ${fallbackDevice.logLabel()}")
                                emitPermissionState(device = fallbackDevice)
                            }
                        }
                    }
                }
            }
        }

        scanAudioDevices().forEach { descriptor ->
            val currentDevice = usbManager.deviceList.values.firstOrNull { it.deviceId == descriptor.deviceId }
                ?: return@forEach
            emitAttached(currentDevice)
            if (usbManager.hasPermission(currentDevice)) {
                emitPermissionState(currentDevice)
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { throwable -> Log.w(TAG, "USB receiver cleanup failed", throwable) }
        }
    }

    private fun scanCurrentDeviceState(): UsbAudioDeviceState {
        val preferredDevice = resolvePreferredAudioDevice(permittedOnly = false)
        if (preferredDevice == null) {
            Log.d(TAG, "scanCurrentDeviceState: no USB audio device connected")
            return UsbAudioDeviceState()
        }

        val hasPermission = usbManager.hasPermission(preferredDevice)

        if (!hasPermission) {
            // The device is physically present but the app-level USB host permission
            // has not been granted yet.  Log a clear diagnostic so log readers can
            // immediately distinguish this from a hardware / OEM block.
            Log.w(
                TAG,
                "USB DAC detected (${preferredDevice.logLabel()}) but app-level USB host " +
                    "permission not granted — direct USB path and DSD capability unavailable " +
                    "until the user approves the permission dialog"
            )
        }

        val parsedProfiles = if (hasPermission) {
            parseSupportedProfiles(preferredDevice)
        } else {
            ParsedProfiles()
        }
        val isDirectUsbTransportSupported = if (hasPermission) {
            val supported = UsbAudioSink.supportsQueuedStreaming(
                device = preferredDevice,
                usbManager = usbManager,
            )
            if (!supported) {
                // Permission is granted but we still cannot claim the USB interface.
                // Likely cause: the OEM kernel UAC2 class driver (OPPO/ColorOS,
                // Xiaomi MIUI, Samsung One UI, etc.) owns the USB audio device
                // exclusively and rejects the app's claimInterface(force=true) call.
                // This is an OEM limitation — native DSD / direct PCM via the custom
                // USB host path are not possible on this device/ROM.
                Log.w(
                    TAG,
                    "USB DAC (${preferredDevice.logLabel()}) permission granted but direct " +
                        "transport probing failed — OEM kernel UAC driver likely has exclusive " +
                        "access to the USB audio interface. Native DSD and direct USB playback " +
                        "are unavailable; falling back to the platform AudioTrack path."
                )
            }
            supported
        } else {
            false
        }
        val state = UsbAudioDeviceState(
            connectedDevice = preferredDevice.toDescriptor(),
            isPermissionGranted = hasPermission,
            supportedProfiles = parsedProfiles.profiles,
            areSupportedProfilesEstimated = parsedProfiles.areProfilesEstimated,
            isDirectUsbTransportSupported = isDirectUsbTransportSupported,
            supportedDsdRates = parsedProfiles.dsdRates,
        )
        val enrichedState = state.copy(
            dsdOutputMode = dsdCapabilityDetector.probeCurrentOutput(state),
        )
        Log.d(
            TAG,
            "USB device state refreshed: " +
                "device=${preferredDevice.logLabel()} " +
                "hasPermission=$hasPermission " +
                "transportSupported=$isDirectUsbTransportSupported " +
                "isDirectUsbReady=${enrichedState.isDirectUsbReady} " +
                "dsdOutputMode=${enrichedState.dsdOutputMode} " +
                "profiles=${parsedProfiles.profiles.size} estimated=${parsedProfiles.areProfilesEstimated} " +
                "dsdRates=${parsedProfiles.dsdRates}"
        )
        return enrichedState
    }

    private fun parseSupportedProfiles(device: UsbDevice): ParsedProfiles {
        val connection = usbManager.openDevice(device)
        val rawDescriptors = runCatching { connection?.rawDescriptors ?: ByteArray(0) }
            .onFailure { throwable ->
                Log.w(TAG, "Failed reading raw descriptors for ${device.logLabel()}", throwable)
            }
            .getOrDefault(ByteArray(0))
        closeConnectionQuietly(connection)

        val parsed = descriptorParser.parseTypeIFormats(rawDescriptors)
        val parsedDsdRates = descriptorParser.parseDsdRates(rawDescriptors)
        if (parsed.isNotEmpty() || parsedDsdRates.isNotEmpty()) {
            return ParsedProfiles(
                profiles = parsed,
                areProfilesEstimated = false,
                dsdRates = parsedDsdRates,
            )
        }

        val fallback = descriptorParser.fallbackNegotiationProfiles()
        Log.w(
            TAG,
            "Descriptor parsing fell back to negotiation ladder for ${device.logLabel()}: $fallback"
        )
        return ParsedProfiles(profiles = fallback, areProfilesEstimated = true)
    }

    private fun resolveTargetDevice(deviceId: Int?): UsbDevice? {
        val requested = deviceId?.let { id ->
            usbManager.deviceList.values.firstOrNull { it.deviceId == id && isUsbAudioDevice(it) }
        }
        return requested ?: resolvePreferredAudioDevice(permittedOnly = false)
    }

    private fun resolvePreferredAudioDevice(permittedOnly: Boolean): UsbDevice? {
        val devices = usbManager.deviceList.values.filter(::isUsbAudioDevice)
        return devices
            .sortedWith(
                compareByDescending<UsbDevice> { usbManager.hasPermission(it) }
                    .thenBy { it.productName.orEmpty().lowercase(Locale.getDefault()) }
            )
            .firstOrNull { !permittedOnly || usbManager.hasPermission(it) }
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean =
        (0 until device.interfaceCount)
            .map(device::getInterface)
            .any(::isUsbAudioStreamingInterface)

    private fun isUsbAudioStreamingInterface(usbInterface: UsbInterface): Boolean =
        usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
            usbInterface.interfaceSubclass == USB_AUDIO_STREAMING_SUBCLASS

    private fun UsbDevice.toDescriptor(): UsbAudioDeviceDescriptor = UsbAudioDeviceDescriptor(
        deviceId = deviceId,
        deviceName = productName
            ?: manufacturerName
            ?: deviceName,
        vendorId = vendorId,
        productId = productId,
    )

    private fun UsbDevice.logLabel(): String =
        "id=$deviceId vendor=$vendorId product=$productId name=${productName ?: manufacturerName ?: deviceName}"

    private fun Intent.parcelableDevice(): UsbDevice? =
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

    private fun Intent.parcelableAudioDeviceOrNull(): UsbDevice? =
        parcelableDevice()?.takeIf(::isUsbAudioDevice)

    private fun closeConnectionQuietly(connection: android.hardware.usb.UsbDeviceConnection?) {
        runCatching { connection?.close() }
    }

    private data class ParsedProfiles(
        val profiles: List<UsbAudioOutputProfile> = emptyList(),
        val areProfilesEstimated: Boolean = false,
        val dsdRates: List<DsdRate> = emptyList(),
    )

    companion object {
        const val ACTION_USB_PERMISSION: String =
            "com.androidexpert35.audiophilemusicplayer.action.USB_PERMISSION"

        private const val TAG = "UsbDeviceScanner"
        private const val USB_AUDIO_STREAMING_SUBCLASS = 0x02
    }
}

