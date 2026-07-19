# Audiophile Music Player

**A bit-perfect, offline-first Android music player built for people who can hear the
difference — and want proof, not marketing.**

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![Min SDK](https://img.shields.io/badge/minSdk-33-blue)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](#)
[![Architecture](https://img.shields.io/badge/architecture-Clean%20%2B%20MVVM%20%2B%20UDF-informational)](#)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Free forever](https://img.shields.io/badge/price-free%2C%20no%20ads%2C%20no%20catch-yellow)](AUTHOR.md)

Audiophile decodes audio itself, drives USB DACs directly at the protocol level, and
tells you — in real time — exactly what path your music took to reach your ears. No
subscription, no premium tier, no ads. Built by [an audiophile, for audiophiles](AUTHOR.md).

---

## Table of contents

- [Why this is different](#why-this-is-different)
- [Highlights](#highlights)
- [The sound engine](#the-sound-engine)
- [Performance & battery](#performance--battery)
- [Full feature set](#full-feature-set)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Known limitations](#known-limitations)
- [Documentation map](#documentation-map)
- [Contributing](#contributing)
- [License](#license)

---

## Why this is different

Most Android music players hand your audio to `AudioTrack` and hope the platform
doesn't touch it. It usually does — Android's system mixer resamples, mixes, and
volume-processes almost everything that passes through it, silently.

Audiophile doesn't hope. It **bypasses that mixer entirely** when a USB DAC is connected,
talking to the DAC over raw USB isochronous transfer from native code — the same layer a
desktop audio driver would use, running on your phone. When bypass genuinely isn't
possible, the app tells you so, out loud, in a telemetry panel, instead of quietly
pretending everything is fine.

Read the full technical breakdown in **[docs/FEATURES.md](docs/FEATURES.md)**.

---

## Highlights

- 🎯 **Dual playback engine** — a battery-friendly ExoPlayer engine for everyday
  listening, and a custom **FFmpeg (JNI) decoder** engine for bit-perfect / hi-res /
  DSD playback, hot-swappable without losing your place in the track.
- 🔌 **Total Android bypass over USB** — claims class-compliant USB DACs directly via
  `libusb` and drives them with raw UAC2 isochronous transfers, skipping AudioFlinger
  completely. See [`docs/FEATURES.md §2`](docs/FEATURES.md#2-total-android-bypass--the-usb-dac-path).
- 🎼 **DSD & DoP support** — native DSD passthrough or DoP encoding when the DAC
  supports it, with a transparent PCM fallback (clearly flagged) when it doesn't.
- 🩹 **Lossy Audio Restoration** — a real FFmpeg `libavfilter` DSP chain that
  synthesizes the harmonics and stereo detail lost to MP3/AAC/Opus/Vorbis/WMA
  compression, with its intensity scaled per-track to how much the source codec and
  bitrate actually damaged. Pure math, no "AI enhance" black box, zero-cost bypass on
  clean or lossless files. [Deep dive →](docs/FEATURES.md#4-lossy-audio-restoration)
- 🎚️ **Hi-Res Dynamic Remaster** — ×2 oversampling (up to 96 kHz) plus an upward
  dynamic expander that gives over-compressed lossless masters (the "Loudness War"
  problem) back up to ~2.5 dB of transient punch, with peak-derived headroom and a
  true-peak limiter so the restored dynamics never clip.
  [Deep dive →](docs/FEATURES.md#5-hi-res-dynamic-remaster)
- ⚡ **Adaptive CPU scheduling** — the decode thread reads your phone's real core
  topology and pins itself to the *right* cluster: efficiency cores for everyday files
  (maximum battery), a measured step up only for DSD / >96 kHz, and **never** the Prime
  core — which would spike power and heat for headroom the audio never needs.
  [Deep dive →](docs/FEATURES.md#7-performance--battery)
- 📊 **Honest, live telemetry** — every track shows its actual decoded format, actual
  output path, and a genuine `isBitPerfect` flag that's only ever `true` when the OS
  confirmed it — never assumed.
- 📴 **Fully offline** — scans your device's local library via `MediaStore`, no account,
  no cloud dependency, no network requirement to play a single file.
- 💛 **Free. No ads. No premium tier.** See [`AUTHOR.md`](AUTHOR.md) for why.

---

## The sound engine

| Stage | Applies to | What it does |
|---|---|---|
| **USB DAC direct path** | Any class-compliant USB DAC | Bypasses AudioFlinger completely via raw `libusb` UAC2 transfer |
| **Android 14 bit-perfect mixer** | Non-USB direct output, API 34+ | Requests `setPreferredMixerAttributes(BIT_PERFECT)` from the OS |
| **DSD / DoP** | `.dsf` / `.dff` sources | Native DSD passthrough or DoP encoding, PCM fallback if unsupported |
| **Lossy Audio Restoration** | MP3, AAC, Opus, Vorbis, WMA | Synthesizes harmonics + stereo detail; intensity scales to codec/bitrate, bypassed when clean or lossless |
| **Hi-Res Dynamic Remaster** | FLAC, WAV, ALAC (≤48 kHz / <24-bit) | ×2 oversampling + dynamic expansion against over-compressed masters |
| **Adaptive 48 kHz resample** | 44.1 kHz sources, no USB DAC | libsoxr VHQ resample, replacing AudioFlinger's lower-quality default |

Every one of these stages is **self-gating**: it knows exactly when it shouldn't touch
your audio, and the telemetry panel tells you which one (if any) applied to the track
you're listening to right now. Full explanation, including the actual DSP filter chains
and the codec/bitrate matrix behind the intensity tuning, in
**[docs/FEATURES.md](docs/FEATURES.md)**.

---

## Performance & battery

High-fidelity audio has a reputation for wrecking battery life. Audiophile treats
efficiency as a first-class feature — the goal is a signal path that's *inaudible* in
its cost, not one that pins your CPU and cooks your phone.

### One switch to turn it all off

The whole audiophile pipeline is opt-in. By default the app runs the **Standard**
(ExoPlayer) engine: zero DSP, no native decode thread, no USB claiming — a normal,
battery-friendly music player. Flip on the **Audiophile** engine only when you actually
want bit-perfect / hi-res / DSD, and flip it back off to reclaim the battery. The swap
is hot: your track keeps playing from the exact same position, no restart.

### Smart per-core scheduling

When the Audiophile engine *is* running, the native decode thread doesn't just spawn
and let the kernel guess. It inspects the SoC's actual cluster layout (via
`cpuinfo_max_freq`) and classifies the current stream by real decode cost, then binds
itself to the cluster that delivers the audio with the least energy:

| Decode load | What it covers | Where it runs |
|---|---|---|
| **LIGHT** | Lossless / lossy PCM ≤ 96 kHz (FLAC, MP3, AAC, ALAC…) | **Always** the efficiency cluster — trivial for any core, maximum battery savings |
| **HEAVY** | DSD (on-the-fly PCM decimation) or PCM > 96 kHz (192 / 352.8 / 384 kHz) | Efficiency cluster on modern all-big SoCs (≥ 1.8 GHz); one measured step up to the mid tier only on older silicon that would otherwise underrun |

Two rules hold across every device:

- **The Prime core is never targeted.** On tri-cluster SoCs (Snapdragon 8 Gen 3,
  Dimensity 9300/9400, Tensor G3…), pinning audio to the Cortex-X core forces the DVFS
  governor to hold it at peak clock for every wakeup — a large power and thermal
  regression for a workload that needs a few hundred MHz. Audiophile deliberately stays
  off it.
- **Homogeneous CPUs are left alone.** If there's only one performance tier, there's
  nothing to optimize, so the app doesn't fight the kernel scheduler — it just requests
  a real-time-friendly thread priority and gets out of the way.

The net effect: a standard FLAC album plays entirely on the little cores at a few
hundred milliwatts, while a 384 kHz or DSD256 file still gets enough silicon to decode
cleanly — without ever paying the Prime-core tax.

Full breakdown of the tier-selection logic, the real SoC cluster layouts it's calibrated
against, and the engine toggle in **[docs/FEATURES.md §7](docs/FEATURES.md#7-performance--battery)**.

---

## Full feature set

Beyond the sound engine, Audiophile is a complete, modern music player:

- Local library scan & indexing (MediaStore → Room cache), with albums, artists, and
  tracks views
- Playlists — create, reorder, search within, and manage tracks
- Playback queue — play next, add to queue, drag-to-reorder, all without interrupting
  playback
- Liked songs and recently-played history
- Synced lyrics (LRCLIB) and artwork enrichment (Deezer)
- Gapless playback (true gapless when format matches between tracks)
- USB DAC format override, live device status, and hot-plug handling
- Full Compose + Material 3 UI, light/dark aware

---

## Architecture

Clean Architecture + MVVM, strict unidirectional data flow:

```
Presentation (Compose, ViewModels)
        │  intents / UiState
        ▼
   Domain (pure Kotlin use cases, no Android imports)
        │
        ▼
     Data (repositories, Room, MediaStore, native audio, USB)
        │
        ▼
  Native (C++17 / JNI — FFmpeg, libusb, UAC2, DSD/DoP)
```

- **Domain is pure Kotlin.** No `Cursor`, `Uri`, `Player`, `UsbDevice`, or JNI handle
  ever crosses into it.
- **UDF only.** Every feature ViewModel exposes one `StateFlow<UiState>`; intents flow
  up through a single `onEvent`.
- Every native `register*`/`unregister*` callback (USB hot-plug, audio device changes,
  Media3 listeners) is wrapped in a `callbackFlow` — no leaked callbacks, no missed
  cleanup.

The full architectural reference — package map, layering rules, DI wiring, and the
per-module deep dives — lives in **[AGENTS.md](AGENTS.md)** and **[docs/ai/](docs/ai/)**.
It's written as the single source of truth for both human contributors and AI coding
agents working on this repo.

---

## Tech stack

| Category | Choice |
|---|---|
| Language | Kotlin 2.3 (`minSdk 33`, target/compile 36) |
| UI | Jetpack Compose + Material 3 |
| Architecture | Clean Architecture + MVVM + UDF |
| DI | Hilt (constructor injection) + KSP |
| Async | Coroutines + Flow (`StateFlow` / `SharedFlow` / `callbackFlow`) |
| Media | AndroidX Media3 (`session`, `common`, `exoplayer`) |
| Persistence | Room (6 migrations) + `SharedPreferences` |
| Networking | Retrofit + OkHttp + Gson (Deezer artwork, LRCLIB lyrics) |
| Images | Coil 3 |
| Native | C++17, CMake, FFmpeg (`--enable-libsoxr`), libusb, JNI |
| Testing | JUnit 4, MockK, Turbine, Compose UI Test |

---

## Getting started

> The project builds on **Windows, macOS, and Linux**. Commands below are shown for both
> PowerShell (`.\gradlew.bat`) and a POSIX shell (`./gradlew`) — use whichever matches
> your OS.

### Prerequisites

- Android Studio (current stable) with NDK + CMake components installed
- A class-compliant USB DAC if you want to exercise the direct-USB path (the app runs
  fine without one — it just uses the Android HAL / Standard engine instead)
- A GitHub **Personal Access Token** with `read:packages` scope, to pull the
  [`CoreUI`](https://github.com/androidexpert35/CoreUI) dependency from GitHub Packages

### Setup

1. Clone the repo:
   ```bash
   git clone https://github.com/androidexpert35/Audiophile-Music-Player.git
   ```
2. Add your GitHub Packages credentials to `local.properties` (git-ignored, never
   commit these):
   ```properties
   gpr.user=<your-github-username>
   gpr.key=<your-personal-access-token>
   ```
3. Build:
   ```powershell
   # Windows (PowerShell)
   .\gradlew.bat :app:compileDebugKotlin
   ```
   ```bash
   # macOS / Linux
   ./gradlew :app:compileDebugKotlin
   ```

### Notes on the native build

- The native (FFmpeg/libusb) build is **two-mode**: if `.so` files are present under
  `app/src/main/jniLibs/<ABI>/`, the full audio bridge compiles. Otherwise, a stub
  compiles so the project still builds, and decoding fails at runtime with a clear
  error. See [`docs/ai/native-audio.md`](docs/ai/native-audio.md) for the FFmpeg build
  configuration.
- A full `:app:assembleDebug` also triggers the CMake native build and is noticeably
  slower — prefer `compileDebugKotlin` + a focused unit test while iterating:
  ```powershell
  # Windows (PowerShell)
  .\gradlew.bat :app:testDebugUnitTest --tests "*SomeTest"
  ```
  ```bash
  # macOS / Linux
  ./gradlew :app:testDebugUnitTest --tests "*SomeTest"
  ```

---

## Known limitations

Android does not let *any* user-space app guarantee bit-perfect output on every device
— and this project would rather say so plainly than oversell itself. OEM mixer
restrictions (ColorOS/OnePlus/Realme confirmed), the advisory nature of
`FLAG_DIRECT`, 24-bit packed PCM inconsistencies, and the practical limits of DSD on
Android are all documented in detail, with the reasoning behind each decision, in
**[docs/BIT_PERFECT_LIMITATIONS.md](docs/BIT_PERFECT_LIMITATIONS.md)**.

---

## Documentation map

| Doc | Covers |
|---|---|
| [`docs/FEATURES.md`](docs/FEATURES.md) | Plain-language deep dive into the sound engine — the doc to read first |
| [`AUTHOR.md`](AUTHOR.md) | Who built this, and why it's free |
| [`AGENTS.md`](AGENTS.md) | Canonical engineering guide — architecture, golden rules, module index |
| [`docs/ai/`](docs/ai/) | Per-module engineering references (architecture, data, playback, native-audio, presentation, di, testing, conventions) |
| [`docs/BIT_PERFECT_LIMITATIONS.md`](docs/BIT_PERFECT_LIMITATIONS.md) | The honest list of platform ceilings |

---

## Contributing

Issues and pull requests are welcome — bug reports, DAC compatibility reports, and
feature ideas especially. Before touching code, skim
[`AGENTS.md`](AGENTS.md) and the [`docs/ai/`](docs/ai/) guide for the area you're
changing; the golden rules there (pure-Kotlin domain layer, `callbackFlow` for every
callback API, never degrading the bit-perfect path) are treated as non-negotiable
throughout the codebase.

Note that building the project requires GitHub Packages credentials for the private
`CoreUI` dependency — see [Getting started](#getting-started).

---

## License

Released under the [MIT License](LICENSE). Use it, fork it, learn from it.
