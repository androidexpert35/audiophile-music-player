# Architecture & Layering

> Part of the Audiophile AI guide. Start at [`/AGENTS.md`](../../AGENTS.md).

Clean Architecture with strict, one-directional dependencies:

```
Presentation  ─▶  Domain  ◀─  Data
   (Compose,       (pure        (Room, MediaStore,
    ViewModels)     Kotlin)      Media3, FFmpeg/USB,
                                 Retrofit)
```

Domain is the stable core. Presentation and Data both depend on Domain; they never
depend on each other. Dependencies point **inward**.

Single Gradle module (`:app`). Layering is enforced by package convention and the
rules below, not by separate Gradle modules.

---

## Package map

Root package: `com.androidexpert35.audiophilemusicplayer`

```
data/
  local/            Room: AudiophileDatabase, dao/, entity/, converter/
  mapper/           Framework/entity → domain mappers, LrcParser, persistence mappers
  playback/         Media3 service, controllers, telemetry, dual-engine + native sinks
    dsd/            DoP encoder, DSD capability detection
    engine/         AudioEngineManager + Standard/Audiophile engines + SUE/HiRes coordinators
    native_/        Kotlin side of the FFmpeg/AudioTrack JNI pipeline
    service/        AudioPlaybackService (MediaSessionService)
    usb/            libusb sinks, UAC2 descriptor parsing, USB routing/volume
  remote/           Retrofit api/ + dto/ (Deezer, LRCLIB)
  repository/       *RepositoryImpl, SettingsPreferences/SettingsRepositoryImpl
  scanner/          MediaStoreScanner, DsdFileScanner, MetadataFallbackReader

di/                 Hilt modules (App, Dispatcher, Network, Coil, Repository, UseCase, Navigation)

domain/
  model/            audio/, common/ (PlaybackResourceError), indexing/, library/,
                    lyrics/, playback/, track/
  repository/       Repository interfaces (contracts only)
  usecase/          One responsibility per use case, pure Kotlin

presentation/
  activity/         MainActivity
  error/            AudiophileUiErrorMapper
  navigation/       AppNavigator, AppRoutes, graph builder, overlay/
  screen/
    common/         AppShell, components/ (shared chrome)
    <feature>/      library, player, search, settings, onboarding (+ components/)
  theme/            Color, Dimens, Motion, Shape, Theme, Type
  viewmodel/        <feature>/ holding *ViewModel + *UiModel/*UiEvent/*UiEffect
```

> Note: UI **state types** live under `presentation/viewmodel/<feature>/`, while the
> screens and composables live under `presentation/screen/<feature>/`. Keep that split.
> Generic `Resource`, state, `BaseViewModel`, navigation, route, and base-screen types
> come from CoreUI; see [`coreui.md`](coreui.md).

---

## Layer rules

### Domain (pure Kotlin)
- ❌ No Android framework imports (`Context`, `ContentResolver`, `Cursor`, `Log`,
  `Uri`, Media3, Room, Retrofit types).
- ✅ Only Kotlin stdlib, coroutines, and domain models.
- ✅ Repository **interfaces** defined here; implementations live in Data.
- ✅ Use cases orchestrate; one public `operator fun invoke()`, no `@Inject`.

### Data
- ✅ Implements domain repository interfaces.
- ✅ Owns Room, MediaStore scanning, the playback engines, the native JNI bridge, USB
  I/O, and remote API calls.
- ✅ Maps framework/entity/DTO types into domain models via explicit mappers.
- ✅ Catches exceptions and returns `Resource.Error(ResourceError.*)`.
- ❌ Never expose `Cursor`, `Uri`, `Player`, `MediaItem`, `UsbDevice`, `Retrofit`
  services, Room entities, or raw JNI handles outside Data.

### Presentation
- ✅ ViewModels depend on use cases (or repository interfaces when a use case would be
  a pass-through) and extend `BaseViewModel`.
- ✅ Composables receive immutable state and emit events; no business logic.
- ✅ Use `AppBaseScreen` for loading/content/error orchestration and `AppShell` for the
  persistent mini-player + bottom navigation chrome.

---

## The dual-engine model (orientation only — details in `playback.md`)

`AudioEngineManager` is a `@Singleton` that holds **both** `StandardEngine` (ExoPlayer)
and `AudiophileEngine` (FFmpeg + AudioTrack/USB), keeps exactly one active, mirrors its
`StateFlow`s, and hot-swaps under a `Mutex` while preserving URI + playhead +
play-when-ready. It implements `AudioPlayerEngine` itself so the rest of the app
depends on one stable type. Default engine at startup is **Standard**.

---

## When adding a feature

1. **Domain** — model(s), repository interface, use case(s).
2. **Data** — implement the repository / scanner / engine integration, add mapper(s),
   wire DI (`RepositoryModule`, `UseCaseModule`, `AppModule` as needed).
3. **Presentation** — `UiModel` / `UiEvent` / `UiEffect`, ViewModel, Screen, components.
4. **Navigation** — add a typed destination to `AppRoutes` and register it through
   the CoreUI graph extensions if it is a real destination. The player is an
   **overlay**, not a nav destination; request it through `PlayerOverlayManager`.
5. **Tests** — unit-test the use case / ViewModel logic.
6. **Docs** — update the relevant `docs/ai/*.md`.
