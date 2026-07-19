package com.androidexpert35.audiophilemusicplayer.data.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioFormatConverter.mapBitDepthToEncoding
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioFormatConverter.mapChannelCountToOutMask
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbBitPerfectRouter
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbRoutingEvent
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort software diagnostic tool that monitors and reports the audio
 * routing tier the Android OS is applying to the current playback stream.
 *
 * The validator does **not** control routing — it only observes. It combines
 * three independent signal sources into a single [AudioPathState] exposed via
 * the [pathState] [StateFlow]:
 *
 * 1. **Engine path reports** — [AudioEngineManager.pathReport] and
 *    [AudioEngineManager.currentFormat] supply the decoded format shape and the
 *    flags the [com.androidexpert35.audiophilemusicplayer.data.playback.native_
 *    .AudioTrackSink] / [com.androidexpert35.audiophilemusicplayer.data.playback
 *    .usb.UsbAudioSink] were built with.
 *
 * 2. **Bit-perfect router events** — [UsbBitPerfectRouter.routingEvents] emits
 *    [UsbRoutingEvent.Applied] when the Android 14+ mixer-attributes preference
 *    is confirmed, and [UsbRoutingEvent.Rejected] / [UsbRoutingEvent.Cleared]
 *    when it is undone. These events drive [_bitPerfectConfirmed].
 *
 * 3. **Output route changes** — a [callbackFlow]-wrapped device callback and
 *    playback callback re-query Android's media policy for [mediaAttributes],
 *    so [pathState] follows the selected music route instead of merely ranking
 *    connected peripherals. Cleanup is automatic via [awaitClose].
 *
 * ### JNI bridge
 *
 * When the C++ FFmpeg decoder layer knows the source format before the engine
 * manager has surfaced it (e.g. immediately after `avcodec_open2`), the native
 * code can call [updateSourceFormat] via JNI to seed the validator early. The
 * engine manager's [AudioFormatInfo] takes precedence over the externally-pushed
 * value whenever both are available.
 *
 * ### Graceful degradation
 *
 * All Android API version checks are encapsulated inside this class. Callers
 * collect [pathState] unconditionally; the validator emits
 * [AudioPathStatus.UNKNOWN] on engine idle, [AudioPathStatus.RESAMPLED] on API
 * 33 without FLAG_DIRECT, and [AudioPathStatus.DIRECT_BIT_PERFECT] only when
 * every condition for that tier is met.
 *
 * @property context Application context used to obtain [AudioManager].
 * @property audioEngineManager Engine coordinator exposing format and path-
 *   report state flows.
 * @property usbBitPerfectRouter API 34+ mixer-attributes coordinator whose
 *   routing events are the primary bit-perfect confirmation signal.
 * @property appScope Long-lived scope hosting all observation coroutines.
 */
@Singleton
class AudioPathValidator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val audioEngineManager: AudioEngineManager,
    private val usbBitPerfectRouter: UsbBitPerfectRouter,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    /** Media-playback attributes used for [AudioTrack.isDirectPlaybackSupported] queries. */
    private val mediaAttributes = AudioAttributesFactory.createMediaAttributes()

    // ── Source format ────────────────────────────────────────────────────────

    /**
     * Source format pushed externally from the C++ JNI layer via
     * [updateSourceFormat]. Used as a fallback when the engine manager has not
     * yet emitted a format update for the current track.
     */
    private val _externalSourceFormat = MutableStateFlow<AudioSourceFormat?>(null)

    /**
     * Merged source format: the engine manager's [AudioFormatInfo] is mapped
     * to [AudioSourceFormat] when available; the JNI-pushed value is the
     * fallback for sessions that pre-date the engine's first state emission.
     */
    private val effectiveSourceFormat: Flow<AudioSourceFormat?> = combine(
        audioEngineManager.currentFormat,
        _externalSourceFormat,
    ) { engineFormat, external ->
        engineFormat?.toSourceFormat() ?: external
    }

    // ── Bit-perfect confirmation ─────────────────────────────────────────────

    /**
     * `true` when [UsbBitPerfectRouter] has received a confirmed
     * [UsbRoutingEvent.Applied] event for the current stream and the
     * confirmation has not since been cleared or superseded by a format change.
     */
    private val _bitPerfectConfirmed = MutableStateFlow(false)

    // ── Active output device ──────────────────────────────────────────────────

    /**
     * Hot [StateFlow] of Android's selected media output device, updated on
     * device and playback-routing changes. The initial value is resolved
     * synchronously at construction so [pathState] is never empty on the first
     * collection.
     *
     * The underlying [callbackFlow] wraps [AudioManager.registerAudioDeviceCallback]
     * per the project's mandatory callback-to-Flow policy; [awaitClose] guarantees
     * unregistration when the scope is cancelled.
     */
    private val currentOutputDevice: StateFlow<AudioDeviceInfo?> =
        observeOutputDeviceChanges()
            .stateIn(
                scope = appScope,
                started = SharingStarted.Eagerly,
                initialValue = resolveCurrentMediaOutputDevice(),
            )

    // ── Combined path state ───────────────────────────────────────────────────

    /**
     * Live audio path diagnostic state derived from all available signal sources.
     *
     * Emits a new [AudioPathState] whenever any of the following change: the
     * engine's format or path report, the bit-perfect confirmation flag, or the
     * active output device. [distinctUntilChanged] suppresses duplicate emissions
     * so downstream collectors only react to genuine state transitions.
     */
    val pathState: StateFlow<AudioPathState> = combine(
        effectiveSourceFormat,
        audioEngineManager.pathReport,
        _bitPerfectConfirmed,
        currentOutputDevice,
    ) { format, report, isBitPerfect, device ->
        buildPathState(
            sourceFormat = format,
            report = report,
            isBitPerfectConfirmed = isBitPerfect,
            device = device,
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = AudioPathState(),
        )

    init {
        // Reset bit-perfect confirmation whenever the source format changes so
        // a newly loaded track always requires a fresh API 34+ router confirmation.
        audioEngineManager.currentFormat
            .map { it?.toFormatKey() }
            .distinctUntilChanged()
            .onEach { _bitPerfectConfirmed.value = false }
            .launchIn(appScope)

        // Translate router events into the boolean confirmation signal.
        usbBitPerfectRouter.routingEvents
            .onEach { event ->
                _bitPerfectConfirmed.value = when (event) {
                    is UsbRoutingEvent.Applied -> true
                    is UsbRoutingEvent.Changed -> event.isBitPerfect
                    is UsbRoutingEvent.Rejected, is UsbRoutingEvent.Cleared -> false
                }
            }
            .launchIn(appScope)
    }

    // ── JNI bridge ────────────────────────────────────────────────────────────

    /**
     * Pushes source format metadata from the FFmpeg C++ decoder layer.
     *
     * Call this from a JNI method immediately after `avcodec_open2` returns
     * successfully, before the engine manager emits its own format update.
     * The externally-provided format is superseded by the engine manager's
     * [AudioFormatInfo] on the first engine state emission, so there is no
     * risk of stale caller-supplied values persisting after the engine takes
     * over.
     *
     * **Thread safety:** safe to call from any thread; [MutableStateFlow.value]
     * is thread-safe by contract.
     *
     * @param sampleRateHz Decoded output sample rate in Hz.
     * @param bitDepth     Source PCM bit depth (16, 24, or 32).
     * @param channelCount Number of interleaved PCM channels (1 or 2).
     */
    fun updateSourceFormat(sampleRateHz: Int, bitDepth: Int, channelCount: Int) {
        _externalSourceFormat.value = AudioSourceFormat(
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            channelCount = channelCount,
        )
        Log.d(TAG, "JNI source format update: sr=$sampleRateHz bd=$bitDepth ch=$channelCount")
    }

    /**
     * Clears any externally-pushed source format and resets the bit-perfect
     * confirmation flag. Intended for use when the C++ layer tears down its
     * current decode session without the engine manager transitioning through
     * a new track load.
     */
    fun clearSourceFormat() {
        _externalSourceFormat.value = null
        _bitPerfectConfirmed.value = false
    }

    // ── State derivation ──────────────────────────────────────────────────────

    /**
     * Constructs an [AudioPathState] from the current combination of engine
     * report, bit-perfect confirmation, and device information.
     *
     * The HAL direct-playback probe is deliberately computed **before** calling
     * [derivePathStatus] so the result can inform that decision. For a USB DAC
     * with [android.media.AudioTrack.FLAG_DIRECT] confirmed, the HAL's own API
     * verdict supersedes the brand-only OEM risk heuristic — if
     * [AudioTrack.isDirectPlaybackSupported] returns `true` for this format,
     * Android's AudioFlinger already evaluated whether direct delivery is safe
     * for this device + format combination and said yes.
     *
     * @param sourceFormat Decoded format descriptor, or `null` when idle.
     * @param report       Current engine path report, or `null` when idle.
     * @param isBitPerfectConfirmed Whether API 34+ or hardware-bypass bit-perfect is active.
     * @param device       Highest-priority currently-connected output device.
     * @return Fully-populated [AudioPathState] snapshot.
     */
    private fun buildPathState(
        sourceFormat: AudioSourceFormat?,
        report: PipelinePathReport?,
        isBitPerfectConfirmed: Boolean,
        device: AudioDeviceInfo?,
    ): AudioPathState {
        // Query the platform synchronously for what the HAL claims it can do
        // with this format, independent of what the engine actually negotiated.
        //
        // DSD streams decoded via FFmpeg can produce sample rates outside
        // AudioFormat's 4 000–192 000 Hz window (e.g. DSD64 → 352 800 Hz).
        // In that case the Sox software resampler is invoked first, producing
        // the rate stored in report.sampleRateHz (e.g. 88 200 Hz / 32-bit).
        // We probe the *negotiated* output rate so isDirectPlaybackSupported
        // reflects whether the DAC can take that Sox-resampled format directly,
        // rather than always returning false for out-of-range DSD sources.
        val probeFormat: AudioSourceFormat? = sourceFormat?.let { fmt ->
            val negotiatedRate = report?.sampleRateHz ?: 0
            if (fmt.sampleRateHz !in 4_000..192_000 &&
                negotiatedRate in 4_000..192_000
            ) {
                // Substitute the Sox-resampled rate; bit-depth and channel count
                // are preserved because Sox does not change those for this path.
                fmt.copy(sampleRateHz = negotiatedRate)
            } else {
                fmt
            }
        }

        // Prefer live AudioDeviceInfo for type/name; fall back to path-report
        // fields when the device observer has not resolved a device yet.
        // Computed BEFORE derivePathStatus so the effective device type can be
        // used as a routing signal. AudioTrack.getRoutedDevice() often returns
        // null until frames begin flowing, so report.routedDeviceType may be 0
        // immediately after the sink is built. The live currentOutputDevice from
        // the AudioDeviceCallback flow has the correct type even at that point.
        val deviceType = device?.type ?: (report?.routedDeviceType ?: 0)
        // AudioDeviceInfo.productName returns CharSequence; toString() gives a
        // stable, non-null String copy safe for use in an immutable data class.
        val deviceName: String? = device?.productName?.toString() ?: report?.routedDeviceName

        // Compute HAL direct capability FIRST so it can feed into derivePathStatus.
        // AudioTrack.isDirectPlaybackSupported returning true means AudioFlinger
        // evaluated this specific format + device combination and confirmed the HAL
        // will route it without sample-rate conversion — this is a stronger,
        // format-specific signal than the brand-based OEM heuristic alone.
        // NOTE: some OEM HALs (ColorOS) return false here even when FLAG_DIRECT is
        // actively being granted — so this is only a secondary signal; the USB
        // device-type check in derivePathStatus is the primary override.
        val isDirectSupported = probeFormat?.let { fmt ->
            checkIsDirectPlaybackSupported(fmt)
        } ?: false

        val pathStatus = derivePathStatus(
            report = report,
            isMixerBitPerfectConfirmed = isBitPerfectConfirmed,
            isHalDirectConfirmedForFormat = isDirectSupported,
            effectiveDeviceType = deviceType,
        )

        // ✅ CHANGED: extracted to computeIsResamplingActive() for readability and testability
        val isResampling = computeIsResamplingActive(report)


        return AudioPathState(
            sourceFormat = sourceFormat,
            activeDevice = device,
            activeDeviceType = deviceType,
            activeDeviceName = deviceName,
            pathStatus = pathStatus,
            // Only set isBitPerfectConfirmed when the status is actually the
            // top tier — an Applied event for a format that has since changed
            // must not bleed into a RESAMPLED state.
            isBitPerfectConfirmed = isBitPerfectConfirmed &&
                pathStatus == AudioPathStatus.DIRECT_BIT_PERFECT,
            isDirectPlaybackSupported = isDirectSupported,
            outputSampleRateHz = report?.sampleRateHz
                ?: sourceFormat?.sampleRateHz
                ?: 0,
            isResamplingActive = isResampling,
        )
    }

    /**
     * Derives the [AudioPathStatus] tier for the current stream.
     *
     * Decision tree (highest-priority first):
     *
     * 1. Custom USB host path ([android.hardware.usb.UsbConstants.USB_CLASS_AUDIO]
     *    sentinel + `audioSessionId == 0` + `encoding == 0` in the report) →
     *    [AudioPathStatus.DIRECT_BIT_PERFECT]. This path bypasses AudioFlinger
     *    entirely; no software processing can interfere.
     *
     * 2. API 34+ `MIXER_BEHAVIOR_BIT_PERFECT` confirmed by the router →
     *    [AudioPathStatus.DIRECT_BIT_PERFECT].
     *
     * 3. No path report yet → [AudioPathStatus.UNKNOWN].
     *
     * 4. `usedDirectFlag = false` → [AudioPathStatus.RESAMPLED].
     *
     * 5. `usedDirectFlag = true` + OEM DSP risk + HAL did **not** confirm direct
     *    playback for this specific format → [AudioPathStatus.OEM_WARNING].
     *
     *    `OEM_WARNING` is intentionally **not** issued when the stream is routed
     *    to a USB output device (TYPE_USB_DEVICE or TYPE_USB_HEADSET). USB
     *    isochronous transfer operates at the kernel UAC2 driver level — OEM
     *    user-space DSP daemons cannot intercept the USB packet stream at that
     *    layer. If FLAG_DIRECT was granted and the stream landed on USB, the PCM
     *    frames reach the DAC exactly as written. `OEM_WARNING` is reserved for
     *    analog and internal-speaker paths where a SoC-side DSP can be inserted
     *    after the HAL, and for the rare case where FLAG_DIRECT was granted to a
     *    non-USB OEM device but neither the HAL API nor USB routing provides any
     *    positive confirmation signal.
     *
     * 6. `usedDirectFlag = true`, no unmitigated OEM risk →
     *    [AudioPathStatus.DIRECT_SUPPORTED].
     *
     * @param report                       Current engine path report; `null` → UNKNOWN.
     * @param isMixerBitPerfectConfirmed   Whether the router confirmed API 34+ bit-perfect.
     * @param isHalDirectConfirmedForFormat Whether [AudioTrack.isDirectPlaybackSupported]
     *   returned `true` for the current source format.
     * @param effectiveDeviceType          The resolved output device type: `device?.type`
     *   when the live [AudioDeviceInfo] is available, otherwise
     *   `report.routedDeviceType`. Using the combined value avoids the race
     *   where `AudioTrack.getRoutedDevice()` returns `null` (→ type 0) before
     *   the first audio frames have been routed, while
     *   [AudioManager.getDevices] already sees the USB DAC.
     * @return Derived [AudioPathStatus] for the current stream.
     */
    private fun derivePathStatus(
        report: PipelinePathReport?,
        isMixerBitPerfectConfirmed: Boolean,
        isHalDirectConfirmedForFormat: Boolean,
        effectiveDeviceType: Int,
    ): AudioPathStatus {
        if (report == null) return AudioPathStatus.UNKNOWN

        // ── Tier 1a: Custom USB host path ────────────────────────────────────
        // The libusb sinks write directly to the USB isochronous endpoint,
        // bypassing AudioFlinger entirely. They mark the report with:
        //   • routedDeviceType == UsbConstants.USB_CLASS_AUDIO (= 1)
        //   • audioSessionId == 0  (no AudioTrack session — raw USB I/O)
        if (isCustomUsbSinkPath(report)) {
            // The transport still bypasses AudioFlinger when SUE, Hi-Res Remaster,
            // or the standalone SoXR stage is active, but the samples are no longer
            // identical to the source. Report the direct tier without claiming
            // bit-perfect signal integrity.
            return if (report.isAppOwnedProcessingActive()) {
                AudioPathStatus.DIRECT_SUPPORTED
            } else {
                AudioPathStatus.DIRECT_BIT_PERFECT
            }
        }

        // ── Tier 1b: API 34+ confirmed bit-perfect ───────────────────────────
        if (isMixerBitPerfectConfirmed &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            return AudioPathStatus.DIRECT_BIT_PERFECT
        }

        // ── Tier 4: Standard mixer ───────────────────────────────────────────
        if (!report.usedDirectFlag) return AudioPathStatus.RESAMPLED

        // ── Tier 2 / 3: FLAG_DIRECT was granted ─────────────────────────────
        //
        // Determine whether two hardware/API-level positive signals are present:
        //
        // Signal A — USB output device:
        //   USB isochronous transfer is handled entirely by the kernel UAC2 driver.
        //   OEM DSP daemons run in user space (or on the application processor
        //   SoC) and cannot intercept kernel USB packet queues. If the route lands
        //   on a USB DAC, the delivered byte stream is necessarily identical to
        //   what the AudioTrack wrote — regardless of OEM brand. Note that
        //   nativeOutputSampleRateHz always reflects the shared AudioFlinger mixer
        //   property (typically 48 kHz) and is irrelevant for FLAG_DIRECT paths;
        //   do not use the nativeOutputSampleRateHz ≠ sampleRateHz mismatch as a
        //   resampling indicator on direct paths.
        //
        // Signal B — HAL direct playback API confirmation:
        //   AudioTrack.isDirectPlaybackSupported returned true for this exact
        //   format + device pair.  AudioFlinger's own capability check confirmed
        //   the stream travels without SRC for this combination, superseding the
        //   coarse brand heuristic. Note: some OEM HALs (ColorOS) do not
        //   implement this API correctly and return false even when FLAG_DIRECT is
        //   actively being granted — so Signal A is the primary USB signal.
        //
        // OEM_WARNING fires only when ALL of: known-OEM brand, not USB-routed,
        // and HAL API returned false. Even one positive signal means the OEM's
        // own infrastructure confirms the path, so the brand warning is unwarranted.

        // effectiveDeviceType combines the live AudioDeviceInfo type (from the
        // AudioDevice callback) with report.routedDeviceType as a fallback. The
        // live device type is preferred because AudioTrack.getRoutedDevice() may
        // not yet have resolved when the PipelinePathReport is first emitted.
        val isRoutedToUsb =
            effectiveDeviceType == AudioDeviceInfo.TYPE_USB_DEVICE ||
                effectiveDeviceType == AudioDeviceInfo.TYPE_USB_HEADSET

        val oemRiskUnmitigated =
            isKnownOemDspRisk() && !isRoutedToUsb && !isHalDirectConfirmedForFormat

        return if (oemRiskUnmitigated) {
            Log.d(
                TAG,
                "derivePathStatus: OEM_WARNING — known-OEM brand on non-USB output " +
                    "(effectiveDeviceType=$effectiveDeviceType reportDeviceType=${report.routedDeviceType}), " +
                    "neither HAL API nor USB routing provides a positive confirmation that FLAG_DIRECT is " +
                    "delivering audio unprocessed. Silent OEM DSP interference cannot be ruled out.",
            )
            AudioPathStatus.OEM_WARNING
        } else {
            if (isKnownOemDspRisk()) {
                // At least one positive signal (USB or HAL API) was present — log
                // which one so it is visible in future diagnostic sessions.
                Log.i(
                    TAG,
                    "derivePathStatus: DIRECT_SUPPORTED on known-OEM device — " +
                        "OEM brand heuristic superseded by: " +
                        "USB=${isRoutedToUsb} " +
                        "halConfirmed=${isHalDirectConfirmedForFormat} " +
                        "(effectiveDeviceType=$effectiveDeviceType reportDeviceType=${report.routedDeviceType}). " +
                        "FLAG_DIRECT confirmed delivering audio on an unprocessed path.",
                )
            }
            AudioPathStatus.DIRECT_SUPPORTED
        }
    }

    // ── Output device observation (callbackFlow) ──────────────────────────────

    /**
     * Wraps the AudioManager device and playback callbacks in a [callbackFlow]
     * so media-routing changes are emitted as a [Flow] instead of raw callbacks.
     *
     * An initial value is emitted synchronously on collection so the first
     * subscriber immediately receives the current device state. [awaitClose]
     * guarantees that the callback is unregistered when the collector's scope
     * is cancelled — no manual lifecycle wiring is required.
     *
     * The callback is registered on [Handler(Looper.getMainLooper())] to keep
     * dispatch on the main thread and avoid cross-thread races inside the flow.
     *
     * @return Cold [Flow] emitting the selected media [AudioDeviceInfo] on every
     *   routing change, or `null` when no output device is resolved.
     */
    private fun observeOutputDeviceChanges(): Flow<AudioDeviceInfo?> = callbackFlow {
        val manager = audioManager
        if (manager == null) {
            Log.w(TAG, "AudioManager unavailable — device tracking disabled")
            trySend(null)
            awaitClose()
            return@callbackFlow
        }

        // Emit the current routing state immediately so the first collector
        // receives the correct device before any change event fires.
        trySend(resolveCurrentMediaOutputDevice())

        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                trySend(resolveCurrentMediaOutputDevice())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                trySend(resolveCurrentMediaOutputDevice())
            }
        }

        val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                trySend(resolveCurrentMediaOutputDevice())
            }
        }

        manager.registerAudioDeviceCallback(
            deviceCallback,
            Handler(Looper.getMainLooper()),
        )
        manager.registerAudioPlaybackCallback(
            playbackCallback,
            Handler(Looper.getMainLooper()),
        )

        // Automatically unregisters the callback when the scope is cancelled.
        awaitClose {
            manager.unregisterAudioDeviceCallback(deviceCallback)
            manager.unregisterAudioPlaybackCallback(playbackCallback)
        }
    }

    /**
     * Resolves the output selected by Android's audio policy for media playback.
     *
     * A connected-device priority scan is retained only as a defensive fallback
     * for transient policy-service failures.
     *
     * @return The best available output [AudioDeviceInfo], or `null` when no
     *   output device is connected at the time of the call.
     */
    private fun resolveCurrentMediaOutputDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val policyDevice = runCatching {
            manager.getAudioDevicesForAttributes(mediaAttributes).firstOrNull()
        }.onFailure { throwable ->
            Log.w(TAG, "Media-policy route query failed; using connected-device fallback", throwable)
        }.getOrNull()

        return policyDevice ?: manager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .minByOrNull { deviceTypePriority(it.type) }
    }

    /**
     * Returns a stable sort key for output device prioritisation.
     * Lower values are higher priority (USB DAC → internal speaker).
     *
     * @param type [AudioDeviceInfo.TYPE_*] constant to rank.
     * @return Priority integer (0 = highest priority).
     */
    private fun deviceTypePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> 0
        AudioDeviceInfo.TYPE_USB_HEADSET -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID -> 2
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> 4
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 5
        else -> 10
    }

    // ── API 34+ mixer-attributes probing ─────────────────────────────────────

    /**
     * Checks whether the connected USB DAC's [AudioManager.getSupportedMixerAttributes]
     * list contains a profile matching the current source format with
     * [AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT].
     *
     * This is a read-only probe — it does not apply any preference. A `true`
     * result means the DAC *can* support bit-perfect for this format even if
     * [UsbBitPerfectRouter] has not yet applied the preference.
     *
     * Called lazily by the UI layer or telemetry collectors when they need to
     * expose the *potential* for bit-perfect as a separate property from the
     * *confirmed* [AudioPathState.isBitPerfectConfirmed].
     *
     * @param sampleRateHz Source sample rate in Hz.
     * @param bitDepth     Source PCM bit depth (16, 24, or 32).
     * @param channelCount Number of interleaved channels (1 or 2).
     * @return `true` when the connected DAC advertises a bit-perfect profile
     *   that matches the supplied parameters; `false` otherwise or on API < 34.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun probeIsBitPerfectCapable(sampleRateHz: Int, bitDepth: Int, channelCount: Int): Boolean {
        val manager = audioManager ?: return false
        val device = usbBitPerfectRouter.findConnectedUsbDac() ?: return false
        val encoding = mapBitDepthToEncoding(bitDepth) ?: return false
        val channelMask = mapChannelCountToOutMask(channelCount) ?: return false

        return runCatching {
            manager.getSupportedMixerAttributes(device).any { attr ->
                attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    attr.format.sampleRate == sampleRateHz &&
                    attr.format.encoding == encoding &&
                    attr.format.channelMask == channelMask
            }
        }.getOrDefault(false)
    }

    // ── AudioTrack.isDirectPlaybackSupported ──────────────────────────────────

    /**
     * Queries [AudioTrack.isDirectPlaybackSupported] for [format] using the
     * app's media [android.media.AudioAttributes].
     *
     * This is a platform-level capability check — it tells us whether the HAL
     * would accept the format on a direct (non-resampled) path if an
     * [AudioTrack] were built right now with those parameters. It does **not**
     * reflect what the currently-active sink actually negotiated.
     *
     * [AudioTrack.isDirectPlaybackSupported] is available from API 29. Since
     * this app's [Build.VERSION.SDK_INT] is always ≥ 33 (minSdk), no API guard
     * is required at the call site.
     *
     * @param format Source format to probe.
     * @return `true` when the HAL claims to support direct playback for
     *   [format] without sample-rate conversion; `false` on unsupported format
     *   parameters or probe failure.
     */
    private fun checkIsDirectPlaybackSupported(format: AudioSourceFormat): Boolean {
        // ✅ CHANGED: delegates to shared AudioFormatConverter instead of local duplicate
        val encoding = mapBitDepthToEncoding(format.bitDepth) ?: return false
        val channelMask = mapChannelCountToOutMask(format.channelCount) ?: return false
        // AudioFormat.Builder.setSampleRate() enforces 4 000–192 000 Hz.
        // DSD streams decoded to PCM by FFmpeg can produce rates up to 352 800 Hz
        // (DSD64) or higher. These rates cannot be probed via AudioTrack, so the
        // direct-playback path is unavailable and we return false immediately.
        if (format.sampleRateHz !in 4_000..192_000) return false
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(format.sampleRateHz)
            .setEncoding(encoding)
            .setChannelMask(channelMask)
            .build()
        return runCatching {
            // AudioTrack.isDirectPlaybackSupported is the canonical API at minSdk 33.
            // The deprecation lint fires because API 34 renamed the preferred call site,
            // but no public AudioManager equivalent exists on API 33 — suppressing here
            // is correct rather than guarding behind a Build.VERSION check.
            @Suppress("DEPRECATION")
            AudioTrack.isDirectPlaybackSupported(audioFormat, mediaAttributes)
        }.getOrDefault(false)
    }

    // ── OEM DSP risk heuristic ────────────────────────────────────────────────

    /**
     * Returns `true` when the device is running firmware from a manufacturer
     * whose proprietary audio DSP daemon is known to intercept
     * [AudioTrack.FLAG_DIRECT] output paths.
     *
     * This is a best-effort heuristic based on documented OEM audio stack
     * behaviour:
     *
     * - **Oppo / OnePlus / Realme** (ColorOS / Realme UI): the `audioserver`
     *   replacement on several ColorOS builds registers a system-level audio
     *   effect that applies even on direct-output streams. Reports in Android
     *   audio developer communities confirm this breaks true bit-perfect
     *   delivery on affected builds.
     * - **Vivo** (OriginOS / FuntouchOS): similar reported behaviour in the
     *   Vivo "Hi-Fi" audio processing layer.
     *
     * Samsung (One UI) and Xiaomi (MIUI / HyperOS) are intentionally excluded
     * from the risk set because their audio stacks have not been confirmed to
     * interfere with FLAG_DIRECT paths on current ROM versions.
     *
     * @return `true` when OEM DSP interference cannot be ruled out.
     */
    private fun isKnownOemDspRisk(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val brand = Build.BRAND.lowercase(Locale.US)
        return manufacturer in OEM_DSP_RISK_SET || brand in OEM_DSP_RISK_SET
    }

    // ── Custom USB sink detection ─────────────────────────────────────────────

    /**
     * Identifies a [PipelinePathReport] produced by the custom libusb hardware-bypass
     * path (both PCM and DSD variants) rather than by the standard
     * [com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioTrackSink].
     *
     * The custom USB sink sets two sentinel values in its report to signal that it
     * writes directly to the USB isochronous endpoint, bypassing AudioFlinger entirely:
     *
     * | Field | Sentinel value | Reason |
     * |---|---|---|
     * | `routedDeviceType` | `UsbConstants.USB_CLASS_AUDIO` (= 1) | USB audio class code, not an `AudioDeviceInfo.TYPE_*` constant |
     * | `audioSessionId` | `0` | No AudioTrack session — raw USB I/O |
     *
     * **Why `encoding` is no longer checked:** The DSD libusb path sets `encoding = 0`
     * (DSD has no valid `AudioFormat.ENCODING_*` constant), but the PCM libusb path
     * correctly sets `encoding` to the actual PCM encoding constant (e.g. `ENCODING_PCM_32BIT`).
     * Requiring `encoding == 0` would therefore miss all PCM libusb sessions.
     *
     * The dual check (`audioSessionId == 0` + `routedDeviceType == USB_CLASS_AUDIO_SENTINEL`)
     * is still sufficient to avoid false positives: `AudioDeviceInfo.TYPE_BUILTIN_EARPIECE`
     * also equals `1`, but a real AudioTrack to the earpiece always has `audioSessionId > 0`.
     *
     * @param report Report to classify.
     * @return `true` when the report was produced by the custom libusb USB sink.
     */
    private fun isCustomUsbSinkPath(report: PipelinePathReport): Boolean =
        report.usedDirectFlag &&
            report.audioSessionId == 0 &&
            report.routedDeviceType == USB_CLASS_AUDIO_SENTINEL

    /** Returns `true` when app-owned DSP or resampling modifies decoded PCM. */
    private fun PipelinePathReport.isAppOwnedProcessingActive(): Boolean =
        sueInfo?.let { info ->
            info.isActive ||
                info.isHiResRemasterActive ||
                info.isForce48kResampleActive
        } == true

    // ── Format conversion utilities ───────────────────────────────────────────

    /**
     * Computes whether sample-rate conversion is currently active by comparing
     * the sink's opened rate against the AudioFlinger output-thread rate on a
     * non-direct (mixed) path.
     *
     * The [PipelinePathReport.activeOutputThreadSampleRateHz] field is the
     * preferred source of truth for standard PCM routes because it reflects the
     * actual thread rate rather than the global mixer property. The legacy
     * [PipelinePathReport.nativeOutputSampleRateHz] is used as a fallback when
     * the thread rate is unavailable.
     *
     * This predicate is only meaningful when `usedDirectFlag = false`. On a
     * direct path the two rate fields represent independent subsystems and a
     * mismatch does not imply resampling.
     *
     * @param report Engine path report, or `null` when idle.
     * @return `true` when resampling is confirmed to be active; `false` when the
     *   stream is direct, idle, or the thread rate is unknown.
     */
    private fun computeIsResamplingActive(report: PipelinePathReport?): Boolean {
        report ?: return false
        if (report.usedDirectFlag) return false
        if (report.sampleRateHz <= 0) return false
        val effectiveThreadRateHz = report.activeOutputThreadSampleRateHz.takeIf { it > 0 }
            ?: report.nativeOutputSampleRateHz.takeIf { it > 0 }
            ?: return false
        return report.sampleRateHz != effectiveThreadRateHz
    }

    /**
     * Derives a [AudioFormatInfo] format key as a triple of
     * (sampleRateHz, sourceBitDepth, channelCount) for change detection.
     *
     * Used to trigger a [_bitPerfectConfirmed] reset when the loaded track
     * changes to a format with different PCM parameters, ensuring a stale
     * API 34+ confirmation from a prior track does not persist into a new session.
     *
     * @return Triple encoding the format's source PCM shape.
     */
    private fun AudioFormatInfo.toFormatKey(): Triple<Int, Int, Int> =
        Triple(sampleRateHz, sourceBitDepth, channelCount)

    /**
     * Maps [AudioFormatInfo] to [AudioSourceFormat] for [effectiveSourceFormat].
     *
     * @return [AudioSourceFormat] carrying the decoded output parameters.
     */
    private fun AudioFormatInfo.toSourceFormat(): AudioSourceFormat = AudioSourceFormat(
        sampleRateHz = sampleRateHz,
        bitDepth = sourceBitDepth,
        channelCount = channelCount,
    )

    private companion object {
        const val TAG = "AudioPathValidator"

        /**
         * Manufacturer/brand identifiers for OEMs whose proprietary audio
         * processing daemons are documented to intercept FLAG_DIRECT output.
         *
         * @see isKnownOemDspRisk
         */
        private val OEM_DSP_RISK_SET = setOf(
            "oppo",
            "oneplus",
            "realme",
            "vivo",
        )
    }
}
