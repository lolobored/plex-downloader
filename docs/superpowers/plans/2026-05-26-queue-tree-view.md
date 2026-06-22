# Queue Tree View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat queue view with a collapsible tree grouped by playlist subscription (top) and by TV show → season (bottom), while keeping the filter bar.

**Architecture:** 5 changes in sequence — DB migration adds `playlist_id` to `download_queue`, entity + DTO gain new fields, `DownloadService` gains overloaded enqueue methods and enriches `getQueue()`, `PlaylistSyncService` threads `playlistId` through `enqueueItem()`, and `QueueView.vue` is fully rewritten as a two-section collapsible tree. All existing item actions (✕, retry, navigate) are preserved.

**Tech Stack:** Spring Boot 3, Liquibase, Spring Data JPA, Vue 3 Composition API, Pinia, Vitest

---

## File Map

| File | Action |
|---|---|
| `backend/src/main/resources/db/changelog/yaml/011-queue-playlist-id.yaml` | Create |
| `backend/src/main/java/…/model/DownloadQueueItem.java` | Modify |
| `backend/src/main/java/…/dto/DownloadQueueItemResponse.java` | Modify |
| `backend/src/main/java/…/service/DownloadService.java` | Modify |
| `backend/src/main/java/…/service/PlaylistSyncService.java` | Modify |
| `backend/src/test/java/…/controller/DownloadControllerTest.java` | Modify |
| `backend/src/test/java/…/service/DownloadServiceTest.java` | Modify |
| `backend/src/test/java/…/service/PlaylistSyncServiceTest.java` | Modify |
| `frontend/src/views/QueueView.vue` | Rewrite |
| `frontend/src/views/__tests__/QueueView.test.js` | Rewrite |

All paths under `backend/src/main/java/` use package `org.lolobored.plexdownloader`.

---

### Task 1: Liquibase migration — add `playlist_id` to `download_queue`

**Files:**
- Create: `backend/src/main/resources/db/changelog/yaml/011-queue-playlist-id.yaml`

The master changelog uses `includeAll` so this file is picked up automatically.

- [ ] **Step 1: Create migration file**

```yaml
databaseChangeLog:
  - changeSet:
      id: 011-queue-playlist-id
      author: system
      changes:
        - sql:
            sql: |
              ALTER TABLE download_queue
                ADD COLUMN playlist_id BIGINT REFERENCES playlists(id) ON DELETE SET NULL
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/resources/db/changelog/yaml/011-queue-playlist-id.yaml
git commit -m "feat: add playlist_id column to download_queue table #37"
```

---

### Task 2: `DownloadQueueItem` entity + `DownloadQueueItemResponse` DTO

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/dto/DownloadQueueItemResponse.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/controller/DownloadControllerTest.java`

The DTO gains four new fields: `playlistId`, `playlistTitle`, `showTitle`, `seasonNumber`. The controller test constructs the record directly and must be updated to compile.

- [ ] **Step 1: Write failing test — verify new DTO fields exist**

In `DownloadControllerTest.java`, the `getQueue_returnsEnrichedItems` test constructs `DownloadQueueItemResponse` directly. The new record adds four fields, so the constructor call must be updated. Run the tests first to confirm they compile-fail:

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java $(cat .sdkmanrc | grep java | cut -d= -f2) && ./gradlew test --tests "*DownloadControllerTest*" --no-daemon 2>&1 | tail -10
```

Expected: compilation failure after DTO is changed in Step 2.

- [ ] **Step 2: Update `DownloadQueueItem.java`** — add `playlistId` field

Replace the field list (after the `cancellationRequested` field, before the enum declarations):

```java
    @Column(name = "cancellation_requested", nullable = false)
    private boolean cancellationRequested = false;

    @Column(name = "playlist_id")
    private Long playlistId;
```

- [ ] **Step 3: Update `DownloadQueueItemResponse.java`** — add four new fields and update factory

Replace the entire file:

```java
package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import java.time.Instant;

public record DownloadQueueItemResponse(
    Long id,
    DownloadQueueItem.MediaType mediaType,
    Long mediaId,
    DownloadQueueItem.Status status,
    DownloadQueueItem.TdarrStatus tdarrStatus,
    String tdarrError,
    String title,
    Integer queuePosition,
    String errorMessage,
    Instant requestedAt,
    Instant completedAt,
    Long showId,
    Long seasonId,
    Long playlistId,
    String playlistTitle,
    String showTitle,
    Integer seasonNumber
) {
    public static DownloadQueueItemResponse from(
            DownloadQueueItem item,
            Long showId, Long seasonId,
            Long playlistId, String playlistTitle,
            String showTitle, Integer seasonNumber) {
        return new DownloadQueueItemResponse(
            item.getId(), item.getMediaType(), item.getMediaId(),
            item.getStatus(), item.getTdarrStatus(), item.getTdarrError(),
            item.getTitle(), item.getQueuePosition(), item.getErrorMessage(),
            item.getRequestedAt(), item.getCompletedAt(),
            showId, seasonId,
            playlistId, playlistTitle,
            showTitle, seasonNumber
        );
    }
}
```

- [ ] **Step 4: Fix `DownloadControllerTest.java`** — update constructor call in `getQueue_returnsEnrichedItems`

Find the test that builds `DownloadQueueItemResponse` directly and replace the constructor call:

```java
DownloadQueueItemResponse resp = new DownloadQueueItemResponse(
    1L, DownloadQueueItem.MediaType.EPISODE, 99L,
    DownloadQueueItem.Status.PENDING, DownloadQueueItem.TdarrStatus.NONE,
    null, "Show S01E01", 1, null,
    java.time.Instant.parse("2026-01-01T00:00:00Z"), null,
    10L, 20L,
    null, null,
    "Breaking Bad", 1
);
```

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests "*DownloadControllerTest*" --no-daemon 2>&1 | tail -10
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java \
        backend/src/main/java/org/lolobored/plexdownloader/dto/DownloadQueueItemResponse.java \
        backend/src/test/java/org/lolobored/plexdownloader/controller/DownloadControllerTest.java
git commit -m "feat: add playlistId/playlistTitle/showTitle/seasonNumber to queue DTO #37"
```

---

### Task 3: `DownloadService` — enqueue overloads + enriched `getQueue()`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java`

`enqueueMovie` and `enqueueEpisode` gain a third `Long playlistId` parameter; the existing 2-arg versions delegate. `getQueue()` gains playlist title lookup and exposes `showTitle` + `seasonNumber` from the existing episode join.

- [ ] **Step 1: Write failing tests**

In `DownloadServiceTest.java`, add a `@Mock PlaylistRepository playlistRepo` field (Mockito will inject it) and add these tests:

```java
@Mock PlaylistRepository playlistRepo;

@Test
void enqueueMovie_withPlaylistId_setsPlaylistIdOnItem() throws IOException {
    Path sourceFile = tempDir.resolve("movie.mkv");
    Files.writeString(sourceFile, "fake");

    Movie movie = new Movie();
    movie.setId(1L);
    movie.setTitle("Inception");
    movie.setFilePath(sourceFile.toString());

    when(settings.get("plex.conversion.dir")).thenReturn(Optional.of(tempDir.toString()));
    when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
    when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
    ArgumentCaptor<DownloadQueueItem> captor = ArgumentCaptor.forClass(DownloadQueueItem.class);
    when(queueRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

    service.enqueueMovie(1L, new User(), 42L);

    assertThat(captor.getValue().getPlaylistId()).isEqualTo(42L);
}

@Test
void enqueueMovie_withoutPlaylistId_setsNullPlaylistId() throws IOException {
    Path sourceFile = tempDir.resolve("movie.mkv");
    Files.writeString(sourceFile, "fake");

    Movie movie = new Movie();
    movie.setId(1L);
    movie.setTitle("Inception");
    movie.setFilePath(sourceFile.toString());

    when(settings.get("plex.conversion.dir")).thenReturn(Optional.of(tempDir.toString()));
    when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
    when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
    ArgumentCaptor<DownloadQueueItem> captor = ArgumentCaptor.forClass(DownloadQueueItem.class);
    when(queueRepo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

    service.enqueueMovie(1L, new User());

    assertThat(captor.getValue().getPlaylistId()).isNull();
}

@Test
void getQueue_returnsPlaylistTitleAndShowTitleForEpisode() {
    DownloadQueueItem item = new DownloadQueueItem();
    item.setId(2L);
    item.setMediaType(DownloadQueueItem.MediaType.EPISODE);
    item.setMediaId(99L);
    item.setStatus(DownloadQueueItem.Status.DONE);
    item.setPlaylistId(5L);

    TvShow show = new TvShow(); show.setId(10L); show.setTitle("Breaking Bad");
    Season season = new Season(); season.setId(20L); season.setSeasonNumber(1); season.setShow(show);
    Episode ep = new Episode(); ep.setId(99L); ep.setSeason(season);

    Playlist playlist = new Playlist(); playlist.setId(5L); playlist.setTitle("Action Movies");

    when(queueRepo.findAllByUserIdOrderByQueuePositionAsc(1L)).thenReturn(List.of(item));
    when(episodeRepo.findWithSeasonAndShowByIdIn(Set.of(99L))).thenReturn(List.of(ep));
    when(playlistRepo.findAllById(Set.of(5L))).thenReturn(List.of(playlist));

    List<DownloadQueueItemResponse> result = service.getQueue(1L);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).showId()).isEqualTo(10L);
    assertThat(result.get(0).seasonId()).isEqualTo(20L);
    assertThat(result.get(0).showTitle()).isEqualTo("Breaking Bad");
    assertThat(result.get(0).seasonNumber()).isEqualTo(1);
    assertThat(result.get(0).playlistId()).isEqualTo(5L);
    assertThat(result.get(0).playlistTitle()).isEqualTo("Action Movies");
}
```

Also update the existing `getQueue_returnsEpisodeItemWithShowAndSeasonId` test — set `show.setTitle("Breaking Bad")` and `season.setSeasonNumber(1)` on the mocks, and stub `playlistRepo.findAllById(any())`:

```java
@Test
void getQueue_returnsEpisodeItemWithShowAndSeasonId() {
    DownloadQueueItem item = new DownloadQueueItem();
    item.setId(2L);
    item.setMediaType(DownloadQueueItem.MediaType.EPISODE);
    item.setMediaId(99L);
    item.setStatus(DownloadQueueItem.Status.DONE);

    TvShow show = new TvShow(); show.setId(10L); show.setTitle("Breaking Bad");
    Season season = new Season(); season.setId(20L); season.setSeasonNumber(1); season.setShow(show);
    Episode ep = new Episode(); ep.setId(99L); ep.setSeason(season);

    when(queueRepo.findAllByUserIdOrderByQueuePositionAsc(1L)).thenReturn(List.of(item));
    when(episodeRepo.findWithSeasonAndShowByIdIn(Set.of(99L))).thenReturn(List.of(ep));
    when(playlistRepo.findAllById(any())).thenReturn(List.of());

    List<DownloadQueueItemResponse> result = service.getQueue(1L);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).showId()).isEqualTo(10L);
    assertThat(result.get(0).seasonId()).isEqualTo(20L);
    assertThat(result.get(0).showTitle()).isEqualTo("Breaking Bad");
    assertThat(result.get(0).seasonNumber()).isEqualTo(1);
    assertThat(result.get(0).mediaId()).isEqualTo(99L);
}
```

Also update `getQueue_returnsMovieItemWithNullShowAndSeasonId` to stub `playlistRepo.findAllById(any())`:

```java
@Test
void getQueue_returnsMovieItemWithNullShowAndSeasonId() {
    DownloadQueueItem item = new DownloadQueueItem();
    item.setId(1L);
    item.setMediaType(DownloadQueueItem.MediaType.MOVIE);
    item.setMediaId(5L);
    item.setStatus(DownloadQueueItem.Status.PENDING);

    when(queueRepo.findAllByUserIdOrderByQueuePositionAsc(1L)).thenReturn(List.of(item));
    when(playlistRepo.findAllById(any())).thenReturn(List.of());

    List<DownloadQueueItemResponse> result = service.getQueue(1L);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).showId()).isNull();
    assertThat(result.get(0).seasonId()).isNull();
    assertThat(result.get(0).playlistId()).isNull();
    assertThat(result.get(0).playlistTitle()).isNull();
    assertThat(result.get(0).mediaId()).isEqualTo(5L);
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "*DownloadServiceTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `PlaylistRepository` not injected, new methods don't exist.

- [ ] **Step 3: Update `DownloadService.java`**

Add `PlaylistRepository` import and field:

```java
import org.lolobored.plexdownloader.repository.PlaylistRepository;
```

Add to the `@RequiredArgsConstructor` fields (after `TdarrClient`):

```java
    private final PlaylistRepository playlistRepo;
```

Replace `enqueueMovie` with the overloaded pair:

```java
    public List<Long> enqueueMovie(Long movieId, User user) {
        return enqueueMovie(movieId, user, null);
    }

    public List<Long> enqueueMovie(Long movieId, User user, Long playlistId) {
        Movie movie = movieRepo.findById(movieId)
            .orElseThrow(() -> new IllegalArgumentException("Movie not found: " + movieId));
        String subDir = "movies/" + Path.of(movie.getFilePath()).getParent().getFileName().toString();
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.MOVIE,
            movieId, movie.getFilePath(), subDir, movie.getTitle());
        item.setPlaylistId(playlistId);
        item = queueRepo.save(item);
        self.executeCopyAsync(item.getId());
        return List.of(item.getId());
    }
```

Replace `enqueueEpisode` with the overloaded pair:

```java
    public List<Long> enqueueEpisode(Long episodeId, User user) {
        return enqueueEpisode(episodeId, user, null);
    }

    public List<Long> enqueueEpisode(Long episodeId, User user, Long playlistId) {
        Episode ep = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        Season season = seasonRepo.findById(ep.getSeason().getId())
            .orElseThrow(() -> new IllegalArgumentException("Season not found for episode: " + episodeId));
        TvShow show = showRepo.findById(season.getShow().getId())
            .orElseThrow(() -> new IllegalArgumentException("Show not found for episode: " + episodeId));
        String subDir = "tvshows/" + Path.of(ep.getFilePath()).getParent().getParent().getFileName().toString()
                        + "/" + Path.of(ep.getFilePath()).getParent().getFileName().toString();
        String epTitle = show.getTitle() + " S" + String.format("%02d", season.getSeasonNumber())
                         + "E" + String.format("%02d", ep.getEpisodeNumber())
                         + (ep.getTitle() != null ? " - " + ep.getTitle() : "");
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
            episodeId, ep.getFilePath(), subDir, epTitle);
        item.setPlaylistId(playlistId);
        item = queueRepo.save(item);
        self.executeCopyAsync(item.getId());
        return List.of(item.getId());
    }
```

Add a private `EpisodeMeta` record (inside the class, before `slugify`):

```java
    private record EpisodeMeta(Long showId, Long seasonId, String showTitle, Integer seasonNumber) {}
```

Replace `getQueue()`:

```java
    public List<DownloadQueueItemResponse> getQueue(Long userId) {
        List<DownloadQueueItem> items = queueRepo.findAllByUserIdOrderByQueuePositionAsc(userId);

        // Batch-fetch episode → season → show metadata
        Set<Long> episodeIds = items.stream()
            .filter(i -> i.getMediaType() == DownloadQueueItem.MediaType.EPISODE)
            .map(DownloadQueueItem::getMediaId)
            .collect(Collectors.toSet());
        Map<Long, EpisodeMeta> episodeMeta = new java.util.HashMap<>();
        if (!episodeIds.isEmpty()) {
            episodeRepo.findWithSeasonAndShowByIdIn(episodeIds).forEach(ep ->
                episodeMeta.put(ep.getId(), new EpisodeMeta(
                    ep.getSeason().getShow().getId(),
                    ep.getSeason().getId(),
                    ep.getSeason().getShow().getTitle(),
                    ep.getSeason().getSeasonNumber()
                ))
            );
        }

        // Batch-fetch playlist titles
        Set<Long> playlistIds = items.stream()
            .filter(i -> i.getPlaylistId() != null)
            .map(DownloadQueueItem::getPlaylistId)
            .collect(Collectors.toSet());
        Map<Long, String> playlistTitles = new java.util.HashMap<>();
        if (!playlistIds.isEmpty()) {
            playlistRepo.findAllById(playlistIds)
                .forEach(p -> playlistTitles.put(p.getId(), p.getTitle()));
        }

        return items.stream().map(item -> {
            Long playlistId    = item.getPlaylistId();
            String playlistTitle = playlistId != null ? playlistTitles.get(playlistId) : null;
            if (item.getMediaType() == DownloadQueueItem.MediaType.EPISODE) {
                EpisodeMeta em = episodeMeta.get(item.getMediaId());
                return DownloadQueueItemResponse.from(item,
                    em != null ? em.showId()       : null,
                    em != null ? em.seasonId()     : null,
                    playlistId, playlistTitle,
                    em != null ? em.showTitle()    : null,
                    em != null ? em.seasonNumber() : null
                );
            }
            return DownloadQueueItemResponse.from(item, null, null, playlistId, playlistTitle, null, null);
        }).toList();
    }
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests "*DownloadServiceTest*" --no-daemon 2>&1 | tail -10
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java
git commit -m "feat: enqueue overloads with playlistId, enrich getQueue with titles #37"
```

---

### Task 4: `PlaylistSyncService` — thread `playlistId` through `enqueueItem()`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/PlaylistSyncService.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/PlaylistSyncServiceTest.java`

`enqueueItem` gains a `Long playlistId` fourth parameter. Both call sites (`syncPlaylist` and `enqueueForSubscription`) already have the playlist's local DB id.

- [ ] **Step 1: Write failing test**

In `PlaylistSyncServiceTest.java`, update the `syncAll_queuesAddedMovieForSubscriber` test to verify the new 3-arg `enqueueMovie`:

```java
@Test
void syncAll_queuesAddedMovieForSubscriber() {
    when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));
    Playlist local = localPlaylist(10L, "pl1");
    when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
    when(playlistRepo.save(any())).thenReturn(local);
    when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of());
    when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("m1", "movie")));

    User user = new User(); user.setId(1L);
    PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
    when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));

    Movie m = new Movie(); m.setId(100L);
    when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
    when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L))
        .thenReturn(false);
    when(downloadService.enqueueMovie(100L, user, 10L)).thenReturn(List.of(1L));
    when(playlistRepo.findAll()).thenReturn(List.of(local));

    service.syncAll();

    verify(downloadService).enqueueMovie(100L, user, 10L);
}
```

Also add a test for `enqueueForSubscription`:

```java
@Test
void enqueueForSubscription_passesPlaylistIdToEnqueue() {
    PlaylistItem pi = new PlaylistItem();
    pi.setPlexId("ep1");
    pi.setMediaType("EPISODE");

    when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(pi));

    Episode ep = new Episode(); ep.setId(200L);
    when(episodeRepo.findByPlexId("ep1")).thenReturn(Optional.of(ep));
    when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(anyLong(),
        eq(DownloadQueueItem.MediaType.EPISODE), eq(200L))).thenReturn(false);

    User user = new User(); user.setId(1L);
    when(downloadService.enqueueEpisode(200L, user, 10L)).thenReturn(List.of(5L));

    service.enqueueForSubscription(10L, user);

    verify(downloadService).enqueueEpisode(200L, user, 10L);
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "*PlaylistSyncServiceTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `enqueueMovie(Long, User, Long)` not found.

- [ ] **Step 3: Update `PlaylistSyncService.java`**

Change `enqueueItem` signature from `(User user, String plexId, String mediaType)` to `(User user, String plexId, String mediaType, Long playlistId)`:

```java
    // Package-private for testing
    void enqueueItem(User user, String plexId, String mediaType, Long playlistId) {
        if ("MOVIE".equals(mediaType)) {
            movieRepo.findByPlexId(plexId).ifPresent(m -> {
                if (!queueRepo.existsByUser_IdAndMediaTypeAndMediaId(
                        user.getId(), DownloadQueueItem.MediaType.MOVIE, m.getId())) {
                    try { downloadService.enqueueMovie(m.getId(), user, playlistId); }
                    catch (Exception e) { log.warn("Failed to enqueue movie {}: {}", m.getId(), e.getMessage()); }
                }
            });
        } else if ("EPISODE".equals(mediaType)) {
            episodeRepo.findByPlexId(plexId).ifPresent(ep -> {
                if (!queueRepo.existsByUser_IdAndMediaTypeAndMediaId(
                        user.getId(), DownloadQueueItem.MediaType.EPISODE, ep.getId())) {
                    try { downloadService.enqueueEpisode(ep.getId(), user, playlistId); }
                    catch (Exception e) { log.warn("Failed to enqueue episode {}: {}", ep.getId(), e.getMessage()); }
                }
            });
        }
    }
```

In `syncPlaylist()`, update the two `enqueueItem` call sites inside the subscriber loop:

```java
            for (String plexId : added) {
                PlexItem pi = fetchedByKey.get(plexId);
                if (pi != null) {
                    String mt = mapMediaType(pi.getType());
                    if (mt != null) enqueueItem(user, pi.getRatingKey(), mt, local.getId());
                }
            }
```

In `enqueueForSubscription()`:

```java
    public void enqueueForSubscription(Long playlistId, User user) {
        List<PlaylistItem> items = itemRepo.findByPlaylistIdOrderByOrdinalAsc(playlistId);
        for (PlaylistItem pi : items) {
            enqueueItem(user, pi.getPlexId(), pi.getMediaType(), playlistId);
        }
    }
```

- [ ] **Step 4: Run all backend tests**

```bash
./gradlew test --no-daemon 2>&1 | tail -20
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/PlaylistSyncService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/PlaylistSyncServiceTest.java
git commit -m "feat: thread playlistId through PlaylistSyncService.enqueueItem #37"
```

---

### Task 5: Frontend — `QueueView.vue` rewrite

**Files:**
- Rewrite: `frontend/src/views/QueueView.vue`
- Rewrite: `frontend/src/views/__tests__/QueueView.test.js`

Full rewrite: two-section collapsible tree (Subscribed Playlists + Individual Downloads), 4 status chips, status labels simplified (DONE and TRANSCODED both → "done"), Unsubscribe on playlist groups, all existing item actions preserved.

- [ ] **Step 1: Write failing tests**

Replace the entire content of `frontend/src/views/__tests__/QueueView.test.js`:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDownloadStore } from '../../stores/download.js'
import QueueView from '../QueueView.vue'
import * as downloadApi from '../../api/download.js'
import * as playlistApi from '../../api/playlists.js'
import { useRouter } from 'vue-router'

vi.mock('../../api/download.js', () => ({
  getQueue:          vi.fn().mockResolvedValue([]),
  enqueue:           vi.fn().mockResolvedValue({}),
  removeQueueItem:   vi.fn().mockResolvedValue(undefined),
  refreshTdarrStatus:vi.fn().mockResolvedValue({}),
  retryQueueItem:    vi.fn().mockResolvedValue({})
}))
vi.mock('../../api/playlists.js', () => ({
  unsubscribe:           vi.fn().mockResolvedValue(undefined),
  getPlaylistQueueCount: vi.fn().mockResolvedValue(0)
}))
vi.mock('vue-router', () => ({
  useRouter: vi.fn(),
  useRoute:  vi.fn(() => ({ params: {} }))
}))

// Helper factories
function movieItem(overrides = {}) {
  return {
    id: 1, mediaType: 'MOVIE', mediaId: 10, title: 'Inception',
    status: 'PENDING', tdarrStatus: 'NONE', tdarrError: null,
    playlistId: null, playlistTitle: null,
    showId: null, seasonId: null, showTitle: null, seasonNumber: null,
    queuePosition: 1, requestedAt: '2026-01-01T00:00:00Z', completedAt: null,
    ...overrides
  }
}
function episodeItem(overrides = {}) {
  return {
    id: 2, mediaType: 'EPISODE', mediaId: 99, title: 'Breaking Bad S01E01 - Pilot',
    status: 'PENDING', tdarrStatus: 'NONE', tdarrError: null,
    playlistId: null, playlistTitle: null,
    showId: 50, seasonId: 100, showTitle: 'Breaking Bad', seasonNumber: 1,
    queuePosition: 2, requestedAt: '2026-01-01T00:00:00Z', completedAt: null,
    ...overrides
  }
}

describe('QueueView', () => {
  let pushMock

  beforeEach(() => {
    pushMock = vi.fn()
    vi.mocked(useRouter).mockReturnValue({ push: pushMock })
    vi.clearAllMocks()
  })

  function factory(items = []) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useDownloadStore(pinia)
    store.queueItems = items
    store.fetchQueue = vi.fn()
    return { wrapper: mount(QueueView, { global: { plugins: [pinia] } }), store }
  }

  // ── Empty / basic ────────────────────────────────────────────────────────────

  it('shows empty state when queue is empty', () => {
    const { wrapper } = factory([])
    expect(wrapper.text()).toContain('Queue is empty')
  })

  it('fetchQueue called on mount', () => {
    const { store } = factory()
    expect(store.fetchQueue).toHaveBeenCalled()
  })

  // ── Count badge ──────────────────────────────────────────────────────────────

  it('count badge hidden when queue is empty', () => {
    const { wrapper } = factory([])
    expect(wrapper.find('[data-testid="count-badge"]').exists()).toBe(false)
  })

  it('count badge shows total visible items', () => {
    const { wrapper } = factory([movieItem(), episodeItem()])
    expect(wrapper.find('[data-testid="count-badge"]').text()).toBe('2')
  })

  // ── Subscribed Playlists section ─────────────────────────────────────────────

  it('playlist section hidden when no playlist items', () => {
    const { wrapper } = factory([movieItem()])
    expect(wrapper.find('[data-testid="section-playlists"]').exists()).toBe(false)
  })

  it('playlist group renders with title', () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    expect(wrapper.find('[data-testid="section-playlists"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Action Movies')
  })

  it('playlist group starts collapsed — no queue-item-rows visible', () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(0)
  })

  it('click playlist group header expands items', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(1)
  })

  it('unsubscribe button present on playlist group header', () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    expect(wrapper.find('[data-testid="unsub-btn-5"]').exists()).toBe(true)
  })

  it('unsubscribe button click does not expand group', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action Movies' })
    ])
    await wrapper.find('[data-testid="unsub-btn-5"]').trigger('click')
    await flushPromises()
    // group should remain collapsed
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(0)
  })

  // ── Individual Downloads section ─────────────────────────────────────────────

  it('individual downloads section hidden when all items have playlistId', () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'Action' })
    ])
    expect(wrapper.find('[data-testid="section-individual"]').exists()).toBe(false)
  })

  it('show group renders for episodes without playlistId', () => {
    const { wrapper } = factory([episodeItem()])
    expect(wrapper.find('[data-testid="section-individual"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Breaking Bad')
  })

  it('show group starts collapsed', () => {
    const { wrapper } = factory([episodeItem()])
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(0)
  })

  it('click show group header expands seasons', async () => {
    const { wrapper } = factory([episodeItem()])
    await wrapper.find('[data-testid="group-header-show-50"]').trigger('click')
    // Season sub-group should now be visible (but still collapsed itself)
    expect(wrapper.find('[data-testid="group-header-season-100"]').exists()).toBe(true)
  })

  it('click season header expands episodes', async () => {
    const { wrapper } = factory([episodeItem()])
    await wrapper.find('[data-testid="group-header-show-50"]').trigger('click')
    await wrapper.find('[data-testid="group-header-season-100"]').trigger('click')
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(1)
  })

  it('solo movie renders flat in individual downloads', () => {
    const { wrapper } = factory([movieItem()])
    expect(wrapper.find('[data-testid="section-individual"]').exists()).toBe(true)
    // Movie renders directly (always visible, no group header)
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(1)
  })

  // ── Status sections within expanded group ────────────────────────────────────

  it('IN_PROGRESS items appear in "In Progress" sub-section', async () => {
    const { wrapper } = factory([
      movieItem({ playlistId: 5, playlistTitle: 'P', status: 'IN_PROGRESS' })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="sub-label-IN_PROGRESS"]').exists()).toBe(true)
  })

  it('DONE and TRANSCODED items both appear in Done sub-section', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, playlistId: 5, playlistTitle: 'P', status: 'DONE', tdarrStatus: 'NONE', title: 'Film A' }),
      movieItem({ id: 2, playlistId: 5, playlistTitle: 'P', mediaId: 11, status: 'DONE', tdarrStatus: 'TRANSCODED', title: 'Film B' }),
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="sub-label-DONE"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="queue-item-row"]')).toHaveLength(2)
    // No TRANSCODED sub-section (both are in Done)
    expect(wrapper.find('[data-testid="sub-label-TRANSCODED"]').exists()).toBe(false)
  })

  it('TDARR_ERROR item shows retry button inside Done section', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, playlistId: 5, playlistTitle: 'P', status: 'ERROR',
                  tdarrStatus: 'TDARR_ERROR', tdarrError: 'codec error' })
    ])
    await wrapper.find('[data-testid="group-header-playlist-5"]').trigger('click')
    expect(wrapper.find('[data-testid="retry-btn"]').exists()).toBe(true)
  })

  // ── Filter bar ───────────────────────────────────────────────────────────────

  it('filter bar has 4 status chips (no TRANSCODING/TRANSCODED)', () => {
    const { wrapper } = factory([])
    expect(wrapper.find('[data-testid="chip-status-PENDING"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-COPYING"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-DONE"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-ERROR"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chip-status-TRANSCODING"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="chip-status-TRANSCODED"]').exists()).toBe(false)
  })

  it('MOVIE type chip hides show groups', async () => {
    const { wrapper } = factory([
      movieItem(),
      episodeItem({ id: 3, mediaId: 100 })
    ])
    await wrapper.find('[data-testid="chip-type-MOVIE"]').trigger('click')
    // show group gone, movie still there
    expect(wrapper.find('[data-testid="section-individual"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="group-header-show-50"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="queue-item-row"]').exists()).toBe(true)
  })

  it('text filter hides non-matching items and empty groups', async () => {
    const { wrapper } = factory([
      movieItem({ title: 'Inception' }),
      movieItem({ id: 3, mediaId: 11, title: 'The Matrix' }),
    ])
    await wrapper.find('[data-testid="filter-search"]').setValue('incep')
    expect(wrapper.text()).toContain('Inception')
    expect(wrapper.text()).not.toContain('The Matrix')
    expect(wrapper.find('[data-testid="count-badge"]').text()).toBe('1')
  })

  it('count badge reflects filtered count', async () => {
    const { wrapper } = factory([
      movieItem({ id: 1, title: 'A' }),
      movieItem({ id: 2, mediaId: 11, title: 'B', status: 'DONE', tdarrStatus: 'NONE',
                  completedAt: '2026-01-01T01:00:00Z' })
    ])
    await wrapper.find('[data-testid="chip-status-DONE"]').trigger('click')
    expect(wrapper.find('[data-testid="count-badge"]').text()).toBe('1')
  })

  // ── Item actions ─────────────────────────────────────────────────────────────

  it('remove button on solo movie calls removeQueueItem', async () => {
    const { wrapper, store } = factory([movieItem()])
    await wrapper.find('[data-testid="remove-btn-1"]').trigger('click')
    await flushPromises()
    expect(downloadApi.removeQueueItem).toHaveBeenCalledWith(1)
    expect(store.fetchQueue).toHaveBeenCalled()
  })

  it('clicking movie row navigates to movie detail', async () => {
    const { wrapper } = factory([movieItem()])
    await wrapper.find('[data-testid="queue-item-row"]').trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/movies/10')
  })

  it('clicking episode row navigates to episode detail', async () => {
    const { wrapper } = factory([episodeItem()])
    // expand show → season to reveal row
    await wrapper.find('[data-testid="group-header-show-50"]').trigger('click')
    await wrapper.find('[data-testid="group-header-season-100"]').trigger('click')
    await wrapper.find('[data-testid="queue-item-row"]').trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/tv/50/seasons/100/episodes/99')
  })

  it('remove button click does not navigate', async () => {
    const { wrapper } = factory([movieItem()])
    await wrapper.find('[data-testid="remove-btn-1"]').trigger('click')
    expect(pushMock).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | grep -E "QueueView|FAIL|pass" | head -20
```

Expected: many failures.

- [ ] **Step 3: Rewrite `QueueView.vue`**

Replace the entire file:

```vue
<template>
  <div>
    <h2>Download Queue <span v-if="totalVisible > 0" class="count-badge" data-testid="count-badge">{{ totalVisible }}</span></h2>

    <div class="filter-bar">
      <div class="filter-group">
        <button type="button" data-testid="chip-type-ALL"
                class="chip" :class="{ active: typeFilter === 'ALL' }"
                @click="setTypeFilter('ALL')">All</button>
        <button type="button" data-testid="chip-type-MOVIE"
                class="chip" :class="{ active: typeFilter === 'MOVIE' }"
                @click="setTypeFilter('MOVIE')">Movie</button>
        <button type="button" data-testid="chip-type-TV"
                class="chip" :class="{ active: typeFilter === 'TV' }"
                @click="setTypeFilter('TV')">TV</button>
      </div>
      <div class="filter-group">
        <button v-for="s in STATUS_CHIPS" :key="s.key"
                type="button"
                :data-testid="'chip-status-' + s.key"
                class="chip" :class="{ active: statusFilter.has(s.key) }"
                @click="toggleStatusFilter(s.key)">{{ s.label }}</button>
      </div>
      <input data-testid="filter-search" v-model="textFilter"
             type="search" class="filter-search" placeholder="Search…" />
    </div>

    <div v-if="dlStore.queueItems.length === 0" class="empty">Queue is empty.</div>
    <div v-else-if="totalVisible === 0" class="empty">No items match filters.</div>

    <!-- ── Subscribed Playlists ── -->
    <section v-if="playlistGroups.length" data-testid="section-playlists" class="tree-section">
      <h3 class="section-label">SUBSCRIBED PLAYLISTS</h3>

      <div v-for="group in playlistGroups" :key="group.playlistId" class="group">
        <div class="group-header"
             :data-testid="'group-header-playlist-' + group.playlistId"
             @click="toggleGroup('playlist-' + group.playlistId)">
          <span class="chevron">{{ isOpen('playlist-' + group.playlistId) ? '▼' : '▶' }}</span>
          <span class="group-title">📋 {{ group.playlistTitle }}</span>
          <span class="group-count">{{ group.items.length }}</span>
          <button class="btn-unsub"
                  :data-testid="'unsub-btn-' + group.playlistId"
                  @click.stop="handleUnsubscribeClick(group.playlistId, group.playlistTitle)">
            Unsubscribe
          </button>
        </div>

        <div v-if="isOpen('playlist-' + group.playlistId)" class="group-body">
          <template v-for="bucket in [buckets(group.items)]" :key="'b'">
            <div v-if="bucket.inProgress.length" class="sub-section">
              <p class="sub-label" data-testid="sub-label-IN_PROGRESS">IN PROGRESS</p>
              <QueueItemRow v-for="item in bucket.inProgress" :key="item.id"
                :item="item" :removing="removing" :retrying="retrying"
                @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
            </div>
            <div v-if="bucket.pending.length" class="sub-section">
              <p class="sub-label" data-testid="sub-label-PENDING">PENDING</p>
              <QueueItemRow v-for="item in bucket.pending" :key="item.id"
                :item="item" :removing="removing" :retrying="retrying"
                @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
            </div>
            <div v-if="bucket.done.length" class="sub-section">
              <p class="sub-label" data-testid="sub-label-DONE">DONE</p>
              <QueueItemRow v-for="item in bucket.done" :key="item.id"
                :item="item" :removing="removing" :retrying="retrying"
                @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
            </div>
          </template>
        </div>
      </div>
    </section>

    <!-- ── Individual Downloads ── -->
    <section v-if="showGroups.length || soloMovies.length" data-testid="section-individual" class="tree-section">
      <h3 class="section-label">INDIVIDUAL DOWNLOADS</h3>

      <!-- Show groups -->
      <div v-for="sg in showGroups" :key="sg.showId" class="group">
        <div class="group-header"
             :data-testid="'group-header-show-' + sg.showId"
             @click="toggleGroup('show-' + sg.showId)">
          <span class="chevron">{{ isOpen('show-' + sg.showId) ? '▼' : '▶' }}</span>
          <span class="group-title">📺 {{ sg.showTitle }}</span>
          <span class="group-count">{{ sg.seasons.reduce((n, s) => n + s.items.length, 0) }}</span>
        </div>

        <div v-if="isOpen('show-' + sg.showId)" class="group-body">
          <div v-for="season in sg.seasons" :key="season.seasonId" class="sub-group">
            <div class="sub-group-header"
                 :data-testid="'group-header-season-' + season.seasonId"
                 @click.stop="toggleGroup('season-' + season.seasonId)">
              <span class="chevron">{{ isOpen('season-' + season.seasonId) ? '▼' : '▶' }}</span>
              <span>Season {{ season.seasonNumber }}</span>
              <span class="group-count">{{ season.items.length }}</span>
            </div>

            <div v-if="isOpen('season-' + season.seasonId)" class="sub-group-body">
              <template v-for="bucket in [buckets(season.items)]" :key="'b'">
                <div v-if="bucket.inProgress.length" class="sub-section">
                  <p class="sub-label" data-testid="sub-label-IN_PROGRESS">IN PROGRESS</p>
                  <QueueItemRow v-for="item in bucket.inProgress" :key="item.id"
                    :item="item" :removing="removing" :retrying="retrying"
                    @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
                </div>
                <div v-if="bucket.pending.length" class="sub-section">
                  <p class="sub-label" data-testid="sub-label-PENDING">PENDING</p>
                  <QueueItemRow v-for="item in bucket.pending" :key="item.id"
                    :item="item" :removing="removing" :retrying="retrying"
                    @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
                </div>
                <div v-if="bucket.done.length" class="sub-section">
                  <p class="sub-label" data-testid="sub-label-DONE">DONE</p>
                  <QueueItemRow v-for="item in bucket.done" :key="item.id"
                    :item="item" :removing="removing" :retrying="retrying"
                    @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
                </div>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- Solo movies (always visible, no group wrapper) -->
      <QueueItemRow v-for="item in soloMovies" :key="item.id"
        :item="item" :removing="removing" :retrying="retrying"
        @remove="remove" @retry="retryItem" @navigate="navigateToItem" />
    </section>

    <ConfirmModal
      v-if="confirmState"
      :message="confirmState.message"
      confirmLabel="Yes, unsubscribe"
      cancelLabel="Keep"
      @confirm="confirmUnsubscribe"
      @cancel="confirmState = null"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, defineComponent, h } from 'vue'
import { useRouter } from 'vue-router'
import { useDownloadStore } from '@/stores/download.js'
import { removeQueueItem, refreshTdarrStatus, retryQueueItem } from '@/api/download.js'
import { unsubscribe, getPlaylistQueueCount } from '@/api/playlists.js'
import ConfirmModal from '@/components/ConfirmModal.vue'

const router  = useRouter()
const dlStore = useDownloadStore()
const removing  = ref(new Set())
const retrying  = ref(new Set())
const openGroups = ref(new Set())
const confirmState = ref(null) // { playlistId, message } | null

// ── Inline QueueItemRow component ────────────────────────────────────────────
const QueueItemRow = defineComponent({
  props: ['item', 'removing', 'retrying'],
  emits: ['remove', 'retry', 'navigate'],
  setup(props, { emit }) {
    return () => {
      const item = props.item
      const isRemoving = props.removing.has(item.id)
      const isRetrying = props.retrying.has(item.id)
      const isInProgress = item.status === 'IN_PROGRESS'
      const isTdarrError = item.tdarrStatus === 'TDARR_ERROR'

      let statusLabel = 'pending'
      if (item.status === 'IN_PROGRESS') statusLabel = 'copying…'
      else if (item.status === 'ERROR' || isTdarrError) statusLabel = 'error'
      else if (item.status === 'DONE') statusLabel = 'done'

      return h('div', {
        class: ['queue-item', 'clickable', isInProgress ? 'active' : '', (item.status === 'DONE' || item.status === 'ERROR') ? 'done' : ''].filter(Boolean).join(' '),
        'data-testid': 'queue-item-row',
        onClick: () => emit('navigate', item)
      }, [
        h('div', { class: 'item-info' }, [
          h('span', { class: 'type' }, item.title || `${item.mediaType} #${item.mediaId}`),
          h('span', { class: 'sub' }, statusLabel),
          item.status === 'ERROR' && item.errorMessage
            ? h('span', { class: 'error-msg' }, item.errorMessage)
            : null,
          isTdarrError && item.tdarrError
            ? h('span', { class: 'error-msg' }, item.tdarrError)
            : null,
        ].filter(Boolean)),
        isTdarrError
          ? h('button', {
              class: 'btn-retry',
              'data-testid': 'retry-btn',
              disabled: isRetrying,
              onClick: (e) => { e.stopPropagation(); emit('retry', item.id) }
            }, isRetrying ? '…' : '⟳ Retry')
          : null,
        h('button', {
          class: 'btn-remove',
          'data-testid': `remove-btn-${item.id}`,
          disabled: isRemoving || isInProgress,
          title: isInProgress ? 'Wait for copy to finish' : 'Remove',
          onClick: (e) => { e.stopPropagation(); emit('remove', item.id) }
        }, isRemoving ? '…' : '✕'),
      ].filter(Boolean))
    }
  }
})

// ── Filter state ──────────────────────────────────────────────────────────────
const typeFilter   = ref('ALL')
const statusFilter = ref(new Set())
const textFilter   = ref('')

const STATUS_CHIPS = [
  { key: 'PENDING', label: 'Pending' },
  { key: 'COPYING', label: 'Copying' },
  { key: 'DONE',    label: 'Done'    },
  { key: 'ERROR',   label: 'Error'   },
]

function matchesType(item) {
  if (typeFilter.value === 'ALL') return true
  if (typeFilter.value === 'MOVIE') return item.mediaType === 'MOVIE'
  if (typeFilter.value === 'TV')    return ['EPISODE', 'SEASON', 'SHOW'].includes(item.mediaType)
  return true
}
function matchesStatus(item) {
  if (statusFilter.value.size === 0) return true
  const a = statusFilter.value
  if (a.has('PENDING') && item.status === 'PENDING') return true
  if (a.has('COPYING') && item.status === 'IN_PROGRESS') return true
  if (a.has('DONE') && item.status === 'DONE' && item.tdarrStatus !== 'TDARR_ERROR') return true
  if (a.has('ERROR') && (item.status === 'ERROR' || item.tdarrStatus === 'TDARR_ERROR')) return true
  return false
}
function matchesText(item) {
  const q = textFilter.value.trim().toLowerCase()
  if (!q) return true
  const display = item.title || `${item.mediaType} #${item.mediaId}`
  return [display, item.mediaType, item.errorMessage, item.tdarrError, item.showTitle, item.playlistTitle]
    .filter(Boolean).some(s => s.toLowerCase().includes(q))
}

function setTypeFilter(type) {
  typeFilter.value = typeFilter.value === type ? 'ALL' : type
}
function toggleStatusFilter(status) {
  const next = new Set(statusFilter.value)
  if (next.has(status)) next.delete(status)
  else next.add(status)
  statusFilter.value = next
}

// ── Tree computation ──────────────────────────────────────────────────────────
const filteredItems = computed(() =>
  dlStore.queueItems.filter(matchesType).filter(matchesStatus).filter(matchesText)
)

const totalVisible = computed(() => filteredItems.value.length)

const playlistGroups = computed(() => {
  const map = new Map()
  for (const item of filteredItems.value) {
    if (item.playlistId == null) continue
    if (!map.has(item.playlistId)) {
      map.set(item.playlistId, {
        playlistId: item.playlistId,
        playlistTitle: item.playlistTitle || `Playlist ${item.playlistId}`,
        items: []
      })
    }
    map.get(item.playlistId).items.push(item)
  }
  return [...map.values()].sort((a, b) =>
    (a.playlistTitle || '').localeCompare(b.playlistTitle || ''))
})

const individualItems = computed(() =>
  filteredItems.value.filter(i => i.playlistId == null)
)

const showGroups = computed(() => {
  const map = new Map()
  for (const item of individualItems.value) {
    if (item.mediaType !== 'EPISODE') continue
    if (!map.has(item.showId)) {
      map.set(item.showId, {
        showId: item.showId,
        showTitle: item.showTitle || `Show ${item.showId}`,
        seasonMap: new Map()
      })
    }
    const sg = map.get(item.showId)
    if (!sg.seasonMap.has(item.seasonId)) {
      sg.seasonMap.set(item.seasonId, {
        seasonId: item.seasonId,
        seasonNumber: item.seasonNumber,
        items: []
      })
    }
    sg.seasonMap.get(item.seasonId).items.push(item)
  }
  return [...map.values()]
    .map(sg => ({
      ...sg,
      seasons: [...sg.seasonMap.values()]
        .sort((a, b) => (a.seasonNumber || 0) - (b.seasonNumber || 0))
    }))
    .sort((a, b) => (a.showTitle || '').localeCompare(b.showTitle || ''))
})

const soloMovies = computed(() =>
  individualItems.value
    .filter(i => i.mediaType === 'MOVIE')
    .sort((a, b) => (a.title || '').localeCompare(b.title || ''))
)

// ── Bucket helper ─────────────────────────────────────────────────────────────
function buckets(items) {
  return {
    inProgress: items.filter(i => i.status === 'IN_PROGRESS'),
    pending:    items.filter(i => i.status === 'PENDING'),
    done:       items.filter(i => i.status === 'DONE' || i.status === 'ERROR'),
  }
}

// ── Collapse / expand ─────────────────────────────────────────────────────────
function toggleGroup(key) {
  const next = new Set(openGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  openGroups.value = next
}
function isOpen(key) { return openGroups.value.has(key) }

// ── Actions ───────────────────────────────────────────────────────────────────
async function remove(id) {
  removing.value = new Set([...removing.value, id])
  try {
    await removeQueueItem(id)
    await dlStore.fetchQueue()
  } finally {
    const next = new Set(removing.value); next.delete(id); removing.value = next
  }
}

async function retryItem(id) {
  retrying.value = new Set([...retrying.value, id])
  try {
    await retryQueueItem(id)
    await dlStore.fetchQueue()
  } catch (e) {
    console.error('Retry failed', e)
  } finally {
    const next = new Set(retrying.value); next.delete(id); retrying.value = next
  }
}

function navigateToItem(item) {
  if (item.mediaType === 'MOVIE') {
    router.push('/movies/' + item.mediaId)
  } else if (item.mediaType === 'EPISODE' && item.showId != null) {
    router.push('/tv/' + item.showId + '/seasons/' + item.seasonId + '/episodes/' + item.mediaId)
  }
}

// ── Unsubscribe ───────────────────────────────────────────────────────────────
async function handleUnsubscribeClick(playlistId, playlistTitle) {
  const count = await getPlaylistQueueCount(playlistId)
  const message = count > 0
    ? `Unsubscribe from "${playlistTitle}" and cancel ${count} queued download${count === 1 ? '' : 's'}?`
    : `Unsubscribe from "${playlistTitle}"?`
  confirmState.value = { playlistId, message }
}

async function confirmUnsubscribe() {
  const { playlistId } = confirmState.value
  confirmState.value = null
  await unsubscribe(playlistId)
  await dlStore.fetchQueue()
}

let pollTimer = null
onMounted(async () => {
  try { await dlStore.fetchQueue() } catch (e) { console.error('Initial queue fetch failed:', e) }
  pollTimer = setInterval(() => dlStore.fetchQueue(), 2000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 24px; }
.count-badge { background: var(--surface2); border: 1px solid var(--border); color: var(--text-muted);
               font-size: .75rem; font-weight: 600; border-radius: 10px; padding: 2px 8px;
               margin-left: 8px; vertical-align: middle; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 24px; }
.filter-group { display: flex; gap: 6px; flex-wrap: wrap; }
.chip { background: var(--surface2); border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 16px; padding: 4px 12px; font-size: .8rem; cursor: pointer;
        transition: background .15s, color .15s, border-color .15s; }
.chip:hover:not(.active) { border-color: var(--accent-blue); color: var(--text); }
.chip.active { background: var(--accent-blue); border-color: var(--accent-blue); color: #fff; }
.filter-search { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
                 border-radius: 16px; padding: 4px 12px; font-size: .8rem; outline: none; min-width: 160px; }
.filter-search:focus { border-color: var(--accent-blue); }
.empty { color: var(--text-muted); padding: 40px 0; text-align: center; }

.tree-section { margin-bottom: 32px; }
.section-label { font-size: .75rem; font-weight: 700; color: var(--text-muted); letter-spacing: .08em;
                 text-transform: uppercase; margin-bottom: 8px; padding-bottom: 6px;
                 border-bottom: 1px solid var(--border); }
.group { margin-bottom: 4px; }
.group-header { display: flex; align-items: center; gap: 10px; padding: 10px 12px;
                background: var(--surface2); border-radius: 8px; cursor: pointer;
                user-select: none; }
.group-header:hover { background: color-mix(in srgb, var(--surface2) 85%, var(--text) 15%); }
.chevron { font-size: .75rem; color: var(--text-muted); width: 12px; }
.group-title { font-weight: 600; flex: 1; }
.group-count { font-size: .75rem; color: var(--text-muted); background: var(--surface);
               border: 1px solid var(--border); border-radius: 10px; padding: 1px 7px; }
.group-body { padding-left: 20px; margin-top: 2px; }

.sub-group { margin-bottom: 2px; }
.sub-group-header { display: flex; align-items: center; gap: 8px; padding: 7px 12px;
                    background: var(--surface); border-radius: 6px; cursor: pointer;
                    font-size: .9rem; color: var(--text-muted); user-select: none; }
.sub-group-header:hover { color: var(--text); }
.sub-group-body { padding-left: 16px; margin-top: 2px; }

.sub-section { margin-bottom: 8px; }
.sub-label { font-size: .68rem; font-weight: 700; color: var(--text-muted); letter-spacing: .07em;
             text-transform: uppercase; margin: 6px 0 4px 0; padding-left: 4px; }

.queue-item { display: flex; align-items: center; gap: 12px; padding: 9px 12px;
              background: var(--surface2); border-radius: 6px; margin-bottom: 4px; }
.queue-item.active { border-left: 3px solid var(--accent-blue); }
.queue-item.done   { opacity: 0.65; }
.queue-item.clickable { cursor: pointer; }
.queue-item.clickable:hover { background: color-mix(in srgb, var(--surface2) 85%, var(--text) 15%); }
.item-info { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.type { font-weight: 500; font-size: .9rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sub  { font-size: .78rem; color: var(--text-muted); }
.error-msg { font-size: .78rem; color: var(--red); }
.btn-remove { background: none; border: none; color: var(--text-muted); cursor: pointer;
              font-size: 1rem; padding: 4px 8px; border-radius: 4px; }
.btn-remove:hover:not(:disabled) { color: var(--red); background: rgba(231,76,60,.1); }
.btn-remove:disabled { opacity: 0.3; cursor: not-allowed; }
.btn-retry { background: none; border: 1px solid var(--red); color: var(--red); cursor: pointer;
             font-size: .8rem; padding: 2px 8px; border-radius: 4px; }
.btn-retry:hover { background: rgba(231,76,60,.1); }
.btn-unsub { background: none; border: 1px solid var(--accent); color: var(--accent); cursor: pointer;
             font-size: .75rem; padding: 2px 10px; border-radius: 4px; white-space: nowrap; }
.btn-unsub:hover { background: rgba(var(--accent-rgb, 52,152,219),.1); }
</style>
```

- [ ] **Step 4: Run frontend tests**

```bash
npm run test -- --reporter=verbose 2>&1 | tail -20
```

Expected: all pass.

- [ ] **Step 5: Run full backend test suite too**

```bash
cd ../backend && ./gradlew test --no-daemon 2>&1 | tail -10
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
cd ..
git add frontend/src/views/QueueView.vue \
        frontend/src/views/__tests__/QueueView.test.js
git commit -m "feat: queue tree view — collapsible playlist and show groups, closes #37"
```

---

## Verification (end-to-end)

1. `cd backend && ./gradlew test --no-daemon` — all green
2. `cd frontend && npm run test` — all green
3. `docker compose up --build` — start healthy
4. Subscribe to a playlist → add items to queue → Queue view shows "SUBSCRIBED PLAYLISTS" section with the playlist group collapsed
5. Click playlist group → expands showing IN PROGRESS / PENDING / DONE sub-sections
6. Queue two episodes from the same show directly → "INDIVIDUAL DOWNLOADS" section shows show group → expand → season → episodes
7. Queue a standalone movie → shows flat in Individual Downloads
8. Apply MOVIE type filter → show groups disappear, movies and playlist groups with movies remain
9. Playlist group "Unsubscribe" button → confirm modal → unsubscribes and refreshes tree
