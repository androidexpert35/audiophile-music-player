# Dependency Injection (Hilt)

> Part of the Audiophile AI guide. Start at [`/AGENTS.md`](../../AGENTS.md).

Hilt with KSP. **Constructor injection everywhere**; field injection is forbidden
except where Android forces it (e.g. `@AndroidEntryPoint` services). Modules live in
`di/`.

---

## Modules

| Module | Provides |
|--------|----------|
| `AppModule` (`object`, `@Provides`) | `ContentResolver`, `SharedPreferences` (settings), `UsbManager`, `AudiophileDatabase` + each DAO, the dispatcher qualifiers, and the `@ApplicationScope` main-thread `CoroutineScope` |
| `DispatcherModule` | The qualifier annotations (`@IoDispatcher`, `@MainDispatcher`, `@DefaultDispatcher`, `@ApplicationScope`, `@LrcLibRetrofit`) |
| `RepositoryModule` (`abstract`, `@Binds`) | Interface → `*RepositoryImpl` bindings |
| `UseCaseModule` (`object`, `@Provides`) | Every use case (use cases have **no** `@Inject`) |
| `NetworkModule` | Shared `OkHttpClient`, Deezer + LRCLIB `Retrofit`/services |
| `CoilModule` | Coil `ImageLoader` (reuses the shared `OkHttpClient`) |
| `NavigationModule` | `NavigationManager` binding (singleton shared coordinator) |
| `AnalysisModule` (`abstract`, `@Binds`) | `StationarySampler` → `FFmpegStationarySampler` — the offline signal-measurement pass, bound behind an interface so the orchestrator's policy is testable without `audiophile_native` |

All installed in `SingletonComponent`.

---

## Qualifiers (`DispatcherModule`)

- `@IoDispatcher` — blocking I/O (MediaStore, files, Room). **Inject this; never
  hardcode `Dispatchers.IO`** in new code.
- `@MainDispatcher` — UI-thread dispatcher.
- `@DefaultDispatcher` — CPU-bound work.
- `@ApplicationScope` — process-lifetime, main-thread `CoroutineScope` with a
  `SupervisorJob` (e.g. the playback position ticker, engine flow mirroring). Kept
  distinct from `@MainDispatcher` so "scope" vs "dispatcher" is unambiguous in the graph.
- `@LrcLibRetrofit` — the LRCLIB-configured `Retrofit` (shares the OkHttp client with
  the default Deezer `Retrofit`, different base URL).

---

## Patterns

```kotlin
// Interface → impl
@Module @InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindMusicRepository(impl: MusicRepositoryImpl): MusicRepository
}

// Framework objects, dispatchers, use cases
@Module @InstallIn(SingletonComponent::class)
object UseCaseModule {
    @Provides
    fun provideGetTracksUseCase(repo: MusicRepository) = GetTracksUseCase(repo)
}
```

Rules:
- ✅ `@Binds` for interface→impl; `@Provides` for framework objects, dispatchers, and
  use cases.
- ✅ Scope long-lived infra (`@Singleton`): DB, DAOs, OkHttp, engines, `NavigationManager`.
- ✅ Register every Room migration in `AppModule`'s `databaseBuilder` (see
  [`data.md`](data.md)).
- ❌ Never `@Inject` a use case. Never field-inject repositories/ViewModels/use cases.
- ❌ Don't add a second `OkHttpClient`/connection pool — reuse the shared one.
