# Tdarr Integration Design

## Goal

When a user queues a movie or TV episode for download, the app copies the file into a Tdarr-watched folder (`/conversion/in-flight`). Tdarr transcodes it, writes the result to `/conversion/libraries`, and deletes the original. The app polls Tdarr status and stores the output path. Users can cancel at any stage — the app cleans up from both folders and evicts the file from Tdarr's DB. `/conversion/libraries` is the rsync source for the user's laptop.

---

## Architecture

### Containers and volume mappings

Three containers share the same physical files via different mount points:

| Container | Movies | TV Shows | Conversion root |
|---|---|---|---|
| Plex | `/movies` | `/tv` | — |
| App (plex-downloader) | `/movies` | `/tvshows` | `/conversion` |
| Tdarr | — | — | `/media/plex-download` |

Host paths (NAS):
- Movies: `/volume1/movies`
- TV shows: `/volume1/tvshows`
- Conversion: `/volume1/downloads/plex-download`

Tdarr watches `/media/plex-download/in-flight` and outputs to `/media/plex-download/libraries`.

### Conversion folder layout

```
/conversion/                        (app mount point, CONVERSION_PATH on host)
  in-flight/                        ← app copies files here; Tdarr watches this
    movies/inception/inception.mkv
    tvshows/breaking_bad/Season 01/E01.mkv
  libraries/                        ← Tdarr writes transcoded output here
    movies/inception/inception.mp4
    tvshows/breaking_bad/Season 01/E01.mp4
```

---

## Section 1: Path mapping

### Problem

`PathMappingService` currently supports one Plex→App prefix pair. This breaks when both movies (`/movies`→`/movies`, identity) and TV (`/tv`→`/tvshows`) are queued simultaneously. Tdarr introduces a third namespace for the conversion folder.

### New settings (replaces old single pair)

| Setting key | Example value | Purpose |
|---|---|---|
| `plex.path.prefix.movies.plex` | `/movies` | Plex container path prefix for movies |
| `plex.path.prefix.movies.app` | `/movies` | App container path prefix for movies |
| `plex.path.prefix.tv.plex` | `/tv` | Plex container path prefix for TV |
| `plex.path.prefix.tv.app` | `/tvshows` | App container path prefix for TV |
| `tdarr.path.prefix.conversion` | `/media/plex-download` | Tdarr container path to conversion root |

Old keys `plex.path.prefix.plex` and `plex.path.prefix.app` are removed.

### PathMappingService changes

**`translate(plexPath)`** — try movies pair first, then TV pair. Throw `IllegalArgumentException` only if neither matches.

**`appToTdarr(appPath)`** — strip `/conversion` prefix, prepend `tdarr.path.prefix.conversion`. Used when passing file path to Tdarr API as `docID`.

**`tdarrToApp(tdarrPath)`** — strip `tdarr.path.prefix.conversion` prefix, prepend `/conversion`. Used when reading `outputFilePaths[0]` from Tdarr response.

### Liquibase migration

Read existing `plex.path.prefix.plex` and `plex.path.prefix.app` values, write them to all four new keys (user fixes TV values in Settings UI afterward), delete the two old keys.

---

## Section 2: Data model

### `download_queue` — one new column

```sql
ALTER TABLE download_queue ADD COLUMN output_file_path TEXT;
```

Populated when Tdarr reports `TRANSCODED`. Null until then. Stores the **app-namespace** path (e.g. `/conversion/libraries/movies/inception/inception.mp4`).

### `TdarrFileResponse` extension

Add `outputFilePaths: List<String>` to `TdarrClient.TdarrFileResponse` (Tdarr namespace paths).

`TdarrFileStatus` record gains `outputFilePath: String` (nullable, first element of `outputFilePaths` if present).

### `DownloadQueueItem` — `destFilePath` path change

`DownloadService.buildItem()` inserts `in-flight/` into the subDir:

```
Before: {conversionDir}/movies/inception/inception.mkv
After:  {conversionDir}/in-flight/movies/inception/inception.mkv
```

---

## Section 3: Lifecycle

```
PENDING → IN_PROGRESS → DONE          (copy states, existing)
                            ↓
               tdarrStatus: NONE
                            ↓ (Tdarr picks up file)
                       PROCESSING
                            ↓
                  TRANSCODED | TDARR_ERROR
```

### Enqueue

Unchanged flow. File copied to `/conversion/in-flight/...`. Item ends with `status=DONE`, `tdarrStatus=NONE`.

### Tdarr poll (TdarrSyncScheduler, configurable cron, default every 30 min)

Query items where `tdarrStatus NOT IN (TRANSCODED, TDARR_ERROR)`.

For each item:
1. Call `tdarrClient.getFileStatus(pathMapping.appToTdarr(item.destFilePath))`
2. Update `tdarrStatus` and `tdarrError`
3. If newly `TRANSCODED`: translate `outputFilePaths[0]` via `pathMapping.tdarrToApp()`, store in `item.outputFilePath`

### Cancel — `DELETE /api/download/{id}`

| Current state | Action |
|---|---|
| `PENDING` | Delete queue row. Delete `/in-flight` file if it exists. |
| `IN_PROGRESS` (copy running) | Return `409 Conflict`. Client retries once status is `DONE`. |
| `DONE` + `tdarrStatus=NONE` or `PROCESSING` | Call `tdarrClient.deleteFile(appToTdarr(destFilePath))`. Delete `/in-flight` file. Delete queue row. |
| `DONE` + `tdarrStatus=TRANSCODED` | Delete `outputFilePath` from `/libraries`. Delete queue row. |
| `DONE` + `tdarrStatus=TDARR_ERROR` | Delete `/in-flight` file (output was never written). Delete queue row. |

File deletion failures are logged as warnings but do not block queue row removal. All cleanup runs synchronously (file deletes + one Tdarr HTTP call are fast).

---

## Section 4: API

### New endpoint

```
DELETE /api/download/{id}
```

- Auth: item must belong to authenticated user (or user is admin)
- `204 No Content` — item removed and files cleaned up
- `404 Not Found` — item does not exist or belongs to another user
- `409 Conflict` — item is `IN_PROGRESS` (copy ongoing); retry when `DONE`

### Queue response DTO — two new fields exposed

- `outputFilePath: String` (nullable)
- `tdarrError: String` (nullable)

### Settings endpoints

`GET /api/admin/settings` and `PUT /api/admin/settings` gain five new keys and drop two old keys (see Section 1). No structural change to endpoints.

---

## Section 5: Frontend

### QueueView

Add a remove button (`✕`) right-aligned on every queue row.

- Click → calls `DELETE /api/download/{id}`, then re-fetches queue
- Button shows spinner while request in flight
- `IN_PROGRESS` items: button disabled with tooltip "Wait for copy to finish"
- No confirmation dialog (2 s poll makes the removal immediately visible)

### `api/download.js`

```js
export async function removeQueueItem(id) {
  await http.delete(`/api/download/${id}`)
}
```

### SettingsView — path mappings section

Replace single Plex prefix pair with:

```
[ Path Mappings ]
  Movies — Plex prefix:    [/movies          ]
  Movies — App prefix:     [/movies          ]
  TV     — Plex prefix:    [/tv              ]
  TV     — App prefix:     [/tvshows         ]
  Tdarr conversion prefix: [/media/plex-download]
```

Keys: `plex.path.prefix.movies.plex`, `plex.path.prefix.movies.app`, `plex.path.prefix.tv.plex`, `plex.path.prefix.tv.app`, `tdarr.path.prefix.conversion`.

---

## Out of scope

- Tdarr flow/plugin configuration (done in Tdarr UI, not this app)
- Rsync setup (user manages externally against `/conversion/libraries`)
- Progress percentage during Tdarr transcoding (Tdarr API does not expose per-file progress reliably)
- Showing actual movie/episode titles in QueueView (uses `mediaType #mediaId` for now)
