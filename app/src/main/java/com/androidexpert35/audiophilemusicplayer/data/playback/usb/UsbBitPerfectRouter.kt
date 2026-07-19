package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioAttributesFactory
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioFormatConverter.mapBitDepthToEncoding
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioFormatConverter.mapChannelCountToOutMask
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configures native bit-perfect USB DAC routing on Android 14 (API 34) and
 * above by negotiating an exact-match [AudioMixerAttributes] with the system
 * [AudioManager].
 *
 * Bit-perfect routing instructs Android to bypass the AudioFlinger resampler
 * and software mixer for the media stream so the decoded PCM frames reach the
 * USB DAC at the source's exact sample rate, bit depth, and channel layout.
 *
 * On Android 13 (API 33) the platform APIs used here do not exist; every
 * public function degrades gracefully to a no-op so callers never need to
 * branch on [Build.VERSION.SDK_INT].
 *
 * @property context Application context used to obtain [AudioManager].
 * @property appScope Long-lived scope that hosts the listener event stream.
 */
@Singleton
class UsbBitPerfectRouter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val mediaAttributes: AudioAttributes = AudioAttributesFactory.createMediaAttributes()

    private val _routingEvents = MutableSharedFlow<UsbRoutingEvent>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    /**
     * Stream of routing lifecycle events emitted as the OS confirms, rejects,
     * or replaces a bit-perfect preference applied through this router.
     *
     * Combines results from explicit [routeAudio] / [clearRouting] calls with
     * push-based notifications received via
     * [AudioManager.OnPreferredMixerAttributesChangedListener] so playback
     * engines see a single ordered timeline of routing state.
     */
    val routingEvents: Flow<UsbRoutingEvent> = _routingEvents.asSharedFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startObservingPreferredMixerChanges()
        }
    }

    /**
     * Begins observing system-level preferred mixer attribute changes and
     * forwards them into [routingEvents].
     *
     * Wraps the platform listener in a [callbackFlow] so registration and
     * unregistration happen automatically with [appScope], satisfying the
     * project's mandatory callback-to-Flow policy.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startObservingPreferredMixerChanges() {
        observePreferredMixerChanges()
            .shareIn(scope = appScope, started = SharingStarted.Eagerly, replay = 0)
            .onEach { event -> _routingEvents.tryEmit(event) }
            .launchIn(appScope)
    }

    /**
     * Finds the currently connected USB DAC, preferring a fully-featured USB
     * device over a USB headset accessory when both are present.
     *
     * @return [AudioDeviceInfo] for a USB output device, or `null` when no USB
     *   audio sink is connected or the platform is below API 23.
     */
    fun findConnectedUsbDac(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Log every output so we can see exactly what the OS enumerates.
        if (outputs.isEmpty()) {
            Log.w(TAG, "findConnectedUsbDac: no output devices reported by AudioManager")
        } else {
            Log.d(TAG, "findConnectedUsbDac: ${outputs.size} output device(s) —")
            outputs.forEachIndexed { i, dev ->
                Log.d(
                    TAG,
                    "  [$i] type=${deviceTypeName(dev.type)}(${dev.type}) " +
                        "id=${dev.id} name='${dev.productName}' " +
                        "isSink=${dev.isSink}"
                )
            }
        }

        return outputs
            .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            .sortedByDescending { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            .firstOrNull()
            .also { chosen ->
                if (chosen == null) {
                    Log.w(TAG, "findConnectedUsbDac: no USB_DEVICE / USB_HEADSET in output list")
                } else {
                    Log.d(
                        TAG,
                        "findConnectedUsbDac: selected '${chosen.productName}' " +
                            "type=${deviceTypeName(chosen.type)} id=${chosen.id}"
                    )
                }
            }
    }

    /**
     * Negotiates and applies an exact-match bit-perfect mixer profile for the
     * connected USB DAC.
     *
     * Maps the supplied PCM parameters to the matching [AudioFormat] encoding
     * and channel mask, queries [AudioManager.getSupportedMixerAttributes],
     * then applies the first profile whose format and bit-perfect behaviour
     * match the source exactly.
     *
     * @param sampleRate Source sample rate in Hz (e.g. 44_100, 96_000, 192_000).
     * @param bitDepth   Source PCM bit depth: 16, 24, or 32 (32 is treated as
     *   IEEE float per Android's PCM_FLOAT contract).
     * @param channelCount Number of interleaved channels (1 = mono, 2 = stereo).
     * @return [UsbRoutingResult] describing whether the DAC accepted the
     *   request, was unsupported, or rejected the format.
     */
    fun routeAudio(sampleRate: Int, bitDepth: Int, channelCount: Int): UsbRoutingResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(TAG, "routeAudio skipped — API ${Build.VERSION.SDK_INT} < 34")
            return UsbRoutingResult.UnsupportedPlatform
        }
        return routeAudioApi34(sampleRate, bitDepth, channelCount)
    }

    /**
     * Clears any bit-perfect preference previously applied to the connected
     * USB DAC, returning mixer control to the OS default policy.
     *
     * Safe to invoke when no preference is active or no USB DAC is connected.
     */
    fun clearRouting() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val manager = audioManager ?: return
        val device = findConnectedUsbDac() ?: return
        runCatching { manager.clearPreferredMixerAttributes(mediaAttributes, device) }
            .onSuccess {
                Log.d(TAG, "Cleared preferred mixer attributes for ${device.productName}")
                _routingEvents.tryEmit(UsbRoutingEvent.Cleared(device.id))
            }
            .onFailure { throwable ->
                Log.w(TAG, "clearPreferredMixerAttributes failed for ${device.productName}", throwable)
            }
    }

    /** Alias for [clearRouting] aligned with the engine's `release()` lifecycle. */
    fun release() = clearRouting()

    @SuppressLint("NewApi")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun routeAudioApi34(sampleRate: Int, bitDepth: Int, channelCount: Int): UsbRoutingResult {
        // ── Entry diagnostics ────────────────────────────────────────────────
        Log.i(
            TAG,
            "routeAudio enter — API=${Build.VERSION.SDK_INT} " +
                "manufacturer='${Build.MANUFACTURER}' brand='${Build.BRAND}' " +
                "model='${Build.MODEL}' " +
                "requested: sr=$sampleRate bd=$bitDepth ch=$channelCount"
        )

        val manager = audioManager ?: run {
            Log.e(TAG, "routeAudio: AudioManager is null — cannot proceed")
            return UsbRoutingResult.NoAudioManager
        }

        val device = findConnectedUsbDac() ?: run {
            Log.w(TAG, "routeAudio: no USB DAC found — aborting")
            return UsbRoutingResult.NoUsbDevice
        }

        // ✅ CHANGED: delegates to shared AudioFormatConverter instead of local duplicate
        val encoding = mapBitDepthToEncoding(bitDepth) ?: run {
            Log.w(TAG, "routeAudio: unsupported bitDepth=$bitDepth — no encoding mapping")
            return UsbRoutingResult.UnsupportedFormat("bitDepth=$bitDepth")
        }
        // ✅ CHANGED: delegates to shared AudioFormatConverter instead of local duplicate
        val channelMask = mapChannelCountToOutMask(channelCount) ?: run {
            Log.w(TAG, "routeAudio: unsupported channelCount=$channelCount — no mask mapping")
            return UsbRoutingResult.UnsupportedFormat("channelCount=$channelCount")
        }

        Log.d(
            TAG,
            "routeAudio: target — sr=$sampleRate " +
                "enc=${encodingName(encoding)}($encoding) " +
                "mask=0x${channelMask.toString(16)} " +
                "for device='${device.productName}' id=${device.id}"
        )

        // ── Query the DAC's supported profiles ───────────────────────────────
        val supportedAttributes = runCatching { manager.getSupportedMixerAttributes(device) }
            .getOrElse { throwable ->
                Log.w(TAG, "routeAudio: getSupportedMixerAttributes threw — ${throwable.message}", throwable)
                return UsbRoutingResult.QueryFailed(throwable.message)
            }

        if (supportedAttributes.isEmpty()) {
            Log.w(
                TAG,
                "routeAudio: '${device.productName}' returned 0 supported mixer attributes. " +
                    "This typically means the OS / OEM audio HAL does not expose mixer profiles " +
                    "for this device (common on ColorOS / FuntouchOS when the built-in Hi-Fi " +
                    "stack intercepts USB audio before AudioFlinger sees it)."
            )
            return UsbRoutingResult.UnsupportedDevice(device.id)
        }

        // Log every profile so we can see exactly what the DAC/HAL offers.
        Log.i(TAG, "routeAudio: '${device.productName}' advertises ${supportedAttributes.size} mixer profile(s):")
        supportedAttributes.forEachIndexed { i, attr ->
            val behavior = when (attr.mixerBehavior) {
                AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "BIT_PERFECT"
                AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT     -> "DEFAULT"
                else                                             -> "UNKNOWN(${attr.mixerBehavior})"
            }
            Log.i(
                TAG,
                "  [$i] behavior=$behavior " +
                    "sr=${attr.format.sampleRate} " +
                    "enc=${encodingName(attr.format.encoding)}(${attr.format.encoding}) " +
                    "mask=0x${attr.format.channelMask.toString(16)}"
            )
        }

        // ── Try to find an exact match ────────────────────────────────────────
        Log.d(
            TAG,
            "routeAudio: searching for BIT_PERFECT profile matching " +
                "sr=$sampleRate enc=${encodingName(encoding)} mask=0x${channelMask.toString(16)}"
        )
        val match = supportedAttributes.firstOrNull { attr ->
            attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                attr.format.sampleRate == sampleRate &&
                attr.format.encoding == encoding &&
                attr.format.channelMask == channelMask
        }

        if (match == null) {
            // Diagnose each mismatch to pinpoint exactly which field is the blocker.
            val bitPerfectProfiles = supportedAttributes.filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }
            if (bitPerfectProfiles.isEmpty()) {
                Log.w(
                    TAG,
                    "routeAudio: NO BIT_PERFECT profiles at all — device only offers DEFAULT " +
                        "behavior. The OEM HAL or vendor USB audio driver may not support " +
                        "MIXER_BEHAVIOR_BIT_PERFECT for '${device.productName}'."
                )
            } else {
                Log.w(TAG, "routeAudio: ${bitPerfectProfiles.size} BIT_PERFECT profile(s) found but none match:")
                bitPerfectProfiles.forEachIndexed { i, attr ->
                    val srOk  = attr.format.sampleRate == sampleRate
                    val encOk = attr.format.encoding == encoding
                    val mskOk = attr.format.channelMask == channelMask
                    Log.w(
                        TAG,
                        "  [$i] sr=${attr.format.sampleRate}(need $sampleRate ✓=$srOk) " +
                            "enc=${encodingName(attr.format.encoding)}(need ${encodingName(encoding)} ✓=$encOk) " +
                            "mask=0x${attr.format.channelMask.toString(16)}(need 0x${channelMask.toString(16)} ✓=$mskOk)"
                    )
                }
            }
            return UsbRoutingResult.NoMatchingProfile
        }

        // ── Apply the preference ──────────────────────────────────────────────
        Log.d(
            TAG,
            "routeAudio: calling setPreferredMixerAttributes — " +
                "device='${device.productName}' sr=$sampleRate " +
                "enc=${encodingName(encoding)} mask=0x${channelMask.toString(16)}"
        )
        val applied = runCatching { manager.setPreferredMixerAttributes(mediaAttributes, device, match) }
            .getOrElse { throwable ->
                Log.e(
                    TAG,
                    "routeAudio: setPreferredMixerAttributes THREW — ${throwable.message}. " +
                        "OEM audio stacks (ColorOS, FuntouchOS) may throw SecurityException or " +
                        "IllegalStateException when the vendor Hi-Fi service holds an exclusive lock.",
                    throwable
                )
                return UsbRoutingResult.Rejected(throwable.message)
            }

        return if (applied) {
            Log.i(
                TAG,
                "routeAudio: BIT_PERFECT route APPLIED ✓ — " +
                    "'${device.productName}' sr=$sampleRate bd=$bitDepth ch=$channelCount"
            )
            val event = UsbRoutingEvent.Applied(
                deviceId = device.id,
                sampleRate = sampleRate,
                encoding = encoding,
                channelMask = channelMask,
            )
            _routingEvents.tryEmit(event)
            UsbRoutingResult.Applied(sampleRate = sampleRate, encoding = encoding, channelMask = channelMask)
        } else {
            Log.w(
                TAG,
                "routeAudio: setPreferredMixerAttributes returned FALSE. " +
                    "The OS accepted the call but did not apply the preference. " +
                    "This can happen when the OEM audio policy manager rejects the request " +
                    "silently (observed on ColorOS 15+ with certain USB DACs). " +
                    "device='${device.productName}' sr=$sampleRate bd=$bitDepth ch=$channelCount"
            )
            _routingEvents.tryEmit(UsbRoutingEvent.Rejected(device.id, reason = "applyReturnedFalse"))
            UsbRoutingResult.Rejected(reason = "applyReturnedFalse")
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun observePreferredMixerChanges(): Flow<UsbRoutingEvent> = callbackFlow {
        val manager = audioManager
        if (manager == null) {
            Log.w(TAG, "observePreferredMixerChanges: AudioManager null — listener not registered")
            awaitClose { /* nothing registered */ }
            return@callbackFlow
        }

        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "UsbBitPerfectRouter-Listener").apply { isDaemon = true }
        }

        val listener = AudioManager.OnPreferredMixerAttributesChangedListener { attributes, device, mixerAttributes ->
            if (attributes.usage != mediaAttributes.usage) return@OnPreferredMixerAttributesChangedListener
            val event = if (mixerAttributes != null) {
                val isBp = mixerAttributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                Log.i(
                    TAG,
                    "OnPreferredMixerAttributesChanged: device='${device.productName}' " +
                        "sr=${mixerAttributes.format.sampleRate} " +
                        "enc=${encodingName(mixerAttributes.format.encoding)} " +
                        "mask=0x${mixerAttributes.format.channelMask.toString(16)} " +
                        "isBitPerfect=$isBp"
                )
                UsbRoutingEvent.Changed(
                    deviceId = device.id,
                    sampleRate = mixerAttributes.format.sampleRate,
                    encoding = mixerAttributes.format.encoding,
                    channelMask = mixerAttributes.format.channelMask,
                    isBitPerfect = isBp,
                )
            } else {
                Log.i(TAG, "OnPreferredMixerAttributesChanged: cleared for device='${device.productName}'")
                UsbRoutingEvent.Cleared(deviceId = device.id)
            }
            trySend(event)
        }

        runCatching { manager.addOnPreferredMixerAttributesChangedListener(executor, listener) }
            .onSuccess { Log.d(TAG, "observePreferredMixerChanges: listener registered") }
            .onFailure { throwable ->
                Log.w(TAG, "addOnPreferredMixerAttributesChangedListener failed", throwable)
            }

        awaitClose {
            runCatching { manager.removeOnPreferredMixerAttributesChangedListener(listener) }
            runCatching { executor.shutdownNow() }
            Log.d(TAG, "observePreferredMixerChanges: listener unregistered")
        }
    }


    /**
     * Returns a human-readable label for an [AudioFormat.ENCODING_*] constant.
     * Used in diagnostic logs so integer constants are immediately legible.
     */
    private fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT        -> "PCM_16BIT"
        AudioFormat.ENCODING_PCM_FLOAT        -> "PCM_FLOAT"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM_24BIT_PACKED"
        AudioFormat.ENCODING_PCM_32BIT        -> "PCM_32BIT"
        AudioFormat.ENCODING_PCM_8BIT         -> "PCM_8BIT"
        AudioFormat.ENCODING_DTS              -> "DTS"
        AudioFormat.ENCODING_DTS_HD           -> "DTS_HD"
        AudioFormat.ENCODING_AC3              -> "AC3"
        AudioFormat.ENCODING_E_AC3            -> "E_AC3"
        else                                  -> "UNKNOWN($encoding)"
    }

    /**
     * Returns a human-readable label for an [AudioDeviceInfo.TYPE_*] constant.
     * Used in diagnostic logs so integer constants are immediately legible.
     */
    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE         -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET        -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP     -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO      -> "BT_SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES   -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET      -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER    -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE   -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_LINE_ANALOG        -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL       -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_HDMI               -> "HDMI"
        else                                    -> "TYPE_$type"
    }

    private companion object {
        const val TAG = "UsbBitPerfectRouter"
    }
}


