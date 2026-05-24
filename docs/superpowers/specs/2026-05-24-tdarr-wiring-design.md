# Tdarr Wiring Design

**Date:** 2026-05-24
**Scope:** Structured copy paths, atomic file delivery, Tdarr v2 status polling, queue view enrichment

---

## Goal

When a file is copied to the conversion folder, it lands in an organised subfolder structure and appears atomically (so Tdarr never sees a partial file). A scheduler polls the Tdarr v2 API every 30 s and persists processing status back to the queue, which the frontend surfaces as status badges.

## Architecture

```
DownloadService          → builds structured dest path, atomic copy (.tmp → rename)
TdarrClient              → POST /api/v2/cruddb to query file status by path
TdarrSyncScheduler       → cron poller: finds DONE items, calls TdarrClient, updates DB
DownloadQueueItem        → gains tdarrStatus + tdarrError fields
SettingsView             → new Tdarr card (URL + cron)
QueueView                → Tdarr status badges on DONE items
```

---

## Section 1: Data Model & Settings

### Migration `004-tdarr-status.sql`

```sql
ALTER TABLE download_queue
  ADD COLUMN tdarr_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
  ADD COLUMN tdarr_error  TEXT;

INSERT INTO settings (key, value) VALUES ('tdarr.server.url', '');
INSERT INTO settings (key, value) VALUES ('tdarr.sync.cron',  '0 */30 * * * *');
```

### `DownloadQueueItem` additions

```java
@Enumerated(EnumType.STRING)
@Column(name = "tdarr_status", nullable = false)
private TdarrStatus tdarrStatus = TdarrStatus.NONE;

@Column(name = "tdarr_error", columnDefinition = "TEXT")
private String tdarrError;

public enum TdarrStatus { NONE, PROCESSING, TRANSCODED, TDARR_ERROR }
```

`Status` enum (PENDING/IN_PROGRESS/DONE/ERROR) is unchanged. `tdarrStatus` is only meaningful when `status = DONE`.

### Settings keys

| Key | Default | Notes |
|---|---|---|
| `tdarr.server.url` | `""` | Empty → polling silently skipped |
| `tdarr.sync.cron` | `0 */30 * * * *` | Configurable via Settings UI |

---

## Section 2: Structured Copy Paths + Atomic Copy

### Path structure

| Media type | Destination path |
|---|---|
| Movie | `{conversionDir}/movies/{slugTitle}/{filename}` |
| Episode | `{conversionDir}/tvshows/{slugShowTitle}/Season {NN}/{filename}` |

`slugTitle` = title lowercased, spaces → underscores, non-alphanumeric chars stripped.
Example: `"Breaking Bad"` → `breaking_bad`, `"Season 1"` → `Season 01` (zero-padded to 2 digits).

### `DownloadService` changes

`buildItem` signature gains `String title` and `String subPath` parameters (computed by callers).

- `enqueueMovie` loads `Movie.title` → builds `movies/{slug}/filename`
- `enqueueEpisode` loads `Episode` + `Episode.season` + `Episode.season.show` → builds `tvshows/{showSlug}/Season {NN}/filename`
- `enqueueSeason` / `enqueueShow` delegate to `enqueueEpisode` per episode (unchanged)

### Atomic copy in `executeCopyAsync`

```java
Path temp = Path.of(item.getDestFilePath() + ".tmp");
Files.createDirectories(dest.getParent());
Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
try {
    Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
} catch (AtomicMoveNotSupportedException e) {
    Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
}
```

Tdarr sees only the final renamed file, never a partial `.tmp`.

---

## Section 3: Tdarr v2 API Client

**File:** `TdarrClient.java` — Spring `@Component`, injects `RestTemplate` + `SettingsService`.

```java
public Optional<TdarrFileStatus> getFileStatus(String absoluteFilePath)
```

**Request:**
```
POST {tdarr.server.url}/api/v2/cruddb
Content-Type: application/json

{
  "data": {
    "collection": "FileJSONDB",
    "mode":       "getByID",
    "docID":      "{absoluteFilePath}"
  }
}
```

**Response mapping:**

| Tdarr `tdarrStatus` value | Our `TdarrStatus` |
|---|---|
| null / not found / empty | `NONE` |
| `"Queued"` | `PROCESSING` |
| `"Processing"` | `PROCESSING` |
| `"Done transcoding"` | `TRANSCODED` |
| `"No action needed"` | `TRANSCODED` |
| `"Transcode error"` | `TDARR_ERROR` |
| `"Health error"` | `TDARR_ERROR` |
| anything else | `PROCESSING` (unknown intermediate state) |

**Error handling:** If `tdarr.server.url` is blank, or the HTTP call throws `RestClientException`, `getFileStatus` returns `Optional.empty()` and logs a warning. Caller skips the item silently.

---

## Section 4: TdarrSyncScheduler

**File:** `TdarrSyncScheduler.java` — implements `SchedulingConfigurer` (same pattern as `WatchedSyncScheduler`).

Cron from `settings` key `tdarr.sync.cron`, default `0 */30 * * * *`.

**`syncAll()` logic:**
1. `queueRepo.findItemsPendingTdarrSync()` — all items where `status = DONE` AND `tdarr_status NOT IN ('TRANSCODED', 'TDARR_ERROR')`
2. For each item:
   - Call `tdarrClient.getFileStatus(item.getDestFilePath())`
   - If `Optional.empty()` → skip (Tdarr unreachable)
   - If `TDARR_ERROR` → set `tdarrStatus = TDARR_ERROR`, `tdarrError = response.error`
   - Otherwise → set `tdarrStatus` from mapping, clear `tdarrError`
   - Save item

### New repository query

```java
@Query("SELECT i FROM DownloadQueueItem i " +
       "WHERE i.status = 'DONE' " +
       "AND i.tdarrStatus NOT IN ('TRANSCODED', 'TDARR_ERROR')")
List<DownloadQueueItem> findItemsPendingTdarrSync();
```

---

## Section 5: Frontend Changes

### SettingsView — new "Tdarr" card section

```html
<section class="card-section">
  <h3>Tdarr</h3>
  <div class="field">
    <label>Tdarr server URL</label>
    <input v-model="form.tdarrUrl" type="url" placeholder="http://192.168.1.10:8265" />
  </div>
  <div class="field">
    <label>Tdarr sync cron</label>
    <input v-model="form.tdarrSyncCron" type="text" placeholder="0 */30 * * * *" />
  </div>
  <button class="btn-save" @click="save" :disabled="saving">Save</button>
</section>
```

`form.tdarrUrl` maps to `tdarr.server.url`, `form.tdarrSyncCron` to `tdarr.sync.cron`. Both included in `save()` payload.

### QueueView — Tdarr status badges on DONE items

```html
<span v-if="item.tdarrStatus === 'NONE'"        class="tdarr-badge none">Queued in Tdarr</span>
<span v-else-if="item.tdarrStatus === 'PROCESSING'"  class="tdarr-badge processing">Transcoding…</span>
<span v-else-if="item.tdarrStatus === 'TRANSCODED'"  class="tdarr-badge transcoded">Transcoded ✓</span>
<span v-else-if="item.tdarrStatus === 'TDARR_ERROR'" class="tdarr-badge error">
  Tdarr error<span v-if="item.tdarrError"> — {{ item.tdarrError }}</span>
</span>
```

Badges only shown when `item.status === 'DONE'`.

---

## Files Changed

| Action | File |
|---|---|
| Create | `backend/src/main/resources/db/changelog/sql/004-tdarr-status.sql` |
| Create | `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java` |
| Create | `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java` |
| Create | `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java` |
| Create | `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java` |
| Modify | `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java` |
| Modify | `backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java` |
| Modify | `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java` |
| Modify | `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java` |
| Modify | `frontend/src/views/QueueView.vue` |
| Modify | `frontend/src/views/SettingsView.vue` |
| Modify | `frontend/src/api/admin.js` |

---

## Testing

**`TdarrClientTest`** — `@ExtendWith(MockitoExtension.class)`, mock `RestTemplate`:
- `getFileStatus_returnNone_whenUrlBlank`
- `getFileStatus_returnProcessing_whenQueued`
- `getFileStatus_returnTranscoded_whenDoneTranscoding`
- `getFileStatus_returnTdarrError_whenTranscodeError`
- `getFileStatus_returnEmpty_whenRestClientException`

**`TdarrSyncSchedulerTest`** — mock `TdarrClient` + `DownloadQueueRepository`:
- `syncAll_skipsItem_whenTdarrReturnsEmpty`
- `syncAll_updatesStatusToProcessing`
- `syncAll_updatesStatusToTranscoded`
- `syncAll_updatesStatusToError`
- `syncAll_skipsItems_alreadyTranscoded`

**`DownloadServiceTest`** — extend existing tests:
- `enqueueMovie_buildsStructuredPath`
- `enqueueEpisode_buildsStructuredPath`
- `executeCopyAsync_usesAtomicRename`

**Frontend** — extend `QueueView` test: badge renders for each `tdarrStatus` value.
