# Heal Orphaned Queue Items on Plex ratingKey Reissue — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the permanent `ffmpeg exit 254` loop that occurs when Radarr/Sonarr upgrades an already-queued title and Plex reissues its ratingKey, orphaning the queue item's `media_id`.

**Architecture:** Two changes in the backend. (A) `TranscodeService.refreshSourcePath` returns a boolean and fails the item fast with a clear error when its canonical media record is gone, instead of silently no-oping into a stale path. (B) `LibrarySyncService` marks `QUEUED` orphaned queue items as `ERROR` at prune time so they surface immediately. Fail-clean; no auto-follow of the upgraded file.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Lombok, JUnit 5 + Mockito + AssertJ, Gradle (wrapper).

## Global Constraints

- Java toolchain: **21** (`backend/build.gradle` toolchain; Gradle auto-provisions — no `.sdkmanrc`).
- Build/test from the `backend/` directory: `./gradlew test`.
- Follow existing test style: Mockito unit tests (`@ExtendWith(MockitoExtension.class)`), AssertJ assertions.
- The user-facing error text MUST be **identical** in both changes (verbatim):
  `Source media no longer in Plex - likely replaced by an upgrade; re-enqueue from library`
- `failItem`/orphan-fail convention: set `status = ERROR`, `transcodeError = <detailed message>`, `errorMessage = "Transcoding failed"`.
- Spring Data `PlexLibraryPage` record is `PlexLibraryPage(int totalSize, List<PlexItem> items)` — **totalSize first**.

---

### Task 1: `refreshSourcePath` fails fast on missing media (Change A)

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeService.java` (method `refreshSourcePath` ~L151-165; call site in `transcode()` ~L65)
- Test: `backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeServiceTest.java`

**Interfaces:**
- Consumes: `MovieRepository.findById(Long)`, `EpisodeRepository.findById(Long)`, existing `failItem(DownloadQueueItem, String)`.
- Produces: `private boolean refreshSourcePath(DownloadQueueItem item)` — returns `true` to proceed, `false` when the media record is gone (item already failed). Call site: `if (!refreshSourcePath(item)) return;`.

- [ ] **Step 1: Write the failing test**

Add to `TranscodeServiceTest`:

```java
@Test
void missingMediaRecord_failsFastWithoutRunningFfmpeg(@TempDir Path tmp) {
    Path src = tmp.resolve("obsession webdl-2160p.m4v"); // deliberately not created
    Path dest = tmp.resolve("dest/obsession.mkv");

    DownloadQueueItem it = item(99L, src.toString(), dest.toString());
    it.setMediaType(DownloadQueueItem.MediaType.MOVIE);
    it.setMediaId(2166L);

    when(queueRepo.findByIdWithProfile(99L)).thenReturn(Optional.of(it));
    when(movieRepo.findById(2166L)).thenReturn(Optional.empty()); // pruned row
    when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().transcode(99L);

    assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
    assertThat(it.getTranscodeError())
        .contains("Source media no longer in Plex");
    verify(mediaProbe, never()).probe(anyString());
    verify(processRunner, never()).start(anyList(), any(), any());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*TranscodeServiceTest.missingMediaRecord_failsFastWithoutRunningFfmpeg'`
Expected: FAIL — current `refreshSourcePath` returns void and no-ops, so `transcode()` proceeds and `mediaProbe.probe` IS called (fails the `never()` verify), and status is not ERROR from this path.

- [ ] **Step 3: Change `refreshSourcePath` to return boolean + fail fast**

Replace the whole method body:

```java
/**
 * Re-resolves the queue item's source path from the canonical Movie/Episode record.
 * Returns false (after failing the item) when the media record no longer exists —
 * e.g. Plex reissued the ratingKey on an upgrade and sync pruned the old row, orphaning
 * this item's mediaId. Otherwise updates the snapshot if the path drifted and returns true.
 */
private boolean refreshSourcePath(DownloadQueueItem item) {
    if (item.getMediaType() == null || item.getMediaId() == null) return true;

    boolean present = false;
    String current = null;
    switch (item.getMediaType()) {
        case MOVIE -> {
            var m = movieRepo.findById(item.getMediaId());
            present = m.isPresent();
            current = m.map(org.lolobored.plexdownloader.model.Movie::getFilePath).orElse(null);
        }
        case EPISODE -> {
            var e = episodeRepo.findById(item.getMediaId());
            present = e.isPresent();
            current = e.map(org.lolobored.plexdownloader.model.Episode::getFilePath).orElse(null);
        }
    }

    if (!present) {
        failItem(item, "Source media no longer in Plex - likely replaced by an upgrade; re-enqueue from library");
        return false;
    }

    if (current != null && !current.equals(item.getSourceFilePath())) {
        log.info("Source path drifted for item {}: {} -> {}",
            item.getId(), item.getSourceFilePath(), current);
        item.setSourceFilePath(current);
        queueRepo.save(item);
    }
    return true;
}
```

- [ ] **Step 4: Update the call site in `transcode()`**

Change the line (was `refreshSourcePath(item);`):

```java
if (!refreshSourcePath(item)) return;   // media gone: item already failed with a clear message
```

It stays **before** `item.setStatus(DownloadQueueItem.Status.TRANSCODING);` and outside the `try`.

- [ ] **Step 5: Run the new test + the existing drift/empty tests**

Run: `cd backend && ./gradlew test --tests '*TranscodeServiceTest'`
Expected: PASS — new test green; the existing drift test (`...remux-1080p` re-point) and the existing cancelled-item test still pass (present-record and null-record-after-exit paths unchanged).

- [ ] **Step 6: Commit**

```bash
cd /Users/laurentlaborde/projects/plex-downloader
git add backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeService.java \
        backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeServiceTest.java
git commit -m "fix: fail transcode fast when media record gone (ratingKey reissue)"
```

---

### Task 2: Prune marks QUEUED orphans ERROR (Change B)

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java` (add two finder methods)
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/LibrarySyncService.java` (add `DownloadQueueRepository` dependency; fail orphans before the two `deleteAll` prune calls ~L122-136)
- Test: `backend/src/test/java/org/lolobored/plexdownloader/service/LibrarySyncServiceTest.java`

**Interfaces:**
- Consumes (Task 1): the `ERROR`/`transcodeError`/`errorMessage` convention and the identical message string.
- Produces:
  - `List<DownloadQueueItem> DownloadQueueRepository.findByMediaTypeAndStatusAndMediaIdIn(DownloadQueueItem.MediaType, DownloadQueueItem.Status, Collection<Long>)`
  - `List<DownloadQueueItem> DownloadQueueRepository.findQueuedEpisodeItemsForShows(Collection<Long> showIds)`

- [ ] **Step 1: Write the failing test**

Add to `LibrarySyncServiceTest`. First add the mock field alongside the others (after `@Mock PlaylistSyncService playlistSyncService;`):

```java
@Mock DownloadQueueRepository queueRepo;
```

Then the test (a live movie keeps `seenMoviePlexIds` non-empty so the prune block runs; the orphan is a different DB row Plex no longer lists):

```java
@Test
void prunedMovie_failsQueuedOrphanItems() {
    PlexLibrary movieLib = new PlexLibrary();
    movieLib.setKey("1");
    movieLib.setType("movie");
    movieLib.setAgent("tv.plex.agents.movie");

    PlexItem live = new PlexItem();
    live.setRatingKey("52365");
    live.setType("movie");
    live.setTitle("Obsession");
    live.setYear(2026);
    live.setThumb("/library/metadata/52365/thumb");
    live.setUpdatedAt(1000L);
    PlexItem liveDetail = new PlexItem();
    liveDetail.setRatingKey("52365");
    liveDetail.setType("movie");
    liveDetail.setTitle("Obsession");
    liveDetail.setYear(2026);
    liveDetail.setThumb("/library/metadata/52365/thumb");
    liveDetail.setUpdatedAt(1000L);
    liveDetail.setRole(List.of());
    liveDetail.setGenre(List.of());
    liveDetail.setDirector(List.of());

    when(plexClient.getLibraries()).thenReturn(List.of(movieLib));
    when(plexClient.getLibraryContents("1", 0)).thenReturn(new PlexLibraryPage(1, List.of(live)));
    when(plexClient.getItemDetail("52365")).thenReturn(liveDetail);
    when(movieRepo.findByPlexId("52365")).thenReturn(Optional.empty());
    when(movieRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(posterStorage.posterUrl("52365")).thenReturn("/api/posters/52365.jpg");

    // The pre-upgrade row Plex no longer lists.
    Movie orphan = new Movie();
    orphan.setId(2166L);
    orphan.setPlexId("old-rating-key");
    when(movieRepo.findByPlexIdNotIn(anySet())).thenReturn(List.of(orphan));

    // A QUEUED queue item still pointing at the orphaned PK.
    DownloadQueueItem stuck = new DownloadQueueItem();
    stuck.setId(713L);
    stuck.setMediaType(DownloadQueueItem.MediaType.MOVIE);
    stuck.setMediaId(2166L);
    stuck.setStatus(DownloadQueueItem.Status.QUEUED);
    when(queueRepo.findByMediaTypeAndStatusAndMediaIdIn(
            eq(DownloadQueueItem.MediaType.MOVIE),
            eq(DownloadQueueItem.Status.QUEUED),
            anySet()))
        .thenReturn(List.of(stuck));
    when(queueRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

    service.syncAll();

    assertThat(stuck.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
    assertThat(stuck.getTranscodeError()).contains("Source media no longer in Plex");
    verify(movieRepo).deleteAll(List.of(orphan));
}
```

Add imports at the top of the test file if missing:
`import org.lolobored.plexdownloader.model.DownloadQueueItem;`
`import org.lolobored.plexdownloader.repository.DownloadQueueRepository;`

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*LibrarySyncServiceTest.prunedMovie_failsQueuedOrphanItems'`
Expected: FAIL — compilation error (`findByMediaTypeAndStatusAndMediaIdIn` and the `queueRepo` field don't exist yet / `LibrarySyncService` has no such dependency).

- [ ] **Step 3: Add the repository finder methods**

In `DownloadQueueRepository` add:

```java
List<DownloadQueueItem> findByMediaTypeAndStatusAndMediaIdIn(
    DownloadQueueItem.MediaType mediaType,
    DownloadQueueItem.Status status,
    Collection<Long> mediaIds);

@Query("SELECT i FROM DownloadQueueItem i WHERE i.mediaType = 'EPISODE' AND i.status = 'QUEUED' "
     + "AND i.mediaId IN (SELECT e.id FROM Episode e WHERE e.season.show.id IN :showIds)")
List<DownloadQueueItem> findQueuedEpisodeItemsForShows(@Param("showIds") Collection<Long> showIds);
```

(`java.util.*` is already imported, so `Collection`/`List` resolve.)

- [ ] **Step 4: Wire the dependency + fail orphans in `LibrarySyncService`**

Add the field alongside the other repositories (after `private final EpisodeRepository episodeRepo;`):

```java
private final DownloadQueueRepository queueRepo;
```

Add a constant near the top of the class body and a helper method:

```java
private static final String ORPHAN_MSG =
    "Source media no longer in Plex - likely replaced by an upgrade; re-enqueue from library";

private void failOrphanedQueueItems(List<DownloadQueueItem> stuck, String kind) {
    if (stuck.isEmpty()) return;
    for (DownloadQueueItem i : stuck) {
        i.setStatus(DownloadQueueItem.Status.ERROR);
        i.setTranscodeError(ORPHAN_MSG);
        i.setErrorMessage("Transcoding failed");
    }
    queueRepo.saveAll(stuck);
    log.info("Failed {} orphaned queue item(s) for pruned {}", stuck.size(), kind);
}
```

Then in the prune section, add the fail call **before** each `deleteAll`:

```java
if (!seenMoviePlexIds.isEmpty()) {
    List<Movie> orphanMovies = movieRepo.findByPlexIdNotIn(seenMoviePlexIds);
    if (!orphanMovies.isEmpty()) {
        Set<Long> orphanMovieIds = orphanMovies.stream().map(Movie::getId).collect(Collectors.toSet());
        failOrphanedQueueItems(
            queueRepo.findByMediaTypeAndStatusAndMediaIdIn(
                DownloadQueueItem.MediaType.MOVIE, DownloadQueueItem.Status.QUEUED, orphanMovieIds),
            "movie(s)");
        log.info("Pruning {} movie(s) no longer in Plex", orphanMovies.size());
        movieRepo.deleteAll(orphanMovies);
    }
}
if (!seenShowPlexIds.isEmpty()) {
    List<TvShow> orphanShows = showRepo.findByPlexIdNotIn(seenShowPlexIds);
    if (!orphanShows.isEmpty()) {
        Set<Long> orphanShowIds = orphanShows.stream().map(TvShow::getId).collect(Collectors.toSet());
        failOrphanedQueueItems(queueRepo.findQueuedEpisodeItemsForShows(orphanShowIds), "show(s)");
        log.info("Pruning {} show(s) no longer in Plex", orphanShows.size());
        showRepo.deleteAll(orphanShows);
    }
}
```

(`DownloadQueueItem` and `DownloadQueueRepository` resolve via the existing `model.*` / `repository.*` wildcard imports; `Set`, `Collectors` already imported.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./gradlew test --tests '*LibrarySyncServiceTest'`
Expected: PASS — new prune test green; existing sync tests (`syncAllUpsertsSingleMovie`, etc.) still pass because `queueRepo.findByMediaType...` returns an empty list by default (no orphan fail, `deleteAll` still invoked as before).

- [ ] **Step 6: Full backend test run**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL — whole suite green.

- [ ] **Step 7: Commit**

```bash
cd /Users/laurentlaborde/projects/plex-downloader
git add backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java \
        backend/src/main/java/org/lolobored/plexdownloader/service/LibrarySyncService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/LibrarySyncServiceTest.java
git commit -m "feat: fail QUEUED orphan queue items when their media is pruned"
```

---

## Self-Review

**Spec coverage:**
- Change A (fail-fast on missing media) → Task 1. ✔
- Change A drift/null-path unchanged → preserved in Task 1 Step 3; existing tests kept green in Step 5. ✔
- Change B (prune fails QUEUED orphans, movies + episodes-under-shows, TRANSCODING untouched) → Task 2 (status filter `QUEUED` only). ✔
- Identical error message both paths → Global Constraints + used verbatim in both tasks. ✔
- Tests in `TranscodeServiceTest` + `LibrarySyncServiceTest` → Tasks 1 & 2. ✔
- Non-goals (no auto-follow, no dedup) → not implemented. ✔

**Placeholder scan:** none — all steps contain concrete code and exact commands.

**Type consistency:** `refreshSourcePath` returns `boolean` (Task 1) and is only called by the guard in `transcode()`. Repo method names `findByMediaTypeAndStatusAndMediaIdIn` / `findQueuedEpisodeItemsForShows` are defined in Task 2 Step 3 and used identically in Steps 1 and 4. `PlexLibraryPage(int, List)` order matches the record. `failItem`/orphan convention identical across both classes.

## Post-implementation (out of plan)

- One-off cleanup of the known pre-existing zombie: `DELETE FROM download_queue WHERE id = 713;` (already flagged to the user).
- Version bump + release so the running container gets the fix (separate step; user drives the release process).
