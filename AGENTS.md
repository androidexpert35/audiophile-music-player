# AGENTS.md — Audiophile Music Player

> Canonical engineering guide for AI coding agents (Codex, Claude Code, Copilot, etc.)
> working on this repository. This file is the **entry point**; the deep, per-module
> rules live in [`docs/ai/`](docs/ai/). Read the module doc that matches the area you
> are touching **before** writing code.

---

## What this project is

Audiophile is an **offline-first, bit-perfect Android music player** for audiophiles.
It scans the device's local audio library via `MediaStore`, caches it in a Room
database, and plays it back through a **dual playback engine** behind a foreground
Media3 `MediaSessionService`:

- **Standard engine** — ExoPlayer (offload disabled), battery-friendly default.
- **Audiophile engine** — an in-process **FFmpeg (JNI) decoder** feeding either an
  Android `AudioTrack` (with `FLAG_DIRECT`) or a **raw libusb UAC2 path** that drives
  USB DACs directly, including **DSD-over-PCM (DoP)** and native DSD passthrough.

The two engines hot-swap at runtime. A real-time telemetry layer reports the actual
decoded/output format and whether the path is genuinely bit-perfect.

This is **not** a generic CRUD app. The hard parts are audio correctness, the native
pipeline, and USB/DSD device negotiation. Treat the bit-perfect guarantees as
load-bearing — see [`docs/ai/native-audio.md`](docs/ai/native-audio.md) and
[`docs/BIT_PERFECT_LIMITATIONS.md`](docs/BIT_PERFECT_LIMITATIONS.md).

---

## Tech stack (authoritative versions live in `gradle/libs.versions.toml`)

| Category | Choice |
|----------|--------|
| Language | Kotlin 2.3.x (`minSdk 33`, `target/compile 36`) |
| UI | Jetpack Compose + Material 3 |
| Architecture | Clean Architecture + MVVM + UDF |
| DI | Hilt (constructor injection) + KSP |
| Async | Coroutines + Flow (`StateFlow` / `SharedFlow` / `callbackFlow`) |
| Media | AndroidX Media3 (`session`, `common`, `exoplayer`) |
| Persistence | Room (6 migrations) + `SharedPreferences` settings |
| Networking | Retrofit + OkHttp + Gson (Deezer artwork, LRCLIB lyrics) |
| Images | Coil 3 |
| Native | C++17, CMake, FFmpeg (`--enable-libsoxr`), libusb, JNI |
| Testing | JUnit 4, MockK, Turbine, Compose UI Test |

---

## Module guides — read before editing

| Area you're touching | Read this |
|----------------------|-----------|
| Layering, package map, where code belongs | [`docs/ai/architecture.md`](docs/ai/architecture.md) |
| Domain models, use cases, `Resource` / errors | [`docs/ai/domain.md`](docs/ai/domain.md) |
| Repositories, Room, MediaStore scan, remote APIs, settings | [`docs/ai/data.md`](docs/ai/data.md) |
| Playback engines, Media3 service, telemetry, USB routing (Kotlin) | [`docs/ai/playback.md`](docs/ai/playback.md) |
| C++/JNI, FFmpeg, libusb, DSD/DoP, bit-perfect ceiling | [`docs/ai/native-audio.md`](docs/ai/native-audio.md) |
| Compose UI, ViewModels, navigation, theming | [`docs/ai/presentation.md`](docs/ai/presentation.md) |
| CoreUI public APIs and integration rules | [`docs/ai/coreui.md`](docs/ai/coreui.md) |
| Hilt modules & qualifiers | [`docs/ai/di.md`](docs/ai/di.md) |
| Tests + build/verify commands | [`docs/ai/testing.md`](docs/ai/testing.md) |
| KDoc, file separation, forbidden patterns, `callbackFlow` | [`docs/ai/conventions.md`](docs/ai/conventions.md) |

---

## Global golden rules (always apply)

These are the non-negotiables. The module docs expand each one.

1. **Respect the layers.** Presentation → Domain → Data. Domain is pure Kotlin (no
   Android imports). Framework types (`Cursor`, `Uri`, `Player`, `MediaItem`,
   `UsbDevice`, JNI handles) never leave the Data layer.
2. **UDF only.** Feature ViewModels extend `BaseViewModel<UiModel, UiEvent, UiEffect>`.
   State flows down via one `StateFlow<UIState<UiModel>>`; intents flow up through
   `onEvent`. Never expose `MutableStateFlow`; never put business logic in Composables.
3. **Use cases are pure.** One `operator fun invoke()`, **no `@Inject`** — wired in
   `di/UseCaseModule.kt`. ViewModels depend on use cases (or, sparingly, repository
   interfaces), never on Data-layer classes directly.
4. **Errors are values.** Data wraps failures in `Resource.Error(ResourceError.*)`;
   never let storage/playback/network exceptions bubble into the UI uncaught.
5. **`callbackFlow` is mandatory** for every `register*`/`unregister*` callback API
   (audio device, USB, content observer, Media3 `Player.Listener`, …). Emit current
   state with `trySend()` first; clean up in `awaitClose`.
6. **Never break bit-perfect.** Do not insert resampling, mixing, gain, or format
   coercion into the audiophile/USB path without an explicit reason. The native
   pipeline and telemetry assume sample-exact data flow.
7. **No `!!`, no `GlobalScope`, no hardcoded `Dispatchers.IO`** in new code (inject
   `@IoDispatcher`). No XML layouts/DataBinding — Compose only.
8. **Document public APIs with complete KDoc** whose first line states *business
   purpose*, not the symbol name. One public type per file.

See [`docs/ai/conventions.md`](docs/ai/conventions.md) for the full forbidden-patterns table.

---

## Build & verify (Windows / PowerShell)

```powershell
# Compile the app module (fast correctness check)
.\gradlew.bat :app:compileDebugKotlin

# Run unit tests (all, or a focused class)
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --tests "*OnboardingViewModelTest"

# Full debug assemble (also runs the native CMake build)
.\gradlew.bat :app:assembleDebug
```

Notes:
- The native build is two-mode: if FFmpeg `.so` files are present under
  `app/src/main/jniLibs/<ABI>/`, the full bridge compiles; otherwise a stub compiles
  and decoding fails at runtime with a clear `FFmpegDecoderException`. See
  [`docs/ai/native-audio.md`](docs/ai/native-audio.md).
- Prefer a `compileDebugKotlin` + targeted unit test over a full assemble when
  iterating; the native build is slow.

---

## Working agreement for agents

- **Match the surrounding code.** This codebase has dense, deliberate KDoc and inline
  comments that explain *why*. New code should read like its neighbours.
- **Make the smallest change that fits the architecture.** Don't introduce new
  patterns (Room-as-core rewrites, alternate playback stacks, network search, etc.)
  unless explicitly asked.
- **Keep this doc set current.** If you add a module, a Room migration, a new engine
  coordinator, or a JNI entry point, update the relevant `docs/ai/*.md` file in the
  same change.
- When unsure which layer something belongs to, re-read
  [`docs/ai/architecture.md`](docs/ai/architecture.md) before guessing.
