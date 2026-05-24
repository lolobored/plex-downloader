# Tdarr Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire Tdarr into the download pipeline — copy files to `/conversion/in-flight`, track transcoding status, store output path, enable cancel/cleanup at any stage, and replace the single Plex path prefix pair with separate movie/TV pairs plus an App↔Tdarr conversion path translator.

**Architecture:** Extend `PathMappingService` with multi-prefix translate and `appToTdarr`/`tdarrToApp` helpers. Add `output_file_path` to `download_queue`. Extend `TdarrClient` to surface `outputFilePaths` from Tdarr's API response. Update `TdarrSyncScheduler` to use Tdarr-namespace paths and persist output path on `TRANSCODED`. Add `cancel()` to `DownloadService` and `DELETE /api/download/{id}` to `DownloadController`. Update Settings (backend + frontend) with new path keys. Add remove button to `QueueView`.

**Tech Stack:** Spring Boot 3, Lombok, JPA/Hibernate, Liquibase YAML, Vue 3 + Vitest, SDKMAN java 21.0.4-tem

**Run backend tests with:**
```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --no-daemon 2>&1 | tail -15
```

**Run frontend tests with:**
```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | tail -30
```

---

## File Map

**Backend — create:**
- `backend/src/main/resources/db/changelog/yaml/007-tdarr-path-settings.yaml`

**Backend — modify:**
- `backend/src/main/java/org/lolobored/plexdownloader/service/PathMappingService.java`
- `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java`
- `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java`
- `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java`
- `backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java`
- `backend/src/main/java/org/lolobored/plexdownloader/controller/DownloadController.java`
- `backend/src/main/java/org/lolobored/plexdownloader/controller/AdminController.java`
- `backend/src/test/java/org/lolobored/plexdownloader/service/PathMappingServiceTest.java`
- `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java`
- `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java`
- `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java`
- `backend/src/test/java/org/lolobored/plexdownloader/controller/AdminControllerTest.java`

**Frontend — modify:**
- `frontend/src/api/download.js`
- `frontend/src/views/QueueView.vue`
- `frontend/src/views/__tests__/QueueView.test.js`
- `frontend/src/views/SettingsView.vue`

---

### Task 1: Liquibase migration — output_file_path column + path prefix key migration

**Files:**
- Create: `backend/src/main/resources/db/changelog/yaml/007-tdarr-path-settings.yaml`

- [ ] **Step 1: Write the migration file**

```yaml
databaseChangeLog:
  - changeSet:
      id: 007-output-file-path
      author: plexdownloader
      changes:
        - addColumn:
            tableName: download_queue
            columns:
              - column:
                  name: output_file_path
                  type: TEXT

  - changeSet:
      id: 007-migrate-path-prefixes
      author: plexdownloader
      changes:
        - sql:
            sql: >
              INSERT INTO settings (key, value)
              SELECT 'plex.path.prefix.movies.plex', value FROM settings WHERE key = 'plex.path.prefix.plex'
              ON CONFLICT (key) DO NOTHING
        - sql:
            sql: >
              INSERT INTO settings (key, value)
              SELECT 'plex.path.prefix.movies.app', value FROM settings WHERE key = 'plex.path.prefix.app'
              ON CONFLICT (key) DO NOTHING
        - sql:
            sql: >
              INSERT INTO settings (key, value)
              SELECT 'plex.path.prefix.tv.plex', value FROM settings WHERE key = 'plex.path.prefix.plex'
              ON CONFLICT (key) DO NOTHING
        - sql:
            sql: >
              INSERT INTO settings (key, value)
              SELECT 'plex.path.prefix.tv.app', value FROM settings WHERE key = 'plex.path.prefix.app'
              ON CONFLICT (key) DO NOTHING
        - delete:
            tableName: settings
            where: "key IN ('plex.path.prefix.plex', 'plex.path.prefix.app')"
```

- [ ] **Step 2: Verify the changelog master includes it**

Check `backend/src/main/resources/db/changelog/db.changelog-master.yaml`. It must include:
```yaml
- include:
    file: db/changelog/yaml/007-tdarr-path-settings.yaml
```
Add this line after the 006 include if missing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/
git commit -m "feat: migration 007 — output_file_path column + path prefix key migration"
```

---

### Task 2: PathMappingService — multi-prefix + Tdarr translation

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/PathMappingService.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/PathMappingServiceTest.java`

- [ ] **Step 1: Write failing tests**

Replace entire `PathMappingServiceTest.java`:

```java
package org.lolobored.plexdownloader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PathMappingServiceTest {

    @Mock SettingsService settings;
    @InjectMocks PathMappingService service;

    private void stubPrefixes(String moviesPlex, String moviesApp, String tvPlex, String tvApp) {
        when(settings.getRequired("plex.path.prefix.movies.plex")).thenReturn(moviesPlex);
        when(settings.getRequired("plex.path.prefix.movies.app")).thenReturn(moviesApp);
        when(settings.getRequired("plex.path.prefix.tv.plex")).thenReturn(tvPlex);
        when(settings.getRequired("plex.path.prefix.tv.app")).thenReturn(tvApp);
    }

    // ── translate ────────────────────────────────────────────────────────────

    @Test
    void translate_moviesPath() {
        stubPrefixes("/movies", "/movies", "/tv", "/tvshows");
        assertThat(service.translate("/movies/Inception/Inception.mkv"))
            .isEqualTo("/movies/Inception/Inception.mkv");
    }

    @Test
    void translate_tvPath() {
        stubPrefixes("/movies", "/movies", "/tv", "/tvshows");
        assertThat(service.translate("/tv/Breaking Bad/S01E01.mkv"))
            .isEqualTo("/tvshows/Breaking Bad/S01E01.mkv");
    }

    @Test
    void translate_trailingSlashStripped() {
        stubPrefixes("/movies/", "/movies", "/tv/", "/tvshows");
        assertThat(service.translate("/movies/film.mkv")).isEqualTo("/movies/film.mkv");
    }

    @Test
    void translate_throwsWhenNoPrefixMatches() {
        stubPrefixes("/movies", "/movies", "/tv", "/tvshows");
        assertThatThrownBy(() -> service.translate("/unknown/file.mkv"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match");
    }

    // ── appToTdarr ──────────────────────────────────────────────────────────

    @Test
    void appToTdarr_translatesConversionPath() {
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion");
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download");

        assertThat(service.appToTdarr("/conversion/in-flight/movies/film.mkv"))
            .isEqualTo("/media/plex-download/in-flight/movies/film.mkv");
    }

    @Test
    void appToTdarr_stripsTrailingSlash() {
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion/");
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download/");

        assertThat(service.appToTdarr("/conversion/in-flight/film.mkv"))
            .isEqualTo("/media/plex-download/in-flight/film.mkv");
    }

    @Test
    void appToTdarr_throwsWhenPathNotUnderConversionDir() {
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion");
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download");

        assertThatThrownBy(() -> service.appToTdarr("/movies/film.mkv"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conversion dir");
    }

    // ── tdarrToApp ──────────────────────────────────────────────────────────

    @Test
    void tdarrToApp_translatesOutputPath() {
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download");
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion");

        assertThat(service.tdarrToApp("/media/plex-download/libraries/movies/film.mp4"))
            .isEqualTo("/conversion/libraries/movies/film.mp4");
    }

    @Test
    void tdarrToApp_throwsWhenPathNotUnderTdarrPrefix() {
        when(settings.getRequired("tdarr.path.prefix.conversion")).thenReturn("/media/plex-download");
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion");

        assertThatThrownBy(() -> service.tdarrToApp("/other/path/film.mp4"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tdarr prefix");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "*PathMappingServiceTest*" --no-daemon 2>&1 | tail -10
```

Expected: FAIL — methods not found

- [ ] **Step 3: Implement PathMappingService**

Replace entire `PathMappingService.java`:

```java
package org.lolobored.plexdownloader.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PathMappingService {

    private final SettingsService settings;

    /** Translate a Plex-namespace path to an app-namespace path.
     *  Tries movies prefix first, then TV prefix. */
    public String translate(String plexPath) {
        String moviesPlex = strip(settings.getRequired("plex.path.prefix.movies.plex"));
        String moviesApp  = strip(settings.getRequired("plex.path.prefix.movies.app"));
        if (plexPath.startsWith(moviesPlex)) {
            return moviesApp + plexPath.substring(moviesPlex.length());
        }
        String tvPlex = strip(settings.getRequired("plex.path.prefix.tv.plex"));
        String tvApp  = strip(settings.getRequired("plex.path.prefix.tv.app"));
        if (plexPath.startsWith(tvPlex)) {
            return tvApp + plexPath.substring(tvPlex.length());
        }
        throw new IllegalArgumentException(
            "Path '" + plexPath + "' does not match any configured prefix");
    }

    /** Translate an app-namespace path (under conversion dir) to Tdarr's namespace. */
    public String appToTdarr(String appPath) {
        String convDir   = strip(settings.getRequired("plex.conversion.dir"));
        String tdarrRoot = strip(settings.getRequired("tdarr.path.prefix.conversion"));
        if (!appPath.startsWith(convDir)) {
            throw new IllegalArgumentException(
                "Path '" + appPath + "' is not under conversion dir '" + convDir + "'");
        }
        return tdarrRoot + appPath.substring(convDir.length());
    }

    /** Translate a Tdarr-namespace path back to an app-namespace path. */
    public String tdarrToApp(String tdarrPath) {
        String tdarrRoot = strip(settings.getRequired("tdarr.path.prefix.conversion"));
        String convDir   = strip(settings.getRequired("plex.conversion.dir"));
        if (!tdarrPath.startsWith(tdarrRoot)) {
            throw new IllegalArgumentException(
                "Path '" + tdarrPath + "' does not start with Tdarr prefix '" + tdarrRoot + "'");
        }
        return convDir + tdarrPath.substring(tdarrRoot.length());
    }

    private static String strip(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "*PathMappingServiceTest*" --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/PathMappingService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/PathMappingServiceTest.java
git commit -m "feat: PathMappingService — multi-prefix translate + appToTdarr/tdarrToApp"
```

---

### Task 3: DownloadQueueItem — add outputFilePath field

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java`

- [ ] **Step 1: Add the field**

In `DownloadQueueItem.java`, after the `tdarrError` field, add:

```java
    @Column(name = "output_file_path", columnDefinition = "TEXT")
    private String outputFilePath;
```

- [ ] **Step 2: Verify compile**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew compileJava --no-daemon -q 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java
git commit -m "feat: add outputFilePath to DownloadQueueItem"
```

---

### Task 4: TdarrClient — expose outputFilePaths from API response

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java`

- [ ] **Step 1: Write failing test**

In `TdarrClientTest.java`, add after the last existing test:

```java
    @Test
    void getFileStatus_includesOutputFilePath_whenTranscoded() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Done transcoding");
        resp.setOutputFilePaths(List.of("/media/plex-download/libraries/movies/film.mp4"));
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        Optional<TdarrClient.TdarrFileStatus> result = client.getFileStatus("/file.mkv");

        assertThat(result.get().status()).isEqualTo(DownloadQueueItem.TdarrStatus.TRANSCODED);
        assertThat(result.get().outputFilePath()).isEqualTo("/media/plex-download/libraries/movies/film.mp4");
    }

    @Test
    void getFileStatus_outputFilePath_isNull_whenNotTranscoded() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        TdarrClient.TdarrFileResponse resp = new TdarrClient.TdarrFileResponse();
        resp.setTdarrStatus("Queued");
        doReturn(resp).when(client).fetchStatus(anyString(), anyString());

        assertThat(client.getFileStatus("/file.mkv").get().outputFilePath()).isNull();
    }
```

Also add `import java.util.List;` to the test imports if not present.

- [ ] **Step 2: Run to verify fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "*TdarrClientTest*" --no-daemon 2>&1 | tail -10
```

Expected: FAIL — `outputFilePath()` does not exist

- [ ] **Step 3: Update TdarrClient**

Replace the `TdarrFileStatus` record, `TdarrFileResponse` inner class, and `getFileStatus` method:

```java
    // Record — add outputFilePath (3rd component)
    public record TdarrFileStatus(
        DownloadQueueItem.TdarrStatus status,
        String errorMessage,
        String outputFilePath) {}
```

In `getFileStatus`, replace the return statements:

```java
    public Optional<TdarrFileStatus> getFileStatus(String absoluteFilePath) {
        String baseUrl = settings.get("tdarr.server.url").orElse("").trim();
        if (baseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            TdarrFileResponse response = fetchStatus(baseUrl, absoluteFilePath);
            if (response == null) {
                return Optional.of(new TdarrFileStatus(DownloadQueueItem.TdarrStatus.NONE, null, null));
            }
            DownloadQueueItem.TdarrStatus status = mapStatus(response.getTdarrStatus());
            String error = status == DownloadQueueItem.TdarrStatus.TDARR_ERROR
                ? response.getErrorMessage() : null;
            String outputPath = (response.getOutputFilePaths() != null && !response.getOutputFilePaths().isEmpty())
                ? response.getOutputFilePaths().get(0) : null;
            return Optional.of(new TdarrFileStatus(status, error, outputPath));
        } catch (RestClientException e) {
            log.warn("Tdarr API error for {}: {}", absoluteFilePath, e.getMessage());
            return Optional.empty();
        }
    }
```

Add `outputFilePaths` to `TdarrFileResponse`:

```java
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TdarrFileResponse {
        @JsonProperty("tdarrStatus")
        private String tdarrStatus;
        @JsonProperty("errorMessage")
        private String errorMessage;
        @JsonProperty("outputFilePaths")
        private java.util.List<String> outputFilePaths;
    }
```

- [ ] **Step 4: Fix broken usages of old 2-arg TdarrFileStatus constructor**

`TdarrSyncSchedulerTest.java` creates `TdarrFileStatus` with 2 args. Add `null` as the 3rd arg to each occurrence:

```java
// Find all: new TdarrClient.TdarrFileStatus(X, Y)
// Replace: new TdarrClient.TdarrFileStatus(X, Y, null)
```

There are four occurrences in `TdarrSyncSchedulerTest`:
1. `new TdarrClient.TdarrFileStatus(DownloadQueueItem.TdarrStatus.PROCESSING, null)` → add `, null`
2. `new TdarrClient.TdarrFileStatus(DownloadQueueItem.TdarrStatus.TRANSCODED, null)` → add `, null`
3. `new TdarrClient.TdarrFileStatus(DownloadQueueItem.TdarrStatus.TDARR_ERROR, "codec not supported")` → add `, null`

- [ ] **Step 5: Run all tests to verify they pass**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java \
        backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java
git commit -m "feat: TdarrClient exposes outputFilePaths from API response"
```

---

### Task 5: TdarrSyncScheduler — use appToTdarr + store outputFilePath on TRANSCODED

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java`

- [ ] **Step 1: Write failing tests**

Add to `TdarrSyncSchedulerTest.java` (add `@Mock PathMappingService pathMapping` to the fields):

```java
    @Mock PathMappingService pathMapping;
```

Add tests:

```java
    @Test
    void syncAll_usesAppToTdarrPathForDocId() {
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/film/film.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr("/conversion/in-flight/movies/film/film.mkv"))
            .thenReturn("/media/plex-download/in-flight/movies/film/film.mkv");
        when(tdarrClient.getFileStatus("/media/plex-download/in-flight/movies/film/film.mkv"))
            .thenReturn(Optional.empty());

        scheduler.syncAll();

        verify(tdarrClient).getFileStatus("/media/plex-download/in-flight/movies/film/film.mkv");
    }

    @Test
    void syncAll_storesTranslatedOutputPathWhenTranscoded() {
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/film/film.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr(any())).thenReturn("/media/plex-download/in-flight/movies/film/film.mkv");
        when(tdarrClient.getFileStatus(any()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.TRANSCODED, null,
                "/media/plex-download/libraries/movies/film/film.mp4")));
        when(pathMapping.tdarrToApp("/media/plex-download/libraries/movies/film/film.mp4"))
            .thenReturn("/conversion/libraries/movies/film/film.mp4");
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED &&
            "/conversion/libraries/movies/film/film.mp4".equals(i.getOutputFilePath())
        ));
    }

    @Test
    void syncAll_doesNotSetOutputPath_whenTranscodedOutputIsNull() {
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/film/film.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr(any())).thenReturn("/media/plex-download/in-flight/movies/film/film.mkv");
        when(tdarrClient.getFileStatus(any()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.TRANSCODED, null, null)));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED &&
            i.getOutputFilePath() == null
        ));
    }
```

Also update the two existing tests that pass a raw app path to `tdarrClient.getFileStatus`:
- `syncAll_updatesStatusToProcessing` — currently stubs `tdarrClient.getFileStatus("/conv/movies/test/movie.mkv")`. Change to use `pathMapping.appToTdarr(...)` stub:

```java
    @Test
    void syncAll_updatesStatusToProcessing() {
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr("/conversion/in-flight/movies/test/movie.mkv"))
            .thenReturn("/media/plex-download/in-flight/movies/test/movie.mkv");
        when(tdarrClient.getFileStatus("/media/plex-download/in-flight/movies/test/movie.mkv"))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.PROCESSING, null, null)));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.PROCESSING &&
            i.getTdarrError() == null
        ));
    }
```

Similarly update `syncAll_updatesStatusToTranscoded`, `syncAll_updatesStatusToError_withMessage`, and `syncAll_skipsItem_whenTdarrReturnsEmpty` to stub `pathMapping.appToTdarr(any())` and pass the Tdarr path to `tdarrClient.getFileStatus`.

Replace the full `TdarrSyncSchedulerTest.java`:

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
    @Mock PathMappingService pathMapping;
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
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr(anyString())).thenReturn("/media/in-flight/movies/test/movie.mkv");
        when(tdarrClient.getFileStatus(anyString())).thenReturn(Optional.empty());

        scheduler.syncAll();

        verify(queueRepo, never()).save(any());
    }

    @Test
    void syncAll_updatesStatusToProcessing() {
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr(anyString())).thenReturn("/media/in-flight/movies/test/movie.mkv");
        when(tdarrClient.getFileStatus(anyString()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.PROCESSING, null, null)));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.PROCESSING &&
            i.getTdarrError() == null
        ));
    }

    @Test
    void syncAll_storesTranslatedOutputPathWhenTranscoded() {
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/film/film.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr(anyString())).thenReturn("/media/in-flight/movies/film/film.mkv");
        when(tdarrClient.getFileStatus(anyString()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.TRANSCODED, null,
                "/media/plex-download/libraries/movies/film/film.mp4")));
        when(pathMapping.tdarrToApp("/media/plex-download/libraries/movies/film/film.mp4"))
            .thenReturn("/conversion/libraries/movies/film/film.mp4");
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED &&
            "/conversion/libraries/movies/film/film.mp4".equals(i.getOutputFilePath())
        ));
    }

    @Test
    void syncAll_doesNotSetOutputPath_whenOutputIsNull() {
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/film/film.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr(anyString())).thenReturn("/media/in-flight/movies/film/film.mkv");
        when(tdarrClient.getFileStatus(anyString()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.TRANSCODED, null, null)));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.syncAll();

        verify(queueRepo).save(argThat(i ->
            i.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED &&
            i.getOutputFilePath() == null
        ));
    }

    @Test
    void syncAll_updatesStatusToError_withMessage() {
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/test/movie.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr(anyString())).thenReturn("/media/in-flight/movies/test/movie.mkv");
        when(tdarrClient.getFileStatus(anyString()))
            .thenReturn(Optional.of(new TdarrClient.TdarrFileStatus(
                DownloadQueueItem.TdarrStatus.TDARR_ERROR, "codec not supported", null)));
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
    void syncAll_usesAppToTdarrPathForDocId() {
        DownloadQueueItem item = doneItem("/conversion/in-flight/movies/film/film.mkv");
        when(queueRepo.findByStatusAndTdarrStatusNotIn(any(), any())).thenReturn(List.of(item));
        when(pathMapping.appToTdarr("/conversion/in-flight/movies/film/film.mkv"))
            .thenReturn("/media/plex-download/in-flight/movies/film/film.mkv");
        when(tdarrClient.getFileStatus("/media/plex-download/in-flight/movies/film/film.mkv"))
            .thenReturn(Optional.empty());

        scheduler.syncAll();

        verify(tdarrClient).getFileStatus("/media/plex-download/in-flight/movies/film/film.mkv");
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

- [ ] **Step 2: Run to verify fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "*TdarrSyncSchedulerTest*" --no-daemon 2>&1 | tail -10
```

Expected: FAIL

- [ ] **Step 3: Update TdarrSyncScheduler**

Replace entire `TdarrSyncScheduler.java`:

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
    private final PathMappingService pathMapping;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            this::syncAll,
            ctx -> {
                String cron = settings.get("tdarr.sync.cron").filter(s -> !s.isBlank()).orElse(DEFAULT_CRON);
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
            try {
                String tdarrPath = pathMapping.appToTdarr(item.getDestFilePath());
                Optional<TdarrClient.TdarrFileStatus> statusOpt = tdarrClient.getFileStatus(tdarrPath);
                if (statusOpt.isEmpty()) {
                    log.warn("Tdarr unreachable, skipping item {}", item.getId());
                    continue;
                }
                TdarrClient.TdarrFileStatus ts = statusOpt.get();
                item.setTdarrStatus(ts.status());
                item.setTdarrError(ts.errorMessage());
                if (ts.status() == DownloadQueueItem.TdarrStatus.TRANSCODED
                        && ts.outputFilePath() != null) {
                    item.setOutputFilePath(pathMapping.tdarrToApp(ts.outputFilePath()));
                }
                queueRepo.save(item);
                log.info("Tdarr status updated: item={} status={}", item.getId(), ts.status());
            } catch (Exception e) {
                log.error("Tdarr sync failed for item {}: {}", item.getId(), e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "*TdarrSyncSchedulerTest*" --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run full suite**

```bash
./gradlew test --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java
git commit -m "feat: TdarrSyncScheduler uses Tdarr-namespace paths, stores outputFilePath"
```

---

### Task 6: DownloadService — in-flight path + cancel()

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add to `DownloadServiceTest.java` — add `@Mock TdarrClient tdarrClient;` to the field list, then add these tests:

```java
    @Mock TdarrClient tdarrClient;
```

```java
    @Test
    void enqueueMovie_destPathContainsInFlight() {
        Movie movie = new Movie();
        movie.setId(1L);
        movie.setTitle("Inception");
        movie.setFilePath("/movies/inception.mkv");

        User user = new User();
        user.setId(1L);

        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conversion");
        when(pathMapping.translate("/movies/inception.mkv")).thenReturn("/movies/inception.mkv");
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> { DownloadQueueItem i = inv.getArgument(0); i.setId(1L); return i; });

        service.enqueueMovie(1L, user);

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath().replace('\\', '/').contains("/in-flight/")
        ));
    }

    @Test
    void cancel_deletesPendingItemAndInFlightFile(@TempDir Path tmp) throws Exception {
        Path inFlightFile = tmp.resolve("film.mkv");
        Files.writeString(inFlightFile, "data");

        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(10L);
        item.setStatus(DownloadQueueItem.Status.PENDING);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        item.setDestFilePath(inFlightFile.toString());
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(10L)).thenReturn(Optional.of(item));
        when(pathMapping.appToTdarr(anyString())).thenReturn("/tdarr/film.mkv");

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        service.cancel(10L, caller);

        assertThat(inFlightFile).doesNotExist();
        verify(queueRepo).delete(item);
    }

    @Test
    void cancel_evictsTdarrAndDeletesInFlightWhenProcessing() throws Exception {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(11L);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.PROCESSING);
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(11L)).thenReturn(Optional.of(item));
        when(pathMapping.appToTdarr("/conversion/in-flight/films/film.mkv"))
            .thenReturn("/media/in-flight/films/film.mkv");

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        service.cancel(11L, caller);

        verify(tdarrClient).deleteFile("/media/in-flight/films/film.mkv");
        verify(queueRepo).delete(item);
    }

    @Test
    void cancel_deletesOutputFileWhenTranscoded(@TempDir Path tmp) throws Exception {
        Path outputFile = tmp.resolve("film.mp4");
        Files.writeString(outputFile, "transcoded");

        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(12L);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TRANSCODED);
        item.setOutputFilePath(outputFile.toString());
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(12L)).thenReturn(Optional.of(item));

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        service.cancel(12L, caller);

        assertThat(outputFile).doesNotExist();
        verify(tdarrClient, never()).deleteFile(anyString());
        verify(queueRepo).delete(item);
    }

    @Test
    void cancel_throws409WhenInProgress() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(13L);
        item.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(13L)).thenReturn(Optional.of(item));

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        assertThatThrownBy(() -> service.cancel(13L, caller))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("409");
    }

    @Test
    void cancel_throws404WhenNotFound() {
        when(queueRepo.findById(99L)).thenReturn(Optional.empty());

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        assertThatThrownBy(() -> service.cancel(99L, caller))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    @Test
    void cancel_throws403WhenNotOwnerAndNotAdmin() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(14L);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(14L)).thenReturn(Optional.of(item));

        User otherUser = new User(); otherUser.setId(2L); otherUser.setRole(User.Role.USER);
        assertThatThrownBy(() -> service.cancel(14L, otherUser))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    @Test
    void cancel_adminCanCancelAnyItem() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(15L);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(15L)).thenReturn(Optional.of(item));
        when(pathMapping.appToTdarr(anyString())).thenReturn("/media/in-flight/films/film.mkv");

        User admin = new User(); admin.setId(2L); admin.setRole(User.Role.ADMIN);
        service.cancel(15L, admin);

        verify(queueRepo).delete(item);
    }
```

Also update existing path assertions in `enqueueMovie_buildsStructuredPath` and `enqueueEpisode_buildsStructuredPath` to expect `in-flight/` in the path:

```java
    // enqueueMovie_buildsStructuredPath — update assert:
    verify(queueRepo).save(argThat(item ->
        item.getDestFilePath() != null &&
        item.getDestFilePath().replace('\\', '/').contains("/in-flight/movies/the_dark_knight/dark.mkv")
    ));

    // enqueueEpisode_buildsStructuredPath — update assert:
    verify(queueRepo).save(argThat(item ->
        item.getDestFilePath() != null &&
        item.getDestFilePath().replace('\\', '/').contains("/in-flight/tvshows/breaking_bad/Season 01/s01e01.mkv")
    ));
```

- [ ] **Step 2: Run to verify fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "*DownloadServiceTest*" --no-daemon 2>&1 | tail -10
```

Expected: FAIL

- [ ] **Step 3: Update DownloadService**

Add `TdarrClient` import and field. Add `import org.springframework.transaction.annotation.Transactional;` and `import org.springframework.web.server.ResponseStatusException;` and `import org.springframework.http.HttpStatus;`.

Updated field list (add `tdarrClient`):

```java
    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final SeasonRepository seasonRepo;
    private final TvShowRepository showRepo;
    private final DownloadQueueRepository queueRepo;
    private final PathMappingService pathMapping;
    private final SettingsService settings;
    private final TdarrClient tdarrClient;
```

In `buildItem`, change the `destPath` line:

```java
        // was: String destPath = Path.of(conversionDir, subDir, filename).toString();
        String destPath = Path.of(conversionDir, "in-flight", subDir, filename).toString();
```

Add the `cancel` method at the end of the class (before the closing `}`):

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

        // Delete in-flight file (always attempt)
        try {
            Files.deleteIfExists(Path.of(item.getDestFilePath()));
        } catch (IOException e) {
            log.warn("Could not delete in-flight file {}: {}", item.getDestFilePath(), e.getMessage());
        }

        if (item.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED) {
            // Delete transcoded output from /libraries
            if (item.getOutputFilePath() != null) {
                try {
                    Files.deleteIfExists(Path.of(item.getOutputFilePath()));
                } catch (IOException e) {
                    log.warn("Could not delete output file {}: {}", item.getOutputFilePath(), e.getMessage());
                }
            }
        } else {
            // Evict from Tdarr DB
            try {
                tdarrClient.deleteFile(pathMapping.appToTdarr(item.getDestFilePath()));
            } catch (Exception e) {
                log.warn("Tdarr eviction failed for item {}: {}", itemId, e.getMessage());
            }
        }

        queueRepo.delete(item);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "*DownloadServiceTest*" --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run full suite**

```bash
./gradlew test --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java
git commit -m "feat: DownloadService adds in-flight path prefix and cancel() method"
```

---

### Task 7: DownloadController — DELETE /api/download/{id}

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/controller/DownloadController.java`

- [ ] **Step 1: Add the endpoint**

Add to `DownloadController.java` after `getQueue()`:

```java
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id,
                       @AuthenticationPrincipal User user) {
        downloadService.cancel(id, user);
    }
```

Add `import org.springframework.web.bind.annotation.ResponseStatus;` if not present.

- [ ] **Step 2: Verify compile and full suite**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/controller/DownloadController.java
git commit -m "feat: DELETE /api/download/{id} cancel endpoint"
```

---

### Task 8: AdminController — replace old path keys with new ones

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/controller/AdminController.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/controller/AdminControllerTest.java`

- [ ] **Step 1: Update getSettings() in AdminController**

Replace the path prefix lines and add new keys. The full updated `getSettings()` body:

```java
    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("plex.server.url",                    settingsService.get("plex.server.url").orElse(""));
        result.put("plex.path.prefix.movies.plex",       settingsService.get("plex.path.prefix.movies.plex").orElse(""));
        result.put("plex.path.prefix.movies.app",        settingsService.get("plex.path.prefix.movies.app").orElse(""));
        result.put("plex.path.prefix.tv.plex",           settingsService.get("plex.path.prefix.tv.plex").orElse(""));
        result.put("plex.path.prefix.tv.app",            settingsService.get("plex.path.prefix.tv.app").orElse(""));
        result.put("plex.conversion.dir",                settingsService.get("plex.conversion.dir").orElse(""));
        result.put("plex.sync.cron",                     settingsService.get("plex.sync.cron").orElse("0 0 */6 * * *"));
        result.put("plex.sync.libraries",                settingsService.get("plex.sync.libraries").orElse(""));
        result.put("tdarr.server.url",                   settingsService.get("tdarr.server.url").orElse(""));
        result.put("tdarr.path.prefix.conversion",       settingsService.get("tdarr.path.prefix.conversion").orElse(""));
        result.put("tdarr.sync.cron",                    settingsService.get("tdarr.sync.cron").orElse("0 */30 * * * *"));
        return result;
    }
```

- [ ] **Step 2: Update AdminControllerTest**

Find `getSettingsReturnsAllKeysWithoutToken` and replace its when/expect block to use new keys:

```java
    @Test
    void getSettingsReturnsAllKeysWithoutToken() throws Exception {
        when(settingsService.get(anyString())).thenReturn(Optional.empty());
        when(settingsService.get("plex.server.url")).thenReturn(Optional.of("http://plex:32400"));
        when(settingsService.get("plex.path.prefix.movies.plex")).thenReturn(Optional.of("/movies"));
        when(settingsService.get("plex.path.prefix.movies.app")).thenReturn(Optional.of("/movies"));
        when(settingsService.get("plex.path.prefix.tv.plex")).thenReturn(Optional.of("/tv"));
        when(settingsService.get("plex.path.prefix.tv.app")).thenReturn(Optional.of("/tvshows"));
        when(settingsService.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        when(settingsService.get("tdarr.path.prefix.conversion")).thenReturn(Optional.of("/media/plex-download"));
        when(settingsService.get("tdarr.sync.cron")).thenReturn(Optional.of("0 */30 * * * *"));

        mockMvc.perform(get("/api/admin/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['plex.server.url']").value("http://plex:32400"))
            .andExpect(jsonPath("$['plex.path.prefix.movies.plex']").value("/movies"))
            .andExpect(jsonPath("$['plex.path.prefix.tv.app']").value("/tvshows"))
            .andExpect(jsonPath("$['tdarr.path.prefix.conversion']").value("/media/plex-download"))
            .andExpect(jsonPath("$['plex.path.prefix.plex']").doesNotExist())
            .andExpect(jsonPath("$['plex.path.prefix.app']").doesNotExist())
            .andExpect(jsonPath("$['plex.server.token']").doesNotExist());
    }
```

- [ ] **Step 3: Run tests**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/controller/AdminController.java \
        backend/src/test/java/org/lolobored/plexdownloader/controller/AdminControllerTest.java
git commit -m "feat: AdminController exposes new path prefix keys + tdarr.path.prefix.conversion"
```

---

### Task 9: Frontend — api/download.js add removeQueueItem

**Files:**
- Modify: `frontend/src/api/download.js`

- [ ] **Step 1: Add the function**

Append to `frontend/src/api/download.js`:

```js
export async function removeQueueItem(id) {
  await http.delete(`/api/download/${id}`)
}
```

- [ ] **Step 2: Verify no test breakage**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | tail -10
```

Expected: all tests pass

- [ ] **Step 3: Commit**

```bash
git add frontend/src/api/download.js
git commit -m "feat: add removeQueueItem to download API"
```

---

### Task 10: Frontend — QueueView remove button

**Files:**
- Modify: `frontend/src/views/QueueView.vue`
- Modify: `frontend/src/views/__tests__/QueueView.test.js`

- [ ] **Step 1: Write failing tests**

Add to `QueueView.test.js` — first add mock for the api module:

```js
import * as downloadApi from '../../api/download.js'
vi.mock('../../api/download.js', () => ({
  removeQueueItem: vi.fn().mockResolvedValue(undefined)
}))
```

Add these tests:

```js
  it('shows remove button on each item', () => {
    const { wrapper } = factory([
      { id: 1, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
        tdarrStatus: 'NONE', tdarrError: null,
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    expect(wrapper.find('[data-testid="remove-btn-1"]').exists()).toBe(true)
  })

  it('remove button is disabled when IN_PROGRESS', () => {
    const { wrapper } = factory([
      { id: 2, mediaType: 'MOVIE', mediaId: 5, status: 'IN_PROGRESS',
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: null }
    ])
    const btn = wrapper.find('[data-testid="remove-btn-2"]')
    expect(btn.element.disabled).toBe(true)
  })

  it('calls removeQueueItem and refreshes queue on click', async () => {
    const { wrapper, store } = factory([
      { id: 3, mediaType: 'MOVIE', mediaId: 5, status: 'DONE',
        tdarrStatus: 'TRANSCODED', tdarrError: null,
        queuePosition: 1, requestedAt: '2026-05-24T10:00:00Z', completedAt: '2026-05-24T10:05:00Z' }
    ])
    await wrapper.find('[data-testid="remove-btn-3"]').trigger('click')
    await flushPromises()
    expect(downloadApi.removeQueueItem).toHaveBeenCalledWith(3)
    expect(store.fetchQueue).toHaveBeenCalled()
  })
```

- [ ] **Step 2: Run to verify fails**

```bash
cd frontend && npm run test -- --reporter=verbose --run 2>&1 | grep "QueueView\|FAIL\|PASS" | head -20
```

Expected: new tests FAIL

- [ ] **Step 3: Update QueueView.vue**

Add `import { removeQueueItem } from '@/api/download.js'` to the script imports.

Add reactive state and handler in `<script setup>`:

```js
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useDownloadStore } from '@/stores/download.js'
import { removeQueueItem } from '@/api/download.js'

const dlStore = useDownloadStore()
const removing = ref(new Set())

async function remove(id) {
  removing.value = new Set([...removing.value, id])
  try {
    await removeQueueItem(id)
    await dlStore.fetchQueue()
  } finally {
    const next = new Set(removing.value)
    next.delete(id)
    removing.value = next
  }
}
```

In the template, add a remove button to each queue row. For every `<div ... class="queue-item ...">`, add at the end:

```html
<button
  :data-testid="'remove-btn-' + item.id"
  class="btn-remove"
  :disabled="item.status === 'IN_PROGRESS' || removing.has(item.id)"
  :title="item.status === 'IN_PROGRESS' ? 'Wait for copy to finish' : 'Remove'"
  @click="remove(item.id)">
  {{ removing.has(item.id) ? '…' : '✕' }}
</button>
```

Add style:

```css
.btn-remove { background: none; border: none; color: var(--text-muted); cursor: pointer;
              font-size: 1rem; padding: 4px 8px; border-radius: 4px; }
.btn-remove:hover:not(:disabled) { color: var(--red); background: rgba(231,76,60,.1); }
.btn-remove:disabled { opacity: 0.3; cursor: not-allowed; }
```

- [ ] **Step 4: Run tests**

```bash
cd frontend && npm run test -- --reporter=verbose --run 2>&1 | tail -15
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/QueueView.vue \
        frontend/src/views/__tests__/QueueView.test.js
git commit -m "feat: QueueView — remove button per item calling DELETE /api/download/{id}"
```

---

### Task 11: Frontend — SettingsView path prefix rework

**Files:**
- Modify: `frontend/src/views/SettingsView.vue`

- [ ] **Step 1: Update SettingsView form state and labels**

In `<script setup>`, replace `plexPathPrefixPlex` and `plexPathPrefixApp` with four new fields, and add `tdarrConversionPrefix`:

Replace in `const form = reactive({...})`:
```js
  // Remove:
  plexPathPrefixPlex: '',
  plexPathPrefixApp:  '',
  // Add:
  plexPathPrefixMoviesPlex: '',
  plexPathPrefixMoviesApp:  '',
  plexPathPrefixTvPlex:     '',
  plexPathPrefixTvApp:      '',
  tdarrConversionPrefix:    '',
```

Replace in `onMounted` load block:
```js
  // Remove:
  form.plexPathPrefixPlex = s['plex.path.prefix.plex']  ?? ''
  form.plexPathPrefixApp  = s['plex.path.prefix.app']   ?? ''
  // Add:
  form.plexPathPrefixMoviesPlex = s['plex.path.prefix.movies.plex'] ?? ''
  form.plexPathPrefixMoviesApp  = s['plex.path.prefix.movies.app']  ?? ''
  form.plexPathPrefixTvPlex     = s['plex.path.prefix.tv.plex']     ?? ''
  form.plexPathPrefixTvApp      = s['plex.path.prefix.tv.app']      ?? ''
  form.tdarrConversionPrefix    = s['tdarr.path.prefix.conversion']  ?? ''
```

Replace in `save()` payload:
```js
  // Remove:
  'plex.path.prefix.plex':  form.plexPathPrefixPlex,
  'plex.path.prefix.app':   form.plexPathPrefixApp,
  // Add:
  'plex.path.prefix.movies.plex': form.plexPathPrefixMoviesPlex,
  'plex.path.prefix.movies.app':  form.plexPathPrefixMoviesApp,
  'plex.path.prefix.tv.plex':     form.plexPathPrefixTvPlex,
  'plex.path.prefix.tv.app':      form.plexPathPrefixTvApp,
  'tdarr.path.prefix.conversion': form.tdarrConversionPrefix,
```

- [ ] **Step 2: Update template path mappings section**

Replace the existing two-field block (with labels "Plex path prefix" and "App path prefix") with:

```html
      <div class="field">
        <label>Movies — Plex path prefix</label>
        <input v-model="form.plexPathPrefixMoviesPlex" type="text" placeholder="/movies" />
      </div>
      <div class="field">
        <label>Movies — App path prefix</label>
        <input v-model="form.plexPathPrefixMoviesApp" type="text" placeholder="/movies" />
      </div>
      <div class="field">
        <label>TV — Plex path prefix</label>
        <input v-model="form.plexPathPrefixTvPlex" type="text" placeholder="/tv" />
      </div>
      <div class="field">
        <label>TV — App path prefix</label>
        <input v-model="form.plexPathPrefixTvApp" type="text" placeholder="/tvshows" />
      </div>
      <div class="field">
        <label>Tdarr conversion prefix</label>
        <input v-model="form.tdarrConversionPrefix" type="text" placeholder="/media/plex-download" />
      </div>
```

- [ ] **Step 3: Run full frontend test suite**

```bash
cd frontend && npm run test -- --reporter=verbose --run 2>&1 | tail -20
```

Expected: all PASS (SettingsView tests may need minor mock updates — see note below)

> **Note:** If `SettingsView.test.js` has a test that stubs `getSettings` with the old `plex.path.prefix.plex` key or checks `putSettings` for the old key, update those stubs to use the new four-key format. Specifically, in `mockSettings()`:
> ```js
> function mockSettings(overrides = {}) {
>   return {
>     'plex.server.url': 'http://plex:32400',
>     'plex.path.prefix.movies.plex': '/movies',
>     'plex.path.prefix.movies.app':  '/movies',
>     'plex.path.prefix.tv.plex':     '/tv',
>     'plex.path.prefix.tv.app':      '/tvshows',
>     'plex.conversion.dir': '/conversion',
>     'plex.sync.cron': '0 0 */6 * * *',
>     'plex.sync.libraries': '1',
>     'tdarr.server.url': '', 'tdarr.sync.cron': '',
>     'tdarr.path.prefix.conversion': '/media/plex-download',
>     ...overrides
>   }
> }
> ```
> And in save tests, remove assertions about `plex.path.prefix.plex` and add `plex.path.prefix.movies.plex`.

- [ ] **Step 4: Run backend full test suite one final time**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/SettingsView.vue \
        frontend/src/views/__tests__/SettingsView.test.js
git commit -m "feat: SettingsView — replace single path prefix pair with movies/TV pairs + Tdarr conversion prefix"
```

---

## Verification

1. Start container: `docker compose up --build`
2. Open `http://localhost:3615` → Settings
3. Confirm four path prefix fields (Movies Plex, Movies App, TV Plex, TV App) + Tdarr conversion prefix field visible
4. Set `Movies Plex=/movies`, `Movies App=/movies`, `TV Plex=/tv`, `TV App=/tvshows`, `Tdarr conversion prefix=/media/plex-download` → Save
5. Queue a movie → confirm `destFilePath` in DB contains `in-flight/` prefix
6. In Tdarr UI: confirm file appears in watched folder at the correct path
7. After transcoding: trigger Tdarr sync → confirm `output_file_path` populated in DB and contains `/libraries/`
8. Remove a queued item → confirm `✕` button works, item disappears, file removed from filesystem
