# Unsubscribe Queue Cleanup — Design

**Issue:** #24 — Unsubscribing from TV show or playlist should remove queued items

## Problem

When a user unsubscribes from a TV show or a Plex playlist, the download queue items that were added because of the subscription remain. Expected: unsubscribing cancels all associated queue entries (PENDING, DONE, ERROR) and cleans up their files. A confirmation popup shows how many items will be removed before committing.

## Behaviour

1. User clicks "Unsubscribe" (TV show) or "Unsubscribe" (playlist).
2. Frontend calls `GET /api/subscriptions/{showId}/queue-count` (or playlist equivalent) → `{ count: N }`.
3. If N > 0: modal "Unsubscribing will remove **N queued item(s)** from your download queue (including in-progress files). Continue?" with **Cancel** / **Unsubscribe** buttons.
4. If N = 0: skip modal, proceed immediately.
5. On confirm: `DELETE /api/subscriptions/{showId}` — deletes subscription row, cancels all non-IN_PROGRESS queue items immediately, marks IN_PROGRESS items with `cancellationRequested = true`.
6. IN_PROGRESS items: the active file copy cannot be safely aborted mid-stream. They are flagged for deferred cancellation — when `executeCopyAsync()` finishes the copy it re-reads the item from DB, detects the flag, and cancels (files deleted, Tdarr evicted, DB entry removed) instead of marking DONE. IN_PROGRESS items ARE included in the count so the user is informed.

## File/Tdarr Cleanup (matching existing `DownloadService.cancel()` logic)

| TdarrStatus | Files deleted |
|---|---|
| `NONE` / `PROCESSING` | `destFilePath` (in-flight copy) evicted from Tdarr |
| `TRANSCODED` | `destFilePath` + `outputFilePath` (transcoded output) both deleted; Tdarr DB eviction |

## Architecture

### Backend

#### `DownloadQueueRepository` — new query

```java
@Query("SELECT i FROM DownloadQueueItem i WHERE i.user.id = :userId AND i.mediaType = 'EPISODE' " +
       "AND i.mediaId IN (SELECT e.id FROM Episode e WHERE e.season.show.id = :showId)")
List<DownloadQueueItem> findAllByUserIdAndShowId(@Param("userId") Long userId,
                                                  @Param("showId") Long showId);
```

#### `DownloadQueueItem` — new field

Add `boolean cancellationRequested` (default `false`) to the entity and the DB schema (Flyway migration).

#### `DownloadService` — refactor + new method

Extract private `doCancelItem(DownloadQueueItem item)` with the file deletion + Tdarr eviction logic (no auth check, no IN_PROGRESS guard — internal use only).

Refactor `cancel(Long itemId, User user)` to delegate to `doCancelItem()` after auth + IN_PROGRESS check.

In `executeCopyAsync()`, after the copy completes (success or error path), re-read the item from DB and check `cancellationRequested`:
```java
// re-read after copy
DownloadQueueItem fresh = queueRepo.findById(itemId).orElse(null);
if (fresh != null && fresh.isCancellationRequested()) {
    doCancelItem(fresh);  // deletes files, Tdarr eviction, removes DB entry
    return;
}
// otherwise proceed with normal DONE/ERROR status update
```

Add:
```java
@Transactional
public int cancelAllForShow(Long userId, Long showId) {
    List<DownloadQueueItem> items = queueRepo.findAllByUserIdAndShowId(userId, showId);
    int cancelled = 0;
    for (DownloadQueueItem item : items) {
        if (item.getStatus() == DownloadQueueItem.Status.IN_PROGRESS) {
            item.setCancellationRequested(true);  // defer — executeCopyAsync will clean up after copy
            queueRepo.save(item);
        } else {
            doCancelItem(item);
        }
        cancelled++;
    }
    return cancelled;
}
```

#### `SubscriptionService`

- Inject `DownloadService` (already injected).
- `cancel(Long userId, Long showId)`: after deleting the subscription, call `downloadService.cancelAllForShow(userId, showId)`.

#### `AdminController` / new `SubscriptionController` endpoints

Add to `SubscriptionController`:
```java
@GetMapping("/{showId}/queue-count")
public Map<String, Integer> getQueueCount(@PathVariable Long showId,
                                           @AuthenticationPrincipal User user) {
    int count = queueRepo.findAllByUserIdAndShowId(user.getId(), showId).size();
    return Map.of("count", count);
}
```

#### `PlaylistSyncService` — new method

```java
@Transactional
public int cancelAllForUser(Long playlistId, User user) {
    List<PlaylistItem> items = itemRepo.findByPlaylistIdOrderByOrdinalAsc(playlistId);
    int cancelled = 0;
    for (PlaylistItem pi : items) {
        Long mediaId = resolveLocalId(pi.getPlexId(), pi.getMediaType());
        if (mediaId == null) continue;
        DownloadQueueItem.MediaType type = DownloadQueueItem.MediaType.valueOf(pi.getMediaType());
        Optional<DownloadQueueItem> qiOpt = queueRepo.findByUser_IdAndMediaTypeAndMediaId(user.getId(), type, mediaId);
        if (qiOpt.isEmpty()) continue;
        DownloadQueueItem qi = qiOpt.get();
        if (qi.getStatus() == DownloadQueueItem.Status.IN_PROGRESS) {
            qi.setCancellationRequested(true);  // defer
            queueRepo.save(qi);
        } else {
            cancelItem(user, pi.getPlexId(), pi.getMediaType());
        }
        cancelled++;
    }
    return cancelled;
}
```

#### `PlaylistController` — new endpoint + unsubscribe change

Add:
```java
@GetMapping("/{id}/queue-count")
@ResponseStatus(HttpStatus.OK)
public Map<String, Integer> getQueueCount(@PathVariable Long id,
                                           @AuthenticationPrincipal User user) {
    int count = playlistSyncService.countQueuedForUser(id, user);
    return Map.of("count", count);
}
```

`PlaylistSyncService.countQueuedForUser()`: same as `cancelAllForUser()` but just counts (no deletion).

Modify `unsubscribe()`:
```java
public void unsubscribe(@PathVariable Long id, @AuthenticationPrincipal User user) {
    playlistSyncService.cancelAllForUser(id, user);   // cleanup first
    subRepo.deleteByUserIdAndPlaylistId(user.getId(), id);
}
```

### Frontend

#### New component `ConfirmModal.vue`

Reusable modal with:
- `message` prop (HTML-safe string)
- `confirmLabel` / `cancelLabel` props (defaults: "Confirm" / "Cancel")
- `@confirm` / `@cancel` emits
- Teleports to `<body>`, backdrop blur

#### `SubscribeButton.vue` — confirm before cancel

Replace `doCancel()`:
```js
async function doCancel() {
  open.value = false
  // 1. fetch count
  const { count } = await getShowQueueCount(props.showId)
  if (count > 0) {
    // show modal — set pendingCancel = true
    pendingCancel.value = { count }
    return
  }
  await confirmCancel()
}

async function confirmCancel() {
  pendingCancel.value = null
  loading.value = true
  try { await watchedStore.unsubscribe(props.showId) }
  finally { loading.value = false }
}
```

Add `<ConfirmModal>` to template, shown when `pendingCancel` is set.

#### `PlaylistDetailView.vue` — confirm before unsubscribe

Replace unsubscribe branch in `toggleSubscription()`:
```js
if (subscribed.value) {
  const { count } = await getPlaylistQueueCount(Number(route.params.id))
  if (count > 0) {
    pendingUnsubscribe.value = { count }
    return
  }
  await doUnsubscribe()
}
```

#### API additions

`watched.js`: `getShowQueueCount(showId)` → `GET /api/subscriptions/{showId}/queue-count`

`playlists.js`: `getPlaylistQueueCount(id)` → `GET /api/playlists/{id}/queue-count`

## Files Modified

| File | Change |
|---|---|
| `backend/.../model/DownloadQueueItem.java` | Add `cancellationRequested` boolean field |
| `backend/src/main/resources/db/migration/V<next>__add_cancellation_requested.sql` | `ALTER TABLE download_queue_item ADD COLUMN cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE` |
| `backend/.../repository/DownloadQueueRepository.java` | Add `findAllByUserIdAndShowId` query |
| `backend/.../service/DownloadService.java` | Extract `doCancelItem()`, add `cancelAllForShow()`, deferred cancel in `executeCopyAsync()` |
| `backend/.../service/SubscriptionService.java` | Call `cancelAllForShow()` in `cancel()` |
| `backend/.../controller/SubscriptionController.java` | Add `GET /{showId}/queue-count` |
| `backend/.../service/PlaylistSyncService.java` | Add `cancelAllForUser()`, `countQueuedForUser()` |
| `backend/.../controller/PlaylistController.java` | Add `GET /{id}/queue-count`, call `cancelAllForUser` on unsubscribe |
| `frontend/src/components/ConfirmModal.vue` | New reusable confirm dialog |
| `frontend/src/components/SubscribeButton.vue` | Fetch count, show modal before cancel |
| `frontend/src/views/PlaylistDetailView.vue` | Fetch count, show modal before unsubscribe |
| `frontend/src/api/watched.js` | Add `getShowQueueCount()` |
| `frontend/src/api/playlists.js` | Add `getPlaylistQueueCount()` |

## Tests

### Backend

`DownloadServiceTest`:
- `doCancelItem_deletesPendingItem` — in-flight file deleted, queue entry removed
- `doCancelItem_deletesTranscodedItem` — both files deleted, Tdarr evict called twice
- `cancelAllForShow_cancelsNonInProgressImmediately` — PENDING/DONE/ERROR cancelled, returns correct count
- `cancelAllForShow_flagsInProgressForDeferredCancel` — IN_PROGRESS item gets `cancellationRequested=true`, not deleted yet, still counted
- `cancelAllForShow_returnsZeroWhenQueueEmpty`
- `executeCopyAsync_cancelsAfterCopyWhenFlagged` — after copy, re-reads item, sees flag, calls `doCancelItem` instead of setting DONE

`SubscriptionServiceTest`:
- `cancel_removesSubscriptionAndCancelsQueueItems` — subscription deleted, `cancelAllForShow` called

`PlaylistSyncServiceTest`:
- `cancelAllForUser_flagsInProgressForDeferredCancel`
- `cancelAllForUser_cancelsNonInProgressImmediately`
- `countQueuedForUser_returnsCorrectCount`

`SubscriptionControllerTest`:
- `getQueueCount_returnsItemCount`

`PlaylistControllerTest`:
- `getQueueCount_returnsItemCount`

### Frontend

`SubscribeButton.test.js`:
- `doCancel_showsModalWhenQueueNotEmpty`
- `doCancel_proceedsImmediatelyWhenQueueEmpty`
- `confirmCancel_callsUnsubscribe`

`PlaylistDetailView.test.js`:
- `unsubscribe_showsModalWhenQueueNotEmpty`
- `unsubscribe_proceedsImmediatelyWhenQueueEmpty`
- `confirmUnsubscribe_callsApiAndUpdatesState`

`ConfirmModal.test.js`:
- `emitsConfirmOnConfirmClick`
- `emitsCancelOnCancelClick`
- `rendersMessageProp`

## Ticket

Commits reference `closes #24`.
