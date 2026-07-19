# Data Layer — Persistence, Scanning, Remote, Settings

> Part of the Audiophile AI guide. Start at [`/AGENTS.md`](../../AGENTS.md).
> Playback-engine and native concerns are in [`playback.md`](playback.md) and
> [`native-audio.md`](native-audio.md).

The Data layer implements every domain repository interface and owns all framework
I/O. Framework/entity/DTO types **never** escape this layer — map to domain models at
the boundary.

---

## Repositories (`data/repository/`)

`*RepositoryImpl` classes implement domain contracts and are bound in
[`RepositoryModule`](../../app/src/main/java/com/androidexpert35/audiophilemusicplayer/di/RepositoryModule.kt)
via `@Binds`. Constructor injection only.

Conventions:
- ✅ Wrap all failures in `Resource.Error(ResourceError.*)` — never throw into callers.
- ✅ Use `runCatching { … }.fold(...)` / `getOrElse` for clean failure handling.
- ✅ Inject `@IoDispatcher` for blocking work; don't hardcode `Dispatchers.IO`.
- ✅ Map Room entities ↔ domain models with explicit mappers (`data/mapper/`).
- ❌ Don't expose DAOs, entities, `Cursor`, or `Retrofit` services to other layers.

Current impls: `MusicRepositoryImpl`, `MediaIndexRepositoryImpl`,
`PlaybackRepositoryImpl`, `PlaybackPersistenceRepositoryImpl`,
`AudioTelemetryRepositoryImpl`,
`RecentlyPlayedRepositoryImpl`, `LyricsRepositoryImpl`, `RemoteImageRepositoryImpl`,
`SettingsRepositoryImpl`, `PlaylistRepositoryImpl`.

`PlaylistRepositoryImpl` stores playlists as UTF-8 extended M3U files in the app-private
`files/playlists/` directory and implements both `PlaylistRepository` and
`LikedSongsRepository`. It reserves `favorites.m3u` for the liked-songs collection, keeps that
file synchronized with ordered Room liked rows, and serializes cross-store updates so individual
heart toggles, whole-album liked updates, and playlist-detail edits all mutate the same collection.
Regular M3U files remain the source of truth for their own membership and order. Use
`CreatePlaylistUseCase`, `AddTrackToPlaylistUseCase`, `AddTracksToPlaylistUseCase`,
`ReorderPlaylistTracksUseCase`, and `ReplacePlaylistTracksUseCase` instead of accessing these
files from presentation code.

---

## Room persistence (`data/local/`)

`AudiophileDatabase` (file `audiophile_music.db`) is the local cache and the source of
truth for indexed library, session state, liked songs, playback history/counts, and lyrics.

- **Entities** (`entity/`): `TrackEntity`, `AlbumEntity`, `ArtistEntity`,
  `LibraryIndexStateEntity`, `PlaybackStateEntity`, `LikedSongEntity`,
  `RecentlyPlayedEntity`, `LyricsCacheEntity`.
- **DAOs** (`dao/`): `LibraryIndexDao`, `PlaybackStateDao`, `LikedSongDao`,
  `RecentlyPlayedDao`, `LyricsCacheDao`. Provided in `AppModule`.
- **Converters** (`converter/`): `LongListTypeConverter` (queue ID lists).

### Migrations are mandatory
Current schema **version is 8** with explicit migrations `MIGRATION_1_2` …
`MIGRATION_7_8`, registered in both `AudiophileDatabase` and `AppModule`'s
`databaseBuilder`. When you change any entity:

1. Bump `@Database(version = N)`.
2. Add a `MIGRATION_(N-1)_N` with the exact `ALTER`/`CREATE` SQL.
3. Register it in `Room.databaseBuilder(...).addMigrations(...)`.
4. Document the change in the `AudiophileDatabase` KDoc "Schema history" block.

❌ Never rely on destructive/`fallbackToDestructiveMigration` — the user's cached
catalogue and playback session must survive upgrades.

---

## MediaStore scanning & indexing (`data/scanner/`)

- `MediaStoreScanner` runs the `MediaStore` audio queries (column names centralized in
  `MediaStoreColumns.kt`) and maps rows into domain/scan models immediately.
- `DsdFileScanner` finds `.dsf`/`.dff` files MediaStore may not index, and caches
  embedded APIC artwork into `cacheDir` (referenced by `TrackEntity.artUri`).
- `MetadataFallbackReader` fills gaps when MediaStore metadata is missing.
- `ScanAndIndexMediaUseCase` drives a full scan → Room index pass; progress is exposed
  via `MediaIndexingProgress`. `ObserveMediaStoreChangesUseCase` watches for library
  changes (wrap the `ContentObserver` in a `callbackFlow`).
- `MediaIndexRepositoryImpl.scanAndIndexMedia()` is a `callbackFlow` (not `flow {}`) so it
  can forward the `onProgress` callback `MediaStoreScanner.scanAudioFilesForIndexing()`
  invokes per file during its ID3v2.2 fallback pass — the real per-file I/O cost, and the
  only phase with enough granularity to drive a step-by-step progress bar. Reported
  progress is a weighted blend (`SCAN_PHASE_WEIGHT`) of that scan phase and the remaining
  in-memory entity-mapping + Room-write phase, which finishes in milliseconds and would
  otherwise make the bar jump straight from 0% to 100%.

Rules:
- ✅ Query MediaStore only from Data; close cursors with `use {}`.
- ✅ Wrap scan/search failures in `ResourceError.StorageError`.
- ✅ Keep search local and case-insensitive (title/artist/album); blank query clears
  state rather than issuing a scan.
- ❌ No remote search / network calls for library queries.

---

## Remote APIs (`data/remote/`)

Networking is **best-effort enrichment only** — never on the critical playback path.

- `DeezerApiService` — artist images and album covers (no API key; public endpoints).
- `LrcLibApiService` — synced/plain lyrics from LRCLIB.
- DTOs in `data/remote/dto/`; deserialized with Gson; parsed via `LrcParser` for `.lrc`.
- Wired in [`NetworkModule`](../../app/src/main/java/com/androidexpert35/audiophilemusicplayer/di/NetworkModule.kt):
  one shared `OkHttpClient` (conservative timeouts) backs both Retrofit clients **and**
  Coil. The LRCLIB Retrofit uses the `@LrcLibRetrofit` qualifier to share the client
  with a different base URL.

Rules:
- ✅ Cache results in Room (`LyricsCacheEntity`, `remoteImageUrl`/`remoteArtUrl`
  columns) to avoid repeat calls — including a `notFound` sentinel so missing lyrics
  aren't re-fetched every time.
- ✅ Accept Deezer artist images only from normalised exact-name matches; never trust
  the first search result without identity validation. Coil keeps fetched image bytes
  in the app's bounded `remote_image_cache` disk cache.
- ✅ Map network failures to `ResourceError.NetworkError`.
- ❌ Don't block the UI on enrichment; degrade gracefully when offline.
- ❌ Don't upload local library metadata anywhere (see [`conventions.md`](conventions.md)).

---

## Settings (`SettingsPreferences` + `SettingsRepositoryImpl`)

App settings use a dedicated **`SharedPreferences`** file (`audiophile_settings`), not
DataStore. `SettingsPreferences` is the single key/default registry — add new keys and
defaults there, never inline string keys at call sites.

Settings currently include: audiophile engine toggle, SUE (Sonic Upscaling
Enhancer) toggle, and Hi-Res remaster toggle. Each is exposed reactively through
`Observe*UseCase` / `Set*UseCase` pairs and consumed by the engine coordinators
in [`playback.md`](playback.md). Direct USB PCM format negotiation is always
automatic and source-native; no manual format override is exposed.
