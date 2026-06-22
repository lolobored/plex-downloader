# Embedded QuickSync Transcoder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the external Tdarr dependency with an in-container ffmpeg transcoder using Intel QuickSync (QSV), with live progress and configurable quality profiles.

**Architecture:** ffmpeg runs inside the existing single container, reading source files directly from the read-only Plex mounts and writing transcoded output to `/plex-conversion/libraries`. A semaphore-guarded worker submits queued items to `TranscodeService`, which spawns ffmpeg via an injectable `ProcessRunner`, parses the `-progress` stream into a percentage, and persists status. Quality is governed by `QualityProfile` rows (one global default, optional per-item override). The frontend's existing 2-second queue poll renders a progress bar.

**Tech Stack:** Java 21 / Spring Boot, Liquibase, PostgreSQL (embedded), JUnit 5 + Mockito + AssertJ + H2 (tests), Vue 3 + Pinia (JS), Docker (Debian runtime), ffmpeg + Intel oneVPL/`hevc_qsv`.

## Global Constraints

- Java toolchain: `JavaLanguageVersion.of(21)`; SDKMAN java `21.0.4-tem` (per `.sdkmanrc`). Before any gradle command run: `source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem`.
- Package root: `org.lolobored.plexdownloader`. New backend code lives in `org.lolobored.plexdownloader.transcode` unless noted.
- Backend test command: `cd backend && ./gradlew test` (JUnit5 via `useJUnitPlatform()`). Single class: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.FfmpegCommandBuilderTest'`.
- Tests must NOT spawn real ffmpeg/ffprobe. All process execution goes through the `ProcessRunner` / `MediaProbe` interfaces, which are stubbed in tests.
- Liquibase changesets: YAML under `backend/src/main/resources/db/changelog/yaml/`, auto-included by `includeAll`. SQL must work on both PostgreSQL and H2 (mirror the existing raw-`sql` changeset style in `007-tdarr-path-settings.yaml`).
- Commit against issue #50. Every commit body ends with `Refs #50`; the final commit of the feature uses `closes #50`. Commits use Conventional Commits.
- QSV-only: no software transcode fallback (out of scope).
- Concurrency default 2, stored in setting `transcode.max.concurrent`.

---

## File Structure

**Backend — new:**
- `model/QualityProfile.java` — entity + nested enums `Codec`, `Container`, `ResolutionCap`, `AudioMode`.
- `repository/QualityProfileRepository.java`
- `service/QualityProfileService.java` — CRUD + default resolution.
- `transcode/MediaInfo.java` — record (duration, width, height).
- `transcode/MediaProbe.java` (interface) + `transcode/FfprobeMediaProbe.java` (impl).
- `transcode/FfmpegCommandBuilder.java` — pure arg-list builder.
- `transcode/ProgressParser.java` — pure `-progress` line → percent.
- `transcode/ProcessRunner.java` (interface) + `transcode/RunningTranscode.java` (interface) + `transcode/ProcessBuilderRunner.java` (impl).
- `transcode/TranscodeService.java` — orchestration + cancel registry.
- `transcode/TranscodeQueueRunner.java` — worker, semaphore, startup recovery, retry.
- `controller/QualityProfileController.java`
- `dto/QualityProfileRequest.java`
- `db/changelog/yaml/012-quality-profiles.yaml`
- `db/changelog/yaml/013-transcode-queue-columns.yaml`

**Backend — modified:**
- `model/DownloadQueueItem.java` — collapse `Status`, drop `TdarrStatus`, add fields.
- `service/DownloadService.java` — output-path build, no copy, submit to worker, cancel logic.
- `dto/DownloadQueueItemResponse.java` — drop tdarr fields, add `progressPercent`, `qualityProfileName`.
- `dto/DownloadRequest.java` — add optional `qualityProfileId`.
- `controller/DownloadController.java` — retry via worker, drop tdarr-refresh.
- `controller/AdminController.java` — drop tdarr settings/test, add `transcode.max.concurrent`.
- `config/AsyncConfig.java` — keep; add transcode executor if needed (see Task 12).

**Backend — deleted:**
- `client/TdarrClient.java`, `service/TdarrSyncScheduler.java`, and their tests.

**Frontend — modified:**
- `api/download.js` — enqueue accepts profileId; add retry + profiles fetch.
- `api/admin.js` (or equivalent) — quality-profile CRUD.
- `stores/download.js` — enqueue passes profileId.
- `views/QueueView.vue` — progress bar.
- `views/SettingsView.vue` — replace Tdarr section with profile CRUD + max-concurrent.
- `components/DownloadButton.vue`, `components/SubscribeButton.vue` — optional profile picker.

**Infra — modified:**
- `Dockerfile` — Debian runtime stage.
- `docker-entrypoint.sh` — `gosu`, Debian postgres paths, `/dev/dri` note, vainfo log.
- `docker-compose.yml` — `/dev/dri` device + `render` group, drop Tdarr env.
- `README.md` — QSV setup, drop Tdarr prerequisites.

---

## Phase 1 — Data model

### Task 1: QualityProfile entity + enums

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/model/QualityProfile.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/model/QualityProfileTest.java`

**Interfaces:**
- Produces: `QualityProfile` entity with getters/setters (Lombok `@Data`). Nested enums:
  - `Codec { HEVC_QSV, H264_QSV }` with `String ffmpegName()` → `"hevc_qsv"` / `"h264_qsv"`.
  - `Container { MKV, MP4 }` with `String extension()` → `".mkv"` / `".mp4"`.
  - `ResolutionCap { KEEP, UHD_4K, P1080, P720 }` with `int maxHeight()` → `0 / 2160 / 1080 / 720` (0 = no cap).
  - `AudioMode { COPY, AAC }`.
  - Fields: `Long id`, `String name`, `Codec codec`, `Container container`, `int qualityLevel`, `ResolutionCap resolutionCap`, `AudioMode audioMode`, `boolean isDefault`.

- [ ] **Step 1: Write the failing test**

```java
package org.lolobored.plexdownloader.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QualityProfileTest {

    @Test
    void codec_mapsToFfmpegName() {
        assertThat(QualityProfile.Codec.HEVC_QSV.ffmpegName()).isEqualTo("hevc_qsv");
        assertThat(QualityProfile.Codec.H264_QSV.ffmpegName()).isEqualTo("h264_qsv");
    }

    @Test
    void container_mapsToExtension() {
        assertThat(QualityProfile.Container.MKV.extension()).isEqualTo(".mkv");
        assertThat(QualityProfile.Container.MP4.extension()).isEqualTo(".mp4");
    }

    @Test
    void resolutionCap_mapsToMaxHeight() {
        assertThat(QualityProfile.ResolutionCap.KEEP.maxHeight()).isEqualTo(0);
        assertThat(QualityProfile.ResolutionCap.UHD_4K.maxHeight()).isEqualTo(2160);
        assertThat(QualityProfile.ResolutionCap.P1080.maxHeight()).isEqualTo(1080);
        assertThat(QualityProfile.ResolutionCap.P720.maxHeight()).isEqualTo(720);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew test --tests 'org.lolobored.plexdownloader.model.QualityProfileTest'`
Expected: FAIL — `QualityProfile` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "quality_profile")
public class QualityProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Codec codec = Codec.HEVC_QSV;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Container container = Container.MKV;

    @Column(name = "quality_level", nullable = false)
    private int qualityLevel = 23;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_cap", nullable = false)
    private ResolutionCap resolutionCap = ResolutionCap.KEEP;

    @Enumerated(EnumType.STRING)
    @Column(name = "audio_mode", nullable = false)
    private AudioMode audioMode = AudioMode.COPY;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    public enum Codec {
        HEVC_QSV("hevc_qsv"), H264_QSV("h264_qsv");
        private final String ffmpegName;
        Codec(String n) { this.ffmpegName = n; }
        public String ffmpegName() { return ffmpegName; }
    }

    public enum Container {
        MKV(".mkv"), MP4(".mp4");
        private final String ext;
        Container(String e) { this.ext = e; }
        public String extension() { return ext; }
    }

    public enum ResolutionCap {
        KEEP(0), UHD_4K(2160), P1080(1080), P720(720);
        private final int maxHeight;
        ResolutionCap(int h) { this.maxHeight = h; }
        public int maxHeight() { return maxHeight; }
    }

    public enum AudioMode { COPY, AAC }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.model.QualityProfileTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/model/QualityProfile.java \
        backend/src/test/java/org/lolobored/plexdownloader/model/QualityProfileTest.java
git commit -m "feat: add QualityProfile entity with codec/container/resolution enums

Refs #50"
```

---

### Task 2: QualityProfile repository + service (CRUD, default resolution)

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/repository/QualityProfileRepository.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/service/QualityProfileService.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/service/QualityProfileServiceTest.java`

**Interfaces:**
- Consumes: `QualityProfile` (Task 1).
- Produces:
  - `QualityProfileRepository extends JpaRepository<QualityProfile, Long>` with `Optional<QualityProfile> findByIsDefaultTrue()`.
  - `QualityProfileService`:
    - `List<QualityProfile> findAll()`
    - `QualityProfile getDefault()` — `findByIsDefaultTrue()` or throws `IllegalStateException`.
    - `QualityProfile resolveOrDefault(Long id)` — `id != null` → `findById` (404-style `IllegalArgumentException` if missing) else `getDefault()`.
    - `QualityProfile create(QualityProfile p)` / `QualityProfile update(Long id, QualityProfile p)` / `void delete(Long id)`.
    - `QualityProfile setDefault(Long id)` — clears `isDefault` on all others, sets on target (transactional).

- [ ] **Step 1: Write the failing test**

```java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.repository.QualityProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QualityProfileServiceTest {

    @Mock QualityProfileRepository repo;
    @InjectMocks QualityProfileService service;

    private QualityProfile profile(Long id, boolean isDefault) {
        QualityProfile p = new QualityProfile();
        p.setId(id);
        p.setName("p" + id);
        p.setDefault(isDefault);
        return p;
    }

    @Test
    void resolveOrDefault_nullId_returnsDefault() {
        QualityProfile def = profile(1L, true);
        when(repo.findByIsDefaultTrue()).thenReturn(Optional.of(def));
        assertThat(service.resolveOrDefault(null)).isSameAs(def);
    }

    @Test
    void resolveOrDefault_withId_returnsThatProfile() {
        QualityProfile p = profile(5L, false);
        when(repo.findById(5L)).thenReturn(Optional.of(p));
        assertThat(service.resolveOrDefault(5L)).isSameAs(p);
    }

    @Test
    void resolveOrDefault_unknownId_throws() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveOrDefault(9L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getDefault_noneConfigured_throws() {
        when(repo.findByIsDefaultTrue()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDefault())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setDefault_clearsOthersAndSetsTarget() {
        QualityProfile a = profile(1L, true);
        QualityProfile b = profile(2L, false);
        when(repo.findAll()).thenReturn(List.of(a, b));
        when(repo.findById(2L)).thenReturn(Optional.of(b));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setDefault(2L);

        assertThat(a.isDefault()).isFalse();
        assertThat(b.isDefault()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.service.QualityProfileServiceTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write minimal implementation**

`QualityProfileRepository.java`:
```java
package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface QualityProfileRepository extends JpaRepository<QualityProfile, Long> {
    Optional<QualityProfile> findByIsDefaultTrue();
}
```

`QualityProfileService.java`:
```java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.repository.QualityProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QualityProfileService {

    private final QualityProfileRepository repo;

    public List<QualityProfile> findAll() {
        return repo.findAll();
    }

    public QualityProfile getDefault() {
        return repo.findByIsDefaultTrue()
            .orElseThrow(() -> new IllegalStateException("No default quality profile configured"));
    }

    public QualityProfile resolveOrDefault(Long id) {
        if (id == null) return getDefault();
        return repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Quality profile not found: " + id));
    }

    public QualityProfile create(QualityProfile p) {
        p.setId(null);
        return repo.save(p);
    }

    @Transactional
    public QualityProfile update(Long id, QualityProfile p) {
        QualityProfile existing = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Quality profile not found: " + id));
        existing.setName(p.getName());
        existing.setCodec(p.getCodec());
        existing.setContainer(p.getContainer());
        existing.setQualityLevel(p.getQualityLevel());
        existing.setResolutionCap(p.getResolutionCap());
        existing.setAudioMode(p.getAudioMode());
        return repo.save(existing);
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }

    @Transactional
    public QualityProfile setDefault(Long id) {
        QualityProfile target = repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Quality profile not found: " + id));
        for (QualityProfile p : repo.findAll()) {
            if (p.isDefault() && !p.getId().equals(id)) {
                p.setDefault(false);
                repo.save(p);
            }
        }
        target.setDefault(true);
        return repo.save(target);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.service.QualityProfileServiceTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/repository/QualityProfileRepository.java \
        backend/src/main/java/org/lolobored/plexdownloader/service/QualityProfileService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/QualityProfileServiceTest.java
git commit -m "feat: QualityProfileService with default resolution and CRUD

Refs #50"
```

---

### Task 3: Liquibase — quality_profile table + seed default

**Files:**
- Create: `backend/src/main/resources/db/changelog/yaml/012-quality-profiles.yaml`

**Interfaces:**
- Produces: `quality_profile` table; one seeded row (`name='Default'`, HEVC_QSV/MKV, qualityLevel 23, KEEP, COPY, `is_default=true`).

This task has no JUnit unit test of its own; it is verified by the full test-suite boot (Liquibase runs against H2 on context startup). The verification step is running the existing suite and confirming clean startup.

- [ ] **Step 1: Write the changelog**

```yaml
databaseChangeLog:
  - changeSet:
      id: 012-quality-profiles
      author: plexdownloader
      changes:
        - createTable:
            tableName: quality_profile
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: name
                  type: VARCHAR(255)
                  constraints:
                    nullable: false
                    unique: true
              - column: { name: codec,          type: VARCHAR(32),  defaultValue: "HEVC_QSV", constraints: { nullable: false } }
              - column: { name: container,       type: VARCHAR(16),  defaultValue: "MKV",      constraints: { nullable: false } }
              - column: { name: quality_level,   type: INT,          defaultValueNumeric: 23,  constraints: { nullable: false } }
              - column: { name: resolution_cap,  type: VARCHAR(16),  defaultValue: "KEEP",     constraints: { nullable: false } }
              - column: { name: audio_mode,      type: VARCHAR(16),  defaultValue: "COPY",     constraints: { nullable: false } }
              - column: { name: is_default,      type: BOOLEAN,      defaultValueBoolean: false, constraints: { nullable: false } }
        - insert:
            tableName: quality_profile
            columns:
              - column: { name: name,           value: "Default" }
              - column: { name: codec,          value: "HEVC_QSV" }
              - column: { name: container,      value: "MKV" }
              - column: { name: quality_level,  valueNumeric: 23 }
              - column: { name: resolution_cap, value: "KEEP" }
              - column: { name: audio_mode,     value: "COPY" }
              - column: { name: is_default,     valueBoolean: true }
```

- [ ] **Step 2: Run the suite to verify Liquibase applies cleanly**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.service.QualityProfileServiceTest'`
Expected: PASS — context starts, changelog applies on H2 without error.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/yaml/012-quality-profiles.yaml
git commit -m "feat: liquibase quality_profile table with seeded default

Refs #50"
```

---

### Task 4: Collapse DownloadQueueItem status + new transcode fields

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/model/DownloadQueueItemTest.java` (create)

**Interfaces:**
- Produces (the new shape every later task depends on):
  - `enum Status { QUEUED, TRANSCODING, DONE, ERROR }` (default `QUEUED`).
  - Removed: `TdarrStatus` enum, `tdarrStatus`, `tdarrError`, `outputFilePath` fields.
  - Added fields: `Integer progressPercent`, `String transcodeError`, `Instant transcodeStartedAt`, `QualityProfile qualityProfile` (`@ManyToOne`, nullable, column `quality_profile_id`).
  - `destFilePath` semantics: now the final output path under `/plex-conversion/libraries`.

> NOTE: this change breaks compilation of `TdarrClient`, `TdarrSyncScheduler`, `DownloadService`, `DownloadQueueItemResponse`, `DownloadController`, and several tests. Those are fixed in Tasks 5–6 and 13–16. Until then the module will not compile; run only this task's targeted unit test of the model in isolation is not possible (model test compiles with the rest). Therefore Tasks 4, 5, 6 form one compile unit: implement 4→5→6, then run tests. Commit at the end of Task 6. (Tasks 4 and 5 have no standalone green test; their "verify" is compilation success at Task 6.)

- [ ] **Step 1: Edit the entity**

Replace the status/tdarr region of `DownloadQueueItem.java`:

```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    @Column(name = "progress_percent")
    private Integer progressPercent;

    @Column(name = "transcode_error", columnDefinition = "TEXT")
    private String transcodeError;

    @Column(name = "transcode_started_at")
    private Instant transcodeStartedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_profile_id")
    private QualityProfile qualityProfile;
```

Remove the `tdarrStatus`, `tdarrError`, `outputFilePath` field declarations and the `TdarrStatus` enum. Update the enum line to:

```java
    public enum MediaType { MOVIE, EPISODE }
    public enum Status { QUEUED, TRANSCODING, DONE, ERROR }
```

Add import: `import org.lolobored.plexdownloader.model.QualityProfile;` is same package — no import needed. Keep `import java.time.Instant;`.

- [ ] **Step 2: Write the model test**

```java
package org.lolobored.plexdownloader.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DownloadQueueItemTest {
    @Test
    void defaultStatusIsQueued() {
        assertThat(new DownloadQueueItem().getStatus())
            .isEqualTo(DownloadQueueItem.Status.QUEUED);
    }

    @Test
    void progressDefaultsToNull() {
        assertThat(new DownloadQueueItem().getProgressPercent()).isNull();
    }
}
```

- [ ] **Step 3: (no run yet — proceed to Task 5; compilation completes at Task 6)**

---

### Task 5: Delete Tdarr client + scheduler

**Files:**
- Delete: `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java`
- Delete: `backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java`
- Delete: `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java`
- Delete: `backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java`

- [ ] **Step 1: Remove the files**

```bash
git rm backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java \
       backend/src/main/java/org/lolobored/plexdownloader/service/TdarrSyncScheduler.java \
       backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java \
       backend/src/test/java/org/lolobored/plexdownloader/service/TdarrSyncSchedulerTest.java
```

- [ ] **Step 2: (proceed to Task 6 — DownloadService/DTO/controllers still reference removed types)**

---

### Task 6: Rework DownloadService (output path, no copy, submit) + DTO + controllers compile

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/DownloadService.java`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/dto/DownloadQueueItemResponse.java`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/dto/DownloadRequest.java`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/controller/DownloadController.java`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/controller/AdminController.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/DownloadServiceTest.java`
- Test: existing `DownloadServiceTest` (rewritten to new model).

**Interfaces:**
- Consumes: `QualityProfileService.resolveOrDefault` (Task 2), `TranscodeQueueRunner.submit` (Task 12 — but to avoid a forward dependency, this task introduces submission through a thin seam; see below).
- Produces:
  - `DownloadService.enqueueMovie(Long, User, Long playlistId, Long qualityProfileId)` and convenience overloads.
  - Output path: `<conversionDir>/libraries/<subDir>/<basename><container.extension()>`.
  - `DownloadQueueItemResponse` record fields: replace `status` enum (new), drop `tdarrStatus`/`tdarrError`, add `Integer progressPercent`, `String qualityProfileName`.
  - `DownloadRequest` record: `(String type, Long id, Long qualityProfileId)`.

> Worker seam: `TranscodeQueueRunner` is built in Task 12. To keep Phase 1 compiling and testable now, in this task introduce an interface `transcode/TranscodeSubmitter` with `void submit(Long itemId)`, inject it into `DownloadService`, and provide a temporary no-op `@Primary`? — NO. Simpler: inject `org.springframework.context.ApplicationEventPublisher` and publish a `TranscodeRequestedEvent(itemId)`. `TranscodeQueueRunner` (Task 12) listens with `@EventListener`. This fully decouples enqueue from the worker and needs no placeholder bean.

- [ ] **Step 1: Add the event type**

Create `backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeRequestedEvent.java`:
```java
package org.lolobored.plexdownloader.transcode;

public record TranscodeRequestedEvent(Long itemId) {}
```

- [ ] **Step 2: Rewrite DownloadService enqueue/build/cancel**

Replace the constructor deps: remove `TdarrClient tdarrClient`; add `QualityProfileService qualityProfileService` and `ApplicationEventPublisher events`.

`buildItem` — compute output path and set profile:
```java
    private DownloadQueueItem buildItem(User user, DownloadQueueItem.MediaType type,
                                        Long mediaId, String plexFilePath, String subDir,
                                        String title, QualityProfile profile) {
        String conversionDir = settings.get("plex.conversion.dir").orElse("/plex-conversion");
        String srcName = Path.of(plexFilePath).getFileName().toString();
        String stem = srcName.replaceFirst("\\.[^.]+$", "");
        String outName = stem + profile.getContainer().extension();
        String destPath = Path.of(conversionDir, "libraries", subDir, outName).toString();

        int nextPos = queueRepo.findMaxQueuePosition().orElse(0) + 1;

        DownloadQueueItem item = new DownloadQueueItem();
        item.setUser(user);
        item.setMediaType(type);
        item.setMediaId(mediaId);
        item.setTitle(title);
        item.setSourceFilePath(plexFilePath);
        item.setDestFilePath(destPath);
        item.setQualityProfile(profile);
        item.setQueuePosition(nextPos);
        item.setStatus(DownloadQueueItem.Status.QUEUED);
        return item;
    }
```

`enqueueMovie` (apply same pattern to episode/season/show; all gain a `Long qualityProfileId` parameter; keep no-arg-profile overloads delegating with `null`):
```java
    public List<Long> enqueueMovie(Long movieId, User user) {
        return enqueueMovie(movieId, user, null, null);
    }
    public List<Long> enqueueMovie(Long movieId, User user, Long playlistId) {
        return enqueueMovie(movieId, user, playlistId, null);
    }
    public List<Long> enqueueMovie(Long movieId, User user, Long playlistId, Long qualityProfileId) {
        Movie movie = movieRepo.findById(movieId)
            .orElseThrow(() -> new IllegalArgumentException("Movie not found: " + movieId));
        QualityProfile profile = qualityProfileService.resolveOrDefault(qualityProfileId);
        String subDir = "movies/" + Path.of(movie.getFilePath()).getParent().getFileName().toString();
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.MOVIE,
            movieId, movie.getFilePath(), subDir, movie.getTitle(), profile);
        item.setPlaylistId(playlistId);
        item = queueRepo.save(item);
        events.publishEvent(new TranscodeRequestedEvent(item.getId()));
        return List.of(item.getId());
    }
```
Apply the equivalent edit to `enqueueEpisode`/`enqueueSeason`/`enqueueShow`: add `Long qualityProfileId` plumbing, resolve the profile once, pass it into `buildItem`, and replace `self.executeCopyAsync(item.getId())` with `events.publishEvent(new TranscodeRequestedEvent(item.getId()))`. For `enqueueSeason`/`enqueueShow`, resolve the profile once at the top and reuse for every episode.

- [ ] **Step 3: Delete the copy machinery**

Remove `executeCopyAsync(...)`, the `@Async`/`setSelf`/`self` field and its `@Autowired @Lazy` setter, and the `deriveLibrariesPath(...)` helper (no longer needed — dest IS the output). Remove now-unused imports (`AtomicMoveNotSupportedException`, `StandardCopyOption`, `@Async`, `@Lazy`, `@Autowired`).

- [ ] **Step 4: Simplify cancel**

`doCancelItem` becomes: delete the output file if present, then delete the row. (Tdarr eviction and in-flight logic removed.) Cancellation of an actively-transcoding item is handled by the worker via `TranscodeService.cancel` in Task 14 — for now keep the existing `409 IN_PROGRESS` guard but change the state name: items being transcoded have status `TRANSCODING`.

```java
    void doCancelItem(DownloadQueueItem item) {
        if (item.getDestFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(item.getDestFilePath()));
            } catch (IOException e) {
                log.warn("Could not delete output file {}: {}", item.getDestFilePath(), e.getMessage());
            }
        }
        queueRepo.delete(item);
    }
```
In `cancel(...)`, change the in-progress guard to:
```java
        if (item.getStatus() == DownloadQueueItem.Status.TRANSCODING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Transcode in progress, cancel via the worker");
        }
```
(The worker-aware cancel that force-kills ffmpeg is added in Task 14; this guard is replaced there.)
In `cancelAllForShow`/`cancelAllForSeason`, replace `Status.IN_PROGRESS` checks with `Status.TRANSCODING`.

- [ ] **Step 5: Update DownloadQueueItemResponse**

```java
public record DownloadQueueItemResponse(
    Long id,
    DownloadQueueItem.MediaType mediaType,
    Long mediaId,
    DownloadQueueItem.Status status,
    Integer progressPercent,
    String transcodeError,
    String qualityProfileName,
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
            item.getStatus(), item.getProgressPercent(), item.getTranscodeError(),
            item.getQualityProfile() != null ? item.getQualityProfile().getName() : null,
            item.getTitle(), item.getQueuePosition(), item.getErrorMessage(),
            item.getRequestedAt(), item.getCompletedAt(),
            showId, seasonId,
            playlistId, playlistTitle,
            showTitle, seasonNumber
        );
    }
}
```

- [ ] **Step 6: Update DownloadRequest**

```java
package org.lolobored.plexdownloader.dto;

public record DownloadRequest(String type, Long id, Long qualityProfileId) {}
```

- [ ] **Step 7: Update DownloadController**

Remove `TdarrSyncScheduler` field and the `/tdarr-refresh` endpoint. Pass profile to enqueue and wire retry to the worker (the worker bean arrives in Task 12; reference it via constructor injection now and accept that compilation of the controller completes once Task 12 exists — to keep THIS task compiling, temporarily route retry through a method on `DownloadService.retry(Long id, User user)` that resets `ERROR`→`QUEUED`, clears `transcodeError`/`progressPercent`, saves, and republishes `TranscodeRequestedEvent`). Implement that `retry` on `DownloadService`:

```java
    @Transactional
    public DownloadQueueItem retry(Long id, User user) {
        DownloadQueueItem item = queueRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found"));
        if (item.getUser() == null || !item.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item");
        }
        if (item.getStatus() != DownloadQueueItem.Status.ERROR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not in ERROR state");
        }
        item.setStatus(DownloadQueueItem.Status.QUEUED);
        item.setErrorMessage(null);
        item.setTranscodeError(null);
        item.setProgressPercent(null);
        queueRepo.save(item);
        events.publishEvent(new TranscodeRequestedEvent(id));
        return item;
    }
```

Controller:
```java
    @PostMapping
    public DownloadResponse download(@RequestBody DownloadRequest req,
                                     @AuthenticationPrincipal User user) {
        List<Long> jobIds = switch (req.type()) {
            case "MOVIE"   -> downloadService.enqueueMovie(req.id(), user, null, req.qualityProfileId());
            case "EPISODE" -> downloadService.enqueueEpisode(req.id(), user, null, req.qualityProfileId());
            case "SEASON"  -> downloadService.enqueueSeason(req.id(), user, req.qualityProfileId());
            case "SHOW"    -> downloadService.enqueueShow(req.id(), user, req.qualityProfileId());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown type: " + req.type());
        };
        return new DownloadResponse(jobIds, "QUEUED");
    }

    @PostMapping("/{id}/retry")
    public DownloadQueueItem retry(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return downloadService.retry(id, user);
    }
```
(Adjust `enqueueEpisode`/`enqueueSeason`/`enqueueShow` signatures to accept `qualityProfileId` consistently with Task 6 Step 2.)

- [ ] **Step 8: Update AdminController**

Remove `TdarrClient` field, the `/tdarr/test` endpoint, and the three `tdarr.*` lines in `getSettings()`. Add to `getSettings()`:
```java
        result.put("transcode.max.concurrent", settingsService.get("transcode.max.concurrent").orElse("2"));
```

- [ ] **Step 9: Rewrite DownloadServiceTest for the new model**

Update the existing test file: remove all `TdarrClient` mock + tdarr assertions; replace `Status.PENDING`/`IN_PROGRESS` with `QUEUED`/`TRANSCODING`; drop `executeCopyAsync_*` tests (copy removed); mock `QualityProfileService` and `ApplicationEventPublisher`; assert output path contains `/libraries/` and ends with the profile extension, and that a `TranscodeRequestedEvent` is published. Representative replacements:

```java
    @Mock QualityProfileService qualityProfileService;
    @Mock org.springframework.context.ApplicationEventPublisher events;
    @InjectMocks DownloadService service;   // no longer @Spy; self-injection removed

    private QualityProfile defaultProfile() {
        QualityProfile p = new QualityProfile();
        p.setId(1L); p.setName("Default");
        p.setContainer(QualityProfile.Container.MKV);
        return p;
    }

    @Test
    void enqueueMovie_buildsLibrariesOutputPathWithProfileExtension() {
        Movie movie = new Movie();
        movie.setId(1L); movie.setTitle("The Dark Knight");
        movie.setFilePath("/plex/movies/The Dark Knight (2008)/dark.avi");
        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("plex.conversion.dir")).thenReturn(Optional.of("/conv"));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> { DownloadQueueItem i = inv.getArgument(0); i.setId(2L); return i; });

        service.enqueueMovie(1L, new User());

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath().replace('\\','/').contains("/libraries/movies/The Dark Knight (2008)/dark.mkv")
            && item.getStatus() == DownloadQueueItem.Status.QUEUED));
        verify(events).publishEvent(any(TranscodeRequestedEvent.class));
    }

    @Test
    void cancel_deletesOutputAndRow(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("film.mkv");
        Files.writeString(out, "data");
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(10L); item.setStatus(DownloadQueueItem.Status.DONE);
        item.setDestFilePath(out.toString());
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);
        when(queueRepo.findById(10L)).thenReturn(Optional.of(item));

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        service.cancel(10L, caller);

        assertThat(out).doesNotExist();
        verify(queueRepo).delete(item);
    }

    @Test
    void retry_resetsErrorToQueuedAndRepublishes() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(7L); item.setStatus(DownloadQueueItem.Status.ERROR);
        User owner = new User(); owner.setId(1L); item.setUser(owner);
        when(queueRepo.findById(7L)).thenReturn(Optional.of(item));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User caller = new User(); caller.setId(1L);
        service.retry(7L, caller);

        assertThat(item.getStatus()).isEqualTo(DownloadQueueItem.Status.QUEUED);
        verify(events).publishEvent(any(TranscodeRequestedEvent.class));
    }
```
Keep and adapt the `getQueue_*`, `cancelAllForShow/Season`, and 403/404/409 tests to the new field names. Remove `injectSelf` `@BeforeEach`.

- [ ] **Step 10: Run the full backend suite**

Run: `./gradlew test`
Expected: PASS — module compiles, no Tdarr references remain. (PlaylistSyncService is fixed in Task 13 if it fails to compile here; if so, do Task 13 before re-running. See note in Task 13.)

- [ ] **Step 11: Commit Tasks 4–6 together**

```bash
git add -A
git commit -m "refactor: collapse queue status, drop Tdarr, ffmpeg output path + event submission

DownloadQueueItem.Status is now QUEUED/TRANSCODING/DONE/ERROR; items carry a
QualityProfile and progressPercent. Enqueue writes the final /libraries output
path and publishes TranscodeRequestedEvent instead of copying. Tdarr client and
scheduler removed.

Refs #50"
```

---

### Task 7: Liquibase — queue columns + status migration + drop tdarr columns

**Files:**
- Create: `backend/src/main/resources/db/changelog/yaml/013-transcode-queue-columns.yaml`

**Interfaces:**
- Produces: `download_queue` gains `progress_percent`, `transcode_error`, `transcode_started_at`, `quality_profile_id` (+ FK); status rows migrated; `tdarr_status`, `tdarr_error`, `output_file_path` dropped.

- [ ] **Step 1: Write the changelog**

```yaml
databaseChangeLog:
  - changeSet:
      id: 013-transcode-queue-columns
      author: plexdownloader
      changes:
        - sql:
            sql: "ALTER TABLE download_queue ADD COLUMN progress_percent INT"
        - sql:
            sql: "ALTER TABLE download_queue ADD COLUMN transcode_error TEXT"
        - sql:
            sql: "ALTER TABLE download_queue ADD COLUMN transcode_started_at TIMESTAMP"
        - sql:
            sql: "ALTER TABLE download_queue ADD COLUMN quality_profile_id BIGINT"
        - addForeignKeyConstraint:
            baseTableName: download_queue
            baseColumnNames: quality_profile_id
            constraintName: fk_download_queue_quality_profile
            referencedTableName: quality_profile
            referencedColumnNames: id
            onDelete: SET NULL
        # --- migrate status (anything not finished-transcoded becomes QUEUED) ---
        - sql:
            sql: "UPDATE download_queue SET status='DONE'  WHERE status='DONE' AND tdarr_status='TRANSCODED'"
        - sql:
            sql: "UPDATE download_queue SET status='ERROR' WHERE tdarr_status='TDARR_ERROR'"
        - sql:
            sql: "UPDATE download_queue SET status='QUEUED' WHERE status IN ('PENDING','IN_PROGRESS') OR (status='DONE' AND (tdarr_status IS NULL OR tdarr_status NOT IN ('TRANSCODED','TDARR_ERROR')))"
        # --- backfill quality_profile_id to the default profile ---
        - sql:
            sql: "UPDATE download_queue SET quality_profile_id = (SELECT id FROM quality_profile WHERE is_default = TRUE LIMIT 1) WHERE quality_profile_id IS NULL"
        # --- drop obsolete tdarr columns ---
        - dropColumn: { tableName: download_queue, columnName: tdarr_status }
        - dropColumn: { tableName: download_queue, columnName: tdarr_error }
        - dropColumn: { tableName: download_queue, columnName: output_file_path }
```

> If H2 rejects `LIMIT 1` inside the subquery during tests, replace that one `UPDATE` with `... = (SELECT MIN(id) FROM quality_profile WHERE is_default = TRUE)`. Verify in Step 2; switch if needed.

- [ ] **Step 2: Run the suite to verify migration applies on H2**

Run: `./gradlew test`
Expected: PASS — Liquibase applies `013` cleanly on a fresh H2 instance.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/yaml/013-transcode-queue-columns.yaml
git commit -m "feat: liquibase migrate queue status, add transcode columns, drop tdarr columns

Refs #50"
```

---

## Phase 2 — Transcode engine (pure units first)

### Task 8: ProgressParser

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/ProgressParser.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/transcode/ProgressParserTest.java`

**Interfaces:**
- Produces: `class ProgressParser` with `java.util.OptionalInt percentFor(String line, double durationSeconds)`.
  - `"progress=end"` → `OptionalInt.of(100)`.
  - `"out_time_us=<micros>"` → percent `= round(micros/1e6 / durationSeconds * 100)`, clamped `[0,100]`.
  - `"out_time=HH:MM:SS.micros"` → same, parsed from timestamp.
  - any other line, or `durationSeconds <= 0` (for time lines) → `OptionalInt.empty()`.

- [ ] **Step 1: Write the failing test**

```java
package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProgressParserTest {

    private final ProgressParser parser = new ProgressParser();

    @Test
    void outTimeUs_halfway() {
        assertThat(parser.percentFor("out_time_us=30000000", 60.0)).hasValue(50);
    }

    @Test
    void progressEnd_is100() {
        assertThat(parser.percentFor("progress=end", 60.0)).hasValue(100);
    }

    @Test
    void outTimeTimestamp_parsed() {
        // 00:00:30.000000 of a 60s clip = 50%
        assertThat(parser.percentFor("out_time=00:00:30.000000", 60.0)).hasValue(50);
    }

    @Test
    void overshoot_clampedTo100() {
        assertThat(parser.percentFor("out_time_us=120000000", 60.0)).hasValue(100);
    }

    @Test
    void unrelatedLine_empty() {
        assertThat(parser.percentFor("bitrate= 1234.5kbits/s", 60.0)).isEmpty();
    }

    @Test
    void zeroDuration_timeLineEmpty() {
        assertThat(parser.percentFor("out_time_us=30000000", 0.0)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.ProgressParserTest'`
Expected: FAIL — class missing.

- [ ] **Step 3: Write minimal implementation**

```java
package org.lolobored.plexdownloader.transcode;

import org.springframework.stereotype.Component;
import java.util.OptionalInt;

@Component
public class ProgressParser {

    public OptionalInt percentFor(String line, double durationSeconds) {
        if (line == null) return OptionalInt.empty();
        String s = line.trim();
        if (s.equals("progress=end")) return OptionalInt.of(100);

        Double seconds = null;
        if (s.startsWith("out_time_us=")) {
            try {
                seconds = Long.parseLong(s.substring("out_time_us=".length()).trim()) / 1_000_000.0;
            } catch (NumberFormatException ignored) { return OptionalInt.empty(); }
        } else if (s.startsWith("out_time=")) {
            seconds = parseTimestamp(s.substring("out_time=".length()).trim());
        }
        if (seconds == null) return OptionalInt.empty();
        if (durationSeconds <= 0) return OptionalInt.empty();

        long pct = Math.round(seconds / durationSeconds * 100.0);
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return OptionalInt.of((int) pct);
    }

    private Double parseTimestamp(String ts) {
        try {
            String[] parts = ts.split(":");
            if (parts.length != 3) return null;
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            double sec = Double.parseDouble(parts[2]);
            return h * 3600.0 + m * 60.0 + sec;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.ProgressParserTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/transcode/ProgressParser.java \
        backend/src/test/java/org/lolobored/plexdownloader/transcode/ProgressParserTest.java
git commit -m "feat: ProgressParser for ffmpeg -progress output

Refs #50"
```

---

### Task 9: FfmpegCommandBuilder + MediaInfo

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/MediaInfo.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/FfmpegCommandBuilder.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/transcode/FfmpegCommandBuilderTest.java`

**Interfaces:**
- Produces:
  - `record MediaInfo(double durationSeconds, int width, int height)`.
  - `class FfmpegCommandBuilder` with `List<String> build(QualityProfile profile, String sourcePath, String destPath, MediaInfo source)`.
  - Arg order (asserted by tests): `ffmpeg -nostdin -y -hwaccel qsv -hwaccel_output_format qsv -i <src> [-vf scale_qsv=-1:<h>] -c:v <codec> -global_quality <q> -c:a <copy|aac> -progress pipe:1 -nostats <dest>`.
  - Downscale filter present only when `profile.resolutionCap.maxHeight() > 0 && source.height() > maxHeight`.

- [ ] **Step 1: Write the failing test**

```java
package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FfmpegCommandBuilderTest {

    private final FfmpegCommandBuilder builder = new FfmpegCommandBuilder();

    private QualityProfile profile(QualityProfile.Codec codec, QualityProfile.AudioMode audio,
                                   QualityProfile.ResolutionCap cap, int quality) {
        QualityProfile p = new QualityProfile();
        p.setCodec(codec); p.setAudioMode(audio); p.setResolutionCap(cap); p.setQualityLevel(quality);
        return p;
    }

    @Test
    void hevcCopyKeep_buildsExpectedArgs() {
        QualityProfile p = profile(QualityProfile.Codec.HEVC_QSV, QualityProfile.AudioMode.COPY,
                                   QualityProfile.ResolutionCap.KEEP, 23);
        List<String> args = builder.build(p, "/movies/a.avi", "/out/a.mkv", new MediaInfo(120, 1920, 1080));

        assertThat(args).containsSubsequence("-hwaccel", "qsv", "-hwaccel_output_format", "qsv");
        assertThat(args).containsSubsequence("-i", "/movies/a.avi");
        assertThat(args).containsSubsequence("-c:v", "hevc_qsv", "-global_quality", "23");
        assertThat(args).containsSubsequence("-c:a", "copy");
        assertThat(args).containsSubsequence("-progress", "pipe:1", "-nostats");
        assertThat(args.get(args.size() - 1)).isEqualTo("/out/a.mkv");
        assertThat(args).doesNotContain("-vf");
    }

    @Test
    void h264Aac_setsCodecAndAac() {
        QualityProfile p = profile(QualityProfile.Codec.H264_QSV, QualityProfile.AudioMode.AAC,
                                   QualityProfile.ResolutionCap.KEEP, 20);
        List<String> args = builder.build(p, "/m/b.mkv", "/out/b.mp4", new MediaInfo(60, 1280, 720));
        assertThat(args).containsSubsequence("-c:v", "h264_qsv", "-global_quality", "20");
        assertThat(args).containsSubsequence("-c:a", "aac");
    }

    @Test
    void resolutionCapBelowSource_addsScaleFilter() {
        QualityProfile p = profile(QualityProfile.Codec.HEVC_QSV, QualityProfile.AudioMode.COPY,
                                   QualityProfile.ResolutionCap.P720, 23);
        List<String> args = builder.build(p, "/m/c.mkv", "/out/c.mkv", new MediaInfo(60, 1920, 1080));
        assertThat(args).containsSubsequence("-vf", "scale_qsv=-1:720");
    }

    @Test
    void resolutionCapAboveSource_noScaleFilter() {
        QualityProfile p = profile(QualityProfile.Codec.HEVC_QSV, QualityProfile.AudioMode.COPY,
                                   QualityProfile.ResolutionCap.P1080, 23);
        List<String> args = builder.build(p, "/m/d.mkv", "/out/d.mkv", new MediaInfo(60, 1280, 720));
        assertThat(args).doesNotContain("-vf");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.FfmpegCommandBuilderTest'`
Expected: FAIL — classes missing.

- [ ] **Step 3: Write minimal implementation**

`MediaInfo.java`:
```java
package org.lolobored.plexdownloader.transcode;

public record MediaInfo(double durationSeconds, int width, int height) {}
```

`FfmpegCommandBuilder.java`:
```java
package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FfmpegCommandBuilder {

    public List<String> build(QualityProfile profile, String sourcePath, String destPath, MediaInfo source) {
        List<String> args = new ArrayList<>();
        args.add("ffmpeg");
        args.add("-nostdin");
        args.add("-y");
        args.add("-hwaccel"); args.add("qsv");
        args.add("-hwaccel_output_format"); args.add("qsv");
        args.add("-i"); args.add(sourcePath);

        int cap = profile.getResolutionCap().maxHeight();
        if (cap > 0 && source.height() > cap) {
            args.add("-vf"); args.add("scale_qsv=-1:" + cap);
        }

        args.add("-c:v"); args.add(profile.getCodec().ffmpegName());
        args.add("-global_quality"); args.add(Integer.toString(profile.getQualityLevel()));

        args.add("-c:a");
        args.add(profile.getAudioMode() == QualityProfile.AudioMode.AAC ? "aac" : "copy");

        args.add("-progress"); args.add("pipe:1");
        args.add("-nostats");

        args.add(destPath);
        return args;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.FfmpegCommandBuilderTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/transcode/MediaInfo.java \
        backend/src/main/java/org/lolobored/plexdownloader/transcode/FfmpegCommandBuilder.java \
        backend/src/test/java/org/lolobored/plexdownloader/transcode/FfmpegCommandBuilderTest.java
git commit -m "feat: FfmpegCommandBuilder for QSV transcode args

Refs #50"
```

---

### Task 10: ProcessRunner abstraction + real impl

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/RunningTranscode.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/ProcessRunner.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/ProcessBuilderRunner.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/transcode/ProcessBuilderRunnerTest.java`

**Interfaces:**
- Produces:
  - `interface RunningTranscode { int waitForExit() throws InterruptedException; void cancel(); }`
  - `interface ProcessRunner { RunningTranscode start(List<String> command, java.util.function.Consumer<String> stdoutLineSink, java.util.function.Consumer<String> stderrLineSink); }`
  - `class ProcessBuilderRunner implements ProcessRunner` — uses `ProcessBuilder`, two daemon reader threads pumping lines to the sinks; `cancel()` → `process.destroyForcibly()`.

The real impl is integration-ish; the unit test uses a cross-platform command (`sh -c`) to confirm stdout lines reach the sink and exit code is returned. Skipped automatically on non-Unix (the CI/dev target is Linux/Mac — both have `sh`).

- [ ] **Step 1: Write the failing test**

```java
package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessBuilderRunnerTest {

    private final ProcessBuilderRunner runner = new ProcessBuilderRunner();

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void capturesStdoutAndExitCode() throws InterruptedException {
        List<String> out = new CopyOnWriteArrayList<>();
        RunningTranscode rt = runner.start(
            List.of("sh", "-c", "echo hello; echo world; exit 0"),
            out::add, line -> {});
        int code = rt.waitForExit();
        assertThat(code).isZero();
        assertThat(out).contains("hello", "world");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void nonZeroExitPropagates() throws InterruptedException {
        RunningTranscode rt = runner.start(List.of("sh", "-c", "exit 3"), l -> {}, l -> {});
        assertThat(rt.waitForExit()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.ProcessBuilderRunnerTest'`
Expected: FAIL — classes missing.

- [ ] **Step 3: Write minimal implementation**

`RunningTranscode.java`:
```java
package org.lolobored.plexdownloader.transcode;

public interface RunningTranscode {
    int waitForExit() throws InterruptedException;
    void cancel();
}
```

`ProcessRunner.java`:
```java
package org.lolobored.plexdownloader.transcode;

import java.util.List;
import java.util.function.Consumer;

public interface ProcessRunner {
    RunningTranscode start(List<String> command,
                           Consumer<String> stdoutLineSink,
                           Consumer<String> stderrLineSink);
}
```

`ProcessBuilderRunner.java`:
```java
package org.lolobored.plexdownloader.transcode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Component
public class ProcessBuilderRunner implements ProcessRunner {

    @Override
    public RunningTranscode start(List<String> command,
                                  Consumer<String> stdoutLineSink,
                                  Consumer<String> stderrLineSink) {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to start: " + String.join(" ", command), e);
        }
        pump(process.getInputStream(), stdoutLineSink, "ffmpeg-out");
        pump(process.getErrorStream(), stderrLineSink, "ffmpeg-err");

        return new RunningTranscode() {
            @Override public int waitForExit() throws InterruptedException { return process.waitFor(); }
            @Override public void cancel() { process.destroyForcibly(); }
        };
    }

    private void pump(InputStream in, Consumer<String> sink, String threadName) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sink.accept(line);
            } catch (IOException e) {
                log.debug("{} reader closed: {}", threadName, e.getMessage());
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.ProcessBuilderRunnerTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/transcode/RunningTranscode.java \
        backend/src/main/java/org/lolobored/plexdownloader/transcode/ProcessRunner.java \
        backend/src/main/java/org/lolobored/plexdownloader/transcode/ProcessBuilderRunner.java \
        backend/src/test/java/org/lolobored/plexdownloader/transcode/ProcessBuilderRunnerTest.java
git commit -m "feat: ProcessRunner abstraction with ProcessBuilder impl

Refs #50"
```

---

### Task 11: FfprobeMediaProbe + MediaProbe interface

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/MediaProbe.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/FfprobeMediaProbe.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/transcode/FfprobeMediaProbeTest.java`

**Interfaces:**
- Produces:
  - `interface MediaProbe { MediaInfo probe(String sourcePath); }`
  - `class FfprobeMediaProbe implements MediaProbe` — runs ffprobe via the injected `ProcessRunner`, collecting stdout lines, then parses them. To keep parsing testable without a real process, the parsing is a separate package-private static method `parse(List<String> lines)`.
  - ffprobe invocation: `ffprobe -v error -select_streams v:0 -show_entries stream=width,height -show_entries format=duration -of default=noprint_wrappers=1 <src>`. Output lines like `width=1920`, `height=1080`, `duration=120.5`.

- [ ] **Step 1: Write the failing test (parsing only — no real ffprobe)**

```java
package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FfprobeMediaProbeTest {

    @Test
    void parsesWidthHeightDuration() {
        MediaInfo info = FfprobeMediaProbe.parse(List.of(
            "width=1920", "height=1080", "duration=120.500000"));
        assertThat(info.width()).isEqualTo(1920);
        assertThat(info.height()).isEqualTo(1080);
        assertThat(info.durationSeconds()).isEqualTo(120.5);
    }

    @Test
    void missingDuration_defaultsToZero() {
        MediaInfo info = FfprobeMediaProbe.parse(List.of("width=640", "height=480"));
        assertThat(info.durationSeconds()).isEqualTo(0.0);
        assertThat(info.width()).isEqualTo(640);
    }

    @Test
    void garbageLinesIgnored() {
        MediaInfo info = FfprobeMediaProbe.parse(List.of("foo=bar", "width=1280", "height=720", ""));
        assertThat(info.width()).isEqualTo(1280);
        assertThat(info.height()).isEqualTo(720);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.FfprobeMediaProbeTest'`
Expected: FAIL — classes missing.

- [ ] **Step 3: Write minimal implementation**

`MediaProbe.java`:
```java
package org.lolobored.plexdownloader.transcode;

public interface MediaProbe {
    MediaInfo probe(String sourcePath);
}
```

`FfprobeMediaProbe.java`:
```java
package org.lolobored.plexdownloader.transcode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@RequiredArgsConstructor
public class FfprobeMediaProbe implements MediaProbe {

    private final ProcessRunner processRunner;

    @Override
    public MediaInfo probe(String sourcePath) {
        List<String> lines = new CopyOnWriteArrayList<>();
        List<String> cmd = List.of(
            "ffprobe", "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1",
            sourcePath);
        RunningTranscode rt = processRunner.start(cmd, lines::add, l -> {});
        try {
            rt.waitForExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return parse(new ArrayList<>(lines));
    }

    static MediaInfo parse(List<String> lines) {
        int width = 0, height = 0;
        double duration = 0.0;
        for (String line : lines) {
            if (line == null) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            try {
                switch (key) {
                    case "width"    -> width = Integer.parseInt(val);
                    case "height"   -> height = Integer.parseInt(val);
                    case "duration" -> duration = Double.parseDouble(val);
                    default -> { /* ignore */ }
                }
            } catch (NumberFormatException ignored) { /* skip */ }
        }
        return new MediaInfo(duration, width, height);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.FfprobeMediaProbeTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/transcode/MediaProbe.java \
        backend/src/main/java/org/lolobored/plexdownloader/transcode/FfprobeMediaProbe.java \
        backend/src/test/java/org/lolobored/plexdownloader/transcode/FfprobeMediaProbeTest.java
git commit -m "feat: FfprobeMediaProbe for source duration/resolution

Refs #50"
```

---

### Task 12: TranscodeService (orchestration + cancel registry)

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeService.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeServiceTest.java`

**Interfaces:**
- Consumes: `MediaProbe`, `FfmpegCommandBuilder`, `ProgressParser`, `ProcessRunner`, `RunningTranscode`, `DownloadQueueRepository`, `QualityProfileService`.
- Produces:
  - `void transcode(Long itemId)` — loads item, sets `TRANSCODING` + `transcodeStartedAt`, probes, builds args, starts process, registers `RunningTranscode` in a `ConcurrentHashMap<Long, RunningTranscode>`, streams progress (writes `progressPercent` only when the integer percent changes), on exit 0 sets `DONE`+`completedAt`+`progressPercent=100`, on non-zero sets `ERROR`+`transcodeError` (last ~20 stderr lines) and deletes the partial output file; always unregisters.
  - `boolean cancel(Long itemId)` — if registered, `cancel()` the process, return true; else false.

- [ ] **Step 1: Write the failing test**

```java
package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TranscodeServiceTest {

    @Mock DownloadQueueRepository queueRepo;
    @Mock MediaProbe mediaProbe;
    @Mock ProcessRunner processRunner;
    @Spy FfmpegCommandBuilder commandBuilder = new FfmpegCommandBuilder();
    @Spy ProgressParser progressParser = new ProgressParser();
    @InjectMocks TranscodeService service;

    private DownloadQueueItem item(Long id, String dest) {
        QualityProfile p = new QualityProfile();
        p.setCodec(QualityProfile.Codec.HEVC_QSV);
        p.setContainer(QualityProfile.Container.MKV);
        p.setResolutionCap(QualityProfile.ResolutionCap.KEEP);
        p.setAudioMode(QualityProfile.AudioMode.COPY);
        DownloadQueueItem i = new DownloadQueueItem();
        i.setId(id); i.setSourceFilePath("/movies/x.avi"); i.setDestFilePath(dest);
        i.setQualityProfile(p);
        i.setStatus(DownloadQueueItem.Status.QUEUED);
        return i;
    }

    @Test
    void success_setsDoneAndFullProgress(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("x.mkv");
        DownloadQueueItem it = item(1L, dest.toString());
        when(queueRepo.findById(1L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe("/movies/x.avi")).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> out = inv.getArgument(1);
            out.accept("out_time_us=30000000"); // 50%
            out.accept("progress=end");
            return new RunningTranscode() {
                public int waitForExit() { return 0; }
                public void cancel() {}
            };
        });

        service.transcode(1L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
        assertThat(it.getProgressPercent()).isEqualTo(100);
        assertThat(it.getCompletedAt()).isNotNull();
    }

    @Test
    void failure_setsErrorAndDeletesPartialOutput(@TempDir Path tmp) throws Exception {
        Path dest = tmp.resolve("y.mkv");
        Files.writeString(dest, "partial");
        DownloadQueueItem it = item(2L, dest.toString());
        when(queueRepo.findById(2L)).thenReturn(Optional.of(it));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mediaProbe.probe(anyString())).thenReturn(new MediaInfo(60, 1920, 1080));
        when(processRunner.start(anyList(), any(), any())).thenAnswer(inv -> {
            Consumer<String> err = inv.getArgument(2);
            err.accept("Error: bad frame");
            return new RunningTranscode() {
                public int waitForExit() { return 1; }
                public void cancel() {}
            };
        });

        service.transcode(2L);

        assertThat(it.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(it.getTranscodeError()).contains("bad frame");
        assertThat(dest).doesNotExist();
    }

    @Test
    void cancel_unknownItem_returnsFalse() {
        assertThat(service.cancel(999L)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.TranscodeServiceTest'`
Expected: FAIL — `TranscodeService` missing.

- [ ] **Step 3: Write minimal implementation**

```java
package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeService {

    private static final int STDERR_TAIL = 20;

    private final DownloadQueueRepository queueRepo;
    private final MediaProbe mediaProbe;
    private final FfmpegCommandBuilder commandBuilder;
    private final ProgressParser progressParser;
    private final ProcessRunner processRunner;

    private final ConcurrentHashMap<Long, RunningTranscode> running = new ConcurrentHashMap<>();

    public void transcode(Long itemId) {
        DownloadQueueItem item = queueRepo.findById(itemId).orElse(null);
        if (item == null) { log.warn("Transcode skipped, item {} gone", itemId); return; }

        item.setStatus(DownloadQueueItem.Status.TRANSCODING);
        item.setTranscodeStartedAt(Instant.now());
        item.setProgressPercent(0);
        item.setTranscodeError(null);
        queueRepo.save(item);

        MediaInfo info = mediaProbe.probe(item.getSourceFilePath());
        List<String> cmd = commandBuilder.build(item.getQualityProfile(),
            item.getSourceFilePath(), item.getDestFilePath(), info);

        try {
            Files.createDirectories(Path.of(item.getDestFilePath()).getParent());
        } catch (IOException e) {
            failItem(item, "Could not create output dir: " + e.getMessage());
            return;
        }

        Deque<String> stderrTail = new ArrayDeque<>();
        final int[] lastPct = { -1 };

        RunningTranscode rt = processRunner.start(cmd,
            line -> {
                OptionalInt pct = progressParser.percentFor(line, info.durationSeconds());
                if (pct.isPresent() && pct.getAsInt() != lastPct[0]) {
                    lastPct[0] = pct.getAsInt();
                    persistProgress(itemId, pct.getAsInt());
                }
            },
            line -> {
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    while (stderrTail.size() > STDERR_TAIL) stderrTail.removeFirst();
                }
            });

        running.put(itemId, rt);
        int exit;
        try {
            exit = rt.waitForExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = -1;
        } finally {
            running.remove(itemId);
        }

        DownloadQueueItem fresh = queueRepo.findById(itemId).orElse(null);
        if (fresh == null) return; // cancelled + deleted
        if (exit == 0) {
            fresh.setStatus(DownloadQueueItem.Status.DONE);
            fresh.setProgressPercent(100);
            fresh.setCompletedAt(Instant.now());
            queueRepo.save(fresh);
            log.info("Transcode done: item={}", itemId);
        } else {
            String tail;
            synchronized (stderrTail) { tail = String.join("\n", stderrTail); }
            deletePartial(fresh.getDestFilePath());
            failItem(fresh, "ffmpeg exit " + exit + (tail.isBlank() ? "" : ": " + tail));
        }
    }

    public boolean cancel(Long itemId) {
        RunningTranscode rt = running.get(itemId);
        if (rt == null) return false;
        rt.cancel();
        return true;
    }

    private void persistProgress(Long itemId, int pct) {
        queueRepo.findById(itemId).ifPresent(i -> {
            i.setProgressPercent(pct);
            queueRepo.save(i);
        });
    }

    private void failItem(DownloadQueueItem item, String message) {
        item.setStatus(DownloadQueueItem.Status.ERROR);
        item.setTranscodeError(message);
        item.setErrorMessage("Transcoding failed");
        queueRepo.save(item);
        log.error("Transcode failed: item={} {}", item.getId(), message);
    }

    private void deletePartial(String dest) {
        if (dest == null) return;
        try {
            Files.deleteIfExists(Path.of(dest));
        } catch (IOException e) {
            log.warn("Could not delete partial output {}: {}", dest, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.TranscodeServiceTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeService.java \
        backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeServiceTest.java
git commit -m "feat: TranscodeService orchestrates ffmpeg with progress + cancel

Refs #50"
```

---

## Phase 3 — Worker

### Task 13: TranscodeQueueRunner (semaphore, event listener, startup recovery) + fix PlaylistSyncService

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunner.java`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/PlaylistSyncService.java` (only if it referenced removed Tdarr types or old enqueue signatures — adjust calls to new `enqueueMovie/Episode(..., qualityProfileId=null)` and `Status` names)
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java` (add finder used by recovery, if absent)
- Test: `backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeQueueRunnerTest.java`

**Interfaces:**
- Consumes: `TranscodeService.transcode`, `DownloadQueueRepository`, `SettingsService`, `TranscodeRequestedEvent`.
- Produces:
  - `TranscodeQueueRunner` with:
    - `@EventListener void onRequested(TranscodeRequestedEvent e)` → `submit(e.itemId())`.
    - `void submit(Long itemId)` → executor task acquires a permit from `Semaphore(maxConcurrent)`, calls `transcodeService.transcode(itemId)`, releases.
    - `@PostConstruct void recover()` → reset rows in `TRANSCODING` to `QUEUED` (interrupted by restart), then submit all `QUEUED` ordered by `queuePosition`.
    - max concurrency read from `transcode.max.concurrent` (default 2) at construction.
  - `DownloadQueueRepository.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status status)` and `findByStatus(...)` finders (add if missing).

> Compile note: this task removes the last references to the old enqueue signatures. If `./gradlew test` failed at Task 6 Step 10 because of `PlaylistSyncService`, complete that fix here (it likely calls `enqueueMovie(id, user, playlistId)` — that overload still exists, so it may need no change beyond `Status` enum renames if it references them).

- [ ] **Step 1: Add repository finders**

In `DownloadQueueRepository`, add (if not present):
```java
    List<DownloadQueueItem> findByStatus(DownloadQueueItem.Status status);
    List<DownloadQueueItem> findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status status);
```

- [ ] **Step 2: Write the failing test**

```java
package org.lolobored.plexdownloader.transcode;

import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TranscodeQueueRunnerTest {

    @Mock DownloadQueueRepository queueRepo;
    @Mock TranscodeService transcodeService;
    @Mock SettingsService settings;

    private TranscodeQueueRunner runner() {
        when(settings.get("transcode.max.concurrent")).thenReturn(Optional.of("2"));
        return new TranscodeQueueRunner(queueRepo, transcodeService, settings);
    }

    @Test
    void submit_invokesTranscode() {
        TranscodeQueueRunner r = runner();
        r.submit(5L);
        await().atMost(ofSeconds(2)).untilAsserted(() ->
            verify(transcodeService).transcode(5L));
    }

    @Test
    void recover_resetsTranscodingAndResubmitsQueued() {
        DownloadQueueItem stuck = new DownloadQueueItem();
        stuck.setId(1L); stuck.setStatus(DownloadQueueItem.Status.TRANSCODING);
        DownloadQueueItem queued = new DownloadQueueItem();
        queued.setId(2L); queued.setStatus(DownloadQueueItem.Status.QUEUED);

        when(queueRepo.findByStatus(DownloadQueueItem.Status.TRANSCODING)).thenReturn(List.of(stuck));
        when(queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.QUEUED))
            .thenReturn(List.of(stuck, queued));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TranscodeQueueRunner r = runner();
        r.recover();

        assertThat(stuck.getStatus()).isEqualTo(DownloadQueueItem.Status.QUEUED);
        await().atMost(ofSeconds(2)).untilAsserted(() -> {
            verify(transcodeService).transcode(1L);
            verify(transcodeService).transcode(2L);
        });
    }
}
```

> Add `testImplementation 'org.awaitility:awaitility'` to `backend/build.gradle` dependencies if not already present (Spring Boot manages the version). If you prefer no new dependency, replace the `await()` blocks with `verify(transcodeService, timeout(2000)).transcode(...)` (Mockito's built-in `timeout`) — use that form to avoid adding a dependency.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.TranscodeQueueRunnerTest'`
Expected: FAIL — `TranscodeQueueRunner` missing.

- [ ] **Step 4: Write minimal implementation**

```java
package org.lolobored.plexdownloader.transcode;

import jakarta.annotation.PostConstruct;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.lolobored.plexdownloader.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
public class TranscodeQueueRunner {

    private final DownloadQueueRepository queueRepo;
    private final TranscodeService transcodeService;
    private final Semaphore permits;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "transcode-worker");
        t.setDaemon(true);
        return t;
    });

    public TranscodeQueueRunner(DownloadQueueRepository queueRepo,
                                TranscodeService transcodeService,
                                SettingsService settings) {
        this.queueRepo = queueRepo;
        this.transcodeService = transcodeService;
        int max = parseMax(settings.get("transcode.max.concurrent").orElse("2"));
        this.permits = new Semaphore(max);
        log.info("Transcode worker: max concurrent = {}", max);
    }

    private int parseMax(String v) {
        try { return Math.max(1, Integer.parseInt(v.trim())); }
        catch (NumberFormatException e) { return 2; }
    }

    @EventListener
    public void onRequested(TranscodeRequestedEvent e) {
        submit(e.itemId());
    }

    public void submit(Long itemId) {
        pool.submit(() -> {
            try {
                permits.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                transcodeService.transcode(itemId);
            } catch (Exception ex) {
                log.error("Transcode worker error for item {}: {}", itemId, ex.getMessage(), ex);
            } finally {
                permits.release();
            }
        });
    }

    @PostConstruct
    public void recover() {
        for (DownloadQueueItem stuck : queueRepo.findByStatus(DownloadQueueItem.Status.TRANSCODING)) {
            stuck.setStatus(DownloadQueueItem.Status.QUEUED);
            stuck.setProgressPercent(null);
            queueRepo.save(stuck);
            log.info("Recovered interrupted transcode: item={}", stuck.getId());
        }
        for (DownloadQueueItem q : queueRepo.findByStatusOrderByQueuePositionAsc(DownloadQueueItem.Status.QUEUED)) {
            submit(q.getId());
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.TranscodeQueueRunnerTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Wire worker-aware cancel into DownloadService**

Now that `TranscodeService.cancel` exists, update `DownloadService.cancel(...)`: inject `TranscodeService`; when an item is `TRANSCODING`, call `transcodeService.cancel(id)` (force-kills ffmpeg) before `doCancelItem`, instead of throwing 409. Replace the guard added in Task 6 Step 4:
```java
        if (item.getStatus() == DownloadQueueItem.Status.TRANSCODING) {
            transcodeService.cancel(itemId);
        }
        doCancelItem(item);
```
Add a `DownloadServiceTest` case asserting `transcodeService.cancel(id)` is invoked for a `TRANSCODING` item and the row is deleted.

- [ ] **Step 7: Run full suite**

Run: `./gradlew test`
Expected: PASS — whole backend green.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: TranscodeQueueRunner worker with semaphore, recovery, event submission

Refs #50"
```

---

## Phase 4 — API surface

### Task 14: QualityProfile controller (admin CRUD + user-readable list)

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/controller/QualityProfileController.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/dto/QualityProfileRequest.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/controller/QualityProfileControllerTest.java`

**Interfaces:**
- Produces endpoints:
  - `GET /api/quality-profiles` (any authenticated user) → `List<QualityProfile>` for the picker.
  - `POST /api/admin/quality-profiles` (ADMIN) → create.
  - `PUT /api/admin/quality-profiles/{id}` (ADMIN) → update.
  - `DELETE /api/admin/quality-profiles/{id}` (ADMIN).
  - `PUT /api/admin/quality-profiles/{id}/default` (ADMIN) → set default.
  - `QualityProfileRequest(String name, Codec codec, Container container, int qualityLevel, ResolutionCap resolutionCap, AudioMode audioMode)`.

Follow the existing controller test style (`@WebMvcTest` or the project's existing controller-test approach — match `AdminControllerTest`). Mock `QualityProfileService`.

- [ ] **Step 1: Inspect existing controller test style**

Run: `sed -n '1,60p' backend/src/test/java/org/lolobored/plexdownloader/controller/AdminControllerTest.java`
Use the same annotations/security-setup it uses (the project already has a working pattern for ADMIN-secured endpoints).

- [ ] **Step 2: Write the failing test**

```java
package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.service.QualityProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QualityProfileControllerTest {

    @Mock QualityProfileService service;
    @InjectMocks QualityProfileController controller;

    @Test
    void list_returnsProfiles() {
        QualityProfile p = new QualityProfile(); p.setId(1L); p.setName("Default");
        when(service.findAll()).thenReturn(List.of(p));
        assertThat(controller.list()).hasSize(1);
    }

    @Test
    void create_delegatesToService() {
        QualityProfileRequest req = new QualityProfileRequest("HD", QualityProfile.Codec.HEVC_QSV,
            QualityProfile.Container.MKV, 22, QualityProfile.ResolutionCap.P1080, QualityProfile.AudioMode.COPY);
        when(service.create(any())).thenAnswer(inv -> inv.getArgument(0));
        QualityProfile created = controller.create(req);
        assertThat(created.getName()).isEqualTo("HD");
        assertThat(created.getQualityLevel()).isEqualTo(22);
    }

    @Test
    void setDefault_delegates() {
        QualityProfile p = new QualityProfile(); p.setId(3L); p.setDefault(true);
        when(service.setDefault(3L)).thenReturn(p);
        assertThat(controller.setDefault(3L).isDefault()).isTrue();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.controller.QualityProfileControllerTest'`
Expected: FAIL — classes missing.

- [ ] **Step 4: Write minimal implementation**

`QualityProfileRequest.java`:
```java
package org.lolobored.plexdownloader.dto;

import org.lolobored.plexdownloader.model.QualityProfile;

public record QualityProfileRequest(
    String name,
    QualityProfile.Codec codec,
    QualityProfile.Container container,
    int qualityLevel,
    QualityProfile.ResolutionCap resolutionCap,
    QualityProfile.AudioMode audioMode
) {
    public QualityProfile toEntity() {
        QualityProfile p = new QualityProfile();
        p.setName(name);
        p.setCodec(codec);
        p.setContainer(container);
        p.setQualityLevel(qualityLevel);
        p.setResolutionCap(resolutionCap);
        p.setAudioMode(audioMode);
        return p;
    }
}
```

`QualityProfileController.java`:
```java
package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.QualityProfileRequest;
import org.lolobored.plexdownloader.model.QualityProfile;
import org.lolobored.plexdownloader.service.QualityProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class QualityProfileController {

    private final QualityProfileService service;

    @GetMapping("/api/quality-profiles")
    public List<QualityProfile> list() {
        return service.findAll();
    }

    @PostMapping("/api/admin/quality-profiles")
    @PreAuthorize("hasRole('ADMIN')")
    public QualityProfile create(@RequestBody QualityProfileRequest req) {
        return service.create(req.toEntity());
    }

    @PutMapping("/api/admin/quality-profiles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public QualityProfile update(@PathVariable Long id, @RequestBody QualityProfileRequest req) {
        return service.update(id, req.toEntity());
    }

    @DeleteMapping("/api/admin/quality-profiles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PutMapping("/api/admin/quality-profiles/{id}/default")
    @PreAuthorize("hasRole('ADMIN')")
    public QualityProfile setDefault(@PathVariable Long id) {
        return service.setDefault(id);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'org.lolobored.plexdownloader.controller.QualityProfileControllerTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Run full suite + commit**

Run: `./gradlew test`
Expected: PASS.
```bash
git add backend/src/main/java/org/lolobored/plexdownloader/controller/QualityProfileController.java \
        backend/src/main/java/org/lolobored/plexdownloader/dto/QualityProfileRequest.java \
        backend/src/test/java/org/lolobored/plexdownloader/controller/QualityProfileControllerTest.java
git commit -m "feat: quality profile REST endpoints (admin CRUD + user list)

Refs #50"
```

---

## Phase 5 — Frontend

> Frontend tasks are verified by the existing Vitest store tests where applicable and by a manual smoke test (`npm run dev` against the backend) since Vue components have no unit harness for the new UI. Run `cd frontend && npm run test` after store changes; run `npm run build` to confirm no compile/type errors after each task.

### Task 15: Frontend API layer — profiles, retry, enqueue with profile

**Files:**
- Modify: `frontend/src/api/download.js`
- Inspect/Modify: `frontend/src/api/admin.js` (or wherever admin calls live — confirm with `ls frontend/src/api`)
- Test: `frontend/src/stores/__tests__/download.*` (extend if present)

- [ ] **Step 1: Confirm api layer shape**

Run: `ls frontend/src/api && sed -n '1,80p' frontend/src/api/download.js`
Note the exact axios/client import and existing `getQueue`/`enqueue` signatures.

- [ ] **Step 2: Extend `download.js`**

Add (matching the file's existing client/import style):
```js
// enqueue now accepts an optional qualityProfileId
export async function enqueue(type, id, qualityProfileId = null) {
  await client.post('/api/download', { type, id, qualityProfileId })
}

export async function retry(itemId) {
  await client.post(`/api/download/${itemId}/retry`)
}

export async function getQualityProfiles() {
  const { data } = await client.get('/api/quality-profiles')
  return data
}
```
(Use the same HTTP client variable already imported at the top of the file — do not introduce a new axios import.)

- [ ] **Step 3: Add admin profile CRUD calls**

In the admin api module, add `createQualityProfile`, `updateQualityProfile`, `deleteQualityProfile`, `setDefaultQualityProfile` hitting the Task 14 endpoints, following the module's existing call style.

- [ ] **Step 4: Update the download store**

In `frontend/src/stores/download.js`, change `enqueue` to forward a profile id and add `retry`:
```js
  async function enqueue(type, id, qualityProfileId = null) {
    await apiEnqueue(type, id, qualityProfileId)
    await fetchQueue()
  }

  async function retry(itemId) {
    await apiRetry(itemId)
    await fetchQueue()
  }
```
Import `retry as apiRetry` from the api module and export `retry` from the store.

- [ ] **Step 5: Verify build + store tests**

Run: `cd frontend && npm run test && npm run build`
Expected: existing tests pass; build succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/download.js frontend/src/api/admin.js frontend/src/stores/download.js
git commit -m "feat: frontend api/store for quality profiles, retry, enqueue profile

Refs #50"
```

---

### Task 16: QueueView progress bar + retry button; status rename

**Files:**
- Modify: `frontend/src/views/QueueView.vue`

**Interfaces:**
- Consumes: queue items now expose `status` (`QUEUED|TRANSCODING|DONE|ERROR`), `progressPercent`, `transcodeError`, `qualityProfileName` (Task 6 DTO).

- [ ] **Step 1: Update status handling**

Replace any references to the old `status`/`tdarrStatus` values and badges with the new `Status` set. Where the row previously showed Tdarr state, show:
- `QUEUED` → "Queued" badge.
- `TRANSCODING` → progress bar bound to `item.progressPercent` (fallback 0), e.g.:
```html
<div v-if="item.status === 'TRANSCODING'" class="progress">
  <div class="progress-bar" :style="{ width: (item.progressPercent ?? 0) + '%' }"></div>
  <span class="progress-label">{{ item.progressPercent ?? 0 }}%</span>
</div>
```
- `DONE` → "Done" badge (green), show output filename if displayed today.
- `ERROR` → "Error" badge (red) + `title="item.transcodeError"`, plus a Retry button calling `dlStore.retry(item.id)`.

Add minimal CSS for `.progress`/`.progress-bar` consistent with the existing stylesheet. Remove the Tdarr-refresh button/handler.

- [ ] **Step 2: Manual smoke test**

Run backend + `cd frontend && npm run dev`. Queue an item; confirm: badge shows Queued → Transcoding with a moving bar (polling every 2 s) → Done; an errored item shows Retry and re-queues on click.

- [ ] **Step 3: Build + commit**

Run: `cd frontend && npm run build`
Expected: success.
```bash
git add frontend/src/views/QueueView.vue
git commit -m "feat: queue transcode progress bar, retry button, new status badges

Refs #50"
```

---

### Task 17: Settings page — quality profiles + max concurrent (replace Tdarr section)

**Files:**
- Modify: `frontend/src/views/SettingsView.vue`

- [ ] **Step 1: Remove the Tdarr section**

Delete the Tdarr server URL / API key / test-connection / sync-cron UI and its handlers/state.

- [ ] **Step 2: Add quality-profiles management**

Add a "Transcoding" `<section class="card-section">` that:
- Loads profiles via `getQualityProfiles()` on mount.
- Lists profiles with their fields; lets admin create/edit (name, codec, container, quality level, resolution cap, audio mode) via the admin api calls; mark one default (`setDefaultQualityProfile`); delete.
- Has a "Max concurrent transcodes" number input bound to setting `transcode.max.concurrent`, saved through the existing `PUT /api/admin/settings` flow (the settings map now includes `transcode.max.concurrent`).

Match the existing `SettingsView` markup/validation conventions (the file already has `card-section`, `field`, `btn-save`, `ok`/`error-inline` patterns).

- [ ] **Step 3: Manual smoke test**

`npm run dev`: create a profile, set it default, change max concurrent, save; reload and confirm persistence.

- [ ] **Step 4: Build + commit**

Run: `cd frontend && npm run build`
Expected: success.
```bash
git add frontend/src/views/SettingsView.vue
git commit -m "feat: settings quality-profile CRUD and max-concurrent, drop Tdarr UI

Refs #50"
```

---

### Task 18: Profile picker on DownloadButton / SubscribeButton

**Files:**
- Modify: `frontend/src/components/DownloadButton.vue`
- Modify: `frontend/src/components/SubscribeButton.vue`

- [ ] **Step 1: Add optional profile selection to DownloadButton**

Load profiles (via the store or a shared composable; reuse `getQualityProfiles()`), present an optional dropdown defaulting to the default profile; pass the chosen id to `dlStore.enqueue(props.type, props.mediaId, selectedProfileId)`. If only one profile exists, hide the dropdown and pass `null` (server uses default).

- [ ] **Step 2: Mirror on SubscribeButton if it enqueues**

If `SubscribeButton` triggers enqueue/subscription that flows into the queue, give it the same optional picker; otherwise leave subscription using the global default (playlist items resolve to the default server-side).

- [ ] **Step 3: Manual smoke test + build**

`npm run dev`: queue a movie with a non-default profile; confirm the queued item's `qualityProfileName` reflects the choice and output extension matches the container.
Run: `cd frontend && npm run build` → success.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/DownloadButton.vue frontend/src/components/SubscribeButton.vue
git commit -m "feat: optional quality-profile picker on download/subscribe

Refs #50"
```

---

## Phase 6 — Infra + docs

### Task 19: Debian runtime image + ffmpeg/QSV + entrypoint

**Files:**
- Modify: `Dockerfile`
- Modify: `docker-entrypoint.sh`

- [ ] **Step 1: Switch the runtime stage**

Replace stage 3 of `Dockerfile`:
```dockerfile
# ── Stage 3: runtime image (Debian) with PostgreSQL + ffmpeg + Intel QSV ──────
FROM eclipse-temurin:21-jre-jammy

# PostgreSQL, ffmpeg, Intel media stack (QSV via oneVPL), gosu for privilege drop
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql postgresql-contrib \
        ffmpeg \
        intel-media-va-driver-non-free \
        libmfx-gen1.2 libvpl2 vainfo \
        gosu \
        wget \
    && rm -rf /var/lib/apt/lists/*

ENV PGDATA=/var/lib/postgresql/data
RUN mkdir -p "$PGDATA" /run/postgresql \
    && chown -R postgres:postgres "$PGDATA" /run/postgresql

WORKDIR /app
COPY --from=backend-build /app/build/libs/*.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

VOLUME ["/var/lib/postgresql/data"]
EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint.sh"]
```
> Package names: `libmfx-gen1.2` + `libvpl2` provide the oneVPL runtime on Ubuntu 22.04 (jammy) for recent Intel iGPUs; `intel-media-va-driver-non-free` provides VAAPI. If a package name is unavailable for the base, verify with `apt-cache search vpl` during the build-verify step and adjust. The Postgres major version on jammy is 14; `pg_ctl`/`initdb`/`psql` are on `PATH` via the `postgresql` metapackage's wrapper, so entrypoint commands need no version path.

- [ ] **Step 2: Update `docker-entrypoint.sh`**

Replace every `su-exec` with `gosu`. Update the conversion-dir bootstrap section (no more `in-flight` needed, but keeping it is harmless; the worker writes to `libraries`):
```sh
mkdir -p /plex-conversion/libraries
chmod -R 777 /plex-conversion/libraries
```
Before `exec java -jar /app/app.jar`, add a non-fatal QSV check:
```sh
echo "[boot] Checking Intel QSV / VAAPI availability..."
vainfo 2>&1 | head -n 20 || echo "[warn] vainfo failed — QSV may be unavailable (check /dev/dri passthrough)"
```
Update the `umask` comment (no longer about Tdarr — output written by this container directly).

- [ ] **Step 3: Build the image to verify it assembles**

Run: `docker build -t plex-downloader:qsv-test .`
Expected: build succeeds; all apt packages resolve. If a media package name fails, adjust per the note in Step 1 and rebuild.

- [ ] **Step 4: Commit**

```bash
git add Dockerfile docker-entrypoint.sh
git commit -m "build: Debian runtime with ffmpeg + Intel QSV, gosu, vainfo check

Refs #50"
```

---

### Task 20: docker-compose `/dev/dri` passthrough + README

**Files:**
- Modify: `docker-compose.yml`
- Modify: `README.md`

- [ ] **Step 1: Add device passthrough to compose**

Under the `plex-downloader` service add:
```yaml
    devices:
      - /dev/dri:/dev/dri
    group_add:
      - "render"
```
Remove any Tdarr-related env/comments. Keep `MOVIES_PATH`/`TV_PATH`/`PLEX_CONVERSION_PATH` volumes unchanged.

- [ ] **Step 2: Update README**

- Replace the description line ("automatically transcode with Tdarr") with embedded QuickSync transcoding.
- Remove the Tdarr prerequisite and the `Tdarr server URL` settings bullet.
- Add prerequisites: an Intel CPU with QuickSync and a host `/dev/dri` render device; note the compose `devices`/`group_add` requirement.
- Remove `PLEX_CONVERSION_PATH` "must match Tdarr mounts" wording; state output files land under `PLEX_CONVERSION_PATH/libraries` for retrieval.
- Add a "Transcoding" settings bullet: quality profiles + max concurrent.

- [ ] **Step 3: Validate compose syntax**

Run: `docker compose config >/dev/null && echo OK`
Expected: `OK`.

- [ ] **Step 4: Commit (final — closes the issue)**

```bash
git add docker-compose.yml README.md
git commit -m "docs: QSV setup, /dev/dri passthrough, drop Tdarr references

closes #50"
```

---

### Task 21: Full verification pass

- [ ] **Step 1: Backend suite green**

Run: `cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew test`
Expected: BUILD SUCCESSFUL, no skipped-by-error tests, no Tdarr references.

- [ ] **Step 2: Frontend build + tests green**

Run: `cd frontend && npm run test && npm run build`
Expected: pass.

- [ ] **Step 3: End-to-end on the NUC (manual)**

Bring up the container with `/dev/dri` mapped. Confirm in logs `vainfo` lists the iGPU. Queue a movie; watch the queue progress bar advance; confirm a transcoded file appears under `PLEX_CONVERSION_PATH/libraries/...` with the chosen container extension and is retrievable from the laptop. Force an error (e.g. unreadable source) and confirm ERROR + working Retry.

- [ ] **Step 4: No commit (verification only).** If issues surface, fix under #50 with `Refs #50` commits.

---

## Self-Review

**Spec coverage:**
- §2 decisions → Tasks 1 (profile/codec/container), 9 (QSV args), 14/17/18 (global+per-item quality), 19/20 (Debian+QSV+/dev/dri), 13 (concurrency=2 setting), 6/13 (ERROR + manual retry). ✓
- §4.1 Docker → Tasks 19, 20. ✓
- §4.2 data model (collapsed Status, new columns, QualityProfile, migration, settings) → Tasks 1, 3, 4, 7; setting `transcode.max.concurrent` in Tasks 6 (admin getSettings) + 13 (consumed). ✓
- §4.3 engine (command builder, progress parser, probe, process runner, service) → Tasks 8–12. ✓
- §4.4 worker (semaphore, recovery, retry, cancel) → Tasks 13 (+6 retry seam, +13 cancel wiring). ✓
- §4.5 API/frontend (DTO progress, settings UI, per-item picker, endpoints) → Tasks 6, 14, 15–18. ✓
- §4.6 removed (Tdarr classes/tests/settings/UI/README/copy logic) → Tasks 5, 6, 16, 17, 20. ✓
- §5 testing (4 pure units + collaborator-level) → Tasks 1/2/8/9/11 (pure), 12/13 (collaborator), 6 (adapted DownloadService). ✓

**Placeholder scan:** No TBD/TODO. Two intentional verify-by-adjust notes (H2 `LIMIT 1` fallback in Task 7; apt media-package name check in Task 19) include the exact alternative to switch to — not open-ended. ✓

**Type consistency:** `Status{QUEUED,TRANSCODING,DONE,ERROR}`, `QualityProfile` enum helper names (`ffmpegName`/`extension`/`maxHeight`), `MediaInfo(durationSeconds,width,height)`, `ProcessRunner.start(...)→RunningTranscode{waitForExit,cancel}`, `ProgressParser.percentFor(line,durationSeconds)→OptionalInt`, `TranscodeRequestedEvent(itemId)`, `resolveOrDefault(Long)` used identically across Tasks 2/6/12. Repository finders `findByStatus`/`findByStatusOrderByQueuePositionAsc` defined in Task 13 before use. ✓
