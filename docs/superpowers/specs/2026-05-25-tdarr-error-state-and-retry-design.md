# Tdarr Error State + Retry Design

**Goal:** When Tdarr marks a file as Error/Cancelled, the plex-downloader queue item transitions to `ERROR` status, and the user can retry transcoding from the queue UI via Tdarr's requeue API.

**Architecture:** Two backend bug fixes (missing `"Cancelled"` mapping, missing `status → ERROR` transition) + new `requeueFile` method in `TdarrClient` + `requeueOne` in `TdarrSyncScheduler` + `POST /api/queue/{id}/retry` endpoint + retry button in `QueueView.vue`.

**Tech Stack:** Spring Boot 3, RestClient, Vue 3 + Vitest, existing `TdarrClient`/`TdarrSyncScheduler`/`DownloadController`

---

## Data Model (no changes needed)

`DownloadQueueItem` already has:
- `status`: `PENDING | IN_PROGRESS | DONE | ERROR`
- `tdarrStatus`: `NONE | PROCESSING | TRANSCODED | TDARR_ERROR`
- `tdarrError`: `String` (nullable)

Both `tdarrStatus` and `tdarrError` are already serialised in the API response and displayed in `QueueView.vue`.

---

## Bug 1: Missing `"Cancelled"` in `TdarrClient.mapStatus()`

`TranscodeDecisionMaker` can return `"Cancelled"` when a Tdarr job is cancelled. Currently falls through to `NONE` instead of `TDARR_ERROR`.

**Fix:** Add `"Cancelled"` → `TDARR_ERROR` alongside `"TranscodeError"`.

---

## Bug 2: `applyTdarrStatus()` never sets `item.status = ERROR`

When `newStatus = TDARR_ERROR`, `applyTdarrStatus()` updates `tdarrStatus` and `tdarrError` but leaves `item.status = DONE`. This means the item stays in the `syncAll()` query result on every cycle and is never surfaced as a terminal failure.

**Fix:** After saving `tdarrStatus = TDARR_ERROR`, also set `item.status = ERROR` with a message like `"Tdarr transcoding failed: <tdarrError>"`.

---

## New: `TdarrClient.requeueFile(String filePath)`

Calls the Tdarr `cruddb` `update` mode to reset the file's transcode and health-check status back to `"Queued"`:

```
POST /api/v2/cruddb
{
  "data": {
    "collection": "FileJSONDB",
    "mode": "update",
    "docID": "<filePath>",
    "obj": {
      "TranscodeDecisionMaker": "Queued",
      "HealthCheck": "Queued",
      "errors": ""
    }
  }
}
```

Returns void; throws `RestClientException` on failure (caller handles).

---

## New: `TdarrSyncScheduler.requeueOne(Long id)`

```
Preconditions:
  - item exists
  - item.status == ERROR
  - item.tdarrStatus == TDARR_ERROR

Actions:
  1. Call tdarrClient.requeueFile(item.destFilePath)
  2. item.status       = DONE
     item.tdarrStatus  = NONE
     item.errorMessage = null
     item.tdarrError   = null
  3. queueRepo.save(item)
  4. Return updated item

Errors:
  - Item not found → 404
  - Wrong status   → 400 "Item is not in TDARR_ERROR state"
  - Tdarr call fails → propagate as 502
```

---

## New: `POST /api/queue/{id}/retry` in `DownloadController`

```java
@PostMapping("/{id}/retry")
public DownloadQueueItem retryTdarr(@PathVariable Long id,
                                     @AuthenticationPrincipal User user) {
    // Ownership check: item.user == user OR user is ADMIN
    // Delegate to tdarrSyncScheduler.requeueOne(id)
}
```

---

## Frontend: Retry Button in `QueueView.vue`

The queue row already shows a `tdarr-error` badge when `tdarrStatus === 'TDARR_ERROR'`. Add a retry button next to (or replacing) that badge:

```html
<button v-if="item.tdarrStatus === 'TDARR_ERROR'"
        class="btn-retry"
        data-testid="retry-btn"
        @click="retryItem(item.id)">
  ⟳ Retry
</button>
```

`retryItem(id)` calls `POST /api/queue/{id}/retry` (new `retryQueueItem(id)` in queue API module), then refreshes the item in the list (optimistic update or re-fetch).

**No changes needed to `PlaylistDetailView.vue`** — it shows tdarr error text already; retry is a queue-level action.

---

## Files Changed

| File | Change |
|------|--------|
| `backend/.../client/TdarrClient.java` | add `"Cancelled"` mapping; add `requeueFile()` |
| `backend/.../service/TdarrSyncScheduler.java` | set `status=ERROR` in `applyTdarrStatus()`; add `requeueOne()` |
| `backend/.../controller/DownloadController.java` | add `POST /{id}/retry` endpoint |
| `backend/.../controller/DownloadControllerTest.java` | tests for retry endpoint |
| `backend/.../service/TdarrSyncSchedulerTest.java` | tests for applyTdarrStatus ERROR transition + requeueOne |
| `backend/.../client/TdarrClientTest.java` | test for `"Cancelled"` mapping + requeueFile |
| `frontend/src/api/queue.js` | add `retryQueueItem(id)` |
| `frontend/src/views/QueueView.vue` | add retry button |
| `frontend/src/views/__tests__/QueueView.test.js` | tests for retry button behaviour |
