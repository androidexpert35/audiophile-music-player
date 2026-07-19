package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.content.SharedPreferences
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbVolumeController.Companion.VOLUME_STEP
import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton volume controller for the direct libusb PCM audio path.
 *
 * Because the libusb engine bypasses Android AudioFlinger entirely, the system
 * volume rocker has no effect on output level. This controller acts as the
 * exclusive software volume authority for the direct-USB path:
 *
 * - The volume level is **persisted** in [SharedPreferences] under
 *   [SettingsPreferences.KEY_USB_VOLUME_PCT] so the user's choice survives
 *   app restarts. On construction the controller reads the stored value
 *   (falling back to [SettingsPreferences.DEFAULT_USB_VOLUME_PCT]) so the
 *   correct level is available before the first isochronous transfer starts.
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
 * @property sharedPreferences Application-scoped preferences used to persist
 *   the volume level across app restarts.
 */
@Singleton
class UsbVolumeController @Inject constructor(
    private val sharedPreferences: SharedPreferences,
) {

    /**
     * Current volume as an integer percentage in `[0, 100]`.
     *
     * Initialised from [SharedPreferences] on construction so the last
     * user-chosen level is restored before the first isochronous transfer
     * is prepared.
     */
    private val _volumePct = MutableStateFlow(
        sharedPreferences.getInt(
            SettingsPreferences.KEY_USB_VOLUME_PCT,
            SettingsPreferences.DEFAULT_USB_VOLUME_PCT,
        )
    )

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
        activeBridgeHandle = handle
        _isLibusbPathActive.value = true
        // Re-apply the persisted level as a safety net; the primary delivery
        // already happened via the initialVolume parameter of nativeAttachUsbEngine.
        pushVolumeToNative(handle, _volumePct.value)
        Log.d(TAG, "attachBridge: handle=0x${handle.toULong().toString(16)} volume=${_volumePct.value}%")
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
        activeBridgeHandle = 0L
        _isLibusbPathActive.value = true
        Log.d(TAG, "attachDirectWriter: volume=${_volumePct.value}%")
    }

    /**
     * Unregisters the active pump bridge.
     *
     * Called by [LibusbPcmAudioSink] when the pump is stopped or the sink is
     * closed. After this call, volume updates are only persisted in [volumePct]
     * and in [SharedPreferences] until the next [attachBridge].
     */
    internal fun detachBridge() {
        activeBridgeHandle = 0L
        _isLibusbPathActive.value = false
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
        _volumePct.value = clamped

        // Persist asynchronously so the level survives app restarts.
        // apply() is non-blocking; the write is committed on a background thread
        // by the platform before the process terminates.
        sharedPreferences.edit()
            .putInt(SettingsPreferences.KEY_USB_VOLUME_PCT, clamped)
            .apply()

        val handle = activeBridgeHandle
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
