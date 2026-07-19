# Conventions — Kotlin, KDoc, File Layout, Forbidden Patterns

> Part of the Audiophile AI guide. Start at [`/AGENTS.md`](../../AGENTS.md).

Cross-cutting rules that apply everywhere. Match the existing code's density and style.

---

## Kotlin

- **Immutable by default** — `val`, `data class`, `copy()`. Avoid mutable shared state
  outside controlled internals.
- **Null safety** — safe calls, elvis, sealed handling. **Never `!!`.**
- **Coroutines** — inject dispatchers (`@IoDispatcher` etc.); use `viewModelScope` in
  ViewModels and the `@ApplicationScope` scope for process-lifetime work. **No
  `GlobalScope`.** Structured concurrency for parallel loads.
- **Flow** — `combine` domain flows into one screen snapshot; collect in Compose with
  lifecycle-aware APIs; keep state one-way (VM → UI).
- **Constants over magic values** — strings/dimens/durations go in resources or the
  shared token objects (`MotionTokens`, `Dimens`, `SettingsPreferences`, etc.).

---

## `callbackFlow` is MANDATORY for callback APIs

Any `register*(callback)` / `unregister*(callback)` API **must** be wrapped in a
`callbackFlow`. Direct callback registration outside a Flow is forbidden — no
exceptions. This matters a lot here: audio device, USB attach/detach, content observer,
and Media3 `Player.Listener` are all callback-based.

```kotlin
private fun observeBluetoothAudioRouting(): Flow<Boolean> = callbackFlow {
    val audioManager = getSystemService(AudioManager::class.java)
    fun isBtActive(): Boolean = /* … */ false

    trySend(isBtActive()) // emit current state BEFORE registering

    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>) { trySend(isBtActive()) }
        override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>) { trySend(isBtActive()) }
    }
    audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

    awaitClose { audioManager?.unregisterAudioDeviceCallback(callback) } // automatic cleanup
}
```

Rules:
- ✅ Emit current state with `trySend()` **before** registering, so the first collector
  gets a value immediately.
- ✅ Clean up in `awaitClose { unregister() }` — never manual `onCreate`/`onDestroy`
  wiring.
- ✅ Use `trySend()` (non-suspending, thread-safe) inside callbacks, never `send()`.
- ✅ Register on `Handler(Looper.getMainLooper())` when the API needs a `Handler`.
- ✅ Return `Flow<T>`; let the caller pick scope/dispatcher and `launchIn(scope)`.
- ❌ Never poll a callback-backed API with `while(true){ delay() }`.

---

## Error handling

- ✅ Catch exceptions in Data; return `Resource.Error(ResourceError.*)`. Prefer
  `runCatching { … }.fold(...)` / `getOrElse`.
- ✅ UI receives errors via `UIState.error` or a `UI_EFFECT`. Pick the right
  `ResourceError` variant (see [`domain.md`](domain.md)).
- ✅ Use the functional API (`map`, `fold`, `onSuccess`, `onError`, `getOrNull`).
- ❌ Never let storage/playback/network exceptions reach the UI uncaught.

---

## Documentation (KDoc)

Every public class, interface, function, and property gets complete KDoc.

- First line states **business purpose**, not the symbol name.
- Document all `@param`, `@return` (non-`Unit`), `@property` (data classes), `@throws`
  (expected), `@see` (related).
- Inline comments explain **why**, not what — workarounds, subtle behavior,
  architectural intent (the native/USB/DSD code is heavily commented this way; keep it).
- ❌ Don't restate a parameter name as its description; don't narrate obvious code.

---

## File separation

- **One public type per file.** Never embed a public `data class` / `sealed` / `enum`
  in a screen or composable file.
- UI state types → `presentation/viewmodel/<feature>/`. Screens/composables →
  `presentation/screen/<feature>/` with reusable pieces in `components/`.
- Extension functions → dedicated `*Ext.kt` beside the type they extend
  (`ResourceErrorExt`, `TrackSortExt`, `PlayerScreenExt`, …).
- Generalize repeated UI with a sealed status/enum model rather than duplicating
  composables.

### File naming
`*UseCase` · `*Repository` (domain) / `*RepositoryImpl` (data) · `*Mapper` · `*Scanner`
· `*Service` · `*Engine` / `*Sink` · `*ViewModel` / `*UiModel` / `*UiEvent` /
`*UiEffect` · `*Screen` · `*Ext` · `*Module` · `*Dao` / `*Entity` (Room) · `*Dto` / `*ApiService` (remote).

---

## Privacy & security

- ✅ Read only the audio media the app needs; keep scanning **local**.
- ✅ Remote calls are best-effort metadata enrichment only (Deezer/LRCLIB); cache
  results to minimize traffic; degrade gracefully offline.
- ✅ Request only the manifest's permissions (`READ_MEDIA_AUDIO`, foreground service,
  `WAKE_LOCK`, `MODIFY_AUDIO_SETTINGS`, `INTERNET`, optional USB host).
- ✅ Fail gracefully when media/USB permission is denied or revoked.
- ❌ Never upload local library metadata anywhere beyond the enrichment lookups.
- ❌ Never log full file paths, raw content URIs, or whole library dumps in production.

---

## Forbidden → use instead

| ❌ Forbidden | ✅ Instead |
|-------------|-----------|
| `!!` | `?.let`, `?:`, sealed handling |
| `GlobalScope` | `viewModelScope` / injected `@ApplicationScope` |
| Hardcoded `Dispatchers.IO` (new code) | inject `@IoDispatcher` |
| Magic numbers/strings | constants, resources, shared tokens |
| XML layouts / DataBinding | Jetpack Compose |
| Business logic in Composables | ViewModel / use case |
| Mutable public state / `MutableStateFlow` exposed | immutable state + `copy()` |
| Field injection | constructor injection |
| `@Inject constructor` on a use case | plain constructor + `UseCaseModule` |
| Subclassing `ViewModel()` for a feature | extend `BaseViewModel<…>` |
| `navigateToRoute(PlayerScreen.route)` | `openPlayerOverlay()` |
| Direct MediaStore access from UI | Data-layer scanner + repository |
| Leaking `Cursor`/`Uri`/`Player`/`MediaSession`/`UsbDevice`/Room entities to UI | map to domain models |
| Ad-hoc playback stack / re-enabling ExoPlayer offload | the engine/manager pipeline |
| Resampling/mixing/gain on the bit-perfect path | keep it sample-exact |
| Manual `register*`/`unregister*` outside a Flow | `callbackFlow { … awaitClose { unregister() } }` |
| `send()` in a callback | `trySend()` |
| Destructive Room migration | explicit `Migration` + version bump |
| Second `OkHttpClient` | reuse the shared client |
| `LazyColumn` for fixed player/settings layouts | `Column + verticalScroll()` |
