# Playback — Engines, Media3 Service, Telemetry, USB Routing

> Part of the Audiophile AI guide. Start at [`/AGENTS.md`](../../AGENTS.md).
> The C++/JNI internals are in [`native-audio.md`](native-audio.md); platform ceilings
> are in [`/docs/BIT_PERFECT_LIMITATIONS.md`](../BIT_PERFECT_LIMITATIONS.md).

All playback code lives in the **Data layer** under `data/playback/`. The UI only ever
sees domain models (`PlaybackState`, `QueueState`, `AudioTelemetry`) via use cases.

---

## The MediaSessionService

`data/playback/service/AudioPlaybackService` is a foreground Media3
`MediaSessionService` (`foregroundServiceType="mediaPlayback"`). Keep it **thin**: it
hosts the session and wires the player; all real logic lives in controllers, the engine
manager, telemetry, and factories.

- ✅ Use Media3 abstractions (`MediaSession`, `MediaController`); the app UI talks to
  playback through the controller/repository, not the service directly.
- ✅ Clean up explicitly in `onDestroy()`; collect flows on a service scope that's
  cancelled there.
- ❌ Never run MediaStore scans on the main thread from the service.
- ❌ Never leak `Player`/`MediaSession` into Domain or Presentation.

`BecomingNoisyReceiver` pauses on a physical output disconnect. Audio focus is
deliberately not requested or observed: OEM focus transitions, screen lock/unlock,
notifications, and competing apps never mutate playback state. Session restore is
handled by `PlaybackControllerSessionRestorer` + `PlaybackPersistenceRepository`.
The listener may disable that restore through the **Clear queue when closing the app**
setting: task removal from Recents clears both the Media3 queue and its singleton
persistence row before the service releases the audio path. The Queue sheet also offers
the same action manually, with confirmation.

---

## Dual-engine architecture

`data/playback/engine/AudioEngineManager` (`@Singleton`) is the runtime coordinator. It
implements `AudioPlayerEngine` so everything downstream depends on one stable type.

| Engine | Backing | Use |
|--------|---------|-----|
| `StandardEngine` | ExoPlayer (**offload disabled**, `WAKE_MODE_LOCAL` so background track boundaries survive CPU suspend) | Default; battery-friendly |
| `AudiophileEngine` | FFmpeg JNI decoder → `AudioTrack` (`FLAG_DIRECT`) **or** libusb UAC2 | Bit-perfect / DSD / USB DAC |

**Hot-swap contract** (`switchTo`): capture active engine's URI + playhead +
play-when-ready → stop/detach it → load the same URI into the target engine at the
saved position → resume if the user was playing. Runs under a `Mutex` so concurrent
toggles serialize and only one engine holds audio focus at a time. The manager mirrors
the active engine's `StateFlow`s onto its own, so consumers never re-subscribe across a
swap. Default at app start is `EngineType.STANDARD`.

When the user enables the audiophile engine, the manager activates it **regardless** of
USB DAC presence — the engine itself decides between direct USB output and an
`AudioTrack` fallback.

### Engine settings coordinators
`AudioEngineSettingsCoordinator`, `SueSettingsCoordinator`,
`HiResRemasterSettingsCoordinator` bridge the reactive settings flows
([`data.md`](data.md)) to engine behavior. The **SUE** (Sonic Upscaling Enhancer) stage
inserts a conditional filter graph (≥2× oversampled harmonic excitation with anti-alias
downsample, cutoff-aware high-band contouring, bitrate-gated stereo widening, soft
low-pass, true-peak limiter) **only for lossy sources** — it is silently bypassed for
lossless even when enabled. `SueProfileResolver`
resolves a `SueProfileResolution` (codec `SueCodecTier` × bitrate → `SueIntensityProfile`
+ `SueSpecialFlags` bitmask) per track; `SueStage` wraps the JNI bridge (`SueBridge`,
implemented natively in `sue_bridge.cpp` — see [`native-audio.md`](native-audio.md)) and
is a zero-cost no-op for lossless/DSD sources. Insertion order in the pipeline is
`FFmpegDecoder → SueStage → SoxResamplerStage → AudiophileOutputSink` (enhancement runs
on full-precision float32 before any resample).

### `BitPerfectPlaybackEngine` — internal decomposition
`engine/audiophile/BitPerfectPlaybackEngine` owns a single `FFmpegDecoder` feeding a
single `AudiophileOutputSink` on a dedicated `THREAD_PRIORITY_AUDIO` thread; all state
mutation happens there, with public methods posting through a `Handler`. It is the
coordinator only — each concern is delegated to a dedicated helper in the same package:

| Helper | Responsibility |
|--------|-----------------|
| `BitPerfectSessionLoader` | Tiered decoder + sink session build (T1–T3) into a `LoadedAudioSession` |
| `BitPerfectSinkRouter` | Picks the output sink (USB direct vs. `AudioTrack`) and builds the DSD transport context from USB hardware state |
| `BitPerfectPcmRatePolicy` | Resolves PCM sample-rate ownership for the enhancement stage per track load |
| `BitPerfectRoutingDiagnostics` | Stateless logcat routing banners (`adb logcat -s AudiophileRouting`) |
| `BitPerfectGaplessQueue` | Preloaded next-track state for gapless / non-gapless auto-advance |
| — auto-advance resilience | Every `doEnqueueNext` failure path downgrades to a URI-only entry (load deferred to EOF) instead of returning empty-handed; at EOF an empty queue re-asks `Listener.onNextTrackUriRequested()` (answered by `AudioEngineManager` from its cached follower URI) and the EOF-time load retries once on ERROR. Playback must never end silently while the playlist still has a follower. |
| `BitPerfectTransportBuffers` | Reusable, grow-only PCM/DSD scratch buffers (direct `ByteBuffer` for the JNI boundary) |
| `BitPerfectWakeLockController` | `PARTIAL_WAKE_LOCK` lifecycle across play/pause/stop/error |
| `BitPerfectIdleSinkReleaseScheduler` | Releases the sink after 2 min paused so the OS reclaims USB-DAC bandwidth; decoder/position survive for a transparent resume |
| — pause-time output release | Every explicit audiophile-engine pause closes the USB sink plus Android 14+ bit-perfect mixer routing. Play transparently rebuilds output; audio-focus and app lifecycle changes do not alter playback |
| `BitPerfectDsdSupport` (`DsdPlaybackContext`) | Immutable DSD transport context (source/effective rate, output mode, DoP encoder) |
| `BitPerfectUriResolver` | `content://` → `/proc/self/fd/<fd>` trampoline resolution for FFmpeg |
| `BitPerfectPlaybackMath` | Pure sink-playhead → playback-position-ms conversion |
| — seek/reload anchoring | Libusb sinks expose an absolute post-seek playhead; the engine captures that value as `sinkStartFrames` and snapshots the live head before pause/DSP/routing reloads so the target is never added twice or rounded back to the last UI tick |
| `BitPerfectDiagnosticsLogger` | Structured `[BP]` failure-mode logging (`adb logcat -s AudiophileDiag`) |
| `PlaybackSampleRatePlanner` (`PlaybackSampleRatePlan`) | Final resampling negotiation result combining SUE/Hi-Res/standalone-SoXR outcomes |
| `BitPerfectEnhancementPipeline` | Routing predicates (`shouldUseSueStage`, …) deciding whether SUE/Hi-Res Remaster applies to a format |

When adding a new concern to this engine, add a new helper rather than growing
`BitPerfectPlaybackEngine` itself, and list it in the table above.

---

## Path classification & static output-rate policy

`data/playback/` (one level above `engine/`) hosts the shared path-classification layer
consumed by both `AudioPathValidator` (diagnostics, below) and
`BitPerfectPcmRatePolicy` (the engine's own rate decision):

- **`PathType`** — enum of the six mutually-exclusive playback paths
  (`USB_DAC_LIBUSB`, `USB_DAC_ANDROID_HAL`, `SUE_LOSSY`, `HI_RES_DAR`, `DSD`,
  `STANDARD_PCM`). Only `STANDARD_PCM` is eligible for the static resolver below.
- **`PathClassifier`** (`@Singleton`) — the single exclusion gate. Ordering is fixed and
  load-bearing: DSD bypasses first, then USB DAC routes (libusb, then Android USB HAL),
  then SUE/Hi-Res Remaster ownership, and only then `STANDARD_PCM`.
- **`StaticOutputRateResolver`** (`@Singleton`) — a pure, **device-agnostic** function of
  the source sample rate only, called strictly after classification confirms
  `STANDARD_PCM`: 44.1 kHz always upsamples to 48 kHz via SoXR VHQ (AudioFlinger's
  160:147 resampler is avoided); 48 kHz passes through untouched; hi-res (>48 kHz)
  passes through at the source rate. Returns a `Decision` (`Decision.Bypass` /
  `Decision.Apply(targetSampleRate, resamplingActive, reason)`).
- **`OutputDeviceHelper`** / **`AudioFormatConverter`** — stateless helpers (device
  priority ranking excluding USB; bit-depth → `AudioFormat` encoding and channel-mask
  mapping) shared by the classifier, the resolver, and `AudioPathValidator`.
- **`PlaybackRuntimeExt`** — small cross-engine utilities (`AUDIPHILE_PATH_TAG`,
  `USB_CLASS_AUDIO_SENTINEL`, the position-ticker interval, `Handler.runOrPost`,
  `CoroutineScope.restartTicker`).

**Rule:** if you add a new `PathType` or change the 44.1/48 kHz rule, update
`PathClassifier`/`StaticOutputRateResolver` **and** this section together — this is
exactly the sample-rate ownership logic the "never break bit-perfect" rule in
[`/AGENTS.md`](../../AGENTS.md) protects.

---

## Telemetry

`AudioTelemetryCollector` observes the active engine/decoder and produces
`AudioTelemetry` (sample rate, bit depth, codec, channels, output path, DSD info,
bit-perfect status). `AudioTelemetryRepositoryImpl` exposes it as a domain `Flow`
consumed by `ObserveAudioTelemetryUseCase` and the player telemetry UI.

- ✅ Telemetry is **diagnostic infrastructure**, never UI logic.
- ✅ Output routing crosses into Domain as a framework-free transport family
  (`Bluetooth`, `USB`, `wired`, `built-in`, …). Bluetooth telemetry labels the
  engine PCM shape separately from the final wireless format, which Android does
  not expose reliably to ordinary applications.
- ✅ The `isBitPerfect` flag is `true` only when the Android 14+ mixer preference was
  confirmed or an unprocessed custom libusb UAC2 path is active. A libusb transport
  carrying SUE, Hi-Res Dynamic Remaster, or explicit SoXR output remains direct but
  is reported as processed, never bit-perfect. Don't loosen this — read
  [`/docs/BIT_PERFECT_LIMITATIONS.md`](../BIT_PERFECT_LIMITATIONS.md) before touching it.
- ✅ `AudioPathValidator` / `PipelinePathReport` classify the actual negotiated path
  (`DIRECT_SUPPORTED`, `OEM_WARNING`, …). Keep that classification honest. This is a
  **read-only diagnostic observer**, distinct from `PathClassifier` below, which makes
  the **routing decision** the engine actually acts on — don't conflate the two.
- ✅ Enhanced libusb telemetry uses the selected UAC2 endpoint's `bBitResolution`.
  A lossy 16-bit decoder feeding float32 SUE therefore reports the DAC-facing
  post-quantisation depth (for example 24-in-32 or 32-in-32), while unprocessed
  S16 widened only for transport continues to report 16-bit source precision.

---

## USB DAC routing (Kotlin side — `data/playback/usb/`)

This is the bit-perfect path. The Kotlin layer negotiates and supervises; the actual
isochronous transfer happens in native code ([`native-audio.md`](native-audio.md)).

- `UsbDeviceScanner` + `UsbAudioBridge` — discover/attach DACs, request
  `UsbManager` permission, observe hot-plug.
- `UsbAudioDescriptorParser` / `UsbAudioDeviceDescriptor` — parse UAC2 descriptors to
  learn supported formats, alt-settings, and DSD capability.
- `UsbBitPerfectRouter` — applies `AudioManager.setPreferredMixerAttributes(BIT_PERFECT)`
  on API 34+ (needs `MODIFY_AUDIO_SETTINGS`) and reports confirmation.
- `LibusbPcmAudioSink` / `LibusbDsdAudioSink` / `LibusbOutputSink` — drive the native
  decoder-pump libusb sink for PCM and DSD (DoP / native passthrough).
- `LibusbPcmEnhancedSink` — keeps the Kotlin DSP write loop active for SUE and
  Hi-Res Dynamic Remaster, then sends float32 output through a JNI float-to-S32LE
  formatter into the same native ring and libusb ISO endpoint. The selected
  subslot width and valid resolution are retained in `PipelinePathReport` for
  output telemetry. It never uses `AudioTrack` while the custom USB transport
  remains healthy.
- `UsbStreamingTargetSelector`, `UsbAudioSinkFactory`, `UsbAudioLifecycleManager`,
  `UsbVolumeController`, `EngineSwapBridge` — selection, lifecycle, volume, and the
  hot-plug engine swap.
- Direct PCM format negotiation is automatic and source-native. The sink accepts
  the decoded track shape only when the DAC advertises it; otherwise playback
  falls back to the Android audio path without inserting conversion.

Rules:
- ✅ Wrap every USB/audio callback (`UsbManager` broadcasts, `AudioDeviceCallback`) in a
  `callbackFlow` with `awaitClose` cleanup — see [`conventions.md`](conventions.md).
- ✅ Surface USB permission/attach state as domain models (`UsbAudioStatus`,
  `UsbAudioFormat`), never raw `UsbDevice`.
- ✅ Software volume remains available for every direct-USB PCM DAC, including DACs
  with their own hardware buttons. Each DAC has an independent persisted level;
  a newly encountered device starts at the safe 60% fallback before the first PCM
  sample. The UI sends a linear position to native code, where one quadratic taper
  is applied. At 100% it is exact unity; do not add a pre-amplifier or apply the
  curve a second time. Native DSD remains unattenuated.
- ✅ Without a libusb-ready DAC, the enhancement graph continues through the platform
  `AudioTrack` sink; USB availability changes only the final transport.
- ✅ Enhanced libusb PCM requires an exact Type-I linear-PCM four-byte subslot;
  honour its advertised valid-bit resolution (including 24-in-32) and fail the USB
  route rather than sending incompatible bytes to a differently packed endpoint.
- ❌ Don't add implicit resampling or mixing in the USB/DSD path. Explicit user-enabled
  enhancement stages are allowed only when telemetry clearly reports processed output.

---

## Active queue mutations

Track-level presentation menus reach the active Media3 queue through the domain
`PlayNextUseCase` and `AddTrackToQueueUseCase`; album-level menus use the atomic,
order-preserving `PlayTracksNextUseCase` and `AddTracksToQueueUseCase`. The player
queue editor uses `MoveQueueItemUseCase`. `PlaybackController` is the single mutation boundary:

- **Play next** inserts at `currentMediaItemIndex + 1`.
- **Add to queue** appends at `mediaItemCount`.
- **Collection mutations** submit the complete ordered `MediaItem` list under one
  command lock, so album order cannot reverse or interleave with another mutation.
- **Move** preserves the active media item and playhead while changing its queue index.

`AudiophileSimpleBasePlayer.handleMoveMediaItems` must keep the adapter playlist,
original queue order, active index, and gapless next-track preload synchronized. Do not
rebuild or restart the active engine for a queue-only reorder.

---

## Hard rules for any playback change

- ❌ Never create a second ad-hoc playback stack outside the engine/manager pipeline.
- ❌ Never re-enable ExoPlayer audio **offload** (it caused the documented
  fresh-install/resume deadlocks; the native pipeline deliberately avoids it).
- ✅ Keep DSD/DoP, 24-bit→32-bit routing, and gapless behavior consistent with the
  decisions recorded in `BIT_PERFECT_LIMITATIONS.md`.
- ✅ When adding a JNI entry point or a new native sink, update both this file and
  [`native-audio.md`](native-audio.md).
