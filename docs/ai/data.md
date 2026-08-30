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

`.m3u`/`.m3u8` files discovered inside the granted music folders (`PlaylistKind.IMPORTED`) are a
second, disk-backed source `PlaylistRepositoryImpl.observePlaylists()` merges in from
`ImportedPlaylistDao` — see [`M3uFileScanner`](#mediastore-scanning--indexing-datascanner) below.
Unlike the app-private collection, mutating an imported playlist writes straight back to its
original document via the SAF permission already granted on its parent tree (`ContentResolver
.openOutputStream(uri, "wt")` / `DocumentsContract.deleteDocument`), not to an app-private copy.

---

## Room persistence (`data/local/`)

`AudiophileDatabase` (file `audiophile_music.db`) is the local cache and the source of
truth for indexed library, session state, liked songs, playback history/counts, and lyrics.

- **Entities** (`entity/`): `TrackEntity` (including source year, genre, composer
  metadata, and the `audioKey` content key), `AlbumEntity`, `ArtistEntity`,
  `LibraryIndexStateEntity`, `PlaybackStateEntity`, `LikedSongEntity`,
  `RecentlyPlayedEntity`, `LyricsCacheEntity`, `ImportedPlaylistEntity`.
- **DAOs** (`dao/`): `LibraryIndexDao`, `PlaybackStateDao`, `LikedSongDao`,
  `RecentlyPlayedDao`, `LyricsCacheDao`, `ImportedPlaylistDao`. Provided in `AppModule`.
- **Converters** (`converter/`): `LongListTypeConverter` (queue ID lists),
  `StringListTypeConverter` (imported-playlist track URIs).

### Migrations are mandatory
Current schema **version is 14** with explicit migrations `MIGRATION_1_2` …
`MIGRATION_13_14`, registered in both `AudiophileDatabase` and `AppModule`'s
`databaseBuilder`. When you change any entity:

1. Bump `@Database(version = N)`.
2. Add a `MIGRATION_(N-1)_N` with the exact `ALTER`/`CREATE` SQL.
3. Register it in `Room.databaseBuilder(...).addMigrations(...)`.
4. Document the change in the `AudiophileDatabase` KDoc "Schema history" block.

❌ Never rely on destructive/`fallbackToDestructiveMigration` — the user's cached
catalogue and playback session must survive upgrades.

### `TrackEntity.audioKey` identifies the audio, not the row
Schema 14 adds `audioKey TEXT NOT NULL DEFAULT ''` to `tracks` and clears
`library_index_state` so the next launch re-indexes the already-granted folders and fills
it in. It exists because `TrackEntity.id` answers the wrong question for anything cached
per track (a measured analysis, above all): a MediaStore delete + re-add mints a **new id
for byte-identical audio**, and a file overwritten in place **keeps its id**.

`AudioContentKey.derive()` builds it from the file size plus two 16 KiB payload windows
read at **25% and 75%** of the file, digested with SHA-256 (128-bit hex prefix, format-
versioned as `1:<sizeHex>:<digest>`).

- ✅ Sample at fractional offsets. The first kilobytes hold ID3v2 / Vorbis comment blocks,
  so digesting them would make "fix a spelling in the artist tag" invalidate an analysis of
  audio that never changed. An in-place tag rewrite — the usual case, because taggers
  rewrite inside the existing padding — leaves size and payload untouched and keys the same.
- ⚠️ A tag edit that **changes the file length** (adding embedded artwork) shifts every
  payload byte and does produce a new key. No offset scheme hides that short of decoding
  the stream, and over-invalidating is the safe direction.
- ✅ An empty key means **"not analysable"**, never an error: `AudioContentKeyReader`
  returns it for a revoked grant, an unmounted volume or a provider that will not open the
  document, and the scanners keep the track — an unanalysable track is still playable.
- ❌ Do not reuse the 31-bit URI hash `DsdFileScanner` mints for `id` as a content key; it
  identifies a location, not a payload.

---

## User-granted music folders (`MusicFolderRegistry`)

**The library scan is scoped to folders the user picks — never to the whole device.**
This is load-bearing for two separate reasons and must not be relaxed:

- A whole-volume scan pulls in every audio file on the device. In practice that means
  messenger voice notes and app sound effects listed as songs.
- `.dsf` / `.dff` are not audio media types to Android, so `READ_MEDIA_AUDIO` grants **no**
  access to them at all — a direct `File` walk of shared storage cannot even list them on
  `minSdk 33`. The persisted document-tree grant taken when the user adds a folder is the
  only way DSD files can be read, which is why DSD tracks appear exactly once a folder
  containing them has been added.

- `MusicFolderRegistry` (`data/repository/`) owns both halves of a grant: the tree URI in
  `SharedPreferences` (`SettingsPreferences.KEY_MUSIC_FOLDER_URIS`) and the matching
  `takePersistableUriPermission`. A URI whose grant was revoked is reported as absent, so
  onboarding can ask for it again. Overlapping grants are collapsed (parent wins).
- `MusicFolderScopeResolver` turns a tree URI (`primary:Music/DSD`) into a
  `MusicFolderScope` carrying both addressing schemes the scan needs: the MediaStore
  `(volumeName, relativePath)` pair and the tree URI itself.
- `MusicFolderRepositoryImpl` adapts the registry to the Domain contract
  (`MusicFolderRepository` → `MusicFolder`), used by `ObserveMusicFoldersUseCase`,
  `HasMusicFoldersUseCase`, `AddMusicFolderUseCase`, `RemoveMusicFolderUseCase`.
- Both the onboarding folder step and the Settings **Music folders** card add and remove
  folders through those use cases; neither is the sole entry point.
- Schema-11 upgrades deliberately retire every previously stored folder URI and release its
  persisted read grant once. This forces the user through the folder picker again under the
  folder-scoped indexing contract; fresh installs record the same selection version with no
  legacy grants to remove.

### An empty scope has two causes — do not conflate them
`MediaIndexRepositoryImpl` branches on `MusicFolderRegistry.hasStoredFolders()`:

- **Nothing on record** — the user removed their last folder. The scan proceeds with an
  empty result, which clears `tracks` / `albums` / `artists`. Leaving the old rows would
  keep serving content from a location the user just revoked.
- **Folders on record that will not resolve** — card unmounted, grant not yet restored
  after a reboot. Transient, so the scan aborts with `Resource.Error` and the cached
  catalogue survives.

### The index records the scope that built it
`LibraryIndexStateEntity.folderSignature` stores `volumeName:relativePath` for every granted
folder. `isLibraryIndexed()` returns `true` only when that signature still matches
`MusicFolderRegistry.folderSignature()`, so a folder added or removed while nothing was
observing still forces a rebuild on the next launch. An empty signature (rows written before
version 9) never matches, which is what retires the old whole-device catalogues.

❌ Never add a "scan everything" fallback when the folder list is empty, and never mark an
index complete without stamping its signature.

---

## MediaStore scanning & indexing (`data/scanner/`)

- `MediaStoreScanner` runs the `MediaStore` audio queries (column names and the
  folder-scoped `WHERE` clause centralized in `MediaStoreColumns.kt`) and maps rows into
  domain/scan models immediately. The selection matches `VOLUME_NAME` plus a
  `RELATIVE_PATH LIKE` prefix per granted folder, with `%` / `_` / `\` escaped.
- `DsdFileScanner` finds `.dsf`/`.dff` files MediaStore never indexes by walking the
  granted document trees via `DocumentsContract`, parsing DSF/DFF headers and ID3v2 tags
  through `SeekableDocumentSource` (positioned reads over the document's `FileChannel`,
  because `RandomAccessFile` cannot open these files), and caches embedded APIC artwork
  into `cacheDir` (referenced by `TrackEntity.artUri`).
  It reports `dateAdded` as the **raw document mtime, or 0 when the provider supplies
  none** (`COLUMN_LAST_MODIFIED` is an optional column). It must not invent a value
  there: `MediaIndexRepositoryImpl.withLibraryFirstSeenDates()` is the single place that
  resolves the library-relative "added" timestamp, because only it can see the previous
  index. An mtime is not `MediaStore.DATE_ADDED` — a SACD rip copied over today still
  carries its original stamp — and the Songs tab is the only surface that sorts on this
  field (`RECENTLY_ADDED` is the default), so getting it wrong buries DSD tracks below
  the whole library while albums (sorted by year), artists (alphabetical) and the short
  facet lists keep showing them. That asymmetry is exactly what a user reports as
  "DSD appears everywhere except in Songs".
- `M3uFileScanner` finds `.m3u`/`.m3u8` playlists MediaStore never indexes the same way
  `DsdFileScanner` finds DSD files — walking the granted document trees — then resolves
  each entry's path against the same scan pass's combined MediaStore+DSD result (exact
  path, then suffix, then unambiguous-filename fallback; unresolved entries are skipped).
  Results are cached in `ImportedPlaylistDao`, not the track/album/artist tables, and
  merged into `PlaylistRepositoryImpl.observePlaylists()` as `PlaylistKind.IMPORTED`.
- `AudioContentKeyReader` samples every scanned file — MediaStore row or granted-tree DSD
  document, both opened through the resolver's file descriptor — for the
  `TrackEntity.audioKey` content key described above. `MediaStoreScanner` reads it inside
  the same per-file pass that runs the ID3v2.2 fallback (so scan progress stays accurate);
  `DsdFileScanner` reads it after the 30-second duration filter, so rejected documents
  never pay for it. The read is blocking and both scanners already run it on `@IoDispatcher`.
- `MetadataFallbackReader` fills gaps when MediaStore metadata is missing. The track
  cache retains best-effort year, genre, and composer tags so the local Library can
  build those sections without a network request or an additional scan.
- Artist catalogue aggregation expands semicolon (`;`), slash (`/`), and vertical-bar
  (`|`) credits into separate artist rows while preserving ampersands inside names.
  Schema 11 clears the reconstructible track/album/artist/imported-playlist index and its
  completion row. User data outside that cache remains intact, and onboarding rebuilds the
  catalogue only after the user selects folders again.
- `ScanAndIndexMediaUseCase` drives a full scan → Room index pass; progress is exposed
  via `MediaIndexingProgress`. `ObserveMediaStoreChangesUseCase` watches for library
  changes (wrap the `ContentObserver` in a `callbackFlow`) **merged with folder-set
  changes**, so adding or removing a folder in Settings re-indexes the same way copying
  files onto the device does. `SettingsViewModel` additionally navigates to
  `AppRoutes.Onboarding` after a folder add/remove succeeds, so that rescan is visible on
  the same indexing screen used on first launch instead of only happening in the
  background — see [`presentation.md`](presentation.md#launch-graph-gate-startup-cost).
- `MediaIndexRepositoryImpl.scanAndIndexMedia()` is a `callbackFlow` (not `flow {}`) so it
  can forward the `onProgress` callback `MediaStoreScanner.scanAudioFilesForIndexing()`
  invokes per file during its ID3v2.2 fallback pass — the real per-file I/O cost, and the
  only phase with enough granularity to drive a step-by-step progress bar. Reported
  progress is a weighted blend (`SCAN_PHASE_WEIGHT`) of that scan phase and the remaining
  in-memory entity-mapping + Room-write phase, which finishes in milliseconds and would
  otherwise make the bar jump straight from 0% to 100%.

Rules:
- ✅ Query MediaStore only from Data; close cursors with `use {}`.
- ✅ Always scope a scan to `MusicFolderRegistry.getScopes()`; an empty scope scans nothing.
- ✅ Wrap scan/search failures in `ResourceError.StorageError`.
- ✅ Keep search local and case-insensitive (title/artist/album); blank query clears
  state rather than issuing a scan.
- ❌ No remote search / network calls for library queries.

---

## Remote APIs (`data/remote/`)

Networking is **best-effort enrichment only** — never on the critical playback path.

- `DeezerApiService` — artist images and album covers (no API key; public endpoints).
- `LrcLibApiService` — synced/plain lyrics from LRCLIB: `/api/get` for an exact match,
  `/api/search` as the fuzzy fallback.
- DTOs in `data/remote/dto/`; deserialized with Gson; parsed via `LrcParser` for `.lrc`.
- Wired in [`NetworkModule`](../../app/src/main/java/com/androidexpert35/audiophilemusicplayer/di/NetworkModule.kt):
  one shared `OkHttpClient` (conservative timeouts) backs both Retrofit clients **and**
  Coil. The LRCLIB Retrofit uses the `@LrcLibRetrofit` qualifier to share the client
  with a different base URL.

Rules:
- ✅ Cache results in Room (`LyricsCacheEntity`, `remoteImageUrl`/`remoteArtUrl`
  columns) to avoid repeat calls — including a `notFound` sentinel so missing lyrics
  aren't re-fetched every time.
- ✅ **Send an identifying `User-Agent` on every LRCLIB call.** LRCLIB is behind
  Cloudflare, which rejects OkHttp's default `okhttp/<version>` agent with a `520`
  before the request reaches the API.
- ✅ Cache a `notFound` sentinel **only** for a genuine "no match" outcome (`/api/get`
  404 *and* an empty `/api/search`). Never cache a transport failure or any other HTTP
  status — that turns a temporary outage into permanent "lyrics unavailable". Sentinels
  also carry a 14-day TTL as a second safety net.
- ✅ Fall back to `/api/search` when the exact lookup misses: local tags routinely drift
  from LRCLIB (`(Remastered 2011)` suffixes, `feat.`/`ft.` credits, `Pt.` markers in
  numbered series titles, a different album, a duration a few seconds off — LRCLIB
  indexes e.g. `Veleno 7`, and its search returns *zero* results for `Veleno pt.7`).
  Rank candidates by title/artist identity, and only trust
  a candidate's synced timings when its duration is within a few seconds of the local
  file — otherwise keep just its plain lyrics.
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
Enhancer) toggle, Hi-Res remaster toggle, and a task-removal queue-retention toggle.
When the latter is enabled, removing the app from Recents removes every queued item except
the current track and persists that one-track session for the next launch. Each is exposed reactively through
`Observe*UseCase` / `Set*UseCase` pairs and consumed by the engine coordinators
in [`playback.md`](playback.md). Direct USB PCM format negotiation is always
automatic and source-native; no manual format override is exposed.

The library's per-section sort order, list/grid choice, and visibility are stored together under
`SettingsPreferences.KEY_LIBRARY_DISPLAY_PREFERENCES`. They are exposed through
`GetLibraryDisplayPreferencesUseCase` / `SetLibraryDisplayPreferencesUseCase` so the
library restores every tab's choices after an app restart. The separate ordered string
setting preserves the user-selected filter-chip order, including Genres, Years, and
Composers.
