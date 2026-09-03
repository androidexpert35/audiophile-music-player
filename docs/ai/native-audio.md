# Native Audio — C++/JNI, FFmpeg, libusb, DSD/DoP

> Part of the Audiophile AI guide. Start at [`/AGENTS.md`](../../AGENTS.md).
> Kotlin-side orchestration is in [`playback.md`](playback.md). Platform ceilings and
> the rationale behind every constraint here are in
> [`/docs/BIT_PERFECT_LIMITATIONS.md`](../BIT_PERFECT_LIMITATIONS.md) — read it before
> changing anything in this layer.

The native layer (`app/src/main/cpp/`) is the heart of bit-perfect playback. It is
C++17, built with CMake, and exposed to Kotlin via JNI. **Audio correctness is
load-bearing here** — a careless change silently degrades sound quality without a
compile error.

---

## Pipeline (Step-15 architecture)

Legacy `AudioTrack` output is severed from the FFmpeg decode path. Decoded PCM/DSD now
flows:

```
FfmpegAudioDecoder (IAudioDecoder)
   └─ DecoderToRingBridge (pump thread)
        └─ SpscRingBuffer
             └─ IsoTransferPool ISO callback → USB DAC (libusb UAC2)
```

When an app-owned PCM enhancement is active, the transport branch is:

```
FFmpegDecoder → SUE / Hi-Res / explicit SoXR (Kotlin-owned loop, float32)
   └─ EngineSwapBridge.nativeWriteToRingBuffer
        └─ finite clamp + direct-USB volume + S32LE packing
             └─ SpscRingBuffer → IsoTransferPool → USB DAC (libusb UAC2)
```

A seek that exists before the pump attaches (track reload from a settings
toggle, resume after pause-time sink release) must be applied with
`nativeSeekUsbDecoder` **before** `nativeAttachUsbEngine`: the attach pre-fills
the ring from the decoder's current position and the ring is never cleared, so
a post-attach seek audibly replays the start of the track before jumping to the
target. Both `LibusbPcmAudioSink.play()` and `LibusbDsdAudioSink.play()` follow
this order.

`nativeStartPlayback()` prepares the ring and event thread but does not submit PCM
transfers into an empty ring. The native decoder-pump route submits after its prefill;
the enhanced writer submits after its first real-audio push. The enhanced selector
must negotiate an exact Type-I PCM four-byte subslot. The writer honours the
endpoint's advertised valid-bit resolution (for example 24-in-32) by clearing low
padding bits. The endpoint selector returns both `bSubslotSize` and
`bBitResolution` to Kotlin so telemetry reports the post-DSP USB precision rather
than the decoder's original source depth. It must never copy raw IEEE-float bytes
into an integer UAC endpoint.

Key source files:
- `i_audio_decoder.h` — pure C++ decoder interface.
- `ffmpeg_session.h` — the single non-JNI FFmpeg session API. Both output engines
  delegate open/read/seek/close to this boundary.
- `ffmpeg_audio_decoder.{h,cpp}` — thin `IAudioDecoder` adapter over `FfmpegSession`.
- `ffmpeg_bridge.cpp` / `ffmpeg_bridge_stub.cpp` — full vs. stub JNI bridge (see below).
- `cpu_affinity_policy.{h,cpp}` — decode-load classification (host-testable) and
  CPU cluster pinning / priority for the decode thread.
- `decoder_to_ring_bridge.{h,cpp}` — pump thread (decoder → ring buffer).
- `audio_gain.h` — the single quadratic UI-position → gain taper shared by the
  decoder pump (`set_volume`) and the enhanced float writer
  (`nativeWriteToRingBuffer`). Never reimplement the taper locally.
- `usb_handle_validation.h` — shared jlong handle validation (MTE/HWASan-safe
  tagged-pointer contract) with the per-bridge error-sentinel bounds; the
  bridges `static_assert` their error codes against it.
- `jni_global_ref.h` — RAII global-ref holder for native callbacks; releases the
  ref when the callback object is destroyed, not when it fires (EOF is not
  guaranteed to fire).
- `pcm_wire_formatter.{h,cpp}` / `usb_pcm_wire_format.h` — shared PCM gain,
  quantisation, endpoint-valid-bit rounding, and explicit little-endian packing for
  both the decoder pump and enhanced writer.
- `usb_playback_state.{h,cpp}` / `usb_producer_coordinator.{h,cpp}` — legal session
  transitions and single-producer ownership during start, seek, fallback, and teardown.
- `spsc_ring_buffer.cpp` — lock-free single-producer/single-consumer buffer.
- `usb_session_transport.{h,cpp}` — shared ring-buffer + libusb-event-thread
  bring-up/rollback used by both `nativeStartPlayback` and
  `nativeStartDsdPlayback` (transfer submission stays per-session).
- `usb_audio_bridge.cpp`, `usb_device_controller.cpp`, `usb_iso_transfer_pool.cpp`,
  `usb_teardown.cpp`, `libusb_event_thread.cpp` — libusb UAC2 transfer + lifecycle.
- `uac2_descriptor_parser.cpp`, `uac2_dsd_detector.cpp` — UAC2 parsing + DSD capability.
- `uac2_clock_control.{h,cpp}` — the ONLY encoder of the UAC2 SET_CUR clock
  transfer (bmRequestType `0x21`, Interface recipient), plus Clock Source
  discovery. The descriptor walk returns both the `bClockID` and the Audio
  Control `bInterfaceNumber` (composite BT/USB DACs do not keep Audio Control
  at interface 0). Session setup tries the parsed `bClockID` first and then the
  other Clock Source entities the descriptor **declares** (multi-clock DACs put
  an internal PLL next to an S/PDIF or word-clock source, and the first
  descriptor is not always the one feeding the USB stream); the UAC2 reference
  default (1) is used only when the walk finds no Clock Source at all —
  **never** add speculative IDs, and never append a bare 1 behind a parsed ID:
  a SET_CUR to a non-existent clock entity stalls EP0 and, on XMOS/FiiO
  firmware, wedges the control pipe until the DAC is re-plugged (field bug:
  distortion after rapid track skips). Each candidate is retried
  `kClockSetAttemptsPerId` times with a `kClockRetryDelayMs` pause, so a DAC
  still re-locking its PLL from the previous track's teardown is not mistaken
  for one refusing the rate. Only after that is a clock-set failure fatal for
  the libusb PCM session; the Kotlin factory then falls back to AudioTrack
  rather than stream against an unprogrammed PLL — a correct but audibly
  degraded outcome, which is why the transient must be retried rather than
  reported. Every clock transfer (setup, DSD switch, teardown soft-reset) must
  go through this module.
- `dop_formatter.cpp`, `native_dsd_formatter.cpp`, `dsd_playback_manager.cpp` —
  DoP encoding, native DSD_U32LE formatting, and native-DSD→DoP automatic fallback.
- `engine_swap_bridge.cpp` — JNI for `ACTION_USB_DEVICE_ATTACHED` hot-plug engine swap
  and the enhanced float32-to-S32LE direct-ring writer.
- `sue_bridge.cpp` — JNI implementation of the Sonic Upscaling Enhancer (SUE): builds an
  `libavfilter` lavfi graph (≥2× oversampled harmonic excitation tuned near the codec's
  expected low-pass cutoff, high-band contouring, stereo widening gated to
  AGGRESSIVE/MODERATE profiles only, soft low-pass, true-peak limiter, anti-alias
  downsample back to the target carrier) for lossy-compressed sources only. The
  intensity matrix lives both here (`PROFILE_MATRIX`) and in `SueProfileResolver.kt` —
  keep them in sync. Kotlin counterpart is `SueBridge`/`SueStage` — see
  [`playback.md`](playback.md).
- `audio_analysis_bridge.cpp` — JNI implementation of the **measurement-only**
  signal analysis, in two modes that share one session type. Class S builds
  `abuffer → aformat=sample_fmts=flt → aspectralstats → astats → abuffersink`
  over a few sampled windows; Class I builds `abuffer → aformat=sample_fmts=dbl
  → astats → ebur128 → abuffersink` over a whole decoded stream. Both read their
  results out of the output frame metadata dictionary. Neither is on the
  playback data path: they apply no gain, no resampling and no dithering, and
  nothing they produce reaches a sink. Kotlin counterparts are
  `data/playback/analysis/AudioAnalysisBridge` and
  `data/playback/analysis/AudioIntegralAnalysisBridge`.
- `audio_analysis_aggregator.{h,cpp}` — the pure aggregation and parsing logic
  behind the Class S mode (window/channel averaging, `-inf`/`nan` rejection, and
  the mid/side and inter-channel-correlation sums). JNI-, Android- and
  FFmpeg-free so it is covered by the host tests in `cpp/tests/`.
- `audio_integral_aggregator.{h,cpp}` — the same for the Class I mode: the
  sample peak, the clipping ratio and the flat-top run-length statistics counted
  straight from decoded samples, plus the cumulative loudness snapshots and the
  derived PLR. Likewise host-tested.

The Kotlin counterparts live in `data/playback/native_/` (`FFmpegDecoder`,
`AudioTrackSink`, `DsdPipelineInfo`, `PipelinePathReport`, …) and
`data/playback/usb/`.

Raw DSD seeks use a two-stage exact-position contract: `av_seek_frame` first
lands on the preceding DSF/DSDIFF packet boundary, then `ffmpeg_bridge.cpp`
converts the remaining timestamp delta to channel-aligned DSD bytes and trims
that prefix from the first normalized post-seek spill. Do not remove the trim or
seek accuracy will regress to the container block size.

### Native ownership and state model

`UsbDriverContext` is the session aggregate and owns the libusb handle, transfer
pool, ring, event thread, playback-state machine, producer coordinator, and optional
DSD fallback manager. Ownership is one-way: JNI entry points borrow the context;
the context owns transport resources; `UsbProducerCoordinator` holds the sole
non-owning producer pointer and is shared with that producer only to make detach safe.

The legal happy paths are:

```
Created → Configured → Priming → StreamingPcm
Created → Configured → Priming → StreamingNativeDsd
Created → Configured → Priming → StreamingDop
StreamingNativeDsd → SwitchingToDop → Priming → StreamingDop
any live state → Stopping → Stopped
```

Illegal cross-mode transitions fail closed. Teardown first quiesces the producer,
then drains/cancels transfers, joins the event thread, and only then destroys the
pool/ring/libusb resources.

### PCM volume and wire precision

The UI supplies a position in `[0, 1]`; native code applies one quadratic taper
(`gain = position²`). There is no hidden pre-amplifier. At position `1.0`, integer
PCM follows an exact integer-only unity path.

`PcmWireFormatter` is the only PCM-to-USB quantisation boundary:

- S16 is widened before gain. Under attenuation, precision is retained in the low
  bits of the 32-bit USB subslot instead of being requantised to `int16_t`.
- S32 and float enhanced output use the same rounding, clamp, valid-bit mask, and
  explicit little-endian writer.
- A 24-valid-bit endpoint clears only its eight declared padding bits; a 32-valid-bit
  endpoint retains all available attenuation precision.
- Buffer shape and capacity are validated before any destination byte is written.

Full volume can therefore be sample-exact for an unprocessed PCM path. Software
attenuation is not mathematically bit-perfect relative to the source, but it is
performed once at the maximum precision the negotiated DAC container exposes.

Because position `1.0` is the *only* bit-perfect position, the stored level is
part of the bit-perfect contract, not a cosmetic preference. Levels persist per
DAC under `KEY_USB_VOLUME_PCT_PREFIX + sha256(vendor:product:serial|name)`;
`UsbVolumeController.seedVolumePct()` seeds any device with no stored level from
the pre-1.1 global key (`LEGACY_KEY_USB_VOLUME_PCT`) before falling back to
`DEFAULT_USB_VOLUME_PCT`. Never drop that read: shipping the per-device keys
without it reset every upgrading listener to the default, which silently
inserted a −8.9 dB digital multiply into the one path whose purpose is to avoid
one.

### DSD transport selection and fallback

DoP-only DACs start directly in DoP with the correct `DSD bit rate / 16` carrier;
they no longer provoke a Native DSD STALL as a mode-selection mechanism. When both
targets exist, Native DSD starts at `DSD bit rate / 32`. A detected early STALL:

1. stops and joins the sole decoder producer;
2. drains all ISO callbacks before touching the ring or pool;
3. allocates a new transfer pool for the DoP endpoint at exactly twice the native
   USB frame rate and validates endpoint microframe bandwidth;
4. switches alt-setting, replaces the drained pool, seeds valid marker-bearing DoP
   silence, and restarts the same producer in DoP mode;
5. updates the state machine and Kotlin telemetry only after submissions succeed.

Incomplete DSD input frames are carried into the next decoder read; no tail bytes
are silently discarded. DoP marker phase is chained across every formatted chunk.

### Silence is format-dependent — never memset(0) an audio buffer

`IsoTransferPool` writes `silence_byte()` — not a literal `0` — wherever it has no
audio: the buffers pre-filled at allocation, the tail of a partial underrun, a
fully empty ring, and the pre-playback window before a ring is attached.

- PCM and DoP idle at `kPcmSilenceByte` (`0x00`), the pool default. A marker-less
  zero DoP frame drops the DAC back to PCM, which is silence.
- **Native DSD idles at `kNativeDsdSilenceByte` (`0x69`).** A 1-bit stream encodes
  amplitude as the density of ones, so a run of `0x00` is a full-scale *negative
  DC*, not silence. `nativeStartDsdPlayback` calls `set_silence_byte()` once the
  transport is known and before the cold-boot burst, which also re-primes the
  already-allocated buffers. Bit order is irrelevant — `0x96` is equally balanced.

Getting this wrong is not subtle: the pool submits all N transfers before the
decoder pump produces its first byte, so the DAC receives ~100 ms of DC and steps
its output rail. DACs with an internal DSD soft-mute (XMOS, e.g. FiiO KA5) hide
it; Cirrus-based dongles (Snowsky Tiny B) reproduce it as a loud tick at the start
of every DSD track. It is at full analogue scale whatever the volume setting says,
because `DecoderToRingBridge` deliberately never applies the volume multiply to a
1-bit stream.


### Measurement bridge (Class S) — reads the signal, never changes it

`audio_analysis_bridge.cpp` exists so a DSP stage can eventually be driven by
what a track actually contains instead of by its codec and bitrate. It is a
separate lavfi graph, opened by the analysis caller on `@IoDispatcher` with its
own `FFmpegDecoder` session — **never** on `BitPerfectPlaybackEngine`'s audio
HandlerThread, which has no slack for a filter graph.

JNI entry points (`data.playback.analysis.AudioAnalysisBridge`, one session per
handle, all calls on a single thread):

| Entry point | Contract |
|-------------|----------|
| `nativeOpen(sampleRateHz, channelCount, inputEncoding)` | Builds the graph; returns the handle or `0L` on failure (stub build included). |
| `nativeConsumeLastInitError()` | Drains the reason a `nativeOpen` returned `0L`. |
| `nativeFeed(handle, directBuffer, frames)` | Pushes one window; returns frames accepted, or a negative sentinel. |
| `nativeReadFeatures(handle, doubleArray)` | Flushes the graph and writes the 12-slot feature vector; `NaN` marks a value that was never measured. |
| `nativeClose(handle)` | Frees the session; safe with `0L`. |

The feature-vector slot order is the wire contract between
`AudioAnalysisFeatureIndex` in `audio_analysis_aggregator.h` and the index
constants in `AudioAnalysisBridge.kt` — change one and you must change the other.

**Filter and metadata names are verified against the shipped build, not
remembered.** `aspectralstats` (`win_size`, `overlap`, `measure=centroid+
rolloff+slope`), `astats` (`metadata`, `reset`, `measure_perchannel`,
`measure_overall`, and the `DC_offset` / `Noise_floor` / `RMS_level` constants)
and the key formats `lavfi.aspectralstats.%d.%s` (1-based channel) and
`lavfi.astats.%s` were all read out of the string table of the FFmpeg 7.1.4
`libavfilter.so` in `jniLibs/arm64-v8a/`. Re-check them against the binary
before adding a statistic; do not port option syntax from another FFmpeg
version.

Mid/side energy and inter-channel correlation are **not** taken from lavfi: no
filter in this build publishes them as metadata (`astats` measures channels in
isolation, and `aphasemeter` — the only filter reporting a correlation at all —
publishes a sign-correlation meter value and opens a second, video output pad).
They are accumulated exactly from the float samples the graph already forwards,
in `audio_analysis_aggregator`. Peak, loudness and clipping counts are integral
measures and are deliberately absent from **this mode** — they belong to the
Class I mode below, because sampling them biases them.

In the stub build (no FFmpeg provisioned) `ffmpeg_bridge_stub.cpp` answers the
same five symbols with the failure sentinel, so the APK assembles and the caller
records the track as not analysable instead of crashing.


### Measurement bridge (Class I) — the whole stream, once

The integral measures — sample peak, true peak, integrated loudness, PLR,
clipping — cannot be sampled. A peak seen in three windows is not the peak of
the track, and an underestimated peak is worse than no peak because a gain stage
will trust it. They therefore only ever come from a pass that saw every sample.

Where the Kotlin write loop already sees the audio those figures accumulate for
free while a track plays. On the pure bit-perfect libusb transport it does not:
the native pump owns the data and `LibusbPcmAudioSink.write()` /
`LibusbDsdAudioSink.write()` are no-ops. That path, and any track the user has
never played, is what this offline pass covers.

It shares `AnalysisCtx`, the graph builder, the feed path and the drain loop
with the Class S mode; only the chain, the sink sample format and the aggregate
differ. JNI entry points
(`data.playback.analysis.AudioIntegralAnalysisBridge`, one session per handle,
all calls on a single thread, **never** the audio HandlerThread):

| Entry point | Contract |
|-------------|----------|
| `nativeOpen(sampleRateHz, channelCount, inputEncoding)` | Builds the integral graph; returns the handle or `0L` on failure (stub build included). |
| `nativeConsumeLastInitError()` | Drains the reason a `nativeOpen` returned `0L`. |
| `nativeFeed(handle, directBuffer, frames)` | Pushes one block **in stream order**; returns frames accepted, or a negative sentinel. |
| `nativeReadFeatures(handle, doubleArray)` | Flushes the graph, closes any open flat-top run and writes the 10-slot feature vector; `NaN` marks a value that was never measured. |
| `nativeClose(handle)` | Frees the session; safe with `0L`. |

A handle carries the mode it was opened with, and `nativeReadFeatures` on the
wrong bridge is rejected rather than reinterpreting one aggregate as the other.

The slot order is the wire contract between `AudioIntegralFeatureIndex` in
`audio_integral_aggregator.h` and the index constants in
`AudioIntegralAnalysisBridge.kt` — change one and you must change the other.

**Filter and metadata names are verified against the shipped build, not
remembered.** `ebur128` and its `metadata`, `peak` (constants `none` / `sample`
/ `true`) and `framelog` (constant `quiet`) options, the `astats` constants
`Peak_level` and `Flat_factor`, and the keys `lavfi.r128.I`,
`lavfi.r128.true_peak`, `lavfi.r128.sample_peak`,
`lavfi.astats.Overall.Peak_level` and `lavfi.astats.Overall.Flat_factor` were
all read out of the string table of the FFmpeg 7.1.4 `libavfilter.so` in
`jniLibs/arm64-v8a/`. `ebur128` republishes that dictionary on every completed
100 ms block with running values, so the last snapshot before EOF is the
whole-stream figure — the same "cumulative, keep the newest" rule as `astats` at
`reset=0`. Its peak keys carry a **linear amplitude**, not dB; the filter's own
end-of-stream log is what applies 20·log10 to them.

The sample peak, the clipping ratio and the flat-top run lengths are **not**
taken from lavfi. `astats` reports `Abs_Peak_count` as the number of samples at
the *observed* maximum rather than at full scale — on a quiet track that is a
count of its own loudest samples, which would read as a catastrophic clipping
ratio — and `Flat_factor` is a single scalar, not a run-length distribution.
They are counted exactly in `audio_integral_aggregator`, against a full-scale
threshold of 0.9999 (FFmpeg normalises integer PCM by the negative full-scale
value, so a 16-bit source's largest positive sample is 32767/32768 and a
threshold of exactly 1.0 would report a clipped 16-bit master as clean). `astats`
stays in the chain and its overall peak is logged beside the counted one, so a
disagreement between the graph and the sample pass is visible rather than silent.

Cost is the open question this pass raises: it decodes the whole file, so
`FFmpegIntegralSampler` reports the wall-clock time and the frames covered for
every pass, both in its result and in a log line tagged `TrackIntegralAnalysis`.
Whatever schedules a library sweep must read that number from a real device
rather than assume one.

The stub build answers the five integral symbols with the same failure sentinel:
no crash, and above all no invented loudness figure.

---

## Build (CMake — `app/src/main/cpp/CMakeLists.txt`)

- **Two-mode build, by feature detection:**
  - If `app/src/main/jniLibs/<ABI>/libavformat.so` exists → the **full bridge** compiles
    (FFmpeg + Step-15 + USB sources), linking `avformat avcodec avfilter swresample
    avutil libusb`.
  - Otherwise → the **stub** (`ffmpeg_bridge_stub.cpp`) compiles so the APK still
    assembles; decoding fails at runtime with a clear `FFmpegDecoderException`.
- **ABIs shipped:** `arm64-v8a`, `armeabi-v7a`, `x86_64` (set in `app/build.gradle.kts`).
- **STL:** `c++_shared`, `-std=c++17`, hidden visibility, function/data sections.
  Release adds `-O3 -flto`. Warnings: `-Wall -Wextra -Werror=return-type`.
- **SoX resampler is embedded in FFmpeg** (`--enable-libsoxr`) and used via the lavfi
  `aresample=...:resampler=soxr:precision=33` graph. There is **no** separate soxr JNI
  bridge — resampling lives inside the decoder's lavfi graph.
- **Single Source of Truth layout:** shared libs → `jniLibs/<ABI>/lib<name>.so`,
  headers → `cpp/include/` (ABI-agnostic). Drop `.so` files in and rebuild; CMake
  auto-detects.

> The `prebuilt/` FFmpeg tree is intentionally **excluded from version control**. CI is
> expected to rebuild FFmpeg from pinned sources (audio-only config) so upstream
> security patches are tracked. See `app/src/main/cpp/prebuilt/README.md`.

---

## Rules for native changes

- ✅ **Keep the data path sample-exact.** No hidden resampling, gain, dithering, or
  channel remap on the bit-perfect/USB path beyond the explicitly intended FFmpeg lavfi
  stages (SoX resample when a rate change is genuinely required; SUE only for lossy).
- ✅ **Match the existing DSD discipline.** DSD framing (DoP vs. native DSD_U32LE,
  bit-order per DAC) is subtle; follow `dsd_playback_manager` / `native_dsd_formatter`
  and the comments there. Wrong framing produces loud noise on real hardware.
- ✅ **Respect the cold-boot / teardown sequences** for USB endpoints
  (`usb_teardown.cpp`, the ISO cold-boot logic) — they exist to avoid
  `LIBUSB_ERROR_BUSY` and audible glitches on first play / device re-attach.
- ✅ Keep JNI signatures in sync on both sides; update the Kotlin `native_`/`usb`
  classes when you change an `extern "C"` entry point.
- ✅ Add host-side tests under `cpp/tests/` for pure wire-format/state logic. Run
  them with ASan+UBSan as well as the Android CMake build.
- ✅ Treat direct transport and bit-perfect content as separate facts: SUE, Hi-Res
  Dynamic Remaster, and explicit resampling modify samples even though libusb still
  bypasses AudioFlinger.
- ✅ Log through the Android `log` lib with the established path tags; keep diagnostics
  rich (this layer is hard to debug on-device) but don't log full file paths/URIs.
- ❌ Don't add new third-party native deps without updating CMake's SSoT layout and the
  ABI list, and confirming licensing.
- ❌ Don't reintroduce ExoPlayer offload or a parallel output path.

When you change this layer, update [`playback.md`](playback.md),
[`/docs/BIT_PERFECT_LIMITATIONS.md`](../BIT_PERFECT_LIMITATIONS.md) (if a limitation
changes), and the CMake header comment if the source set changes.
