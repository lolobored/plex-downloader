# Tdarr Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the conversion pipeline to Tdarr: files land in an organised subfolder structure via atomic copy, a scheduler polls the Tdarr v2 API to track processing status, and the queue view shows live Tdarr badges.

**Architecture:** `DownloadService` builds structured dest paths (`movies/{slug}/` / `tvshows/{show}/Season NN/`) and copies atomically (`.tmp` → rename). `TdarrClient` queries the Tdarr v2 `cruddb` endpoint by file path. `TdarrSyncScheduler` (cron, configurable) iterates DONE queue items and updates `tdarr_status` in DB. Frontend shows status badges in QueueView and a new Tdarr card in SettingsView.

**Tech Stack:** Spring Boot 3, Spring Data JPA, Liquibase, RestClient, SchedulingConfigurer (same pattern as `WatchedSyncScheduler`), Vue 3, Pinia, Vitest

---

## File Map

| Action | File |
|---|---|
| Create | `backend/src/main/resources/db/changelog/sql/004-tdarr-status.sql` |
| Modify | `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java` |
| Modify | `backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java` |
| Modify | `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java` |
| Create | `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java` |
| Create | `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java` |
| Modify | `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java` |
| Create | `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java` |
| Create | `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java` |
| Modify | `frontend/src/api/admin.js` |
| Modify | `frontend/src/views/SettingsView.vue` |
| Modify | `frontend/src/views/__tests__/SettingsView.test.js` |
| Modify | `frontend/src/views/QueueView.vue` |
| Modify | `frontend/src/views/__tests__/QueueView.test.js` |

---

## Background — Existing Code to Understand

**`DownloadService`** (service package) — current `buildItem` flat-copies `{conversionDir}/{filename}`. `executeCopyAsync` is `@Async`; in unit tests `@Async` is not active so it runs synchronously.

**`DownloadQueueItem`** (model) — has `Status` enum (PENDING/IN_PROGRESS/DONE/ERROR). Migration will add `tdarr_status` VARCHAR + `tdarr_error` TEXT.

**`WatchedSyncScheduler`** — reference implementation for `SchedulingConfigurer` pattern. Copy it exactly.

**`SettingsService.get(key)`** returns `Optional<String>`. `getRequired(key)` throws if missing.

**Test pattern for HTTP clients** — `@Spy @InjectMocks` + `protected` method for the HTTP call, stubbed with `doReturn(...).when(spy).methodName(...)`.

---

## Task 1: DB Migration + DownloadQueueItem TdarrStatus

**Files:**
- Create: `backend/src/main/resources/db/changelog/sql/004-tdarr-status.sql`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java`
- Test: context loads test (via `./gradlew test`)

- [ ] **Step 1: Write the failing test**

Add a test that instantiates `DownloadQueueItem` and asserts the default `tdarrStatus` is `NONE`. This will fail because neither the field nor the enum exist yet.

File: `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java`

Add this test to the class (below existing tests):

```java
@Test
void downloadQueueItem_hasDefaultTdarrStatusNone() {
    DownloadQueueItem item = new DownloadQueueItem();
    assertThat(item.getTdarrStatus()).isEqualTo(DownloadQueueItem.TdarrStatus.NONE);
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "org.lolobored.plexdownloader.service.DownloadServiceTest.downloadQueueItem_hasDefaultTdarrStatusNone"
```

Expected: FAIL — `cannot find symbol: method getTdarrStatus()`

- [ ] **Step 3: Create the migration**

Create `backend/src/main/resources/db/changelog/sql/004-tdarr-status.sql`:

```sql
-- liquibase formatted sql

-- changeset plexdownloader:004-tdarr-status-columns
ALTER TABLE download_queue
  ADD COLUMN tdarr_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
  ADD COLUMN tdarr_error  TEXT;

-- changeset plexdownloader:004-tdarr-settings
INSERT INTO settings (key, value) VALUES ('tdarr.server.url', '');
INSERT INTO settings (key, value) VALUES ('tdarr.sync.cron',  '0 */30 * * * *');
```

- [ ] **Step 4: Add TdarrStatus enum and fields to DownloadQueueItem**

Open `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java`.

Add the new enum and fields. Final file:

```java
package org.lolobored.plexdownloader.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.time.Instant;

@Data @ToString(exclude = "user") @Entity @Table(name = "download_queue")
public class DownloadQueueItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private MediaType mediaType;
    @Column(name = "media_id", nullable = false)
    private Long mediaId;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    @Enumerated(EnumType.STRING)
    @Column(name = "tdarr_status", nullable = false)
    private TdarrStatus tdarrStatus = TdarrStatus.NONE;
    @Column(name = "tdarr_error", columnDefinition = "TEXT")
    private String tdarrError;
    @Column(name = "queue_position")
    private Integer queuePosition;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "source_file_path", columnDefinition = "TEXT")
    private String sourceFilePath;
    @Column(name = "dest_file_path", columnDefinition = "TEXT")
    private String destFilePath;
    @Column(name = "requested_at", updatable = false)
    private Instant requestedAt = Instant.now();
    @Column(name = "completed_at")
    private Instant completedAt;

    public enum MediaType { MOVIE, EPISODE }
    public enum Status { PENDING, IN_PROGRESS, DONE, ERROR }
    public enum TdarrStatus { NONE, PROCESSING, TRANSCODED, TDARR_ERROR }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./gradlew test --tests "org.lolobored.plexdownloader.service.DownloadServiceTest.downloadQueueItem_hasDefaultTdarrStatusNone"
```

Expected: PASS

- [ ] **Step 6: Run full test suite**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL, all existing tests pass (migration runs on H2 in test context).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/changelog/sql/004-tdarr-status.sql \
        backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java
git commit -m "feat: add tdarr_status/tdarr_error to download_queue + settings keys"
```

---

## Task 2: DownloadService — Structured Paths + Atomic Copy

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java`

### Sub-task 2A: Structured paths

- [ ] **Step 1: Write failing tests for structured paths**

Add two tests to `DownloadServiceTest`. The first verifies movies land under `movies/{slug}/`, the second verifies episodes land under `tvshows/{showSlug}/Season NN/`.

```java
@Test
void enqueueMovie_buildsStructuredPath() {
    Movie movie = new Movie();
    movie.setId(1L);
    movie.setTitle("The Dark Knight");
    movie.setFilePath("/plex/movies/dark.mkv");

    User user = new User();
    user.setId(1L);

    when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
    when(settings.getRequired("plex.conversion.dir")).thenReturn("/conv");
    when(pathMapping.translate("/plex/movies/dark.mkv")).thenReturn("/mnt/movies/dark.mkv");
    when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
    when(queueRepo.save(any())).thenAnswer(inv -> {
        DownloadQueueItem i = inv.getArgument(0);
        i.setId(2L);
        return i;
    });

    service.enqueueMovie(1L, user);

    verify(queueRepo).save(argThat(item ->
        item.getDestFilePath() != null &&
        item.getDestFilePath().replace('\\', '/').contains("movies/the_dark_knight/dark.mkv")
    ));
}

@Test
void enqueueEpisode_buildsStructuredPath() {
    TvShow show = new TvShow();
    show.setId(100L);
    show.setTitle("Breaking Bad");

    Season season = new Season();
    season.setId(10L);
    season.setSeasonNumber(1);
    season.setShow(show);

    Episode ep = new Episode();
    ep.setId(1L);
    ep.setFilePath("/plex/tvshows/bb/s01e01.mkv");
    ep.setSeason(season);

    User user = new User();
    user.setId(1L);

    when(episodeRepo.findById(1L)).thenReturn(Optional.of(ep));
    when(seasonRepo.findById(10L)).thenReturn(Optional.of(season));
    when(showRepo.findById(100L)).thenReturn(Optional.of(show));
    when(settings.getRequired("plex.conversion.dir")).thenReturn("/conv");
    when(pathMapping.translate("/plex/tvshows/bb/s01e01.mkv")).thenReturn("/mnt/tvshows/bb/s01e01.mkv");
    when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
    when(queueRepo.save(any())).thenAnswer(inv -> {
        DownloadQueueItem i = inv.getArgument(0);
        i.setId(3L);
        return i;
    });

    service.enqueueEpisode(1L, user);

    verify(queueRepo).save(argThat(item ->
        item.getDestFilePath() != null &&
        item.getDestFilePath().replace('\\', '/').contains("tvshows/breaking_bad/Season 01/s01e01.mkv")
    ));
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./gradlew test --tests "org.lolobored.plexdownloader.service.DownloadServiceTest.enqueueMovie_buildsStructuredPath" --tests "org.lolobored.plexdownloader.service.DownloadServiceTest.enqueueEpisode_buildsStructuredPath"
```

Expected: FAIL — dest path doesn't contain subdirectories yet.

- [ ] **Step 3: Implement structured paths in DownloadService**

Replace the entire `DownloadService.java` with:

```java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private DownloadService self;
    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final SeasonRepository seasonRepo;
    private final TvShowRepository showRepo;
    private final DownloadQueueRepository queueRepo;
    private final PathMappingService pathMapping;
    private final SettingsService settings;

    @Autowired
    @Lazy
    public void setSelf(DownloadService self) {
        this.self = self;
    }

    public List<Long> enqueueMovie(Long movieId, User user) {
        Movie movie = movieRepo.findById(movieId)
            .orElseThrow(() -> new IllegalArgumentException("Movie not found: " + movieId));
        String subDir = "movies/" + slugify(movie.getTitle());
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.MOVIE,
            movieId, movie.getFilePath(), subDir);
        item = queueRepo.save(item);
        self.executeCopyAsync(item.getId());
        return List.of(item.getId());
    }

    public List<Long> enqueueEpisode(Long episodeId, User user) {
        Episode ep = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        Season season = seasonRepo.findById(ep.getSeason().getId())
            .orElseThrow(() -> new IllegalArgumentException("Season not found for episode: " + episodeId));
        TvShow show = showRepo.findById(season.getShow().getId())
            .orElseThrow(() -> new IllegalArgumentException("Show not found for episode: " + episodeId));
        String subDir = "tvshows/" + slugify(show.getTitle()) +
                        "/Season " + String.format("%02d", season.getSeasonNumber());
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
            episodeId, ep.getFilePath(), subDir);
        item = queueRepo.save(item);
        self.executeCopyAsync(item.getId());
        return List.of(item.getId());
    }

    public List<Long> enqueueSeason(Long seasonId, User user) {
        Season season = seasonRepo.findById(seasonId)
            .orElseThrow(() -> new IllegalArgumentException("Season not found: " + seasonId));
        TvShow show = showRepo.findById(season.getShow().getId())
            .orElseThrow(() -> new IllegalArgumentException("Show not found for season: " + seasonId));
        List<Episode> episodes = episodeRepo.findBySeasonIdOrderByEpisodeNumber(seasonId);
        if (episodes.isEmpty()) throw new IllegalArgumentException("Season has no episodes: " + seasonId);
        String subDir = "tvshows/" + slugify(show.getTitle()) +
                        "/Season " + String.format("%02d", season.getSeasonNumber());
        List<Long> ids = new ArrayList<>();
        for (Episode ep : episodes) {
            DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
                ep.getId(), ep.getFilePath(), subDir);
            item = queueRepo.save(item);
            self.executeCopyAsync(item.getId());
            ids.add(item.getId());
        }
        return ids;
    }

    public List<Long> enqueueShow(Long showId, User user) {
        List<Season> seasons = seasonRepo.findByShowIdOrderBySeasonNumber(showId);
        if (seasons.isEmpty()) throw new IllegalArgumentException("Show not found or empty: " + showId);
        List<Long> ids = new ArrayList<>();
        for (Season season : seasons) {
            ids.addAll(enqueueSeason(season.getId(), user));
        }
        return ids;
    }

    public List<DownloadQueueItem> getQueue() {
        return queueRepo.findAllByOrderByQueuePositionAsc();
    }

    static String slugify(String title) {
        if (title == null) return "unknown";
        return title.toLowerCase()
                    .replaceAll("[^a-z0-9\\s]", "")
                    .trim()
                    .replaceAll("\\s+", "_");
    }

    private DownloadQueueItem buildItem(User user, DownloadQueueItem.MediaType type,
                                        Long mediaId, String plexFilePath, String subDir) {
        String appPath = pathMapping.translate(plexFilePath);
        String conversionDir = settings.getRequired("plex.conversion.dir");
        String filename = Path.of(appPath).getFileName().toString();
        String destPath = Path.of(conversionDir, subDir, filename).toString();

        int nextPos = queueRepo.findMaxQueuePosition().orElse(0) + 1;

        DownloadQueueItem item = new DownloadQueueItem();
        item.setUser(user);
        item.setMediaType(type);
        item.setMediaId(mediaId);
        item.setSourceFilePath(plexFilePath);
        item.setDestFilePath(destPath);
        item.setQueuePosition(nextPos);
        item.setStatus(DownloadQueueItem.Status.PENDING);
        return item;
    }

    @Async
    public void executeCopyAsync(Long itemId) {
        DownloadQueueItem item = queueRepo.findById(itemId).orElse(null);
        if (item == null) return;

        item.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        queueRepo.save(item);

        try {
            Path source = Path.of(pathMapping.translate(item.getSourceFilePath()));
            Path dest = Path.of(item.getDestFilePath());
            if (!Files.exists(source)) {
                throw new IOException("Source file not found: " + source);
            }
            Files.createDirectories(dest.getParent());
            Path temp = Path.of(item.getDestFilePath() + ".tmp");
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            item.setStatus(DownloadQueueItem.Status.DONE);
            item.setCompletedAt(Instant.now());
        } catch (IOException e) {
            item.setStatus(DownloadQueueItem.Status.ERROR);
            item.setErrorMessage(e.getMessage());
            log.error("Copy failed for item {}: {}", itemId, e.getMessage());
        }
        queueRepo.save(item);
    }
}
```

- [ ] **Step 4: Run structured path tests**

```bash
cd backend && ./gradlew test --tests "org.lolobored.plexdownloader.service.DownloadServiceTest.enqueueMovie_buildsStructuredPath" --tests "org.lolobored.plexdownloader.service.DownloadServiceTest.enqueueEpisode_buildsStructuredPath"
```

Expected: PASS

### Sub-task 2B: Atomic copy test

- [ ] **Step 5: Write failing test for atomic copy**

Add to `DownloadServiceTest`:

```java
@Test
void executeCopyAsync_atomicRename_cleansUpTempFile() throws Exception {
    Path sourceFile = tempDir.resolve("source.mkv");
    Files.writeString(sourceFile, "video-content");
    Path destDir = tempDir.resolve("dest");
    Path destFile = destDir.resolve("output.mkv");

    DownloadQueueItem item = new DownloadQueueItem();
    item.setId(5L);
    item.setSourceFilePath("/plex/source.mkv");
    item.setDestFilePath(destFile.toString());
    item.setStatus(DownloadQueueItem.Status.PENDING);

    when(queueRepo.findById(5L)).thenReturn(Optional.of(item));
    when(pathMapping.translate("/plex/source.mkv")).thenReturn(sourceFile.toString());
    when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.executeCopyAsync(5L);

    assertThat(destFile).exists();
    assertThat(destDir.resolve("output.mkv.tmp")).doesNotExist();
    assertThat(item.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
}
```

- [ ] **Step 6: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests "org.lolobored.plexdownloader.service.DownloadServiceTest.executeCopyAsync_atomicRename_cleansUpTempFile"
```

Expected: FAIL — old code uses `Files.copy` without `.tmp` temp file, `output.mkv.tmp` won't be cleaned up or doesn't exist check may fail.

Note: the implementation in Step 3 already includes atomic copy. Run the test — it should actually PASS here because we already wrote the implementation. If it fails, check that `AtomicMoveNotSupportedException` is handled (the `tmpDir` is same filesystem so `ATOMIC_MOVE` should work; if not, the fallback `REPLACE_EXISTING` will be used).

- [ ] **Step 7: Run full test suite**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java
git commit -m "feat: structured copy paths and atomic file delivery"
```

---

## Task 3: TdarrClient

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java`
- Create: `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java`

- [ ] **Step 1: Write failing tests**

Create `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java`:

```java
package org.lolobored.plexdownloader.client;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TdarrClientTest {

    @Mock SettingsService settings;
    @Spy @InjectMocks TdarrClient client;

    @Test
    void getFileStatus_returnsEmpty_whenUrlBlank() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("  "));
        assertThat(client.getFileStatus("/some/file.mkv")).isEmpty();
    }

    @Test
    void getFileStatus_returnsEmpty_whenUrlMissing() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.empty());
        assertThat(client.getFileStatus("/some/file.mkv")).isEmpty();
    }

    @Test
    void getFileStatus_returnsProcessing_whenQueued() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Queued");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        Optional<TdarrClient.TdarrFileStatus> result = client.getFileStatus("/file.mkv");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(DownloadQueueItem.TdarrStatus.PROCESSING);
    }

    @Test
    void getFileStatus_returnsProcessing_whenProcessing() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Processing");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv").get().status())
            .isEqualTo(DownloadQueueItem.TdarrStatus.PROCESSING);
    }

    @Test
    void getFileStatus_returnsTranscoded_whenDoneTranscoding() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Done transcoding");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv").get().status())
            .isEqualTo(DownloadQueueItem.TdarrStatus.TRANSCODED);
    }

    @Test
    void getFileStatus_returnsTranscoded_whenNoActionNeeded() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("No action needed");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv").get().status())
            .isEqualTo(DownloadQueueItem.TdarrStatus.TRANSCODED);
    }

    @Test
    void getFileStatus_returnsTdarrError_withErrorMessage() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Transcode error");
        resp.setErrorMessage("codec not supported");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        Optional<TdarrClient.TdarrFileStatus> result = client.getFileStatus("/file.mkv");

        assertThat(result.get().status()).isEqualTo(DownloadQueueItem.TdarrStatus.TDARR_ERROR);
        assertThat(result.get().errorMessage()).isEqualTo("codec not supported");
    }

    @Test
    void getFileStatus_returnsEmpty_whenRestClientException() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        doThrow(new RestClientException("connection refused"))
            .when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv")).isEmpty();
    }

    @Test
    void getFileStatus_returnsNone_whenResponseNull() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        doReturn(null).when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv").get().status())
            .isEqualTo(DownloadQueueItem.TdarrStatus.NONE);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./gradlew test --tests "org.lolobored.plexdownloader.client.TdarrClientTest"
```

Expected: FAIL — `TdarrClient` does not exist yet.

- [ ] **Step 3: Implement TdarrClient**

Create `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java`:

```java
package org.lolobored.plexdownloader.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.service.SettingsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TdarrClient {

    private final SettingsService settings;

    public record TdarrFileStatus(DownloadQueueItem.TdarrStatus status, String errorMessage) {}

    public Optional<TdarrFileStatus> getFileStatus(String absoluteFilePath) {
        String baseUrl = settings.get("tdarr.server.url").orElse("").trim();
        if (baseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            TdarrFileResponse response = fetchStatus(baseUrl, absoluteFilePath);
            if (response == null) {
                return Optional.of(new TdarrFileStatus(DownloadQueueItem.TdarrStatus.NONE, null));
            }
            DownloadQueueItem.TdarrStatus status = mapStatus(response.getTdarrStatus());
            String error = status == DownloadQueueItem.TdarrStatus.TDARR_ERROR
                ? response.getErrorMessage() : null;
            return Optional.of(new TdarrFileStatus(status, error));
        } catch (RestClientException e) {
            log.warn("Tdarr API error for {}: {}", absoluteFilePath, e.getMessage());
            return Optional.empty();
        }
    }

    protected TdarrFileResponse fetchStatus(String baseUrl, String filePath) {
        Map<String, Object> body = Map.of("data", Map.of(
            "collection", "FileJSONDB",
            "mode",       "getByID",
            "docID",      filePath
        ));
        return RestClient.create().post()
            .uri(baseUrl + "/api/v2/cruddb")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(TdarrFileResponse.class);
    }

    private DownloadQueueItem.TdarrStatus mapStatus(String tdarrStatus) {
        if (tdarrStatus == null || tdarrStatus.isBlank()) {
            return DownloadQueueItem.TdarrStatus.NONE;
        }
        return switch (tdarrStatus) {
            case "Queued", "Processing" -> DownloadQueueItem.TdarrStatus.PROCESSING;
            case "Done transcoding", "No action needed" -> DownloadQueueItem.TdarrStatus.TRANSCODED;
            case "Transcode error", "Health error" -> DownloadQueueItem.TdarrStatus.TDARR_ERROR;
            default -> DownloadQueueItem.TdarrStatus.PROCESSING;
        };
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TdarrFileResponse {
        @JsonProperty("tdarrStatus")
        private String tdarrStatus;
        @JsonProperty("errorMessage")
        private String errorMessage;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./gradlew test --tests "org.lolobored.plexdownloader.client.TdarrClientTest"
```

Expected: all 8 tests PASS

- [ ] **Step 5: Run full suite**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java \
        backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java
git commit -m "feat: add TdarrClient (v2 cruddb status polling)"
```

---

## Task 4: DownloadQueueRepository Query + TdarrSyncScheduler

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java`
- Create: `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java`

- [ ] **Step 1: Write failing tests for the scheduler**

Create `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java`:

```java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TdarrSyncSchedulerTest {

    @Mock TdarrClient tdarrClient;
    @Mock DownloadQueueRepository queueRepo;
    @Mock SettingsService settings;
    @InjectMocks TdarrSyncScheduler scheduler;

    private DownloadQueueItem doneItem(String destPath) {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(1L);
        item.setDestFilePath(destPath);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        return item;
    }

    @Test
    void syncAll_skipsItem_whenTdarrReturnsEmpty() {
        DownloadQueueItem item = doneItem("/conv/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(tdarrClient.getFileStatus(anyString())).thenReturn(Optional.empty());

        scheduler.syncAll();

        verify(queueRepo, never()).save(any());
    }

    @Test
    void syncAll_updatesStatusToProcessing() {
        DownloadQueueItem item = doneItem("/conv/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(tdarrClient.getFileStatus("/conv/movies/test/movie.mkv"))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.PROCESSING, null)));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.PROCESSING &&
            i.getTdarrError() == null
        ));
    }

    @Test
    void syncAll_updatesStatusToTranscoded() {
        DownloadQueueItem item = doneItem("/conv/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(tdarrClient.getFileStatus(anyString()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.TRANSCODED, null)));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED
        ));
    }

    @Test
    void syncAll_updatesStatusToError_withMessage() {
        DownloadQueueItem item = doneItem("/conv/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(tdarrClient.getFileStatus(anyString()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.TDARR_ERROR, "codec not supported")));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TDARR_ERROR &&
            "codec not supported".equals(i.getTdarrError())
        ));
    }

    @Test
    void syncAll_doesNothingWhenNoItemsPending() {
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of());

        scheduler.syncAll();

        verify(tdarrClient, never()).getFileStatus(any());
        verify(queueRepo, never()).save(any());
    }

    @Test
    void syncAll_queriesCorrectStatuses() {
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of());

        scheduler.syncAll();

        verify(queueRepo).findByStatusAndTdarrStatusNotIn(
            eq(DownloadQueueItem.Status.DONE),
            argThat((Collection<DownloadQueueItem.TdarrStatus> col) ->
                col.contains(DownloadQueueItem.TdarrStatus.TRANSCODED) &&
                col.contains(DownloadQueueItem.TdarrStatus.TDARR_ERROR)
            )
        );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && ./gradlew test --tests "org.lolobored.plexdownloader.service.TdarrSyncSchedulerTest"
```

Expected: FAIL — `TdarrSyncScheduler` does not exist.

- [ ] **Step 3: Add repository method**

Open `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java` and add:

```java
import java.util.Collection;

// add inside the interface:
List<DownloadQueueItem> findByStatusAndTdarrStatusNotIn(
    DownloadQueueItem.Status status,
    Collection<DownloadQueueItem.TdarrStatus> tdarrStatuses
);
```

Full updated file:

```java
package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface DownloadQueueRepository extends JpaRepository<DownloadQueueItem, Long> {

    List<DownloadQueueItem> findAllByOrderByQueuePositionAsc();

    @Query("SELECT i FROM DownloadQueueItem i WHERE i.status = 'IN_PROGRESS'")
    Optional<DownloadQueueItem> findInProgress();

    @Query("SELECT MAX(i.queuePosition) FROM DownloadQueueItem i WHERE i.status = 'PENDING'")
    Optional<Integer> findMaxQueuePosition();

    @Query("SELECT i.mediaId FROM DownloadQueueItem i " +
           "WHERE i.user.id = :userId AND i.mediaType = 'EPISODE' " +
           "AND i.status IN ('PENDING', 'IN_PROGRESS', 'DONE') " +
           "AND i.mediaId IN (SELECT e.id FROM Episode e WHERE e.season.show.id = :showId)")
    Set<Long> findActiveEpisodeIdsForShow(@Param("userId") Long userId, @Param("showId") Long showId);

    List<DownloadQueueItem> findByStatusAndTdarrStatusNotIn(
        DownloadQueueItem.Status status,
        Collection<DownloadQueueItem.TdarrStatus> tdarrStatuses
    );
}
```

- [ ] **Step 4: Implement TdarrSyncScheduler**

Create `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java`:

```java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TdarrSyncScheduler implements SchedulingConfigurer {

    private static final String DEFAULT_CRON = "0 */30 * * * *";

    private final TdarrClient tdarrClient;
    private final DownloadQueueRepository queueRepo;
    private final SettingsService settings;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            this::syncAll,
            ctx -> {
                String cron = settings.get("tdarr.sync.cron").orElse(DEFAULT_CRON);
                return new CronTrigger(cron).nextExecution(ctx);
            }
        );
    }

    void syncAll() {
        List<DownloadQueueItem> items = queueRepo.findByStatusAndTdarrStatusNotIn(
            DownloadQueueItem.Status.DONE,
            List.of(DownloadQueueItem.TdarrStatus.TRANSCODED, DownloadQueueItem.TdarrStatus.TDARR_ERROR)
        );
        log.info("Tdarr sync: checking {} items", items.size());
        for (DownloadQueueItem item : items) {
            Optional<TdarrClient.TdarrFileStatus> statusOpt =
                tdarrClient.getFileStatus(item.getDestFilePath());
            if (statusOpt.isEmpty()) {
                log.warn("Tdarr unreachable, skipping item {}", item.getId());
                continue;
            }
            TdarrClient.TdarrFileStatus ts = statusOpt.get();
            item.setTdarrStatus(ts.status());
            item.setTdarrError(ts.errorMessage());
            queueRepo.save(item);
            log.info("Tdarr status updated: item={} status={}", item.getId(), ts.status());
        }
    }
}
```

- [ ] **Step 5: Run scheduler tests**

```bash
cd backend && ./gradlew test --tests "org.lolobored.plexdownloader.service.TdarrSyncSchedulerTest"
```

Expected: all 6 tests PASS

- [ ] **Step 6: Run full suite**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java \
        backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java
git commit -m "feat: add TdarrSyncScheduler (cron poller for Tdarr job status)"
```

---

## Task 5: Frontend — admin.js + SettingsView + QueueView

**Files:**
- Modify: `frontend/src/api/admin.js`
- Modify: `frontend/src/views/SettingsView.vue`
- Modify: `frontend/src/views/__tests__/SettingsView.test.js`
- Modify: `frontend/src/views/QueueView.vue`
- Modify: `frontend/src/views/__tests__/QueueView.test.js`

### Sub-task 5A: admin.js

- [ ] **Step 1: Update admin.js — no test needed (pure passthrough)**

Replace `frontend/src/api/admin.js` with:

```js
import http from './axios.js'

export async function getSettings() {
  const { data } = await http.get('/api/admin/settings')
  return data
}

export async function putSettings(settings) {
  await http.put('/api/admin/settings', settings)
}

export async function getSyncStatus() {
  const { data } = await http.get('/api/admin/sync/status')
  return data
}

export async function triggerSync() {
  await http.post('/api/admin/sync')
}
```

No change needed — settings are key-value generic; `putSettings` already sends any keys. `getSettings` returns whatever keys the server has. The new `tdarr.server.url` and `tdarr.sync.cron` keys will be handled by SettingsView directly.

### Sub-task 5B: SettingsView

- [ ] **Step 2: Write failing test for Tdarr settings fields**

Add to `frontend/src/views/__tests__/SettingsView.test.js` (inside the existing `describe` block):

```js
it('renders tdarr URL field', async () => {
  getSettings.mockResolvedValue({
    'plex.server.url':        'http://localhost:32400',
    'plex.path.prefix.plex':  '/data/plex',
    'plex.path.prefix.app':   '/movies',
    'plex.poster.dir':        '/posters',
    'plex.conversion.dir':    '/conversion',
    'plex.sync.cron':         '0 0 */6 * * *',
    'watched.sync.cron':      '0 */15 * * * *',
    'tdarr.server.url':       'http://tdarr:8265',
    'tdarr.sync.cron':        '0 */30 * * * *'
  })
  getSyncStatus.mockResolvedValue({ state: 'IDLE', lastSyncAt: null, itemsSynced: 0, error: null })
  const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: { role: 'ADMIN' } } })
  const w = mount(SettingsView, { global: { plugins: [pinia] } })
  await flushPromises()
  const input = w.find('input[name="tdarrUrl"]')
  expect(input.exists()).toBe(true)
  expect(input.element.value).toBe('http://tdarr:8265')
})
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd frontend && npm test -- --run --reporter=verbose 2>&1 | grep -A3 "tdarr URL"
```

Expected: FAIL — `input[name="tdarrUrl"]` not found.

- [ ] **Step 4: Update SettingsView.vue**

Replace the entire file with the version below. Key changes: add `tdarrUrl` and `tdarrSyncCron` to `form`, add them to `onMounted` mapping, include them in `save()` payload, add a "Tdarr" card section.

```vue
<template>
  <div v-if="!authStore.isAdmin" class="error">Access denied.</div>
  <div v-else>
    <h2>Settings</h2>

    <section class="card-section">
      <h3>Plex Connection</h3>
      <div class="field">
        <label>Server URL</label>
        <input name="plexUrl" v-model="form.plexUrl" type="url" placeholder="http://localhost:32400" />
      </div>
      <div class="field">
        <label>Plex Token</label>
        <input name="plexToken" v-model="form.plexToken" type="password" placeholder="xxxxxxxxxxxxxxxxxxxx" />
      </div>
      <button class="btn-save" @click="save" :disabled="saving">
        {{ saving ? 'Saving…' : 'Save' }}
      </button>
      <p v-if="saveOk" class="ok">Saved.</p>
    </section>

    <section class="card-section">
      <h3>Path Mapping</h3>
      <div class="field">
        <label>Plex path prefix</label>
        <input name="plexPrefix" v-model="form.plexPathPrefixPlex" type="text" />
      </div>
      <div class="field">
        <label>App path prefix</label>
        <input name="appPrefix" v-model="form.plexPathPrefixApp" type="text" />
      </div>
      <div class="field">
        <label>Poster directory</label>
        <input name="posterDir" v-model="form.plexPosterDir" type="text" readonly class="readonly" />
      </div>
      <div class="field">
        <label>Conversion directory</label>
        <input name="conversionDir" v-model="form.plexConversionDir" type="text" readonly class="readonly" />
      </div>
      <button class="btn-save" @click="save" :disabled="saving">
        {{ saving ? 'Saving…' : 'Save' }}
      </button>
    </section>

    <section class="card-section">
      <h3>Library Sync</h3>
      <div class="field">
        <label>Library sync cron expression</label>
        <input name="syncCron" v-model="form.syncCron" type="text" placeholder="0 0 */6 * * *" />
      </div>
      <div class="field">
        <label>Watched status sync cron</label>
        <input name="watchedSyncCron" v-model="form.watchedSyncCron" type="text" placeholder="0 */15 * * * *" />
      </div>
      <div class="sync-status" v-if="syncStatus">
        <span :class="['state', syncStatus.state.toLowerCase()]">{{ syncStatus.state }}</span>
        <span v-if="syncStatus.lastSyncAt" class="last-sync">
          Last sync: {{ new Date(syncStatus.lastSyncAt).toLocaleString() }}
          ({{ syncStatus.itemsSynced }} items)
        </span>
        <span v-if="syncStatus.error" class="sync-error">{{ syncStatus.error }}</span>
      </div>
      <div class="sync-actions">
        <button class="btn-save" @click="save" :disabled="saving">Save cron</button>
        <button class="btn-sync" data-testid="sync-btn" @click="sync" :disabled="syncing">
          {{ syncing ? 'Syncing…' : '↻ Sync Now' }}
        </button>
      </div>
    </section>

    <section class="card-section">
      <h3>Tdarr</h3>
      <div class="field">
        <label>Tdarr server URL</label>
        <input name="tdarrUrl" v-model="form.tdarrUrl" type="url" placeholder="http://192.168.1.10:8265" />
      </div>
      <div class="field">
        <label>Tdarr sync cron</label>
        <input name="tdarrSyncCron" v-model="form.tdarrSyncCron" type="text" placeholder="0 */30 * * * *" />
      </div>
      <button class="btn-save" @click="save" :disabled="saving">Save</button>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth.js'
import { getSettings, putSettings, getSyncStatus, triggerSync } from '@/api/admin.js'

const authStore  = useAuthStore()
const saving     = ref(false)
const saveOk     = ref(false)
const syncing    = ref(false)
const syncStatus = ref(null)
let saveOkTimer = null
onUnmounted(() => clearTimeout(saveOkTimer))

const form = reactive({
  plexUrl:            '',
  plexToken:          '',
  plexPathPrefixPlex: '',
  plexPathPrefixApp:  '',
  plexPosterDir:      '',
  plexConversionDir:  '',
  syncCron:           '',
  watchedSyncCron:    '',
  tdarrUrl:           '',
  tdarrSyncCron:      ''
})

onMounted(async () => {
  const [s, ss] = await Promise.all([getSettings(), getSyncStatus()])
  form.plexUrl            = s['plex.server.url']        ?? ''
  form.plexToken          = ''  // never pre-fill tokens
  form.plexPathPrefixPlex = s['plex.path.prefix.plex']  ?? ''
  form.plexPathPrefixApp  = s['plex.path.prefix.app']   ?? ''
  form.plexPosterDir      = s['plex.poster.dir']        ?? ''
  form.plexConversionDir  = s['plex.conversion.dir']    ?? ''
  form.syncCron           = s['plex.sync.cron']         ?? ''
  form.watchedSyncCron    = s['watched.sync.cron']      ?? ''
  form.tdarrUrl           = s['tdarr.server.url']       ?? ''
  form.tdarrSyncCron      = s['tdarr.sync.cron']        ?? ''
  syncStatus.value = ss
})

async function save() {
  saving.value = true
  saveOk.value = false
  const payload = {
    'plex.server.url':        form.plexUrl,
    'plex.path.prefix.plex':  form.plexPathPrefixPlex,
    'plex.path.prefix.app':   form.plexPathPrefixApp,
    'plex.sync.cron':         form.syncCron,
    'watched.sync.cron':      form.watchedSyncCron,
    'tdarr.server.url':       form.tdarrUrl,
    'tdarr.sync.cron':        form.tdarrSyncCron
  }
  if (form.plexToken) payload['plex.server.token'] = form.plexToken
  try {
    await putSettings(payload)
    form.plexToken = ''
    saveOk.value = true
    saveOkTimer = setTimeout(() => { saveOk.value = false }, 2000)
  } finally {
    saving.value = false
  }
}

async function sync() {
  syncing.value = true
  try {
    await triggerSync()
    syncStatus.value = await getSyncStatus()
  } finally {
    syncing.value = false
  }
}
</script>

<style scoped>
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 24px; }
h3 { font-size: 1rem; font-weight: 600; margin-bottom: 16px; }
.card-section { background: var(--surface); border: 1px solid var(--border); border-radius: 10px;
                padding: 24px; max-width: 600px; margin-bottom: 24px; }
.field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
label { font-size: .85rem; color: var(--text-muted); }
input { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
        border-radius: 6px; padding: 8px 12px; font-size: .9rem; }
input:focus { outline: none; border-color: var(--accent-blue); }
input.readonly { opacity: 0.6; cursor: default; }
.btn-save { background: var(--accent); color: #000; border: none; border-radius: 6px;
            padding: 8px 20px; font-weight: 600; }
.btn-save:disabled { opacity: 0.6; }
.ok { color: var(--green); font-size: .85rem; margin-top: 8px; }
.sync-status { margin: 12px 0; display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.state { padding: 3px 10px; border-radius: 12px; font-size: .8rem; font-weight: 600; text-transform: uppercase; }
.state.idle    { background: var(--surface2); color: var(--text-muted); }
.state.running { background: var(--accent-blue); color: #fff; }
.state.done    { background: var(--green); color: #fff; }
.state.error   { background: var(--red); color: #fff; }
.last-sync  { font-size: .85rem; color: var(--text-muted); }
.sync-error { font-size: .85rem; color: var(--red); }
.sync-actions { display: flex; gap: 12px; align-items: center; margin-top: 4px; }
.btn-sync { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
            border-radius: 6px; padding: 8px 16px; }
.btn-sync:hover:not(:disabled) { border-color: var(--accent-blue); }
.error { color: var(--red); padding: 40px; text-align: center; }
</style>
```

- [ ] **Step 5: Run SettingsView test**

```bash
cd frontend && npm test -- --run --reporter=verbose 2>&1 | grep -E "SettingsView|tdarr"
```

Expected: all SettingsView tests pass including new Tdarr URL field test.

### Sub-task 5C: QueueView

- [ ] **Step 6: Write failing tests for Tdarr badges**

Add to `frontend/src/views/__tests__/QueueView.test.js` (inside `describe('QueueView', ...)`:

```js
it('shows NONE tdarr badge on DONE item', () => {
  const { wrapper } = factory([
    { id: 10, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
      tdarrStatus: 'NONE', tdarrError: null,
      queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
  ])
  expect(wrapper.text()).toContain('Queued in Tdarr')
})

it('shows PROCESSING tdarr badge on DONE item', () => {
  const { wrapper } = factory([
    { id: 11, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
      tdarrStatus: 'PROCESSING', tdarrError: null,
      queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
  ])
  expect(wrapper.text()).toContain('Transcoding…')
})

it('shows TRANSCODED tdarr badge on DONE item', () => {
  const { wrapper } = factory([
    { id: 12, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
      tdarrStatus: 'TRANSCODED', tdarrError: null,
      queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
  ])
  expect(wrapper.text()).toContain('Transcoded ✓')
})

it('shows TDARR_ERROR badge with error message', () => {
  const { wrapper } = factory([
    { id: 13, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
      tdarrStatus: 'TDARR_ERROR', tdarrError: 'codec not supported',
      queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
  ])
  expect(wrapper.text()).toContain('Tdarr error')
  expect(wrapper.text()).toContain('codec not supported')
})
```

- [ ] **Step 7: Run tests to verify they fail**

```bash
cd frontend && npm test -- --run --reporter=verbose 2>&1 | grep -E "tdarr badge|FAIL"
```

Expected: FAIL — badges not rendered yet.

- [ ] **Step 8: Update QueueView.vue**

Replace `frontend/src/views/QueueView.vue` with:

```vue
<template>
  <div>
    <h2>Download Queue</h2>

    <div v-if="allEmpty" class="empty">Queue is empty.</div>

    <section v-if="inProgress.length" class="section">
      <h3>In Progress</h3>
      <div v-for="item in inProgress" :key="item.id" class="queue-item active">
        <span class="spinner">⏳</span>
        <div class="item-info">
          <span class="type">{{ item.mediaType }} #{{ item.mediaId }}</span>
          <span class="sub">Copying…</span>
        </div>
      </div>
    </section>

    <section v-if="pending.length" class="section">
      <h3>Pending</h3>
      <div v-for="item in pending" :key="item.id" class="queue-item">
        <span class="pos">#{{ item.queuePosition }}</span>
        <div class="item-info">
          <span class="type">{{ item.mediaType }} #{{ item.mediaId }}</span>
          <span class="sub">Queued {{ formatDate(item.requestedAt) }}</span>
        </div>
      </div>
    </section>

    <section v-if="done.length" class="section">
      <h3>Completed</h3>
      <div v-for="item in done" :key="item.id" class="queue-item done">
        <span class="done-icon">✓</span>
        <div class="item-info">
          <span class="type">{{ item.mediaType }} #{{ item.mediaId }}</span>
          <span class="sub">{{ formatDate(item.completedAt) }}</span>
          <span v-if="item.status === 'ERROR'" class="error-msg">{{ item.errorMessage }}</span>
        </div>
        <template v-if="item.status === 'DONE'">
          <span v-if="item.tdarrStatus === 'NONE'"        class="tdarr-badge none">Queued in Tdarr</span>
          <span v-else-if="item.tdarrStatus === 'PROCESSING'"  class="tdarr-badge processing">Transcoding…</span>
          <span v-else-if="item.tdarrStatus === 'TRANSCODED'"  class="tdarr-badge transcoded">Transcoded ✓</span>
          <span v-else-if="item.tdarrStatus === 'TDARR_ERROR'" class="tdarr-badge tdarr-error">
            Tdarr error<span v-if="item.tdarrError"> — {{ item.tdarrError }}</span>
          </span>
        </template>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useDownloadStore } from '@/stores/download.js'

const dlStore = useDownloadStore()

const inProgress = computed(() => dlStore.queueItems.filter(i => i.status === 'IN_PROGRESS'))
const pending    = computed(() => dlStore.queueItems.filter(i => i.status === 'PENDING'))
const done       = computed(() => dlStore.queueItems.filter(i => i.status === 'DONE' || i.status === 'ERROR'))
const allEmpty   = computed(() => inProgress.value.length === 0 && pending.value.length === 0 && done.value.length === 0)

function formatDate(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString()
}

let pollTimer = null
onMounted(async () => {
  await dlStore.fetchQueue()
  pollTimer = setInterval(() => dlStore.fetchQueue(), 2000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 24px; }
.empty { color: var(--text-muted); padding: 40px 0; text-align: center; }
.section { margin-bottom: 32px; }
h3 { font-size: 1rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;
     letter-spacing: .05em; margin-bottom: 12px; padding-bottom: 8px;
     border-bottom: 1px solid var(--border); }
.queue-item { display: flex; align-items: center; gap: 16px; padding: 12px 16px;
              background: var(--surface2); border-radius: 8px; margin-bottom: 8px; }
.queue-item.active { border-left: 3px solid var(--accent-blue); }
.queue-item.done   { opacity: 0.65; }
.spinner, .done-icon { font-size: 1.2rem; }
.pos { font-size: 1rem; color: var(--text-muted); min-width: 28px; }
.item-info { display: flex; flex-direction: column; gap: 2px; flex: 1; }
.type { font-weight: 500; }
.sub  { font-size: .8rem; color: var(--text-muted); }
.error-msg { font-size: .8rem; color: var(--red); }
.tdarr-badge { font-size: .75rem; font-weight: 600; padding: 2px 8px; border-radius: 10px;
               white-space: nowrap; }
.tdarr-badge.none       { background: var(--surface); color: var(--text-muted);
                           border: 1px solid var(--border); }
.tdarr-badge.processing { background: rgba(52,152,219,.15); color: var(--accent-blue); }
.tdarr-badge.transcoded { background: rgba(39,174,96,.15); color: var(--green); }
.tdarr-badge.tdarr-error { background: rgba(231,76,60,.15); color: var(--red); }
</style>
```

- [ ] **Step 9: Run all frontend tests**

```bash
cd frontend && npm test -- --run
```

Expected: all 55 tests pass (51 existing + 4 new Tdarr badge tests).

- [ ] **Step 10: Commit**

```bash
git add frontend/src/api/admin.js \
        frontend/src/views/SettingsView.vue \
        frontend/src/views/__tests__/SettingsView.test.js \
        frontend/src/views/QueueView.vue \
        frontend/src/views/__tests__/QueueView.test.js
git commit -m "feat: Tdarr settings + queue view status badges"
```

---

## Final Verification

- [ ] **Run full backend test suite**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL

- [ ] **Run full frontend test suite**

```bash
cd frontend && npm test -- --run
```

Expected: all tests pass
