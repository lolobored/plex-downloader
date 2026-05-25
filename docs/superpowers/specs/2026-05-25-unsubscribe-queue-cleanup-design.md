# Unsubscribe Queue Cleanup — Design

**Issue:** #24 — Unsubscribing from TV show or playlist should remove queued items

## Problem

When a user unsubscribes from a TV show or a Plex playlist, the download queue items that were added because of the subscription remain. Expected: unsubscribing cancels all associated queue entries (PENDING, DONE, ERROR) and cleans up their files. A confirmation popup shows how many items will be removed before committing.

## Behaviour

1. User clicks "Unsubscribe" (TV show) or "Unsubscribe" (playlist).
2. Frontend calls `GET /api/subscriptions/{showId}/queue-count` (or playlist equivalent) → `{ count: N }`.
3. If N > 0: modal "Unsubscribing will remove **N queued item(s)** from your download queue (including in-progress files). Continue?" with **Cancel** / **Unsubscribe** buttons.
4. If N = 0: skip modal, proceed immediately.
5. On confirm: `DELETE /api/subscriptions/{showId}` — deletes subscription row, cancels all cancellable queue items.
6. Items with status `IN_PROGRESS` are excluded from cancellation (active file copy cannot be safely aborted mid-stream). They finish copying; since the subscription is deleted, no further items are enqueued. IN_PROGRESS items ARE included in the count so the user is informed.

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

#### `DownloadService` — refactor + new method

Extract private `doCancelItem(DownloadQueueItem item)` with the file deletion + Tdarr eviction logic (no auth check, no IN_PROGRESS guard — internal use only).

Refactor `cancel(Long itemId, User user)` to delegate to `doCancelItem()` after auth + IN_PROGRESS check.

Add:
```java
@Transactional
public int cancelAllForShow(Long userId, Long showId) {
    List<DownloadQueueItem> items = queueRepo.findAllByUserIdAndShowId(userId, showId);
    int cancelled = 0;
    for (DownloadQueueItem item : items) {
        if (item.getStatus() == DownloadQueueItem.Status.IN_PROGRESS) continue; // skip active copy
        doCancelItem(item);
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
        Optional<DownloadQueueItem> qi = queueRepo.findByUser_IdAndMediaTypeAndMediaId(user.getId(), type, mediaId);
        if (qi.isEmpty()) continue;
        if (qi.get().getStatus() == DownloadQueueItem.Status.IN_PROGRESS) continue;
        cancelItem(user, pi.getPlexId(), pi.getMediaType());
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
| `backend/.../repository/DownloadQueueRepository.java` | Add `findAllByUserIdAndShowId` query |
| `backend/.../service/DownloadService.java` | Extract `doCancelItem()`, add `cancelAllForShow()` |
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
- `cancelAllForShow_cancelsAllNonInProgress` — skips IN_PROGRESS, cancels rest, returns correct count
- `cancelAllForShow_returnsZeroWhenQueueEmpty`

`SubscriptionServiceTest`:
- `cancel_removesSubscriptionAndCancelsQueueItems` — subscription deleted, `cancelAllForShow` called

`PlaylistSyncServiceTest`:
- `cancelAllForUser_skipsInProgress`
- `cancelAllForUser_cancelsAllOtherStatuses`
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
