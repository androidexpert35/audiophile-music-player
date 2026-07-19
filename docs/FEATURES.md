# The Sound Engine — A Deep Dive

> This document explains **how** Audiophile's audio pipeline actually works, in plain
> language, for people who care about the "why" behind the DSP — not just the feature
> list. Everything described here is real, shipping code, not marketing copy. Where it
> helps, the exact source files are linked so you can go verify it yourself.
>
> For the engineering-reference version of this document (package names, JNI
> signatures, threading rules), see [`docs/ai/native-audio.md`](ai/native-audio.md) and
> [`docs/ai/playback.md`](ai/playback.md).

---

## Table of contents

1. [The dual-engine model](#1-the-dual-engine-model)
2. [Total Android bypass — the USB DAC path](#2-total-android-bypass--the-usb-dac-path)
3. [DSD & DoP](#3-dsd--dop)
4. [Lossy Audio Restoration](#4-lossy-audio-restoration)
5. [Hi-Res Dynamic Remaster](#5-hi-res-dynamic-remaster)
6. [Adaptive 48 kHz resampling](#6-adaptive-48-khz-resampling)
7. [Performance & battery](#7-performance--battery)
8. [Telemetry — showing its work](#8-telemetry--showing-its-work)
9. [Known ceilings](#9-known-ceilings)

---

## 1. The dual-engine model

Audiophile ships two playback engines behind one player UI, and hot-swaps between them
at runtime without losing your playhead position:

| Engine | Backing | When it's used |
|---|---|---|
| **Standard** | ExoPlayer (Media3), offload disabled | Default. Battery-friendly, zero setup. |
| **Audiophile** | A custom in-process **FFmpeg (JNI) decoder**, feeding either an `AudioTrack` opened with `FLAG_DIRECT`, or a **raw libusb UAC2 path** straight to a USB DAC | Toggled on for lossless/hi-res listening or whenever a USB DAC is connected. |

Flipping the switch doesn't restart your song: the manager captures the active URI,
playhead position, and play/pause state, tears down the old engine, and reloads the
same track into the new one at the same position — under a mutex, so a double-tap can't
leave two engines fighting for audio focus.

Everything downstream — telemetry, USB routing, DSP — only ever runs on the
**Audiophile** engine. The Standard engine exists so the app is still a perfectly good
everyday music player without asking anything of your hardware.

---

## 2. Total Android bypass — the USB DAC path

This is the feature that makes Audiophile different from "just another music player":
when you plug in a USB DAC, Audiophile does not politely ask Android to play nice with
it. It **claims the USB device directly** and talks to it at the protocol level.

### Why that's necessary

On stock Android, every app's audio — no matter how carefully decoded — gets funneled
through **AudioFlinger**, the system mixer. AudioFlinger exists to mix multiple apps'
audio together, apply system volume, and resample everything to one shared clock rate
(usually 48 kHz). That's exactly what a bit-perfect signal chain cannot survive: your
192 kHz/24-bit FLAC gets silently resampled down to whatever the mixer feels like, and
there's no user-space API that reliably stops it.

Android 14 introduced `AudioManager.setPreferredMixerAttributes(BIT_PERFECT)`, which
*can* ask the mixer to step aside — Audiophile applies this automatically via
[`UsbBitPerfectRouter`](../app/src/main/java/com/androidexpert35/audiophilemusicplayer/data/playback/usb).
But it's a request, not a guarantee, and several OEM skins (ColorOS/OPPO/OnePlus/Realme
confirmed empirically) strip it out of their HAL entirely.

### What Audiophile does instead

For class-compliant USB DACs, Audiophile bypasses the Android audio stack **completely**:

```
FFmpeg decoder → lock-free ring buffer → libusb isochronous transfer → USB DAC
```

This path is implemented in native C++ (`usb_audio_bridge.cpp`,
`usb_device_controller.cpp`, `usb_iso_transfer_pool.cpp`) using `libusb` to claim the
UAC2 (USB Audio Class 2) interface directly and push isochronous transfers straight to
the DAC's endpoint. AudioFlinger never sees this stream — it isn't a mixer client, isn't
resampled, isn't volume-ducked by the system, and isn't touched by any OEM DSP daemon
running on the application processor. The transfer happens entirely in the kernel's UAC2
driver, which is the one part of the audio stack no vendor skin can quietly intercept.

The Kotlin side (`data/playback/usb/`) handles the parts that *do* need Android's
cooperation — requesting `UsbManager` permission, parsing UAC2 descriptors to learn
which sample rates/bit depths/channel counts the DAC actually advertises, and watching
for hot-plug/hot-unplug — then hands the negotiated format down to native code, which
owns the actual audio.

Volume on this path is software-applied with one **quadratic taper**. At 100% the
native gain is exact unity; below 100% telemetry reports high-precision attenuation,
not source-bit identity.

### The result

The in-app telemetry only reports `isBitPerfect = true` when **either** the Android 14+
mixer preference was confirmed by the OS **or** this raw USB path is active — never on
faith. See [§7](#7-telemetry--showing-its-work).

---

## 3. DSD & DoP

Android has no `AudioTrack` encoding for DSD — it doesn't officially exist as a concept
on the platform. Audiophile's native layer parses UAC2 descriptors to detect DSD-capable
DACs and then does one of two things, whichever the DAC actually supports:

- **DoP (DSD-over-PCM):** wraps DSD bitstream data inside a PCM frame using the standard
  DoP marker convention, over the raw USB path above, decoded as plain PCM.
- **Native DSD passthrough:** formats the stream as `DSD_U32LE` and sends it straight
  through, when the DAC's descriptors confirm native DSD support.

If the connected output can't carry DSD in either form (e.g. the device's own analog
jack, or a DAC that doesn't advertise DSD support), Audiophile falls back to decoding the
`.dsf`/`.dff` file to high-rate PCM (typically 88.2 kHz/32-bit) so the track still
plays — the telemetry UI is explicit that this fallback happened, because at that point
it is no longer literal DSD passthrough. See `dsd_playback_manager.cpp` and
`native_dsd_formatter.cpp` for the framing logic, and
[`dialog_hires_remaster` / DSD telemetry strings](../app/src/main/res/values/strings.xml)
for the exact copy shown to the user.

---

## 4. Lossy Audio Restoration

**In-app name:** *Lossy Audio Restoration* (internal codename: **SUE**, the Sonic
Upscaling Enhancer — you'll see this name in the source and logs).

### What it's for

MP3, AAC, Ogg Vorbis, Opus, and WMA all achieve their file-size savings the same way:
they throw away information permanently, mostly high-frequency detail and precise
stereo placement, based on a psychoacoustic model of what a listener is least likely to
notice. That data is gone — it cannot be un-deleted. What SUE does is not restoration in
the literal sense; it's a **mathematically-driven reconstruction** of plausible harmonic
content and stereo width, built from an `libavfilter` (FFmpeg) DSP graph. No neural
network, no "AI enhance" black box — every stage is a named, inspectable audio filter.

From the app's own in-product explanation:

> Lossy compression (MP3, AAC, Opus…) permanently deletes high-frequency detail and can
> narrow the stereo image to save space. This engine does not use "AI magic" — it is
> purely mathematical DSP that scales its intensity to how much damage the source codec
> and bitrate actually did: it synthesizes the missing high-frequency harmonics, widens
> the stereo field on the most heavily compressed sources (where it has collapsed the
> most), and smooths intermodulation artifacts with a gentle low-pass, then closes with a
> true-peak limiter so every added stage stays clipping-free. Clean, high-bitrate lossy
> files may need little or no processing and are automatically bypassed — as are lossless
> files, to preserve bit-perfect playback.

### The pipeline

Built per-track in [`sue_bridge.cpp`](../app/src/main/cpp/sue_bridge.cpp) as an
`AVFilterGraph`, in this order:

1. **Conditional pre-exciter upsampling** to the negotiated target carrier rate.
2. **Harmonic excitation** — synthesizes plausible high-frequency content tuned near the
   source codec's known low-pass cutoff.
3. **High-band contouring** — gentle EQ shaping so the reconstructed band sits naturally
   against the real one.
4. **Guarded Mid-Side stereo widening** — gated to the more aggressive intensity
   profiles only, so it's never applied where it would exaggerate compression artifacts.
5. **Soft apodizing low-pass filter** — strips the intermodulation and aliasing
   products the excitation stage adds above ~19.5 kHz.
6. **True-peak limiter**, placed after the low-pass so it guards the combined additive
   energy, then an **anti-alias downsample** (libsoxr VHQ) back to the target carrier
   rate when the chain oversampled.
7. **Float32 output** (`aformat=sample_fmts=flt`) — the stage feeds the rest of the
   pipeline in float, so there is no final dither step (dithering into float32 is a
   no-op).

### It knows when to leave your music alone

This is the part that matters as much as the DSP itself: **SUE only ever touches lossy
sources.** For FLAC, WAV, ALAC, or DSD, the native context is never even created — the
stage is a genuine zero-cost no-op, not just "disabled." That gate is enforced in
[`SueStage.kt`](../app/src/main/java/com/androidexpert35/audiophilemusicplayer/data/playback/engine/audiophile/SueStage.kt)
and mirrored natively, and it's covered by
[`SueProfileResolverTest`](../app/src/test/java/com/androidexpert35/audiophilemusicplayer/data/playback/engine/audiophile/SueProfileResolverTest.kt).

It also knows when a lossy file is *already* clean enough that "restoring" it would just
be coloration. Intensity is resolved per-track from a **codec-tier × bitrate matrix**
([`SueProfileResolver.kt`](../app/src/main/java/com/androidexpert35/audiophilemusicplayer/data/playback/engine/audiophile/SueProfileResolver.kt)),
calibrated against measured encoder behavior (e.g. LAME's MP3 low-pass filter defaults,
which run from ~16.4 kHz at 96 kbps up to ~20.5 kHz at 320 kbps):

| Codec tier | ≤96 kbps | 97–128 kbps | 129–192 kbps | 193–256 kbps | ≥257 kbps |
|---|---|---|---|---|---|
| **MP3 / WMA** (oldest psychoacoustic model) | Aggressive | Aggressive | Light | Subtle | **Bypass** |
| **AAC-LC / Vorbis** | Aggressive | Moderate | Moderate | Subtle | **Bypass** |
| **AAC-HE** (already has SBR) | Subtle | Subtle | Subtle | **Bypass** | **Bypass** |
| **Opus** (full-band from ~64–96 kbps) | Subtle | **Bypass** | **Bypass** | **Bypass** | **Bypass** |

At high enough bitrates, or for codecs whose psychoacoustic model already preserves
(or synthesizes, in AAC-HE's case) the full band, SUE resolves straight to `BYPASS` —
applying DSP to an already-transparent source would be a pure deviation from the
lossless reference, so it doesn't.

---

## 5. Hi-Res Dynamic Remaster

**In-app name:** *Hi-Res Dynamic Remaster.*

This is SUE's counterpart for the *other* side of the format spectrum: lossless sources
(FLAC, WAV, ALAC) that are technically intact but were mastered loud and flat. From the
app's own explanation dialog:

> Modern music is often over-compressed in the studio to sound louder (the Loudness
> War), sacrificing dynamic punch. This engine works only on lossless sources (FLAC,
> ALAC, WAV…) that aren't already native hi-res — those are left completely untouched.
> On eligible tracks it acts as a mastering chain: it applies a gentle upward expansion
> to transients — never compression — so drum hits and peaks regain up to ~2.5 dB of the
> punch flattened by loudness-war mastering, while quiet passages stay untouched;
> oversamples the source (44.1→88.2 kHz or 48→96 kHz) so the expansion and limiting
> stages have clean headroom to work in; and closes with a true-peak limiter so the added
> dynamics and the oversampling never clip, with the initial gain automatically tucked in
> based on the track's own peak level.

### The mechanics

- **Oversampling:** integer-multiple upsampling — 44.1 kHz → 88.2 kHz, 48 kHz →
  96 kHz — staying inside the same clock family as the source (no fractional-ratio
  resampling artifacts). Sources already above 48 kHz pass through at their native rate
  unchanged.
- **Upward dynamic expansion:** a `compand`-based expander (unity below −30 dB, so the
  noise floor and ambience stay untouched; ~1:1.09 upward slope from −30 to −3 dB) that
  gently restores transient punch — never compression — that heavy studio limiting
  flattened out, adding up to ~2.5 dB to peaks while quiet passages are left alone. The
  expansion runs at the native rate; the oversampling then gives the limiter clean
  headroom to catch the inter-sample peaks it produces.
- **Peak-derived headroom (ReplayGain-aware):** reads the file's own
  `REPLAYGAIN_TRACK_PEAK` tag (via
  `FFmpegDecoder.getReplayGainDb`) to drive the pre-expansion gain stage, clamped to
  [−6, 0] dB, defaulting to −3 dB when no tag is present — enough headroom that the
  expansion curve's peaks land around −0.5 dBFS instead of clipping. Note this uses only
  the ReplayGain *peak* tag: `REPLAYGAIN_TRACK_GAIN` is intentionally ignored, since
  applying it would drop the output by 8–12 dB on hot masters and defeat the point.

### It also knows when to stay out of the way

Two conditions gate this stage, both enforced in
[`HiResRemasterSettingsCoordinator.kt`](../app/src/main/java/com/androidexpert35/audiophilemusicplayer/data/playback/engine/HiResRemasterSettingsCoordinator.kt):

1. The source must be lossless — lossy tracks are SUE's territory, never this one's.
2. The source must **not** already be native hi-res: files at 24-bit or above, or above
   48 kHz, bypass unconditionally, because oversampling something already beyond the
   floor this stage targets would be pointless.

SUE and Hi-Res Remaster are **mutually exclusive per track** — a file is processed by at
most one of the two, decided automatically from its own format, never something you
have to figure out yourself.

---

## 6. Adaptive 48 kHz resampling

When no USB DAC is connected and a source isn't already at 48 kHz, Android's built-in
mixer resampler (the AudioFlinger default, a 160:147 rational resampler) is
noticeably lower quality than what's achievable in user space. Rather than hand that job
to the OS, Audiophile resamples 44.1 kHz sources to 48 kHz itself using **libsoxr in Very
High Quality mode** (`aresample=resampler=soxr:precision=33:cutoff=0.91`, with
triangular high-pass dithering), embedded directly inside the FFmpeg build. 48 kHz
sources pass through untouched; hi-res sources above 48 kHz are left at their native
rate. This routing decision is made by a single, pure, device-agnostic function —
[`StaticOutputRateResolver`](../app/src/main/java/com/androidexpert35/audiophilemusicplayer/data/playback/StaticOutputRateResolver.kt)
— and only ever runs after the path classifier confirms no DSD, USB DAC, SUE, or Hi-Res
stage already owns the resampling decision for this track.

---

## 7. Performance & battery

High-fidelity audio has a reputation for wrecking battery life. Two independent
mechanisms keep that cost in check: the engine toggle you control, and a CPU
scheduling policy the app applies automatically.

### The engine toggle is the big lever

As covered in [§1](#1-the-dual-engine-model), the entire audiophile pipeline — native
decode thread, DSP stages, USB claiming — only exists behind the **Audiophile** engine
toggle
([`SetAudiophileEngineEnabledUseCase`](../app/src/main/java/com/androidexpert35/audiophilemusicplayer/domain/usecase/SetAudiophileEngineEnabledUseCase.kt)).
With it off, the app runs the **Standard** ExoPlayer engine: no native decode thread, no
DSP, no USB claim — an ordinary, battery-friendly player. Toggling it back on is a hot
swap, not a restart: the currently playing track reloads into the new engine at the
same position.

### Adaptive CPU scheduling for the decode thread

When the Audiophile engine *is* active, the native decode thread doesn't spawn and let
the kernel guess where to run it. On startup it reads the SoC's actual cluster layout
from `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`, classifies the current
stream's decode cost, and pins itself to the cluster that gets the job done for the
least energy — implemented in
[`cpu_affinity_policy.cpp`](../app/src/main/cpp/cpu_affinity_policy.cpp) /
[`.h`](../app/src/main/cpp/cpu_affinity_policy.h), deliberately free of FFmpeg/JNI types
so the classification logic is host-unit-testable.

**Step 1 — classify the load.** `classify_decode_load()` is a pure function of the
stream, not the device:

| `DecodeLoad` | Covers |
|---|---|
| **LIGHT** | Lossless/lossy PCM ≤ 96 kHz — standard FLAC, MP3, AAC, ALAC playback |
| **HEAVY** | DSD (on-the-fly PCM decimation), or any PCM stream > 96 kHz (192/352.8/384 kHz) |

**Step 2 — enumerate real cluster tiers**, not a naive "top half of cores" heuristic.
The policy reads every online CPU's `cpuinfo_max_freq`, collapses them into the set of
distinct frequency tiers, and reasons about *that* topology:

- **1 tier (homogeneous SoC)** → no pinning at all; the kernel scheduler is already
  fine, so the policy gets out of the way.
- **LIGHT load** → **always** the lowest tier, on every device. Decoding a FLAC or MP3
  is trivial for any core, so there's no reason to wake anything bigger.
- **HEAVY load, lowest tier ≥ 1.8 GHz** → stay on the lowest tier. Modern all-big SoCs
  (Snapdragon 8 Elite Gen 5, Dimensity 9500) ship an "efficiency" cluster fast enough to
  decimate DSD or push 384 kHz PCM without help — so the battery win still applies.
- **HEAVY load, weak LITTLE tier, 2-cluster SoC** → escalate to the big cluster; with
  only two tiers there's no Prime core to avoid.
- **HEAVY load, weak LITTLE tier, ≥3-cluster SoC** → land on the *middle* tier (a
  lower-biased median of the non-Prime, non-LITTLE tiers), never the top one.

**The one rule that holds everywhere: the Prime core is never a pinning target on
3-tier-or-finer SoCs.** Binding an audio thread to a Cortex-X-class core forces the DVFS
governor to hold it at its peak operating point for every wakeup — a large, needless
battery and thermal cost for a workload that needs a few hundred MHz of headroom. A
safety net re-checks the computed target after every branch and re-routes it off the
Prime tier if any of the frequency math above accidentally lands there. This is why the
policy is written against real silicon, not a generic "big vs. LITTLE" split:

| SoC family | Cluster layout |
|---|---|
| Snapdragon 8 Gen 3 | 1× Cortex-X4 (Prime) + 5× A720 + 2× A520 |
| Dimensity 9300 / 9400 | 1× Prime + 3× sub-Prime + 4× A720 |
| Tensor G3 | 1× X3 + 4× A715 + 4× A510 |
| Snapdragon 8 Elite Gen 5 / Dimensity 9500 | All-big — even the "lowest" tier runs ≥ 2 GHz |

Alongside cluster pinning, `configure_current_thread_priority()` raises the decode
thread's scheduling priority (`setpriority(PRIO_PROCESS, 0, -16)`) so it gets
real-time-friendly treatment from the kernel without needing the biggest core to do it.
Both calls fail soft: a missing `cpuinfo_max_freq` (some kernels don't expose it), a
`sched_setaffinity` rejection, or a `setpriority` failure are logged and the thread
simply runs unpinned — never a crash, never a playback interruption.

The pure classification and tier-selection logic is covered by
[`native_core_tests.cpp`](../app/src/main/cpp/tests/native_core_tests.cpp) on the host,
independent of any real device's `/sys` layout.

---

## 8. Telemetry — showing its work

Every claim this document makes is meant to be independently checkable from inside the
app itself, in real time, on your own hardware. The player's telemetry panel
(`AudioTelemetryCollector` → `ObserveAudioTelemetryUseCase`) reports, per track:

- The actual decoded sample rate, bit depth, channel count, and codec.
- The actual output path taken (USB direct / Android HAL / Bluetooth / built-in), not
  just what was requested.
- Whether SUE or Hi-Res Remaster is active on this specific track, and *why* if it
  isn't (lossless bypass, clean-bitrate bypass, disabled, unavailable).
- DSD transport mode (native / DoP / unsupported) when applicable.
- A genuine `isBitPerfect` flag — which, per the rule in
  [`playback.md`](ai/playback.md#telemetry), is only ever `true` when the Android 14+
  mixer preference was actually confirmed by the OS, or the raw USB path above is
  active. It is never set optimistically.

This exists so "bit-perfect" isn't a marketing word here — you can watch the pipeline
report exactly what it's doing to your music, track by track, and catch it if it's
wrong. If your device's OEM silently blocks the bit-perfect mixer path (see below), the
telemetry says so instead of pretending otherwise.

---

## 9. Known ceilings

No user-space Android app can promise perfection on every device — and pretending
otherwise would contradict the whole point of this section. The full, specific list of
platform limitations (OEM mixer restrictions, why `FLAG_DIRECT` is only ever a hint, the
24-bit-packed PCM inconsistency, DSD's practical limits, gapless tiers, and more) is
documented in detail in
[`/docs/BIT_PERFECT_LIMITATIONS.md`](BIT_PERFECT_LIMITATIONS.md). Read it if you
want the unfiltered version of what "bit-perfect on Android" can and can't mean.
