# Domain Layer

> Part of the Audiophile AI guide. Start at [`/AGENTS.md`](../../AGENTS.md).

The domain layer is **pure Kotlin** — no Android, Room, Media3, or Retrofit imports.
It contains models, repository interfaces (contracts), and use cases.

---

## Models (`domain/model/`)

Grouped by concern: `audio/` (`AudioTelemetry`, `AudioFormat`, `AudioCodec`,
`DsdRate`, `DsdOutputMode`, `UsbAudioFormat`, `TelemetryStatus`, …), `track/`
(`Track`, `Album`, `Artist` + `TrackArtistParser`, `TrackSortExt`), `playback/`
(`PlaybackState`, `QueueState`, `RepeatMode`, `ShuffleMode`, `PersistedPlaybackState`),
`lyrics/` (`Lyrics`, `LyricLine`), `library/` (`LikedSong`, `Playlist`, `PlaylistKind`,
`RecentlyPlayedEntry`),
`indexing/` (`MediaIndexingProgress`), and `common/` (`Resource`, `ResourceError`).

Rules:
- ✅ Immutable `data class`es (`val` only; use `copy()`).
- ✅ Domain models abstract away MediaStore, Media3, Room, and USB framework types.
- ✅ Pure helper logic that belongs to a model goes in a `*Ext.kt` / `*Parser.kt`
  beside it (e.g. `TrackArtistParser`, `TrackSortExt`) and stays unit-testable.
- ✅ Document `@property` for every public data-class field.

---

## Repository interfaces (`domain/repository/`)

Contracts only — implementations live in Data. Current set includes
`MusicRepository`, `PlaybackRepository`, `AudioTelemetryRepository`,
`MediaIndexRepository`, `SettingsRepository`, `LikedSongsRepository`,
`RecentlyPlayedRepository`, `LyricsRepository`, `RemoteImageRepository`,
`PlaybackPersistenceRepository`.

`PlaylistRepository` and `LikedSongsRepository` are separate domain contracts but share one Data
coordinator. The reserved favorites collection is exposed as `PlaylistKind.FAVORITES`, so Domain
and Presentation can select system-playlist behaviour without depending on filenames.

`RecentlyPlayedRepository` owns both recency ordering and persistent per-track
play counts. `ObserveMostPlayedTracksUseCase` maps a caller-provided artist track
collection into the live personal ranking without exposing Room rows to Presentation.

- ✅ Return `Resource<T>` for one-shot operations or `Flow<T>` for streams.
- ✅ Suspend functions for async one-shots; cold/hot `Flow` for observation.
- ❌ No framework types in signatures (no `Uri`, `Cursor`, `Player`, `UsbDevice`).

---

## Use cases (`domain/usecase/`)

Use cases are the primary dependency of ViewModels. They are **pure Kotlin** with
**no `@Inject`** annotation — all wiring is in [`di/UseCaseModule.kt`](../../app/src/main/java/com/androidexpert35/audiophilemusicplayer/di/UseCaseModule.kt).

```kotlin
/**
 * Retrieves the full list of local audio tracks from the indexed catalogue.
 *
 * @property musicRepository Repository for reading cached/MediaStore-backed tracks.
 */
class GetTracksUseCase(
    private val musicRepository: MusicRepository
) {
    /**
     * @return [Resource.Success] with all tracks, or [Resource.Error] on failure.
     */
    suspend operator fun invoke(): Resource<List<Track>> = musicRepository.getTracks()
}
```

Rules:
- ✅ Single public `operator fun invoke()`.
- ✅ Plain constructor — **never** `@Inject` on a use case.
- ✅ Provide it in `UseCaseModule` via `@Provides`.
- ✅ Keep thin and orchestration-focused; combine flows here when a screen needs a
  single merged stream.
- ❌ No Android dependencies.
- ❌ Don't bypass an existing use case from a ViewModel when one already covers the
  operation.

Naming follows intent: `Get*`, `Observe*`, `Set*`, `Play/Pause/Resume*`, `Skip*`,
`Scan*`, `Record*`, `Toggle*`, `Request*`, `Refresh*`, `Restore/Save*`.

---

## Error model (`domain/model/common/`)

```kotlin
sealed class Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val data: ResourceError? = null) : Resource<Nothing>()
}

sealed class ResourceError {
    data class LogicError(val errorMessage: String?, val errorCode: String? = null) : ResourceError()
    data class DatabaseError(val message: String) : ResourceError()
    data class StorageError(val message: String) : ResourceError()
    data class PlaybackError(val message: String, val errorCode: Int? = null) : ResourceError()
    data class NetworkError(val message: String) : ResourceError()
    data object UnknownError : ResourceError()
}
```

- Use the functional extension API in `ResourceErrorExt.kt` — `map`, `fold`,
  `onSuccess`, `onError`, `getOrNull` — instead of manual `when` on every call site.
- Choose the right error type: `StorageError` for MediaStore/permission/file,
  `PlaybackError` for engine/controller, `DatabaseError` for Room, `NetworkError` for
  Retrofit/OkHttp, `LogicError` for validation, `UnknownError` as last resort.
- `BaseViewModel.processErrorResource()` already maps each `ResourceError` variant to a
  localized `UIError`; keep that mapping in sync when adding a new variant.

See [`docs/ai/conventions.md`](conventions.md) for the full error-handling rules.
