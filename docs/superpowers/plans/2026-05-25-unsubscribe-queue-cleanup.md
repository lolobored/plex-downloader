# Unsubscribe Queue Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a user unsubscribes from a TV show or Plex playlist, cancel all associated download queue items (immediately for non-active items, deferred for in-progress copies), and show a confirmation modal with the item count before proceeding.

**Architecture:** Add `cancellationRequested` flag to `DownloadQueueItem`; extract `doCancelItem()` from `DownloadService.cancel()` for reuse; add `cancelAllForShow()` to `DownloadService` and `cancelAllForUser()` to `PlaylistSyncService`; wire both into the unsubscribe paths; add two queue-count GET endpoints; add a reusable `ConfirmModal.vue` and wire it into `SubscribeButton.vue` and `PlaylistDetailView.vue`.

**Tech Stack:** Spring Boot 3, Liquibase YAML migrations, Mockito unit tests, SpringBootTest + MockMvc controller tests, Vue 3 + Vitest + `@vue/test-utils`

---

## Context

- Java version: `21.0.4-tem` (read from `.sdkmanrc`)
- Liquibase uses `includeAll` on `db/changelog/yaml/` — any new `.yaml` file in that dir is picked up automatically, sorted by filename. Latest is `008-queue-title.yaml`.
- `DownloadService` already has a `cancel(Long itemId, User user)` method with full file deletion + Tdarr eviction logic. We extract that logic into a private `doCancelItem()` method.
- `SubscriptionService` already injects `DownloadQueueRepository` (field `queueRepo`). We add `getQueueCount()` and the existing `cancel()` will call `downloadService.cancelAllForShow()`.
- `PlaylistSyncService` already has `cancelItem(User, String plexId, String mediaType)` — we add `cancelAllForUser()` and `countQueuedForUser()` that iterate playlist items and call it.
- Frontend test pattern: services tested with `@ExtendWith(MockitoExtension.class)` + `@InjectMocks`; controllers with `@SpringBootTest(webEnvironment = MOCK)` + `@MockitoBean`.
- `SubscriptionController` is at `/api/subscriptions`, `PlaylistController` at `/api/playlists`.
- Test command (backend): `source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --no-daemon`
- Test command (frontend): `cd frontend && npm run test`

---

### Task 1: Add `cancellationRequested` field to entity + Liquibase migration

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java`
- Create: `backend/src/main/resources/db/changelog/yaml/009-cancellation-requested.yaml`

- [ ] **Step 1: Write the Liquibase migration**

Create `backend/src/main/resources/db/changelog/yaml/009-cancellation-requested.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 009-cancellation-requested
      author: system
      changes:
        - addColumn:
            tableName: download_queue
            columns:
              - column:
                  name: cancellation_requested
                  type: BOOLEAN
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
```

- [ ] **Step 2: Add field to `DownloadQueueItem`**

In `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java`, add after the `completedAt` field:

```java
@Column(name = "cancellation_requested", nullable = false)
private boolean cancellationRequested = false;
```

The class already has `@Data` from Lombok so `isCancellationRequested()` and `setCancellationRequested(boolean)` are generated automatically.

- [ ] **Step 3: Run backend tests to verify migration applies and entity compiles**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
cd backend && ./gradlew test --no-daemon 2>&1 | tail -20
```

Expected: all green (field is ignored by existing tests; Hibernate `validate` mode passes because the column now exists in the schema)

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java \
        backend/src/main/resources/db/changelog/yaml/009-cancellation-requested.yaml
git commit -m "feat: add cancellationRequested field to DownloadQueueItem

refs #24"
```

---

### Task 2: Add `findAllByUserIdAndShowId` to `DownloadQueueRepository`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java`

- [ ] **Step 1: Write the failing test**

In `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java`, add this test (it calls a method that doesn't exist yet on the repo — the test validates that `cancelAllForShow` uses the query correctly; we use it here simply to verify the query compiles and is wired):

Actually we validate the query indirectly via `cancelAllForShow` tests in Task 3. For this task, just add the method — it's a Spring Data JPA derived query, verifiable by the app starting up. Confirm with compile:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
cd backend && ./gradlew compileJava --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Add query to `DownloadQueueRepository`**

In `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java`, add after `findByStatusOrderByQueuePositionAsc`:

```java
@Query("SELECT i FROM DownloadQueueItem i WHERE i.user.id = :userId AND i.mediaType = 'EPISODE' " +
       "AND i.mediaId IN (SELECT e.id FROM Episode e WHERE e.season.show.id = :showId)")
List<DownloadQueueItem> findAllByUserIdAndShowId(@Param("userId") Long userId,
                                                  @Param("showId") Long showId);
```

You also need this import at the top (check if `@Query` and `@Param` are already imported — they are on lines that have `findActiveEpisodeIdsForShow`):
```java
// already present: @Query, @Param — no new imports needed
```

- [ ] **Step 3: Verify compile**

```bash
cd backend && ./gradlew compileJava --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java
git commit -m "feat: add findAllByUserIdAndShowId query to DownloadQueueRepository

refs #24"
```

---

### Task 3: Refactor `DownloadService` — extract `doCancelItem()`, add `cancelAllForShow()`, deferred cancel in `executeCopyAsync()`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add these tests to `DownloadServiceTest.java`. Place them after the existing `cancel_adminCanCancelAnyItem` test:

```java
@Test
void cancelAllForShow_cancelsNonInProgressItems(@TempDir Path tmp) throws Exception {
    Path inFlightFile = tmp.resolve("ep.mkv");
    Files.writeString(inFlightFile, "data");

    DownloadQueueItem pending = new DownloadQueueItem();
    pending.setId(20L);
    pending.setStatus(DownloadQueueItem.Status.PENDING);
    pending.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
    pending.setDestFilePath(inFlightFile.toString());

    when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(pending));

    int count = service.cancelAllForShow(1L, 10L);

    assertThat(count).isEqualTo(1);
    verify(queueRepo).delete(pending);
    assertThat(inFlightFile).doesNotExist();
}

@Test
void cancelAllForShow_flagsInProgressForDeferredCancel() {
    DownloadQueueItem active = new DownloadQueueItem();
    active.setId(21L);
    active.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
    active.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
    active.setDestFilePath("/conv/in-flight/ep.mkv");

    when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(active));
    when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    int count = service.cancelAllForShow(1L, 10L);

    assertThat(count).isEqualTo(1);
    assertThat(active.isCancellationRequested()).isTrue();
    verify(queueRepo).save(active);
    verify(queueRepo, never()).delete(any());
}

@Test
void cancelAllForShow_returnsZeroWhenQueueEmpty() {
    when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of());

    int count = service.cancelAllForShow(1L, 10L);

    assertThat(count).isEqualTo(0);
    verify(queueRepo, never()).delete(any());
}

@Test
void executeCopyAsync_cancelsAfterCopyWhenFlagged() throws Exception {
    Path sourceFile = tempDir.resolve("source.mkv");
    Files.writeString(sourceFile, "content");
    Path destFile = tempDir.resolve("out").resolve("out.mkv");

    DownloadQueueItem item = new DownloadQueueItem();
    item.setId(30L);
    item.setSourceFilePath(sourceFile.toString());
    item.setDestFilePath(destFile.toString());
    item.setStatus(DownloadQueueItem.Status.PENDING);
    item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);

    // fresh re-read returns item with cancellationRequested = true
    DownloadQueueItem freshItem = new DownloadQueueItem();
    freshItem.setId(30L);
    freshItem.setSourceFilePath(sourceFile.toString());
    freshItem.setDestFilePath(destFile.toString());
    freshItem.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
    freshItem.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
    freshItem.setCancellationRequested(true);

    when(queueRepo.findById(30L))
        .thenReturn(Optional.of(item))    // first call: load item
        .thenReturn(Optional.of(freshItem)); // second call: re-read after copy
    when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.executeCopyAsync(30L);

    // item should be deleted, not saved as DONE
    verify(queueRepo).delete(freshItem);
    verify(queueRepo, never()).save(argThat(i ->
        i instanceof DownloadQueueItem qi && qi.getStatus() == DownloadQueueItem.Status.DONE));
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
cd backend && ./gradlew test --tests "*DownloadServiceTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — methods `cancelAllForShow`, `findAllByUserIdAndShowId` not yet wired

- [ ] **Step 3: Implement `doCancelItem()`, refactor `cancel()`, add `cancelAllForShow()`, update `executeCopyAsync()`**

Replace the entire `cancel()` method and add `doCancelItem()` and `cancelAllForShow()` in `DownloadService.java`. The full section to replace (from the `@Transactional` before `cancel` through the end of the method body at line 193):

```java
@Transactional
public void cancel(Long itemId, User user) {
    DownloadQueueItem item = queueRepo.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found"));

    if (!item.getUser().getId().equals(user.getId()) && user.getRole() != User.Role.ADMIN) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item");
    }
    if (item.getStatus() == DownloadQueueItem.Status.IN_PROGRESS) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Copy in progress, retry when DONE");
    }
    doCancelItem(item);
}

@Transactional
public int cancelAllForShow(Long userId, Long showId) {
    List<DownloadQueueItem> items = queueRepo.findAllByUserIdAndShowId(userId, showId);
    int cancelled = 0;
    for (DownloadQueueItem item : items) {
        if (item.getStatus() == DownloadQueueItem.Status.IN_PROGRESS) {
            item.setCancellationRequested(true);
            queueRepo.save(item);
        } else {
            doCancelItem(item);
        }
        cancelled++;
    }
    return cancelled;
}

// Package-private for testing
void doCancelItem(DownloadQueueItem item) {
    // Delete in-flight file (always attempt — may already be gone after transcoding)
    if (item.getDestFilePath() != null) {
        try {
            Files.deleteIfExists(Path.of(item.getDestFilePath()));
        } catch (IOException e) {
            log.warn("Could not delete in-flight file {}: {}", item.getDestFilePath(), e.getMessage());
        }
    }

    if (item.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED) {
        String librariesPath = item.getOutputFilePath();
        if (librariesPath == null && item.getDestFilePath() != null) {
            Path derived = deriveLibrariesPath(item.getDestFilePath());
            if (derived != null) librariesPath = derived.toString();
        }
        if (librariesPath != null) {
            final String lp = librariesPath;
            try {
                Files.deleteIfExists(Path.of(lp));
            } catch (IOException e) {
                log.warn("Could not delete transcoded file {}: {}", lp, e.getMessage());
            }
            try {
                tdarrClient.deleteFile(lp);
            } catch (Exception e) {
                log.warn("Tdarr eviction (libraries) failed for item {}: {}", item.getId(), e.getMessage());
            }
        } else {
            log.warn("Item {} is TRANSCODED but could not resolve libraries path", item.getId());
        }
    } else {
        if (item.getDestFilePath() != null) {
            try {
                tdarrClient.deleteFile(item.getDestFilePath());
            } catch (Exception e) {
                log.warn("Tdarr eviction failed for item {}: {}", item.getId(), e.getMessage());
            }
        }
    }

    queueRepo.delete(item);
}
```

Now update `executeCopyAsync()`. Replace the final two lines (`queueRepo.save(item);`) and the catch block ending with the final save. The full method becomes:

```java
@Async("downloadExecutor")
public void executeCopyAsync(Long itemId) {
    DownloadQueueItem item = queueRepo.findById(itemId).orElse(null);
    if (item == null) return;

    item.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
    queueRepo.save(item);

    try {
        Path source = Path.of(item.getSourceFilePath());
        Path dest = Path.of(item.getDestFilePath());
        if (!Files.exists(source)) {
            throw new IOException("Source file not found: " + source);
        }
        Files.createDirectories(dest.getParent());
        Path temp = Path.of(item.getDestFilePath() + ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setCompletedAt(Instant.now());
    } catch (IOException e) {
        item.setStatus(DownloadQueueItem.Status.ERROR);
        item.setErrorMessage(e.getMessage());
        log.error("Copy failed for item {}: {}", itemId, e.getMessage());
    }

    // Re-read to detect cancellationRequested flag set by an unsubscribe during copy
    DownloadQueueItem fresh = queueRepo.findById(itemId).orElse(null);
    if (fresh != null && fresh.isCancellationRequested()) {
        doCancelItem(fresh);
        return;
    }
    queueRepo.save(item);
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd backend && ./gradlew test --tests "*DownloadServiceTest*" --no-daemon 2>&1 | tail -20
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java
git commit -m "refactor: extract doCancelItem(), add cancelAllForShow(), deferred cancel in executeCopyAsync

refs #24"
```

---

### Task 4: `SubscriptionService` — add `getQueueCount()`, call `cancelAllForShow()` in `cancel()`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/SubscriptionService.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/SubscriptionServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add these tests to `SubscriptionServiceTest.java`, after the existing `cancel_deletesSubscription` test:

```java
@Test
void cancel_callsCancelAllForShowAfterDeletingSubscription() {
    ShowSubscription sub = new ShowSubscription();
    sub.setId(5L);
    when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.of(sub));
    when(downloadService.cancelAllForShow(1L, 10L)).thenReturn(3);

    service.cancel(1L, 10L);

    InOrder order = inOrder(subscriptionRepo, downloadService);
    order.verify(subscriptionRepo).delete(sub);
    order.verify(downloadService).cancelAllForShow(1L, 10L);
}

@Test
void cancel_doesNotCallCancelWhenNoSubscription() {
    when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.empty());

    service.cancel(1L, 10L);

    verify(downloadService, never()).cancelAllForShow(anyLong(), anyLong());
}

@Test
void getQueueCount_returnsItemCount() {
    DownloadQueueItem item1 = new DownloadQueueItem(); item1.setId(1L);
    DownloadQueueItem item2 = new DownloadQueueItem(); item2.setId(2L);
    when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(item1, item2));

    int count = service.getQueueCount(1L, 10L);

    assertThat(count).isEqualTo(2);
}
```

You also need `import org.mockito.InOrder;` at the top of the test class.

- [ ] **Step 2: Run tests to verify they fail**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
cd backend && ./gradlew test --tests "*SubscriptionServiceTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `getQueueCount` method missing, `cancelAllForShow` not called

- [ ] **Step 3: Update `SubscriptionService`**

In `cancel(Long userId, Long showId)`, call `cancelAllForShow` after deleting the subscription:

```java
@Transactional
public void cancel(Long userId, Long showId) {
    subscriptionRepo.findByUserIdAndShowId(userId, showId)
        .ifPresent(sub -> {
            subscriptionRepo.delete(sub);
            downloadService.cancelAllForShow(userId, showId);
        });
}
```

Add this new method anywhere in the class:

```java
public int getQueueCount(Long userId, Long showId) {
    return queueRepo.findAllByUserIdAndShowId(userId, showId).size();
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "*SubscriptionServiceTest*" --no-daemon 2>&1 | tail -10
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/SubscriptionService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/SubscriptionServiceTest.java
git commit -m "feat: cancel queue items on show unsubscribe, add getQueueCount

refs #24"
```

---

### Task 5: `SubscriptionController` — add `GET /{showId}/queue-count` endpoint

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/controller/SubscriptionController.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/controller/SubscriptionControllerTest.java`

- [ ] **Step 1: Write failing test**

In `SubscriptionControllerTest.java`, add after the existing `unsubscribe_returns204` test:

```java
@Test
void getQueueCount_returnsCount() throws Exception {
    when(subscriptionService.getQueueCount(1L, 10L)).thenReturn(7);

    mockMvc.perform(get("/api/subscriptions/10/queue-count"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(7));
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
cd backend && ./gradlew test --tests "*SubscriptionControllerTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — endpoint not found (404)

- [ ] **Step 3: Add endpoint to `SubscriptionController`**

In `SubscriptionController.java`, add after the `unsubscribe` method:

```java
@GetMapping("/{showId}/queue-count")
public Map<String, Integer> getQueueCount(@PathVariable Long showId,
                                           @AuthenticationPrincipal User user) {
    return Map.of("count", subscriptionService.getQueueCount(user.getId(), showId));
}
```

Add import at top of file: `import java.util.Map;`

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "*SubscriptionControllerTest*" --no-daemon 2>&1 | tail -10
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/controller/SubscriptionController.java \
        backend/src/test/java/org/lolobored/plexdownloader/controller/SubscriptionControllerTest.java
git commit -m "feat: add GET /api/subscriptions/{showId}/queue-count endpoint

refs #24"
```

---

### Task 6: `PlaylistSyncService` — add `countQueuedForUser()` and `cancelAllForUser()`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/PlaylistSyncService.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/PlaylistSyncServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add these tests to `PlaylistSyncServiceTest.java`, after the existing `enqueueForSubscription_queuesAllCurrentItems` test:

```java
@Test
void countQueuedForUser_returnsCorrectCount() {
    PlaylistItem pi1 = new PlaylistItem(); pi1.setPlexId("m1"); pi1.setMediaType("MOVIE");
    PlaylistItem pi2 = new PlaylistItem(); pi2.setPlexId("e1"); pi2.setMediaType("EPISODE");
    PlaylistItem pi3 = new PlaylistItem(); pi3.setPlexId("m2"); pi3.setMediaType("MOVIE"); // not in queue
    when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(pi1, pi2, pi3));

    User user = new User(); user.setId(1L);
    Movie m1 = new Movie(); m1.setId(100L);
    Episode ep = new Episode(); ep.setId(200L);
    Movie m2 = new Movie(); m2.setId(300L);

    when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m1));
    when(episodeRepo.findByPlexId("e1")).thenReturn(Optional.of(ep));
    when(movieRepo.findByPlexId("m2")).thenReturn(Optional.of(m2));

    DownloadQueueItem qi1 = new DownloadQueueItem(); qi1.setStatus(DownloadQueueItem.Status.PENDING);
    DownloadQueueItem qi2 = new DownloadQueueItem(); qi2.setStatus(DownloadQueueItem.Status.DONE);
    when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L))
        .thenReturn(Optional.of(qi1));
    when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.EPISODE, 200L))
        .thenReturn(Optional.of(qi2));
    when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 300L))
        .thenReturn(Optional.empty());

    int count = service.countQueuedForUser(10L, user);

    assertThat(count).isEqualTo(2);
}

@Test
void cancelAllForUser_cancelsNonInProgressImmediately(@TempDir Path tmp) throws Exception {
    Path inFlightFile = tmp.resolve("movie.mkv");
    Files.writeString(inFlightFile, "data");

    PlaylistItem pi = new PlaylistItem(); pi.setPlexId("m1"); pi.setMediaType("MOVIE");
    when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(pi));

    User user = new User(); user.setId(1L);
    Movie m = new Movie(); m.setId(100L);
    when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));

    DownloadQueueItem qi = new DownloadQueueItem();
    qi.setStatus(DownloadQueueItem.Status.PENDING);
    qi.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
    qi.setDestFilePath(inFlightFile.toString());
    when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L))
        .thenReturn(Optional.of(qi));

    int count = service.cancelAllForUser(10L, user);

    assertThat(count).isEqualTo(1);
    verify(queueRepo).delete(qi);
    assertThat(inFlightFile).doesNotExist();
}

@Test
void cancelAllForUser_flagsInProgressForDeferredCancel() {
    PlaylistItem pi = new PlaylistItem(); pi.setPlexId("m1"); pi.setMediaType("MOVIE");
    when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(pi));

    User user = new User(); user.setId(1L);
    Movie m = new Movie(); m.setId(100L);
    when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));

    DownloadQueueItem qi = new DownloadQueueItem();
    qi.setId(50L);
    qi.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
    qi.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
    qi.setDestFilePath("/conv/in-flight/movie.mkv");
    when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L))
        .thenReturn(Optional.of(qi));
    when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    int count = service.cancelAllForUser(10L, user);

    assertThat(count).isEqualTo(1);
    assertThat(qi.isCancellationRequested()).isTrue();
    verify(queueRepo).save(qi);
    verify(queueRepo, never()).delete(any());
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
cd backend && ./gradlew test --tests "*PlaylistSyncServiceTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — methods `countQueuedForUser`, `cancelAllForUser` not found

- [ ] **Step 3: Add methods to `PlaylistSyncService`**

Add these methods at the end of `PlaylistSyncService.java`, before the closing `}`:

```java
public int countQueuedForUser(Long playlistId, User user) {
    List<PlaylistItem> items = itemRepo.findByPlaylistIdOrderByOrdinalAsc(playlistId);
    int count = 0;
    for (PlaylistItem pi : items) {
        Long mediaId = resolveLocalId(pi.getPlexId(), pi.getMediaType());
        if (mediaId == null) continue;
        DownloadQueueItem.MediaType type = DownloadQueueItem.MediaType.valueOf(pi.getMediaType());
        if (queueRepo.findByUser_IdAndMediaTypeAndMediaId(user.getId(), type, mediaId).isPresent()) {
            count++;
        }
    }
    return count;
}

@Transactional
public int cancelAllForUser(Long playlistId, User user) {
    List<PlaylistItem> items = itemRepo.findByPlaylistIdOrderByOrdinalAsc(playlistId);
    int cancelled = 0;
    for (PlaylistItem pi : items) {
        Long mediaId = resolveLocalId(pi.getPlexId(), pi.getMediaType());
        if (mediaId == null) continue;
        DownloadQueueItem.MediaType type = DownloadQueueItem.MediaType.valueOf(pi.getMediaType());
        Optional<DownloadQueueItem> qiOpt =
            queueRepo.findByUser_IdAndMediaTypeAndMediaId(user.getId(), type, mediaId);
        if (qiOpt.isEmpty()) continue;
        DownloadQueueItem qi = qiOpt.get();
        if (qi.getStatus() == DownloadQueueItem.Status.IN_PROGRESS) {
            qi.setCancellationRequested(true);
            queueRepo.save(qi);
        } else {
            cancelItem(user, pi.getPlexId(), pi.getMediaType());
        }
        cancelled++;
    }
    return cancelled;
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "*PlaylistSyncServiceTest*" --no-daemon 2>&1 | tail -10
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/PlaylistSyncService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/PlaylistSyncServiceTest.java
git commit -m "feat: add countQueuedForUser and cancelAllForUser to PlaylistSyncService

refs #24"
```

---

### Task 7: `PlaylistController` — add `GET /{id}/queue-count`, call `cancelAllForUser()` on unsubscribe

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/controller/PlaylistController.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/controller/PlaylistControllerTest.java`

- [ ] **Step 1: Write failing tests**

In `PlaylistControllerTest.java`:

Replace the test `unsubscribe_deletesSubscription_doesNotCancelFiles` with:

```java
@Test
void unsubscribe_callsCancelAllForUser_thenDeletesSubscription() throws Exception {
    when(playlistSyncService.cancelAllForUser(1L, user)).thenReturn(3);

    mockMvc.perform(delete("/api/playlists/1/subscribe")).andExpect(status().isNoContent());

    verify(playlistSyncService).cancelAllForUser(1L, user);
    verify(subRepo).deleteByUserIdAndPlaylistId(1L, 1L);
}
```

Add the new queue-count test after the subscribe tests:

```java
@Test
void getQueueCount_returnsCount() throws Exception {
    when(playlistSyncService.countQueuedForUser(1L, user)).thenReturn(4);

    mockMvc.perform(get("/api/playlists/1/queue-count"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(4));
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
cd backend && ./gradlew test --tests "*PlaylistControllerTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `cancelAllForUser` not called, endpoint not found

- [ ] **Step 3: Update `PlaylistController`**

Replace the `unsubscribe` method:

```java
@DeleteMapping("/{id}/subscribe")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void unsubscribe(@PathVariable Long id, @AuthenticationPrincipal User user) {
    playlistSyncService.cancelAllForUser(id, user);
    subRepo.deleteByUserIdAndPlaylistId(user.getId(), id);
}
```

Add the new endpoint after `unsubscribe`:

```java
@GetMapping("/{id}/queue-count")
public Map<String, Integer> getQueueCount(@PathVariable Long id,
                                           @AuthenticationPrincipal User user) {
    return Map.of("count", playlistSyncService.countQueuedForUser(id, user));
}
```

Add import at top: `import java.util.Map;`

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "*PlaylistControllerTest*" --no-daemon 2>&1 | tail -10
```

Expected: all PASS

- [ ] **Step 5: Run full backend test suite**

```bash
cd backend && ./gradlew test --no-daemon 2>&1 | tail -20
```

Expected: all green

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/controller/PlaylistController.java \
        backend/src/test/java/org/lolobored/plexdownloader/controller/PlaylistControllerTest.java
git commit -m "feat: add GET /api/playlists/{id}/queue-count, cancel queue on unsubscribe

refs #24"
```

---

### Task 8: Frontend API additions — `getShowQueueCount` and `getPlaylistQueueCount`

**Files:**
- Modify: `frontend/src/api/watched.js`
- Modify: `frontend/src/api/playlists.js`

- [ ] **Step 1: Add `getShowQueueCount` to `watched.js`**

In `frontend/src/api/watched.js`, add after `syncNow`:

```js
export const getShowQueueCount = (showId) =>
  api.get(`/api/subscriptions/${showId}/queue-count`).then(r => r.data)
```

- [ ] **Step 2: Add `getPlaylistQueueCount` to `playlists.js`**

In `frontend/src/api/playlists.js`, add after `unsubscribe`:

```js
export async function getPlaylistQueueCount(id) {
  const { data } = await http.get(`/api/playlists/${id}/queue-count`)
  return data  // { count: N }
}
```

Note: `playlists.js` uses `http` (imported as `import http from './axios.js'`) while `watched.js` uses `api`. Check the existing import in `playlists.js`: it uses `import http from './axios.js'`.

- [ ] **Step 3: Run frontend tests (they should still pass — no component changes yet)**

```bash
cd frontend && npm run test 2>&1 | tail -20
```

Expected: all PASS (API functions are not tested in isolation here; component tests mock them)

- [ ] **Step 4: Commit**

```bash
git add frontend/src/api/watched.js frontend/src/api/playlists.js
git commit -m "feat: add getShowQueueCount and getPlaylistQueueCount API functions

refs #24"
```

---

### Task 9: New `ConfirmModal.vue` component

**Files:**
- Create: `frontend/src/components/ConfirmModal.vue`
- Create: `frontend/src/components/__tests__/ConfirmModal.test.js`

- [ ] **Step 1: Write failing tests**

Create `frontend/src/components/__tests__/ConfirmModal.test.js`:

```js
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmModal from '../ConfirmModal.vue'

// Vitest + happy-dom: Teleport renders inline in tests
describe('ConfirmModal', () => {
  it('renders message prop', () => {
    const w = mount(ConfirmModal, {
      props: { message: 'Are you sure?' },
      attachTo: document.body
    })
    expect(w.text()).toContain('Are you sure?')
    w.unmount()
  })

  it('emits confirm on confirm button click', async () => {
    const w = mount(ConfirmModal, {
      props: { message: 'Delete?', confirmLabel: 'Yes', cancelLabel: 'No' },
      attachTo: document.body
    })
    await w.find('.btn-confirm').trigger('click')
    expect(w.emitted('confirm')).toBeTruthy()
    w.unmount()
  })

  it('emits cancel on cancel button click', async () => {
    const w = mount(ConfirmModal, {
      props: { message: 'Delete?' },
      attachTo: document.body
    })
    await w.find('.btn-cancel').trigger('click')
    expect(w.emitted('cancel')).toBeTruthy()
    w.unmount()
  })

  it('uses custom button labels', () => {
    const w = mount(ConfirmModal, {
      props: { message: 'X', confirmLabel: 'Remove', cancelLabel: 'Keep' },
      attachTo: document.body
    })
    expect(w.find('.btn-confirm').text()).toBe('Remove')
    expect(w.find('.btn-cancel').text()).toBe('Keep')
    w.unmount()
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | grep -A5 "ConfirmModal"
```

Expected: FAIL — component not found

- [ ] **Step 3: Create `ConfirmModal.vue`**

Create `frontend/src/components/ConfirmModal.vue`:

```vue
<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click.self="$emit('cancel')">
      <div class="modal-box">
        <p class="modal-message">{{ message }}</p>
        <div class="modal-actions">
          <button class="btn-cancel" @click="$emit('cancel')">{{ cancelLabel }}</button>
          <button class="btn-confirm" @click="$emit('confirm')">{{ confirmLabel }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
defineProps({
  message:      { type: String, required: true },
  confirmLabel: { type: String, default: 'Confirm' },
  cancelLabel:  { type: String, default: 'Cancel' }
})
defineEmits(['confirm', 'cancel'])
</script>

<style scoped>
.modal-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,.65);
  display: flex; align-items: center; justify-content: center; z-index: 1000;
  backdrop-filter: blur(2px);
}
.modal-box {
  background: var(--surface); border: 1px solid var(--border); border-radius: 10px;
  padding: 24px; max-width: 400px; width: 90%; box-shadow: 0 8px 32px rgba(0,0,0,.5);
}
.modal-message { margin-bottom: 20px; line-height: 1.5; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; }
.btn-cancel {
  background: transparent; border: 1px solid var(--border); color: var(--text-muted);
  border-radius: 6px; padding: 8px 16px; cursor: pointer;
}
.btn-cancel:hover { border-color: var(--text); color: var(--text); }
.btn-confirm {
  background: var(--red); border: 1px solid var(--red); color: #fff;
  border-radius: 6px; padding: 8px 16px; font-weight: 600; cursor: pointer;
}
.btn-confirm:hover { opacity: .85; }
</style>
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | grep -A10 "ConfirmModal"
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ConfirmModal.vue \
        frontend/src/components/__tests__/ConfirmModal.test.js
git commit -m "feat: add ConfirmModal reusable component

refs #24"
```

---

### Task 10: Update `SubscribeButton.vue` — fetch count and show modal before cancel

**Files:**
- Modify: `frontend/src/components/SubscribeButton.vue`
- Modify: `frontend/src/components/__tests__/SubscribeButton.test.js`

- [ ] **Step 1: Write failing tests**

Add these tests to `frontend/src/components/__tests__/SubscribeButton.test.js`:

```js
import { flushPromises } from '@vue/test-utils'
import * as watchedApi from '../../api/watched.js'

vi.mock('../../api/watched.js', () => ({
  getShowQueueCount: vi.fn()
}))
```

Add the import at the top of the file (after existing imports) and add the mock. Then add these tests inside the `describe('SubscribeButton', ...)` block:

```js
  it('shows modal when cancel is clicked and queue has items', async () => {
    watchedApi.getShowQueueCount.mockResolvedValue({ count: 3 })
    const { wrapper, store } = factory(10)
    await wrapper.find('button.sub-btn').trigger('click') // open picker
    await wrapper.find('.cancel-opt').trigger('click')   // click cancel sub
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ConfirmModal' }).exists()).toBe(true)
    expect(store.unsubscribe).not.toHaveBeenCalled()
  })

  it('proceeds immediately when queue is empty', async () => {
    watchedApi.getShowQueueCount.mockResolvedValue({ count: 0 })
    const { wrapper, store } = factory(10)
    store.unsubscribe = vi.fn().mockResolvedValue(undefined)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('.cancel-opt').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ConfirmModal' }).exists()).toBe(false)
    expect(store.unsubscribe).toHaveBeenCalledWith(10)
  })

  it('calls unsubscribe when modal confirm is clicked', async () => {
    watchedApi.getShowQueueCount.mockResolvedValue({ count: 5 })
    const { wrapper, store } = factory(10)
    store.unsubscribe = vi.fn().mockResolvedValue(undefined)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.find('.cancel-opt').trigger('click')
    await flushPromises()
    await wrapper.findComponent({ name: 'ConfirmModal' }).vm.$emit('confirm')
    await flushPromises()
    expect(store.unsubscribe).toHaveBeenCalledWith(10)
  })
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | grep -A5 "SubscribeButton"
```

Expected: FAIL — ConfirmModal not in template, `getShowQueueCount` not called

- [ ] **Step 3: Update `SubscribeButton.vue`**

Replace the entire file content:

```vue
<template>
  <div class="subscribe-wrap" :class="{ small }">
    <button class="sub-btn" :class="statusClass" @click.stop="toggle" :disabled="loading">
      <template v-if="loading">⏳</template>
      <template v-else-if="current">📥 Next {{ current }}</template>
      <template v-else>⬇ Download</template>
    </button>

    <div v-if="open" class="picker" @click.stop>
      <div class="picker-section">
        <p class="picker-label">{{ current ? 'Change subscription' : 'Subscribe: keep ahead' }}</p>
        <button v-for="n in COUNTS" :key="`sub-${n}`"
                class="picker-opt" :class="{ active: current === n }"
                @click="doSubscribe(n)">
          📥 Next {{ n }}
        </button>
      </div>
      <div class="picker-section">
        <p class="picker-label">One-time download</p>
        <button v-for="n in COUNTS" :key="`once-${n}`"
                class="picker-opt"
                @click="doOneTime(n)">
          ⬇ Download {{ n }}
        </button>
      </div>
      <div v-if="current" class="picker-section">
        <button class="picker-opt cancel-opt" @click="doCancel">✕ Cancel subscription</button>
      </div>
    </div>
  </div>

  <ConfirmModal
    v-if="pendingCancel"
    :message="`Unsubscribing will remove ${pendingCancel.count} queued item(s) from your download queue (including files in progress). Continue?`"
    confirmLabel="Unsubscribe"
    cancelLabel="Keep"
    @confirm="confirmCancel"
    @cancel="abortCancel"
  />
</template>

<script setup>
import { ref, computed } from 'vue'
import { useWatchedStore } from '@/stores/watched.js'
import { getShowQueueCount } from '@/api/watched.js'
import ConfirmModal from '@/components/ConfirmModal.vue'

const COUNTS = [5, 10, 15, 20]

const props = defineProps({
  showId: { type: Number, required: true },
  small:  { type: Boolean, default: false }
})

const watchedStore  = useWatchedStore()
const open          = ref(false)
const loading       = ref(false)
const pendingCancel = ref(null)

const current     = computed(() => watchedStore.getSubscription(props.showId))
const statusClass = computed(() => current.value ? 'active-sub' : 'idle')

function toggle() { open.value = !open.value }

async function doSubscribe(n) {
  open.value = false
  loading.value = true
  try { await watchedStore.subscribe(props.showId, n) }
  finally { loading.value = false }
}

async function doOneTime(n) {
  open.value = false
  loading.value = true
  try { await watchedStore.enqueueUnwatched(props.showId, n) }
  finally { loading.value = false }
}

async function doCancel() {
  open.value = false
  loading.value = true
  try {
    const { count } = await getShowQueueCount(props.showId)
    if (count > 0) {
      pendingCancel.value = { count }
      return  // loading stays true until modal resolves
    }
    await watchedStore.unsubscribe(props.showId)
  } finally {
    if (!pendingCancel.value) loading.value = false
  }
}

async function confirmCancel() {
  pendingCancel.value = null
  try { await watchedStore.unsubscribe(props.showId) }
  finally { loading.value = false }
}

function abortCancel() {
  pendingCancel.value = null
  loading.value = false
}
</script>

<style scoped>
.subscribe-wrap { position: relative; display: inline-block; }
.sub-btn { display: inline-flex; align-items: center; gap: 6px; border: none;
           border-radius: 6px; padding: 8px 16px; font-size: .9rem; font-weight: 600;
           transition: opacity .15s; cursor: pointer; }
.subscribe-wrap.small .sub-btn { padding: 4px 8px; font-size: .8rem; border-radius: 4px; }
.sub-btn:disabled { cursor: default; opacity: .6; }
.idle      { background: var(--accent); color: #000; }
.idle:hover { opacity: .85; }
.active-sub { background: var(--accent-blue); color: #fff; }

.picker { position: absolute; top: calc(100% + 6px); left: 0; z-index: 100;
          background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
          padding: 12px; min-width: 220px; box-shadow: 0 4px 16px rgba(0,0,0,.4); }
.picker-section { margin-bottom: 12px; }
.picker-section:last-child { margin-bottom: 0; }
.picker-label { font-size: .75rem; color: var(--text-muted); text-transform: uppercase;
                letter-spacing: .05em; margin-bottom: 6px; }
.picker-opt { display: block; width: 100%; text-align: left; background: transparent;
              border: 1px solid var(--border); border-radius: 6px; padding: 6px 10px;
              color: var(--text); font-size: .85rem; margin-bottom: 4px; cursor: pointer; }
.picker-opt:hover { background: var(--surface2); }
.picker-opt.active { border-color: var(--accent-blue); color: var(--accent-blue); }
.cancel-opt { color: var(--red); border-color: var(--red); }
.cancel-opt:hover { background: rgba(231,76,60,.1); }
</style>
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | grep -A15 "SubscribeButton"
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/SubscribeButton.vue \
        frontend/src/components/__tests__/SubscribeButton.test.js
git commit -m "feat: show queue-count confirmation modal before cancelling show subscription

refs #24"
```

---

### Task 11: Update `PlaylistDetailView.vue` — fetch count and show modal before unsubscribe

**Files:**
- Modify: `frontend/src/views/PlaylistDetailView.vue`
- Modify: `frontend/src/views/__tests__/PlaylistDetailView.test.js`

- [ ] **Step 1: Write failing tests**

In `frontend/src/views/__tests__/PlaylistDetailView.test.js`, update the mock at the top to include `getPlaylistQueueCount`:

```js
vi.mock('../../api/playlists.js', () => ({
  getPlaylist:           vi.fn(),
  subscribe:             vi.fn(),
  unsubscribe:           vi.fn(),
  getPlaylistQueueCount: vi.fn()
}))
```

Add the import:
```js
import { getPlaylist, subscribe, unsubscribe, getPlaylistQueueCount } from '../../api/playlists.js'
```

Replace the existing `unsubscribe button calls unsubscribe API` test and add three new tests:

```js
  it('unsubscribe shows modal when queue has items', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    getPlaylistQueueCount.mockResolvedValue({ count: 4 })
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await flushPromises()
    expect(unsubscribe).not.toHaveBeenCalled()
    expect(w.findComponent({ name: 'ConfirmModal' }).exists()).toBe(true)
  })

  it('unsubscribe proceeds immediately when queue is empty', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    getPlaylistQueueCount.mockResolvedValue({ count: 0 })
    unsubscribe.mockResolvedValue(undefined)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await flushPromises()
    expect(unsubscribe).toHaveBeenCalledWith(1)
    expect(w.findComponent({ name: 'ConfirmModal' }).exists()).toBe(false)
  })

  it('calls unsubscribe when modal confirm is clicked', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    getPlaylistQueueCount.mockResolvedValue({ count: 2 })
    unsubscribe.mockResolvedValue(undefined)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await flushPromises()
    await w.findComponent({ name: 'ConfirmModal' }).vm.$emit('confirm')
    await flushPromises()
    expect(unsubscribe).toHaveBeenCalledWith(1)
    expect(w.findComponent({ name: 'ConfirmModal' }).exists()).toBe(false)
  })
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | grep -A10 "PlaylistDetailView"
```

Expected: FAIL — `getPlaylistQueueCount` not called, no modal

- [ ] **Step 3: Update `PlaylistDetailView.vue`**

Replace the entire file content:

```vue
<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="playlist">
    <button class="back" @click="router.back()">← Back</button>

    <div class="detail-header">
      <div>
        <h1>{{ playlist.title }}</h1>
        <p class="meta">{{ playlist.items.length }} items · {{ playlist.playlistType }}</p>
      </div>
      <button
        class="btn-subscribe"
        :class="{ subscribed: subscribed }"
        data-testid="subscribe-btn"
        :disabled="subscribing"
        @click="toggleSubscription"
      >
        {{ subscribed ? '✓ Unsubscribe' : 'Subscribe' }}
      </button>
    </div>

    <div class="item-list">
      <div v-for="item in playlist.items" :key="item.id" class="item-row">
        <img
          class="item-thumb"
          :src="`/api/posters/${item.plexId}.jpg`"
          :alt="item.title || item.plexId"
          loading="lazy"
        />
        <div class="item-info">
          <div class="item-title">{{ item.title || item.plexId }}</div>
          <div class="item-sub">
            {{ item.mediaType.toLowerCase() }}
            <template v-if="item.year">· {{ item.year }}</template>
          </div>
        </div>
        <span class="status-badge" :class="statusClass(item)">{{ statusLabel(item) }}</span>
      </div>
    </div>
  </div>

  <ConfirmModal
    v-if="pendingUnsubscribe"
    :message="`Unsubscribing will remove ${pendingUnsubscribe.count} queued item(s) from your download queue (including files in progress). Continue?`"
    confirmLabel="Unsubscribe"
    cancelLabel="Keep"
    @confirm="confirmUnsubscribe"
    @cancel="abortUnsubscribe"
  />
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getPlaylist, subscribe, unsubscribe, getPlaylistQueueCount } from '@/api/playlists.js'
import ConfirmModal from '@/components/ConfirmModal.vue'

const route  = useRoute()
const router = useRouter()

const playlist          = ref(null)
const loading           = ref(true)
const subscribed        = ref(false)
const subscribing       = ref(false)
const pendingUnsubscribe = ref(null)

onMounted(async () => {
  try {
    const data = await getPlaylist(Number(route.params.id))
    playlist.value   = data
    subscribed.value = data.subscribed
  } finally {
    loading.value = false
  }
})

async function toggleSubscription() {
  subscribing.value = true
  try {
    if (subscribed.value) {
      const { count } = await getPlaylistQueueCount(Number(route.params.id))
      if (count > 0) {
        pendingUnsubscribe.value = { count }
        return  // loading stays true until modal resolves
      }
      await doUnsubscribe()
    } else {
      await subscribe(Number(route.params.id))
      subscribed.value = true
    }
  } finally {
    if (!pendingUnsubscribe.value) subscribing.value = false
  }
}

async function doUnsubscribe() {
  await unsubscribe(Number(route.params.id))
  subscribed.value = false
}

async function confirmUnsubscribe() {
  pendingUnsubscribe.value = null
  try { await doUnsubscribe() }
  finally { subscribing.value = false }
}

function abortUnsubscribe() {
  pendingUnsubscribe.value = null
  subscribing.value = false
}

function statusClass(item) {
  if (!item.queueStatus) return 'status-none'
  if (item.tdarrStatus === 'TRANSCODED') return 'status-done'
  if (item.queueStatus === 'IN_PROGRESS' || item.tdarrStatus === 'PROCESSING') return 'status-processing'
  if (item.queueStatus === 'ERROR' || item.tdarrStatus === 'TDARR_ERROR') return 'status-error'
  return 'status-queued'
}

function statusLabel(item) {
  if (!item.queueStatus) return 'not queued'
  if (item.tdarrStatus === 'TRANSCODED') return 'transcoded'
  if (item.queueStatus === 'IN_PROGRESS' || item.tdarrStatus === 'PROCESSING') return 'processing'
  if (item.queueStatus === 'ERROR') return 'error'
  if (item.tdarrStatus === 'TDARR_ERROR') return 'tdarr error'
  return 'queued'
}
</script>

<style scoped>
.back { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 6px; padding: 6px 14px; margin-bottom: 24px; cursor: pointer; }

.detail-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  margin-bottom: 24px; gap: 16px;
}
h1 { font-size: 1.6rem; font-weight: 700; margin-bottom: 4px; }
.meta { color: var(--text-muted); font-size: .9rem; }

.btn-subscribe {
  padding: 8px 20px; border-radius: 6px; font-size: .9rem; font-weight: 600;
  border: 1px solid var(--accent); color: var(--accent); background: transparent;
  cursor: pointer; white-space: nowrap; flex-shrink: 0;
  transition: background .15s, color .15s;
}
.btn-subscribe.subscribed { background: var(--accent); color: #fff; }
.btn-subscribe:disabled { opacity: .5; cursor: default; }

.item-list { display: flex; flex-direction: column; gap: 4px; }

.item-row {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 12px; background: var(--surface2); border-radius: 6px;
}
.item-thumb {
  width: 32px; height: 48px; object-fit: cover;
  border-radius: 3px; flex-shrink: 0; background: var(--surface);
}
.item-info { flex: 1; min-width: 0; }
.item-title { font-size: .85rem; font-weight: 500; white-space: nowrap;
              overflow: hidden; text-overflow: ellipsis; }
.item-sub { font-size: .75rem; color: var(--text-muted); margin-top: 2px; }

.status-badge {
  border-radius: 4px; padding: 3px 8px; font-size: .7rem;
  white-space: nowrap; flex-shrink: 0; font-weight: 600;
}
.status-done       { background: var(--green, #22c55e); color: #fff; }
.status-processing { background: #f59e0b; color: #fff; }
.status-error      { background: var(--red, #ef4444); color: #fff; }
.status-queued     { background: var(--surface); color: var(--text-muted); border: 1px solid var(--border); }
.status-none       { background: var(--surface); color: var(--text-muted); border: 1px solid var(--border); }

.loading { color: var(--text-muted); padding: 40px; text-align: center; }
</style>
```

- [ ] **Step 4: Run all frontend tests**

```bash
cd frontend && npm run test 2>&1 | tail -20
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/PlaylistDetailView.vue \
        frontend/src/views/__tests__/PlaylistDetailView.test.js
git commit -m "feat: show queue-count confirmation modal before playlist unsubscribe

closes #24"
```

---

## Verification

1. `cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew test --no-daemon` — all green
2. `cd frontend && npm run test` — all green
3. `docker compose up --build` — services start healthy
4. Browse to `http://localhost:3615/tv` → click on a show → subscribe → confirm items appear in queue
5. Click "Cancel subscription" → modal appears showing count → click "Unsubscribe" → queue items disappear
6. Click on a playlist → subscribe → items queued → unsubscribe → modal → confirm → items gone
7. Start a download → while IN_PROGRESS, unsubscribe → modal → confirm → subscription gone, flag set → after copy finishes, item auto-removed from queue and file deleted
