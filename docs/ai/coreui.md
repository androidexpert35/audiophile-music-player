# CoreUI — Integration and Agent Reference

> Vendored and renamed from `CoreUI/doc/AGENTS.md` so AI agents working on
> Audiophile have a repository-local reference for the library. The source library is
> [`androidexpert35/CoreUI`](https://github.com/androidexpert35/CoreUI).
>
> CoreUI is integrated as a Gradle dependency and is the sole implementation of
> Audiophile's generic resource, state, ViewModel, navigation, route, and base-screen
> primitives. Read this document together with [`presentation.md`](presentation.md)
> before changing UI, ViewModel, error, resource, or navigation code.
> The completed migration sequence and its architectural decisions are documented in
> [`../COREUI_REFACTORING_PLAN.md`](../COREUI_REFACTORING_PLAN.md).

---

## What CoreUI is

CoreUI is an Android Jetpack Compose library (`com.tony.coreui`) that provides:

- typed state management through `UIState`, `UIStatus`, and `UIError`;
- a `BaseViewModel` with loading/error/success transitions and coroutine safety;
- command-based navigation decoupled from `NavController`;
- reusable Compose scaffolds for loading, error, dialog, and content layers;
- typed route definitions with path and query arguments;
- a `Resource<T>` wrapper for domain results.

Library baseline:

| Setting | Value |
|---------|-------|
| Maven coordinate | `com.tony.coreui:coreui:1.0.5` |
| Package | `com.tony.coreui` |
| Target/compile SDK | 36 |
| Minimum SDK | 29 |
| Kotlin | 2.3.20 |
| Compose BOM | 2026.03.01 |
| Mandatory DI framework | None |

Audiophile consumes the library from GitHub Packages. Repository credentials must stay
in a user-level Gradle properties file or the git-ignored project
`local.properties`; never commit them.

---

## Package map

```text
com.tony.coreui
├── domain/
│   └── resource/
│       ├── Resource.kt
│       └── ResourceError.kt
├── data/
│   ├── navigation/
│   │   └── NavigationManagerImpl.kt
│   └── strings/
│       ├── CoreUiStringProvider.kt
│       └── StringResolver.kt
└── presentation/
    ├── state/
    │   ├── UIState.kt
    │   ├── UIStatus.kt
    │   ├── UIError.kt
    │   └── UIErrorDisplayMode.kt
    ├── viewmodel/
    │   └── BaseViewModel.kt
    ├── error/
    │   └── UiErrorMapper.kt
    ├── navigation/
    │   ├── NavigationCommand.kt
    │   ├── NavigationManager.kt
    │   ├── NavigationOptions.kt
    │   ├── route/
    │   │   ├── RouteDefinition.kt
    │   │   ├── RouteArgument.kt
    │   │   └── RouteValueType.kt
    │   ├── graph/
    │   │   ├── NavigationNode.kt
    │   │   └── NavGraphBuilderExtensions.kt
    │   └── compose/
    │       ├── CoreUiNavigator.kt
    │       └── NavigationCommandBridge.kt
    └── components/
        └── basescreen/
            ├── AppBaseScreen.kt
            ├── LoadingScreen.kt
            ├── ErrorScreen.kt
            ├── BaseDialog.kt
            ├── BaseLoadingType.kt
            ├── ErrorDialogConfig.kt
            ├── BaseScreenRenderPolicy.kt
            └── SystemAppearance.kt
```

The host flow is:

```text
Repository -> Resource<T> -> BaseViewModel -> UIState<T> -> AppBaseScreen
                                      |
                                      +-> NavigationManager
                                          -> NavigationCommand
                                          -> CoreUiNavigator/NavController
```

---

## Domain results

### `Resource<T>`

```kotlin
sealed class Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val data: ResourceError? = null) : Resource<Nothing>()
}
```

Available functional helpers:

| Function | Purpose |
|----------|---------|
| `map` | Transform success data while preserving an error |
| `fold` | Unwrap either the success or error branch |
| `onSuccess` | Run a side effect only for success |
| `onError` | Run a side effect only for error |
| `getOrNull` | Return success data or `null` |

### `ResourceError`

```kotlin
interface ResourceError {
    data class LogicError(
        val errorMessage: String?,
        val errorCode: String?
    ) : ResourceError

    data class ValidationError(
        val message: String,
        val field: String?
    ) : ResourceError

    data class DatabaseError(val message: String) : ResourceError
    data class StorageError(val message: String) : ResourceError

    data class ServiceError(
        val message: String,
        val errorCode: String?
    ) : ResourceError

    data class NetworkError(
        val message: String,
        val httpCode: Int?
    ) : ResourceError

    data object UnknownError : ResourceError
}
```

Use CoreUI `ResourceError` as the domain-level error contract. Audiophile extends the
open interface only with `PlaybackResourceError`, which is mapped by
`AudiophileUiErrorMapper`.

---

## String resolution

The default `BaseViewModel` uses the global `CoreUiStringProvider`. Initialize it once
from the host `Application` before any CoreUI ViewModel is created:

```kotlin
class AudiophileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CoreUiStringProvider.init(this)
    }
}
```

The resolver contract is:

```kotlin
interface StringResolver {
    fun get(@StringRes id: Int, vararg formatArgs: Any): String
    fun getPlural(@PluralsRes id: Int, quantity: Int, vararg formatArgs: Any): String
}
```

For tests or host-specific behavior, inject another `StringResolver` instead of using
the global provider:

```kotlin
class FakeStringResolver : StringResolver {
    override fun get(id: Int, vararg formatArgs: Any): String = "test_string_$id"
    override fun getPlural(id: Int, quantity: Int, vararg formatArgs: Any): String =
        "test_plural_${id}_$quantity"
}
```

String resolution is a presentation concern. Never use `CoreUiStringProvider` from
Audiophile's domain layer.

---

## Presentation state

### `UIState<T>`

```kotlin
data class UIState<T>(
    val status: UIStatus = UIStatus.IDLE,
    val data: T? = null,
    val error: UIError? = null,
    val showErrorDialog: Boolean = false
)
```

```kotlin
enum class UIStatus {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR
}
```

```kotlin
data class UIError(
    val title: String,
    val message: String,
    val type: Any? = null,
    val retryAction: (() -> Unit)? = null,
    val displayMode: UIErrorDisplayMode = UIErrorDisplayMode.DIALOG,
    val metadata: Map<String, Any?> = emptyMap()
)
```

```kotlin
enum class UIErrorDisplayMode {
    DIALOG,
    FULL_SCREEN,
    NONE
}
```

Display modes mean:

- `DIALOG`: `AppBaseScreen` renders a `BaseDialog`;
- `FULL_SCREEN`: `AppBaseScreen` renders an `ErrorScreen`;
- `NONE`: the error remains in state and the host renders it.

---

## `BaseViewModel`

Class signature:

```kotlin
abstract class BaseViewModel<UI_TYPE, UI_EVENT, UI_EFFECT>(
    navigationManager: NavigationManager? = null,
    stringResolver: StringResolver = CoreUiStringProvider,
    uiErrorMapper: UiErrorMapper = DefaultUiErrorMapper(stringResolver)
) : ViewModel()
```

Public observable state:

```kotlin
val uiState: StateFlow<UIState<UI_TYPE>>
val uiEffect: SharedFlow<UI_EFFECT>
```

All UI interactions enter through:

```kotlin
fun onEvent(event: UI_EVENT)
protected abstract fun handleEvent(event: UI_EVENT)
```

Protected state/effect helpers:

| Method | Purpose |
|--------|---------|
| `setLoadingState()` | Enter loading |
| `setSuccessState(newData)` | Store data and enter success |
| `setIdleState(newData)` | Reset to idle |
| `setErrorState(error)` | Store a mapped UI error |
| `updateUiData(newData)` | Replace only the data field |
| `updateUiState { state -> ... }` | Transform the complete state |
| `emitEffect(effect)` | Emit a one-shot effect |
| `showErrorPopup(visible)` | Change dialog visibility |
| `dismissErrorPopup()` | Hide the error dialog |

The primary async fetch API is:

```kotlin
protected fun <RESOURCE> launchUiStateUpdate(
    retryAction: (() -> Unit)? = null,
    dataFetchBlock: suspend () -> Resource<RESOURCE>,
    processSuccess: (RESOURCE) -> UI_TYPE,
    updateUiAfterError: ((UIError) -> UI_TYPE?)? = null,
    invokeOnCompletion: ((success: Boolean) -> Unit)? = null,
    skipLoading: Boolean = false
)
```

It performs loading, fetch, success mapping, error mapping, and completion handling in
`viewModelScope`.

```kotlin
private fun loadAlbum(id: Long) {
    launchUiStateUpdate(
        retryAction = { loadAlbum(id) },
        dataFetchBlock = { repository.getAlbum(id) },
        processSuccess = { album -> album.toUiModel() }
    )
}
```

Use `executeAsync { ... }` for fire-and-forget work that does not manage `UIState`.
Use `handleError(...)` for manual error handling outside `launchUiStateUpdate`.

Navigation helpers exposed by the base class:

```kotlin
fun navigateToRoute(
    route: String,
    options: NavigationOptions = NavigationOptions()
)

fun navigateUp()
fun popBackStack(route: String? = null, inclusive: Boolean = false)

fun navigateAndClearBackstackTo(
    route: String,
    popUpToRoute: String? = null,
    inclusive: Boolean = true
)
```

They are no-ops when no `NavigationManager` was supplied.

---

## Navigation

### Commands and manager

```kotlin
sealed interface NavigationCommand {
    data class Navigate(
        val route: String,
        val options: NavigationOptions
    ) : NavigationCommand

    data object NavigateUp : NavigationCommand

    data class PopBackStack(
        val route: String?,
        val inclusive: Boolean
    ) : NavigationCommand

    data class NavigateAndClearBackStack(
        val route: String,
        val popUpToRoute: String?,
        val inclusive: Boolean
    ) : NavigationCommand
}
```

```kotlin
data class NavigationOptions(
    val launchSingleTop: Boolean = false,
    val restoreState: Boolean = false,
    val popUpToRoute: String? = null,
    val popUpToInclusive: Boolean = false,
    val allowRepeatOnSameRoute: Boolean = false,
    val extras: Map<String, Any?> = emptyMap()
)
```

```kotlin
interface NavigationManager {
    val navigationCommands: SharedFlow<NavigationCommand>
    val currentRoute: StateFlow<String?>

    fun navigate(
        route: String,
        options: NavigationOptions = NavigationOptions()
    )

    fun navigateUp()
    fun popBackStack(route: String? = null, inclusive: Boolean = false)

    fun navigateAndClearBackStack(
        route: String,
        popUpToRoute: String? = null,
        inclusive: Boolean = true
    )
}
```

The provided implementation is instantiated by the host:

```kotlin
val navigationManager: NavigationManager = NavigationManagerImpl()
```

### Typed routes

```kotlin
class RouteDefinition(
    val baseRoute: String,
    arguments: List<RouteArgument<*>> = emptyList()
)
```

Important members:

- `routePattern` is the Navigation Compose pattern;
- `navArguments` is the matching `NamedNavArgument` list;
- `createRoute(...)` builds a concrete route;
- `requireArgument(...)` extracts a mandatory value;
- `getArgument(...)` extracts a nullable/optional value.

```kotlin
data class RouteArgument<T>(
    val name: String,
    val location: RouteArgumentLocation,
    val valueType: RouteValueType<T>,
    val nullable: Boolean = false,
    val defaultValue: T? = null
)
```

Factory families exist for `String`, `Long`, `Int`, `Boolean`, `Float`, enums, and
custom value types:

```kotlin
stringPathArgument("name")
longPathArgument("id")
intPathArgument("page")
booleanPathArgument("enabled")
floatPathArgument("ratio")

stringQueryArgument("filter", nullable = true)
longQueryArgument("offset", defaultValue = 0L)
```

Use the top-level `route(...)` builder and the `with` infix function:

```kotlin
object Routes {
    val albumId = longPathArgument("albumId")
    val filter = stringQueryArgument("filter", nullable = true)
    val album = route("album", albumId, filter)
}

val destination = Routes.album.createRoute(
    Routes.albumId with 42L,
    Routes.filter with "recent"
)
```

Never concatenate route strings when a typed `RouteDefinition` exists.

### Graph nodes and host

```kotlin
val homeDestination = destinationNode(Routes.home)
val mainFlow = flowNode(
    route = "main",
    startDestination = homeDestination
)
val root = rootNode(startDestination = mainFlow)
```

```kotlin
@Composable
fun CoreUiNavigator(
    navigationManager: NavigationManager,
    root: NavigationRootNode,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    builder: NavGraphBuilder.() -> Unit
)
```

Register destinations with the `destination(...)` extension and nested flows with
`flow(...)`. `CoreUiNavigator` embeds the command bridge that translates
`NavigationCommand` values into `NavController` operations.

---

## Screen components

### `AppBaseScreen<T>`

`AppBaseScreen` coordinates content, loading, and errors:

```kotlin
@Composable
fun <T> AppBaseScreen(
    uiState: UIState<T>,
    statusBarColor: Color = MaterialTheme.colorScheme.surface,
    navigationBarColor: Color = statusBarColor,
    useLightStatusIcons: Boolean? = null,
    useLightNavigationIcons: Boolean? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
    renderPolicy: BaseScreenRenderPolicy = BaseScreenRenderPolicy(),
    errorDialogConfig: ErrorDialogConfig = ErrorDialogConfig(),
    loadingType: BaseLoadingType = BaseLoadingType.DEFAULT,
    loadingScreen: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
    errorDialog: (@Composable (UIError, () -> Unit) -> Unit)? = null,
    errorScreen: (@Composable (UIError) -> Unit)? = null,
    contentWithState: (@Composable (T, UIState<T>) -> Unit)? = null,
    onErrorDialogDismiss: () -> Unit = {},
    dialogProperties: DialogProperties = DialogProperties(),
    content: @Composable (T) -> Unit
)
```

Rendering behavior:

| State | Result |
|-------|--------|
| `IDLE` or `SUCCESS` | Content, or `emptyContent` when data is null |
| `LOADING` + `DEFAULT` | Loading screen; content visibility follows policy |
| `LOADING` + `OVERLAY` | Content with loading overlay |
| `LOADING` + `NONE` | Content only |
| `ERROR` + `DIALOG` | Existing content plus dialog |
| `ERROR` + `FULL_SCREEN` | Full-screen error |
| `ERROR` + `NONE` | Content only; host handles error |

Supporting policies:

```kotlin
data class BaseScreenRenderPolicy(
    val applySystemAppearance: Boolean = true,
    val hideContentOnDefaultLoading: Boolean = true,
    val keepContentVisibleOnError: Boolean = true
)
```

```kotlin
data class ErrorDialogConfig(
    val onConfirm: () -> Unit = {},
    val onRetry: (() -> Unit)? = null,
    val onCancel: (() -> Unit)? = null,
    val onDismissRequest: (() -> Unit)? = null,
    val confirmButtonText: String? = null,
    val retryButtonText: String? = null,
    val dismissButtonText: String? = null
)
```

```kotlin
enum class BaseLoadingType {
    DEFAULT,
    OVERLAY,
    NONE
}
```

`LoadingScreen`, `ErrorScreen`, and `BaseDialog` are public and can also be used as
standalone building blocks. Prefer `AppBaseScreen` for normal feature screens.

---

## Error mapping

```kotlin
interface UiErrorMapper {
    fun map(errorObject: Any, retryAction: (() -> Unit)? = null): UIError
    fun mapResourceError(
        resource: ResourceError?,
        retryAction: (() -> Unit)? = null
    ): UIError
}
```

`DefaultUiErrorMapper` handles:

- `Throwable`;
- every built-in `ResourceError` subtype;
- unknown objects via a generic fallback.

For app-specific errors, inject a custom mapper and delegate unknown categories:

```kotlin
class AppUiErrorMapper(
    resolver: StringResolver
) : UiErrorMapper {
    private val delegate = DefaultUiErrorMapper(resolver)

    override fun map(
        error: Any,
        retryAction: (() -> Unit)?
    ): UIError = when (error) {
        is AppDomainError.SessionExpired -> UIError(
            title = "Session expired",
            message = "Please sign in again.",
            type = error,
            displayMode = UIErrorDisplayMode.FULL_SCREEN
        )

        else -> delegate.map(error, retryAction)
    }
}
```

Audiophile injects `StringResolver` and `AudiophileUiErrorMapper` into every feature
ViewModel. JVM tests use deterministic fakes rather than the global Android resolver.

Do not implement mapping logic inside individual `BaseViewModel` subclasses.

---

## Complete host pattern

```kotlin
data class AlbumUiModel(
    val title: String,
    val trackCount: Int
)

sealed interface AlbumUiEvent {
    data class Load(val id: Long) : AlbumUiEvent
    data object RetryClicked : AlbumUiEvent
    data object BackClicked : AlbumUiEvent
}

sealed interface AlbumUiEffect

class AlbumViewModel(
    private val repository: AlbumRepository,
    navigationManager: NavigationManager
) : BaseViewModel<AlbumUiModel, AlbumUiEvent, AlbumUiEffect>(
    navigationManager = navigationManager
) {
    private var lastAlbumId = -1L

    override fun handleEvent(event: AlbumUiEvent) {
        when (event) {
            is AlbumUiEvent.Load -> loadAlbum(event.id)
            AlbumUiEvent.RetryClicked -> loadAlbum(lastAlbumId)
            AlbumUiEvent.BackClicked -> navigateUp()
        }
    }

    private fun loadAlbum(id: Long) {
        lastAlbumId = id
        launchUiStateUpdate(
            retryAction = { loadAlbum(id) },
            dataFetchBlock = { repository.getAlbum(id) },
            processSuccess = { album ->
                AlbumUiModel(
                    title = album.title,
                    trackCount = album.trackCount
                )
            }
        )
    }
}
```

```kotlin
@Composable
fun AlbumScreen(
    viewModel: AlbumViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppBaseScreen(
        uiState = uiState,
        errorDialogConfig = ErrorDialogConfig(
            onRetry = {
                viewModel.onEvent(AlbumUiEvent.RetryClicked)
            }
        ),
        onErrorDialogDismiss = viewModel::dismissErrorPopup
    ) { model ->
        AlbumContent(
            model = model,
            onEvent = viewModel::onEvent
        )
    }
}
```

---

## Rules and anti-patterns

### Required

- Initialize `CoreUiStringProvider` before creating a CoreUI `BaseViewModel`, unless
  every ViewModel receives another resolver.
- Use `launchUiStateUpdate` for async data fetches with the normal
  loading-success-error lifecycle.
- Keep `onEvent` as the single public entry point for screen interactions.
- Use typed route definitions and arguments.
- Inject `NavigationManager`; never pass `NavController` into a ViewModel.
- Use `emitEffect` for one-shot effects.
- Use `AppBaseScreen` for standard loading/error/content orchestration.
- Inject a custom `UiErrorMapper` when Audiophile errors need host-specific mapping.

### Forbidden

- Do not create or own `UIState` in Composables.
- Do not expose `MutableStateFlow` or use it for one-shot effects.
- Do not build typed routes through string concatenation.
- Do not put navigation decisions in arbitrary Composables.
- Do not use `CoreUiStringProvider` from the domain layer.
- Do not duplicate CoreUI public types under Audiophile packages after their migration.
- Do not leave both an embedded type and its CoreUI replacement in active use without
  an explicit boundary adapter and migration note.

---

## Audiophile host boundary

The migration is complete. Audiophile owns only the behavior that is specific to the
music player:

1. `PlaybackResourceError` extends CoreUI's open `ResourceError` contract.
2. `AudiophileUiErrorMapper` preserves localized app copy and playback categorization.
3. `PlayerOverlayManager` coordinates the permanently composed player without
   pretending it is a NavHost destination.
4. `AppNavigator` owns the shell and player layers around `CoreUiNavigator`.
5. `AppRoutes` owns typed destination declarations and argument values.

Do not reintroduce local copies, type aliases, or wrappers for CoreUI generic
primitives. If CoreUI is missing reusable behavior, evolve the library and upgrade
the dependency instead of embedding another implementation in Audiophile.

---

*Imported for Audiophile on 2026-07-17 from CoreUI `doc/AGENTS.md`; adapted with
Audiophile-specific integration and migration-boundary notes.*
