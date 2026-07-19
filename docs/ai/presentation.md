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
- The favorites collection uses the normal playlist row, picker, navigation, detail, playback,
  and editing pipeline. `PlaylistArtwork` is the shared preview boundary: standard playlists
  render a cover mosaic, while `PlaylistKind.FAVORITES` renders the reserved gradient heart.

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

The telemetry dialog calls the last app-owned PCM stage **Engine Output**. When the
active route is Bluetooth, the output card uses the neutral **System managed** state
and never guesses the codec's final sample rate or bit depth. Keep that Bluetooth
card compact: device, transport label, system-managed badge, and the app-output
disclaimer only; do not add expandable routing diagnostics. Confirmed USB/direct
paths retain the gold bit-perfect treatment. For every non-Bluetooth route, the
collapsed output card leads with one plain-language verdict (confirmed bit-perfect,
direct, Android-managed, or checking); individual direct-path and bit-perfect flags
belong under **Technical details** rather than in the primary summary.
