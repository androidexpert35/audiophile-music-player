# Presentation — Compose, MVVM/UDF, Navigation, Theming

> Part of the Audiophile AI guide. Start at [`/AGENTS.md`](../../AGENTS.md).

Material 3 + Jetpack Compose, MVVM with strict Unidirectional Data Flow. Target 60 fps;
transport controls and seeking must feel immediate.

---

## UDF & BaseViewModel

Every feature ViewModel extends
[`BaseViewModel<UI_TYPE, UI_EVENT, UI_EFFECT>`](../../app/src/main/java/com/androidexpert35/audiophilemusicplayer/presentation/screen/base/BaseViewModel.kt).

| Generic | Purpose |
|---------|---------|
| `UI_TYPE` | Immutable model rendered by the Composable |
| `UI_EVENT` | Sealed user intents flowing **up** |
| `UI_EFFECT` | One-shot effects flowing **down** (snackbars, nav prompts) |

```
Composable ──UI_EVENT──▶ ViewModel ──UseCase──▶ Repository/Data
Composable ◀─UIState──── (BaseVM)  ◀─Resource── 
           ◀─UI_EFFECT──
```

`BaseViewModel` already provides: one `StateFlow<UIState<UI_TYPE>>` (`uiState`), a
buffered `SharedFlow<UI_EFFECT>` (`uiEffect`), `onEvent` → `handleEvent`, error mapping
(`handleError`/`processErrorResource`), `launchUiStateUpdate` (loading→success→error),
`updateUiData`/`setSuccessState`/`setLoadingState`, `executeAsync`, `emitEffect`, and
all navigation helpers.

```kotlin
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val pausePlaybackUseCase: PausePlaybackUseCase,
    private val resumePlaybackUseCase: ResumePlaybackUseCase,
    private val observePlaybackStateUseCase: ObservePlaybackStateUseCase,
    private val observeAudioTelemetryUseCase: ObserveAudioTelemetryUseCase,
    private val observeQueueStateUseCase: ObserveQueueStateUseCase,
    navigationManager: NavigationManager,
) : BaseViewModel<PlayerUiModel, PlayerUiEvent, PlayerUiEffect>(navigationManager) {

    override fun handleEvent(event: PlayerUiEvent) = when (event) {
        is PlayerUiEvent.Pause -> executeAsync { pausePlaybackUseCase() }
        is PlayerUiEvent.Resume -> executeAsync { resumePlaybackUseCase() }
        /* … */
    }
}
```

Rules:
- ✅ `@HiltViewModel` + constructor-injected use cases; pass `navigationManager` to the
  base.
- ✅ Build the screen model by `combine`-ing domain flows into one immutable snapshot.
- ✅ `UiModel` is immutable (`val` only, `copy()`); default values so the first frame
  renders.
- ❌ Never expose `MutableStateFlow`; never subclass `ViewModel()` directly for a
  feature; never do playback/scan/search logic in a Composable.

State types live under `presentation/viewmodel/<feature>/` as separate files:
`*ViewModel.kt`, `*UiModel.kt`, `*UiEvent.kt`, `*UiEffect.kt` (+ feature helpers like
`LibrarySortOrder`, `LyricsState`). Shared base wrappers (`UIState`, `UIStatus`,
`UIError`) live in `presentation/screen/base/`.

---

## Screen structure

```kotlin
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppBaseScreen(uiState = uiState) { model ->
        PlayerContent(model = model, onEvent = viewModel::onEvent)
    }
}

@Composable
private fun PlayerContent(model: PlayerUiModel, onEvent: (PlayerUiEvent) -> Unit) {
    // Stateless UI composed from components/.
}
```

- ✅ `AppBaseScreen` orchestrates loading/content/error; `SystemAppearance` aligns
  system bars on immersive screens.
- ✅ The only private composable allowed in a `*Screen.kt` is the stateless `*Content`.
- ✅ Reusable sub-composables live in the feature's `components/` folder, one per file.
- ❌ Never declare a public `data class` / `sealed` / `enum` inside a screen file.

---

## Shared shell & chrome (`presentation/screen/common/`)

- `AppShell` is the global floating container hosting the **mini-player** + **bottom
  navigation**; it wraps the nav host. Reuse `MiniPlayerBar`, `AppBottomNavBar`,
  `AppTopBar`, `ShellBottomPanel`, `AudioOutputControl`, `UsbVolumeOverlay`,
  `FeaturePlaceholderScreen` — don't recreate chrome per screen.
- Respect `LocalShellBottomPadding` so content clears the floating panel.
- `ThrottledClick` guards rapid transport taps; use it instead of ad-hoc debouncing.
- `TrackOptionsMenu` owns the shared play-next, add-to-queue, and optional
  add-to-playlist menu used by track rows. `TrackReorderState` owns the leading-handle
  drag gesture shared by playlist editing and the active queue editor.
- The favorites collection uses the normal playlist row, navigation, detail, playback, and editing
  pipeline, but is excluded from the add-to-playlist picker because likes are changed through heart
  actions. `PlaylistArtwork` is the shared preview boundary: standard playlists render a cover
  mosaic, while `PlaylistKind.FAVORITES` renders the reserved gradient heart.

The Library filter row is driven by `LibraryContentType` and the persisted Library Sections
settings. Genres, Years, and Composers are metadata facets derived from the local indexed tracks;
their rows show a track count and an arrow entry point. Tapping one opens the exact standard Songs
surface, filtered to that metadata value: it retains the Songs sort selector, list/grid toggle, and
track actions, and uses only the filtered list as its playback queue. Settings can hide any section
(while retaining at least one visible) and drag-reorder the filter row; both changes apply to the
live Library immediately.

Both section preference flows read SharedPreferences synchronously on subscription, so in
`LibraryViewModel` they emit during `init` — before the IO-backed catalogue and Room streams have
produced any UI model. That observer must therefore seed an empty `LibraryUiModel` rather than skip
the emission: SharedPreferences never re-emits an unchanged value, so a dropped first emission
leaves the saved order lost until the next settings edit. It patches `UIState.data` through
`updateUiData` so the initial catalogue load keeps owning the loading status.

`TrackArtworkCard` is the shared artwork-first track card for the artist popular-songs row and
the three-column Songs grid. It resolves local or MediaStore album artwork, retains the music-note
fallback, and uses the existing HD badge for lossless tracks; do not create a parallel grid card.

---

## Navigation

- ViewModels navigate **only** via `NavigationManager` (from `BaseViewModel`):
  `navigateToRoute`, `navigateUp`, `popBackStack`, `navigateAndClearBackstackTo`.
- `CoreUiNavigator` bridges `NavigationCommand`s to the `NavController`; the outer
  `AppNavigator` retains ownership of the app shell and persistent player layer.
- Routes and arguments are defined with CoreUI typed primitives in `AppRoutes`.
- ⚠️ **The player is an overlay, not a nav destination.** Library, Search, and
  `MainActivity` request it through `PlayerOverlayManager`; never synthesize a player
  route or mutate the navigation back stack.
- ❌ Never touch `NavController` from a ViewModel or call `navController.navigate()`
  from arbitrary composables.

### Launch graph gate (startup cost)

`MainActivity.resolveStartDestination()` picks the launch graph **before** the NavHost
is composed and passes it to `AppNavigator` as an `AppStartDestination`
(`Deciding`/`Onboarding`/`Main`). When media permission is granted **and**
`IsMediaLibraryIndexedUseCase()` is true, the app enters through `AppRoutes.MainRoot`
(start = `MainFlow`) instead of `AppRoutes.Root` (start = `Onboarding`), so the
onboarding screen is never composed and its enter transition never overlaps the
library's first composition. While `Deciding` (the brief async index read)
`AppNavigator` paints only a themed background so nothing flashes.

`AppRoutes.Onboarding` is not exclusively a first-launch destination: `SettingsViewModel`
also navigates there (a plain forward `navigateToRoute(AppRoutes.Onboarding.route)`, same
primitive `PlayerViewModel` uses) after `AddMusicFolderUseCase`/`RemoveMusicFolderUseCase`
succeeds, so a folder add/remove is visibly re-indexed instead of happening silently.
`OnboardingViewModel.initialize()`/`resumeAfterPermission()` re-evaluate state on every
entry, so with permission and a folder already granted this lands straight in
`OnboardingState.Scanning` and returns to `MainFlow` via the same
`OnboardingUiEffect.NavigateToHome` path used on first launch — no separate screen or
ViewModel was added for this. `LibraryViewModel`'s MediaStore `ContentObserver` still
independently re-indexes on external content changes that don't go through Settings
(e.g. files copied onto the device without touching the folder list).

`AppNavigator` also defers composing the player overlay (`PlayerViewModel` flow
collection + `BlurredBackground` GPU layer) until two frames after launch, then keeps it
pre-warmed off-screen for the jank-free slide-in — an early `ACTION_VIEW` open forces it
in immediately.

---

## Performance & motion

- Stable, immutable `UiModel`s; pass `viewModel::onEvent` (a stable method ref).
- `LazyColumn`/`LazyVerticalGrid` **only for dynamic/large lists**, always with a
  stable `key`. **Fixed layouts** (player, settings, placeholders) use
  `Column + verticalScroll(rememberScrollState())`.
- Use `derivedStateOf`/`remember` to avoid needless recomposition; collect effects in a
  `LaunchedEffect` over `viewModel.uiEffect`.
- Animate via the central `MotionTokens` in `presentation/theme/Motion.kt` — no ad-hoc
  duration constants. Theming tokens live in `theme/` (`Color`, `Dimens`, `Shape`,
  `Type`); use them instead of magic values.
- Add light+dark `@Preview`s for UI-heavy reusable components with realistic sample
  data; previews are optional for wiring-only changes.

---

## Lyrics & player components

The player has a rich component set (`screen/player/components/`): artwork, blurred
background, seek bar (+ `SeekBarStateResolver`), playback controls, queue dialog,
telemetry dialog (`telemetrydialog/`), and a full **lyrics sheet** (synced + plain,
driven by `LyricsState` and the `Lyrics`/`LyricLine` domain models). Keep new player UI
in this folder and stateless.

`PlayerOutputMenu` is the explicit ownership escape hatch. **Release DAC** preserves
the current queue/playhead and confirms completion through a snackbar; **Exit and
release DAC** emits `PlayerUiEffect.ExitApplication` only after the same release use
case succeeds, then the screen removes the activity task.

The telemetry dialog calls the last app-owned PCM stage **Engine Output**. When the
active route is Bluetooth, the output card uses the neutral **System managed** state
and never guesses the codec's final sample rate or bit depth. Keep that Bluetooth
card compact: device, transport label, system-managed badge, and the app-output
disclaimer only; do not add expandable routing diagnostics. Confirmed USB/direct
paths retain the gold bit-perfect treatment. For every non-Bluetooth route, the
collapsed output card leads with one plain-language verdict (confirmed bit-perfect,
direct, Android-managed, or checking); individual direct-path and bit-perfect flags
belong under **Technical details** rather than in the primary summary.
