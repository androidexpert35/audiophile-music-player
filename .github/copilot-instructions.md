# Audiophile Music Player — Copilot Instructions

> **The canonical, always-current guide is [`/AGENTS.md`](../AGENTS.md) and the
> per-module docs in [`/docs/ai/`](../docs/ai/).** Read the module doc for the area you
> are editing before generating code. This file is a concise cheat-sheet; the deep rules
> (and their rationale) live in those docs so nothing drifts across tools.

You are an expert Android engineer working on an **offline-first, bit-perfect** music
player. It scans local audio via `MediaStore`, caches it in **Room**, and plays it
through a **dual playback engine** behind a foreground Media3 `MediaSessionService`:

- **Standard** = ExoPlayer (offload disabled), the battery-friendly default.
- **Audiophile** = in-process **FFmpeg (JNI) decoder** → `AudioTrack` (`FLAG_DIRECT`)
  **or** a raw **libusb UAC2** path driving USB DACs directly (PCM, DSD-over-PCM / native
  DSD). The two engines hot-swap at runtime; telemetry reports the real output format and
  whether the path is bit-perfect.

Audio correctness and the native/USB/DSD pipeline are the hard, load-bearing parts —
treat them with care. See [`/docs/BIT_PERFECT_LIMITATIONS.md`](../docs/BIT_PERFECT_LIMITATIONS.md).

## Stack
Kotlin 2.3 · Compose + Material 3 · Clean Architecture + MVVM + UDF · Hilt (KSP) ·
Coroutines/Flow · Media3 (session/common/exoplayer) · Room (6 migrations) ·
SharedPreferences settings · Retrofit/OkHttp/Gson (Deezer artwork, LRCLIB lyrics) ·
Coil 3 · C++17/CMake/FFmpeg/libusb (JNI). `minSdk 33`, `target/compile 36`.
Authoritative versions: `gradle/libs.versions.toml`.

## Golden rules (full detail in `docs/ai/`)
1. **Layers:** Presentation → Domain → Data. Domain is **pure Kotlin** (no Android
   imports). Framework types (`Cursor`, `Uri`, `Player`, `MediaItem`, `UsbDevice`, Room
   entities, JNI handles) **never** leave Data — map to domain models.
2. **UDF:** feature ViewModels extend `BaseViewModel<UiModel, UiEvent, UiEffect>`; one
   `StateFlow<UIState<UiModel>>` down, intents up via `onEvent`. Never expose
   `MutableStateFlow`; no business logic in Composables.
3. **Use cases:** one `operator fun invoke()`, **no `@Inject`** — wired in
   `di/UseCaseModule.kt`. ViewModels depend on use cases, not Data classes.
4. **Errors are values:** Data returns `Resource.Error(ResourceError.*)`; nothing throws
   into the UI. Use the `map/fold/onSuccess/onError` API.
5. **`callbackFlow` is mandatory** for every `register*/unregister*` callback (audio
   device, USB, content observer, Media3 `Player.Listener`): `trySend()` current state
   first, clean up in `awaitClose`.
6. **Never degrade the bit-perfect / USB / DSD path** — no resampling/mixing/gain unless
   explicitly intended. No ExoPlayer offload. No second playback stack.
7. **Room:** any entity change = version bump + explicit `Migration` registered in
   `AppModule` (never destructive migration).
8. No `!!`, no `GlobalScope`, no hardcoded `Dispatchers.IO` (inject `@IoDispatcher`),
   Compose only (no XML/DataBinding).
9. **One public type per file**; complete KDoc whose first line is the *business
   purpose*. UI state in `presentation/viewmodel/<feature>/`, screens in
   `presentation/screen/<feature>/`.
10. The player is an **overlay**, not a nav destination — open it with
    `openPlayerOverlay()`, never `navigateToRoute(PlayerScreen.route)`.

## Module docs — open the relevant one before coding
[`architecture`](../docs/ai/architecture.md) · [`domain`](../docs/ai/domain.md) ·
[`data`](../docs/ai/data.md) · [`playback`](../docs/ai/playback.md) ·
[`native-audio`](../docs/ai/native-audio.md) · [`presentation`](../docs/ai/presentation.md) ·
[`di`](../docs/ai/di.md) · [`testing`](../docs/ai/testing.md) ·
[`conventions`](../docs/ai/conventions.md)

## Verify (Windows / PowerShell)
```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest --tests "*SomeTest"
```
Prefer compile + focused unit tests while iterating; a full `:app:assembleDebug` also
runs the slow native CMake build.
