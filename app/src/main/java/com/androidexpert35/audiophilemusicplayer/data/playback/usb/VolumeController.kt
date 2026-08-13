package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.content.SharedPreferences
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbVolumeController.Companion.VOLUME_STEP
import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton volume controller for the direct libusb PCM audio path.
 *
 * Because the libusb engine bypasses Android AudioFlinger entirely, the system
 * volume rocker has no effect on output level. This controller acts as the
 * exclusive software volume authority for the direct-USB path:
 *
 * - The volume level is **persisted per DAC** in [SharedPreferences] under a
 *   stable USB-identity key. [activateDevice] restores that DAC's last level,
 *   falling back to [SettingsPreferences.DEFAULT_USB_VOLUME_PCT] the first time
 *   it is encountered, before the first isochronous transfer starts.
 * - The active [LibusbPcmAudioSink] calls [attachBridge] when its pump thread
 *   starts and [detachBridge] when the pump stops or the sink is closed.
 * - `MainActivity` calls [stepUp] / [stepDown] in response to physical volume
 *   key events that it consumes before they reach the system volume dialog.
 * - The current level is exposed as [volumePct] (`[0..100]`) so any Composable
 *   can observe it without polling.
 *
 * ### Volume curve (quadratic taper — applied in C++)
 *
 * This class stores and transmits a **raw linear position** (`pct / 100f`).
 * The C++ `DecoderToRingBridge::set_volume()` applies a **quadratic (x²) power
 * curve** immediately and stores the pre-computed gain scalar — the identical
 * curve `EngineSwapBridge::nativeWriteToRingBuffer` applies for the enhanced
 * (DSP) write path, so passthrough and enhanced sinks sound equally loud at
 * the same slider position:
 *
 * ```
 *   gain = position ^ 2
 * ```
 *
 * Representative values:
 *
 * | Slider % | Gain (^2) | Equiv. dB |
 * |---------|-----------|-----------|
 * | 100 %   | 1.000 000 |   0.0 dB  |
 * |  90 %   | 0.810 000 |  −1.8 dB  |
 * |  75 %   | 0.562 500 |  −5.0 dB  |
 * |  50 %   | 0.250 000 | −12.0 dB  |
 * |  25 %   | 0.062 500 | −24.1 dB  |
 * |   0 %   | 0.000 000 |  −∞ (mute)|
 *
 * ### Startup mute guard and pre-flight ordering
 *
 * The C++ `volume_scalar_` atomic is initialised to **0.0f** (silence).
 * [LibusbPcmAudioSink.play] passes the current [volumePct] as `initialVolume`
 * directly to `nativeAttachUsbEngine`, which calls `bridge->set_volume()`
 * **before** `bridge->start()`.  This guarantees the quadratic taper is applied
 * to the correct persisted level before the pump thread produces its first
 * audio chunk — eliminating any startup blast or silence gap.
 *
 * [attachBridge] additionally re-applies the level after the bridge handle is
 * returned, serving as a redundant safety net and keeping the bridge up-to-date
 * if [setVolumePct] is called between `nativeAttachUsbEngine` and `attachBridge`.
 *
 * ### Thread safety
 *
 * All public methods may be called from any thread. The bridge handle is stored
 * in a `@Volatile` field; JNI calls are inherently thread-safe at the native
 * boundary. SharedPreferences writes use `apply()` (asynchronous, non-blocking).
 *
 * DACs with physical volume buttons are deliberately not inferred or bypassed:
 * USB descriptors do not reliably describe whether those buttons control an
 * internal analogue stage. PCM software attenuation therefore remains available
 * for every direct-USB device. Native DSD transports continue to ignore it.
 *
 * @property sharedPreferences Application-scoped preferences used to persist
 *   each DAC's volume level across app restarts.
 */
@Singleton
class UsbVolumeController @Inject constructor(
    private val sharedPreferences: SharedPreferences,
) {

    /**
     * Current volume as an integer percentage in `[0, 100]`.
     *
     * Initialised to the safe fallback and replaced with the selected DAC's
     * persisted value by [activateDevice] before a USB sink is prepared.
     */
    private val _volumePct = MutableStateFlow(SettingsPreferences.DEFAULT_USB_VOLUME_PCT)

    /**
     * Observable volume level in the range `[0, 100]`.
     *
     * - `0`   = muted
     * - `100` = full scale / no attenuation
     */
    val volumePct: StateFlow<Int> = _volumePct.asStateFlow()

    /**
     * `true` while a native libusb pump bridge is attached and the direct-USB
     * audio path is actively routing audio.
     *
     * Flips to `true` in [attachBridge] and back to `false` in [detachBridge].
     * Observers (e.g. `MainActivity`) use this to decide whether physical volume
     * keys should route to [setVolumePct] (libusb gain scalar) or to the system
     * [android.media.AudioManager] stream volume.
     */
    private val _isLibusbPathActive = MutableStateFlow(false)

    /** @see _isLibusbPathActive */
    val isLibusbPathActive: StateFlow<Boolean> = _isLibusbPathActive.asStateFlow()

    /**
     * Opaque bridge handle from [EngineSwapBridge.nativeAttachUsbEngine].
     * `0L` when no pump is active.
     */
    @Volatile
    private var activeBridgeHandle: Long = 0L

    private val stateLock = Any()

    @Volatile
    private var activeDevicePreferenceKey: String? = null

    // ── Device selection ─────────────────────────────────────────────────────

    /**
     * Selects the DAC whose independent PCM volume should be restored and saved.
     *
     * The persistent identity uses vendor ID, product ID, and the USB serial when
     * available. Devices without a serial fall back to their product label; two
     * indistinguishable units of the same model consequently share a level.
     * Selection is idempotent and may safely be repeated for every sink build.
     *
     * @param device USB identity snapshot associated with the sink being opened.
     */
    internal fun activateDevice(device: UsbAudioDeviceDescriptor) {
        val preferenceKey = preferenceKey(device)
        val restoredVolume = synchronized(stateLock) {
            if (activeDevicePreferenceKey == preferenceKey) return
            activeDevicePreferenceKey = preferenceKey
            sharedPreferences.getInt(
                preferenceKey,
                SettingsPreferences.DEFAULT_USB_VOLUME_PCT,
            ).coerceIn(MIN_VOLUME_PCT, MAX_VOLUME_PCT).also { restored ->
                _volumePct.value = restored
            }
        }
        Log.d(
            TAG,
            "activateDevice: vendor=${device.vendorId} product=${device.productId} " +
                "volume=$restoredVolume%",
        )
    }

    // ── Bridge lifecycle ──────────────────────────────────────────────────────────

    /**
     * Registers the active pump bridge so subsequent volume changes are forwarded
     * to the native PCM scalar immediately.
     *
     * Re-applies the [current volume][volumePct] to [handle] straight away as a
     * safety net — [LibusbPcmAudioSink.play] already delivers the persisted level
     * to `nativeAttachUsbEngine` as `initialVolume` (setting it before the pump
     * thread starts), so this call is typically a no-op redundant write. It guards
     * against the narrow window between `nativeAttachUsbEngine` returning and
     * [attachBridge] being called where a concurrent [setVolumePct] could otherwise
     * go undelivered.
     *
     * Called by [LibusbPcmAudioSink.play] after [EngineSwapBridge.nativeAttachUsbEngine]
     * returns a valid handle.
     *
     * @param handle Validated bridge handle (must pass
     *   [EngineSwapBridge.isValidBridgeHandle]).
     */
    internal fun attachBridge(handle: Long) {
        val currentVolume = synchronized(stateLock) {
            activeBridgeHandle = handle
            _isLibusbPathActive.value = true
            _volumePct.value
        }
        // Re-apply the persisted level as a safety net; the primary delivery
        // already happened via the initialVolume parameter of nativeAttachUsbEngine.
        pushVolumeToNative(handle, currentVolume)
        Log.d(TAG, "attachBridge: handle=0x${handle.toULong().toString(16)} volume=$currentVolume%")
    }

    /**
     * Marks the Kotlin-driven libusb writer as the active direct-USB volume owner.
     *
     * This path has no native decoder-pump bridge handle because DSP output is
     * pushed into the native ring directly. Volume changes remain persisted in
     * [volumePct] and are sampled by
     * [LibusbPcmEnhancedSink] on every write.
     */
    internal fun attachDirectWriter() {
        val currentVolume = synchronized(stateLock) {
            activeBridgeHandle = 0L
            _isLibusbPathActive.value = true
            _volumePct.value
        }
        Log.d(TAG, "attachDirectWriter: volume=$currentVolume%")
    }

    /**
     * Unregisters the active pump bridge.
     *
     * Called by [LibusbPcmAudioSink] when the pump is stopped or the sink is
     * closed. After this call, volume updates are only persisted in [volumePct]
     * and in [SharedPreferences] until the next [attachBridge].
     */
    internal fun detachBridge() {
        synchronized(stateLock) {
            activeBridgeHandle = 0L
            _isLibusbPathActive.value = false
        }
        Log.d(TAG, "detachBridge")
    }

    // ── Volume control ────────────────────────────────────────────────────────────

    /**
     * Increments the volume by [step] percentage points, clamped at `100`.
     *
     * @param step Positive step size in percentage points. Defaults to [VOLUME_STEP].
     */
    fun stepUp(step: Int = VOLUME_STEP) {
        setVolumePct((_volumePct.value + step).coerceAtMost(MAX_VOLUME_PCT))
    }

    /**
     * Decrements the volume by [step] percentage points, clamped at `0`.
     *
     * @param step Positive step size in percentage points. Defaults to [VOLUME_STEP].
     */
    fun stepDown(step: Int = VOLUME_STEP) {
        setVolumePct((_volumePct.value - step).coerceAtLeast(MIN_VOLUME_PCT))
    }

    /**
     * Sets the volume to an explicit percentage, persists it, and applies it
     * to the active pump.
     *
     * The new level is written to [SharedPreferences] via `apply()` (asynchronous)
     * so the next cold start restores the exact position the user chose last.
     *
     * @param pct Target volume in `[0, 100]`. Values outside this range are clamped.
     */
    fun setVolumePct(pct: Int) {
        val clamped = pct.coerceIn(MIN_VOLUME_PCT, MAX_VOLUME_PCT)
        val handle = synchronized(stateLock) {
            _volumePct.value = clamped

            // No global fallback is written: a level only belongs to the DAC that
            // was explicitly selected before its sink was opened.
            activeDevicePreferenceKey?.let { preferenceKey ->
                sharedPreferences.edit()
                    .putInt(preferenceKey, clamped)
                    .apply()
            }
            activeBridgeHandle
        }
        if (handle != 0L) {
            pushVolumeToNative(handle, clamped)
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────────

    /**
     * Converts [pct] to a normalised linear position in `[0.0, 1.0]` and
     * forwards it to the C++ bridge via [EngineSwapBridge.nativeSetVolume].
     *
     * The taper is a **pure C++ concern**: `DecoderToRingBridge::set_volume()`
     * applies `gain = position²` (quadratic curve) and stores the pre-computed
     * gain scalar so `pump_loop()` reads the final multiplier directly from the
     * atomic without a per-chunk function call.  This Kotlin layer always sends
     * the raw linear position; the curve shape is invisible to callers here.
     *
     * @param handle Active bridge handle — caller is responsible for checking `!= 0L`.
     * @param pct    Volume percentage `[0, 100]`.
     */
    private fun pushVolumeToNative(handle: Long, pct: Int) {
        val linearPosition = pct / MAX_VOLUME_PCT.toFloat()
        EngineSwapBridge.nativeSetVolume(handle, linearPosition)
        Log.v(TAG, "pushVolumeToNative: pct=$pct linearPosition=$linearPosition " +
                "handle=0x${handle.toULong().toString(16)}")
    }

    private fun preferenceKey(device: UsbAudioDeviceDescriptor): String {
        val stableIdentity = buildString {
            append(device.vendorId)
            append(':')
            append(device.productId)
            append(':')
            append(device.serialNumber?.takeIf(String::isNotBlank) ?: device.deviceName)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stableIdentity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return SettingsPreferences.KEY_USB_VOLUME_PCT_PREFIX + digest
    }

    private companion object {
        const val TAG = "UsbVolumeController"
        const val MIN_VOLUME_PCT = 0
        const val MAX_VOLUME_PCT = 100

        /**
         * Granularity of a single volume key press.
         *
         * Set to `1` so the full `[0, 100]` range is divided into 100 discrete steps,
         * giving fine-grained control at low listening levels where the quadratic taper
         * compresses the effective loudness range significantly.
         * At 50 % the gain is only −12.0 dB, so fine steps near the bottom are
         * critical for comfortable late-night listening.
         *
         * `MainActivity` may pass a larger value for accelerated key-repeat scrolling.
         */
        const val VOLUME_STEP = 1
    }
}
