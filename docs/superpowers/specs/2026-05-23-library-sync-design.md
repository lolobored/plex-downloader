# Library Sync & Browse Design

**Date:** 2026-05-23  
**Scope:** Plex library sync, offline browse, file copy to Tdarr

---

## Goal

Sync the full Plex library (movies + TV shows) into PostgreSQL on a schedule. Users browse media without Plex being online. Selecting a movie or episode copies the file to the `/conversion` folder for Tdarr to process.

---

## Architecture

### Existing (unchanged)
- Plex PIN auth → JWT
- User, Movie, TvShow, Season, Episode, DownloadQueueItem, Actor, Setting models
- JWT filter + Spring Security config

### New components

| Component | Responsibility |
|---|---|
| `PlexMediaServerClient` | HTTP client for local Plex server (not plex.tv) |
| `LibrarySyncService` | Orchestrates full sync: list → detail → poster download → DB upsert |
| `LibrarySyncScheduler` | `@Scheduled` cron + guards manual trigger |
| `SettingsService` | Typed read/write wrapper around `SettingRepository` |
| `PosterStorageService` | Downloads and saves poster/thumb images to disk |
| `PathMappingService` | Translates Plex file paths → app-accessible paths |
| `DownloadService` | Copies source file(s) to conversion folder, enqueues queue items |
| `AdminController` | Settings CRUD, sync trigger, sync status |
| `LibraryController` | Browse endpoints — reads DB only, no Plex at request time |

---

## Settings

Stored in the `settings` table (existing key-value entity). Managed via admin UI.

| Key | Example value | Required |
|---|---|---|
| `plex.server.url` | `http://192.168.1.10:32400` | yes |
| `plex.path.prefix.plex` | `/data/media` | yes |
| `plex.path.prefix.app` | `/mnt` | yes |
| `plex.poster.dir` | `/data/posters` | yes |
| `plex.conversion.dir` | `/data/conversion` | yes |
| `plex.sync.cron` | `0 0 */6 * * *` | no (default: every 6h) |

`SettingsService` caches settings in memory; cache invalidated on every write.

Sync validates all required settings exist before starting. Missing setting → sync aborts with a clear error (no mid-sync crash).

### Admin API

```
GET  /api/admin/settings          → { key: value, ... }
PUT  /api/admin/settings          → upserts one or more keys
GET  /api/admin/sync/status       → { state: IDLE|RUNNING|ERROR, lastSyncAt, itemsSynced, error }
POST /api/admin/sync              → triggers full sync (async); no-op if already running
```

All endpoints require `ADMIN` role.

---

## Plex Media Server Client

`PlexMediaServerClient` — `@Component`. Reads `plex.server.url` from `SettingsService` at call time (not cached in constructor — URL changes take effect immediately).

Admin's `plexToken` (from `users` table, id=1) attached to every request.

**Request headers:**
```
X-Plex-Token: {admin plexToken}
Accept: application/json
X-Plex-Client-Identifier: plex-downloader-app
```

**Methods:**

```java
List<PlexLibrary> getLibraries()
// GET /library/sections
// Returns: key, title, type (movie|show), agent

PlexLibraryPage getLibraryContents(String libraryKey, int offset, int size)
// GET /library/sections/{key}/all?includeGuids=1
// Returns: totalSize, List<PlexItem>

PlexItemDetail getItemDetail(String ratingKey)
// GET /library/metadata/{ratingKey}
// Returns: full metadata + Role[] (cast) + Genre[] + Director[]

List<PlexItem> getChildren(String ratingKey)
// GET /library/metadata/{ratingKey}/children
// Returns: seasons (when called on show) or episodes (when called on season)

void downloadThumb(String thumbPath, Path destination)
// GET {plex.server.url}{thumbPath}?X-Plex-Token={token}
// Streams to destination file
```

**Key DTOs:**

`PlexItem`:
- `ratingKey`, `parentRatingKey`, `grandparentRatingKey`
- `type` (movie | show | season | episode)
- `title`, `year`, `summary`, `rating`, `studio`, `duration`
- `thumb` (path e.g. `/library/metadata/123/thumb`)
- `updatedAt` (Unix epoch seconds — used to skip unchanged posters)
- `Guid[]` → parsed to `tmdbId`, `imdbId`, `tvdbId`
- `Media[].Part[].file` → first part taken as `filePath`
- `Genre[]`, `Director[]`

`PlexItemDetail` extends `PlexItem` with:
- `Role[]` → cast: `name`, `character`, `thumb`

**Error handling:** `PlexApiException` (unchecked) wraps HTTP/network errors. Sync catches it per item — one bad item logs a warning and continues; the sync does not abort.

---

## Library Sync

### Sync flow

```
1. Validate required settings
2. Load admin user (id=1) → plexToken → fail if missing
3. GET /library/sections → keep type=movie and type=show only
4. For each movie library:
     Paginate /library/sections/{key}/all?includeGuids=1 (page size 50)
     For each movie:
       GET /library/metadata/{ratingKey}   ← cast, genres, directors
       Download poster if updatedAt changed (skip if file exists and DB updatedAt matches)
       Upsert Movie in DB
5. For each show library:
     Paginate /library/sections/{key}/all (returns shows)
     For each show:
       GET /library/metadata/{ratingKey}   ← cast, genres
       Download show poster
       Upsert TvShow in DB
       GET /library/metadata/{ratingKey}/children  ← seasons
       For each season:
         Download season poster
         Upsert Season in DB
         GET /library/metadata/{seasonKey}/children  ← episodes
         For each episode:
           Upsert Episode in DB (no extra detail call)
6. Update lastSyncAt, set state = IDLE
```

### Sync state (in-memory)

```java
enum SyncState { IDLE, RUNNING, ERROR }

SyncState state;
Instant lastSyncAt;
int itemsSynced;
String lastError;
```

### Upsert strategy

Match on `plexId` (= Plex `ratingKey`). Exists → update all fields. Missing → insert. Items removed from Plex are **not** auto-deleted (safety). Orphan cleanup is a future admin action.

### Poster skip logic

Before downloading: check if `{plex.poster.dir}/{ratingKey}.jpg` exists AND entity `syncedAt` is after Plex `updatedAt`. If both true → skip download.

### Scheduler

`LibrarySyncScheduler` — `@Scheduled` with cron from `plex.sync.cron` setting (default `0 0 */6 * * *`). Manual trigger from `AdminController.triggerSync()` calls the same `LibrarySyncService.syncAll()`. Guard: if `state == RUNNING`, manual trigger returns 409 immediately.

---

## Path Mapping

`PathMappingService`:

```java
// plex.path.prefix.plex = /data/media
// plex.path.prefix.app  = /mnt
// input:  /data/media/Movies/Inception.mkv
// output: /mnt/Movies/Inception.mkv
String translate(String plexPath)
```

Throws `PathMappingException` if path does not start with the configured Plex prefix.

---

## File Copy (Download Action)

`DownloadService` — handles user download requests.

**API:**
```
POST /api/download
Body: { type: "MOVIE" | "SHOW" | "SEASON" | "EPISODE", id: Long }
Response: { jobIds: [Long], status: "QUEUED" }
```

**Flow:**
1. Load entity/entities from DB → collect `filePath` values
2. Translate each path via `PathMappingService`
3. Verify source file exists → 404 if missing
4. `Files.copy(source, dest, REPLACE_EXISTING)` where `dest = {plex.conversion.dir}/{filename}`
5. Skip copy if dest already exists with same byte size (idempotent)
6. Create one `DownloadQueueItem` per file (type=MOVIE or EPISODE, status=PENDING)
7. Return job IDs

SHOW and SEASON requests expand into multiple individual MOVIE/EPISODE queue items at the service layer. `DownloadQueueItem.MediaType` stays `{MOVIE, EPISODE}`.

**New fields on `DownloadQueueItem`:** `sourceFilePath` (TEXT), `destFilePath` (TEXT).

Copy runs **asynchronously** (`@Async`). The endpoint enqueues items immediately (status=PENDING) and returns job IDs. A background thread performs the actual `Files.copy` calls and updates each item's status to DONE or ERROR. Frontend polls `GET /api/download/queue` to track progress.

---

## Data Model Changes

### `User` — add
```java
@Column(name = "plex_token")
private String plexToken;
```
Stored in `AuthService.upsertUser()` from the Plex PIN authToken.

### `Movie` — add
```java
@Column(name = "tmdb_id") private Long tmdbId;
@Column(name = "imdb_id") private String imdbId;
@Column private Float rating;
@Column private String studio;

@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "movie_directors", joinColumns = @JoinColumn(name = "movie_id"))
@Column(name = "director")
private List<String> directors = new ArrayList<>();

@ManyToMany(fetch = FetchType.LAZY)
@JoinTable(name = "movie_actors",
    joinColumns = @JoinColumn(name = "movie_id"),
    inverseJoinColumns = @JoinColumn(name = "actor_id"))
private List<Actor> actors = new ArrayList<>();
```

### `TvShow` — add
```java
@Column(name = "tmdb_id") private Long tmdbId;
@Column(name = "tvdb_id") private Long tvdbId;
@Column private Float rating;
@Column(name = "total_seasons") private Integer totalSeasons;

@ManyToMany(fetch = FetchType.LAZY)
@JoinTable(name = "show_actors",
    joinColumns = @JoinColumn(name = "show_id"),
    inverseJoinColumns = @JoinColumn(name = "actor_id"))
private List<Actor> actors = new ArrayList<>();
```

### `Season` — no changes ✓
### `Episode` — no changes ✓
### `Actor` — no changes ✓

### `DownloadQueueItem` — add
```java
@Column(name = "source_file_path", columnDefinition = "TEXT")
private String sourceFilePath;

@Column(name = "dest_file_path", columnDefinition = "TEXT")
private String destFilePath;
```

---

## Library Browse API

`LibraryController` — reads DB only, no Plex calls at browse time.

```
GET /api/movies?page=0&size=50&search=&genre=    → paginated movie list
GET /api/movies/{id}                             → movie detail + cast
GET /api/tv?page=0&size=50&search=               → paginated show list
GET /api/tv/{showId}                             → show detail + cast + seasons
GET /api/tv/{showId}/seasons/{seasonId}          → season + episodes
GET /api/tv/{showId}/seasons/{seasonId}/episodes/{episodeId} → episode detail
GET /api/posters/{ratingKey}.jpg                 → serves poster from disk
```

Poster endpoint reads from `{plex.poster.dir}/{ratingKey}.jpg` and streams bytes. Returns 404 if not yet downloaded.

---

## What Is Not In Scope

- TMDB enrichment (no external API keys required)
- Incremental/recent-only sync (full sync only; future iteration)
- Orphan cleanup UI
- Async file copy progress tracking
- Actor photo download (actor `photoUrl` stored but download deferred)
