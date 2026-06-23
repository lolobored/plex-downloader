# Subtitle Awareness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect and store subtitle languages for source (Movie/Episode) and transcoded-output (DownloadQueueItem) files, via a background scan + capture-on-transcode, and let the user filter by subtitle status ("no subtitles" / by-language) in the Movies, TV Shows, and Queue views.

**Architecture:** A `SubtitleProbe` (ffprobe `-select_streams s`) yields per-file subtitle languages stored as a comma-padded CSV string plus a `scanned_at` timestamp (null = unknown). The transcode DONE path captures the output's subtitles inline; a throttled `SubtitleScanService` (scheduled + manual) backfills source files and pre-existing outputs. List queries gain DB-side subtitle filters; the frontend shows a badge + hover languages and filter controls; Settings gets a scan section.

**Tech Stack:** Spring Boot (Java 21), Liquibase, JPA/Hibernate, JUnit5 + Mockito + AssertJ + H2; Vue 3 + Pinia (plain JS); ffprobe via jellyfin-ffmpeg (path from `TranscodeConfig.ffprobe()`).

## Global Constraints

- Java toolchain `JavaLanguageVersion.of(21)`; SDKMAN java `21.0.4-tem`. Before any gradle: `source "$HOME/.sdkman/bin/sdkman-init.sh"; sdk use java 21.0.4-tem; cd /Users/laurentlaborde/projects/plex-downloader/backend && ./gradlew ...` (use `;` before the `cd && ./gradlew` to avoid a zsh completion error).
- Backend tests: `./gradlew test`. Frontend (from `frontend/`): `npm run test`, `npm run build`. No real ffprobe in tests — go through the `ProcessRunner` interface (stub it).
- Liquibase YAML under `backend/src/main/resources/db/changelog/yaml/`, auto-included; raw-`sql` changeset style; H2 + PostgreSQL compatible. Latest existing is `016-*`; add `017`, `018`.
- **CSV storage format:** subtitle languages are stored **comma-padded** — `","` for none-but-scanned, `",eng,fra,"` for two streams. This makes "has lang X" a clean `LIKE '%,X,%'` with no substring false-positives (`eng` never matches `enga`). `subtitles_scanned_at == null` means unknown/not-scanned.
- Untagged subtitle stream language → `und`. A probe that fails (missing/unreadable file) → leave the row UNSCANNED (do not store `","`); count as failed.
- Commit against issue #77. Every commit body ends with `Refs #77`.
- Package root `org.lolobored.plexdownloader`.

---

## File Structure

**Backend — new:**
- `transcode/SubtitleProbe.java` — ffprobe subtitle-language probe + pure parse.
- `service/SubtitleScanService.java` — throttled background scan + status.
- `service/SubtitleScanScheduler.java` — cron trigger + manual `@Async` start.
- `controller/SubtitleScanController.java` — admin scan/status endpoints.
- `util/SubtitleLangs.java` — pure helpers: list↔comma-padded CSV, and JPQL token for filters.
- `dto/SubtitleScanStatus.java` — record.
- `db/changelog/yaml/017-source-subtitles.yaml`, `018-output-subtitles.yaml`.

**Backend — modified:**
- `model/Movie.java`, `model/Episode.java`, `model/DownloadQueueItem.java` — add fields.
- `transcode/TranscodeService.java` — capture output subtitles on DONE.
- `repository/MovieRepository.java`, `EpisodeRepository.java`, `DownloadQueueRepository.java` — filtered finders + unscanned finders.
- `dto/DownloadQueueItemResponse.java` + `service/DownloadService.getQueue` — source+output subtitle fields (source joined from Movie/Episode).
- Movies/TV list controllers + their response DTOs — subtitle fields + filter params.
- `controller/AdminController.java` (or wherever `getSettings` lives) — `subtitles.scan.cron` exposure (optional).

**Frontend — new:**
- `components/SubtitleBadge.vue` — badge + hover languages.
**Frontend — modified:**
- `views/MoviesView.vue`, `views/TvShowDetailView.vue`/`SeasonDetailView.vue` (episode lists), `views/QueueView.vue` — badge + filter controls.
- `views/SettingsView.vue` — Subtitles section.
- `api/admin.js`, `api/movies`/`api/library.js`, `api/download.js` — scan + filter params.

---

## Phase 1 — Detection core

### Task 1: SubtitleLangs util (pure CSV helpers)

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/util/SubtitleLangs.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/util/SubtitleLangsTest.java`

**Interfaces:**
- Produces: `SubtitleLangs.toCsv(List<String> langs) -> String` (comma-padded: `[]`→`","`, `["eng","fra"]`→`",eng,fra,"`, lowercased, blanks→`und`, order preserved, dups kept); `SubtitleLangs.fromCsv(String csv) -> List<String>` (strip pad → list; `","`/null/blank → empty); `SubtitleLangs.token(String lang) -> String` (`",eng,"` for LIKE).

- [ ] **Step 1: Write the failing test**

```java
package org.lolobored.plexdownloader.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SubtitleLangsTest {
    @Test void toCsv_empty_isCommaOnly() {
        assertThat(SubtitleLangs.toCsv(List.of())).isEqualTo(",");
    }
    @Test void toCsv_padsAndLowercases() {
        assertThat(SubtitleLangs.toCsv(List.of("eng", "FRA"))).isEqualTo(",eng,fra,");
    }
    @Test void toCsv_blankBecomesUnd() {
        assertThat(SubtitleLangs.toCsv(java.util.Arrays.asList("eng", "", null))).isEqualTo(",eng,und,und,");
    }
    @Test void fromCsv_roundTrips() {
        assertThat(SubtitleLangs.fromCsv(",eng,fra,")).containsExactly("eng", "fra");
        assertThat(SubtitleLangs.fromCsv(",")).isEmpty();
        assertThat(SubtitleLangs.fromCsv(null)).isEmpty();
    }
    @Test void token_isCommaPadded() {
        assertThat(SubtitleLangs.token("ENG")).isEqualTo(",eng,");
    }
}
```

- [ ] **Step 2: Run, verify FAIL** — `./gradlew test --tests 'org.lolobored.plexdownloader.util.SubtitleLangsTest'` → FAIL (class missing).

- [ ] **Step 3: Implement**

```java
package org.lolobored.plexdownloader.util;

import java.util.ArrayList;
import java.util.List;

/** Subtitle languages stored comma-padded: "," = none, ",eng,fra," = two streams. */
public final class SubtitleLangs {
    private SubtitleLangs() {}

    public static String toCsv(List<String> langs) {
        if (langs == null || langs.isEmpty()) return ",";
        StringBuilder sb = new StringBuilder(",");
        for (String l : langs) {
            String v = (l == null || l.isBlank()) ? "und" : l.trim().toLowerCase();
            sb.append(v).append(',');
        }
        return sb.toString();
    }

    public static List<String> fromCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            if (!p.isBlank()) out.add(p.trim().toLowerCase());
        }
        return out;
    }

    /** Comma-padded token for a `LIKE '%' || token || '%'` containment check. */
    public static String token(String lang) {
        return "," + (lang == null ? "" : lang.trim().toLowerCase()) + ",";
    }
}
```

- [ ] **Step 4: Run, verify PASS.**
- [ ] **Step 5: Commit** — `git add backend/src/main/java/org/lolobored/plexdownloader/util/SubtitleLangs.java backend/src/test/java/org/lolobored/plexdownloader/util/SubtitleLangsTest.java && git commit -m "feat: SubtitleLangs comma-padded CSV helpers

Refs #77"`

---

### Task 2: SubtitleProbe (ffprobe subtitle languages)

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/transcode/SubtitleProbe.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/transcode/SubtitleProbeTest.java`

**Interfaces:**
- Consumes: `ProcessRunner` (Task exists), `TranscodeConfig.ffprobe()`, `SubtitleLangs` (Task 1).
- Produces: `SubtitleProbe.probe(String filePath) -> ProbeResult`; `record ProbeResult(boolean ok, List<String> langs)` (ok=false when the ffprobe exit code != 0 → caller leaves row unscanned). Pure `static List<String> parse(List<String> lines)`.
- ffprobe: `ffprobe -v error -select_streams s -show_entries stream_tags=language -of default=noprint_wrappers=1 <file>` → lines like `TAG:language=eng` (untagged streams emit no language line, OR `TAG:language=` empty). To count untagged streams too, ALSO request `-show_entries stream=index:stream_tags=language` so every subtitle stream emits an `index=` line; map each `index=` to the `language=` that follows (or `und` if none).

- [ ] **Step 1: Write the failing test** (parse is the unit; probe wiring uses a stub ProcessRunner)

```java
package org.lolobored.plexdownloader.transcode;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SubtitleProbeTest {
    @Test void parse_twoStreamsWithLangs() {
        List<String> out = SubtitleProbe.parse(List.of(
            "index=2", "TAG:language=eng",
            "index=3", "TAG:language=fra"));
        assertThat(out).containsExactly("eng", "fra");
    }
    @Test void parse_untaggedStreamBecomesUnd() {
        List<String> out = SubtitleProbe.parse(List.of("index=2"));
        assertThat(out).containsExactly("und");
    }
    @Test void parse_emptyLangBecomesUnd() {
        List<String> out = SubtitleProbe.parse(List.of("index=2", "TAG:language="));
        assertThat(out).containsExactly("und");
    }
    @Test void parse_noSubtitleStreams_empty() {
        assertThat(SubtitleProbe.parse(List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Run, verify FAIL** — `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.SubtitleProbeTest'` → FAIL.

- [ ] **Step 3: Implement**

```java
package org.lolobored.plexdownloader.transcode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Probes a media file for its subtitle stream languages via ffprobe. */
@Component
@RequiredArgsConstructor
public class SubtitleProbe {

    private final ProcessRunner processRunner;
    private final TranscodeConfig config;

    public record ProbeResult(boolean ok, List<String> langs) {}

    public ProbeResult probe(String filePath) {
        List<String> lines = new CopyOnWriteArrayList<>();
        List<String> cmd = List.of(
            config.ffprobe(), "-v", "error",
            "-select_streams", "s",
            "-show_entries", "stream=index:stream_tags=language",
            "-of", "default=noprint_wrappers=1",
            filePath);
        RunningTranscode rt = processRunner.start(cmd, lines::add, l -> {});
        int exit;
        try {
            exit = rt.waitForExit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, List.of());
        }
        if (exit != 0) return new ProbeResult(false, List.of());
        return new ProbeResult(true, parse(new ArrayList<>(lines)));
    }

    /** Each `index=` line starts a subtitle stream; the language is the following
     *  `TAG:language=` (or `und` if absent/blank before the next index). */
    static List<String> parse(List<String> lines) {
        List<String> langs = new ArrayList<>();
        boolean inStream = false;
        String pending = null;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.startsWith("index=")) {
                if (inStream) langs.add(pending == null || pending.isBlank() ? "und" : pending);
                inStream = true;
                pending = null;
            } else if (line.startsWith("TAG:language=")) {
                pending = line.substring("TAG:language=".length()).trim().toLowerCase();
            }
        }
        if (inStream) langs.add(pending == null || pending.isBlank() ? "und" : pending);
        return langs;
    }
}
```

- [ ] **Step 4: Run, verify PASS.**
- [ ] **Step 5: Commit** — `git add backend/src/main/java/org/lolobored/plexdownloader/transcode/SubtitleProbe.java backend/src/test/java/org/lolobored/plexdownloader/transcode/SubtitleProbeTest.java && git commit -m "feat: SubtitleProbe extracts subtitle languages via ffprobe

Refs #77"`

---

### Task 3: Entity fields + migrations

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/model/Movie.java`, `Episode.java`, `DownloadQueueItem.java`
- Create: `backend/src/main/resources/db/changelog/yaml/017-source-subtitles.yaml`, `018-output-subtitles.yaml`
- Test: verified by suite boot (Liquibase on H2).

**Interfaces:**
- Produces (Lombok `@Data` getters/setters): `Movie`/`Episode`: `String subtitleLangs` (`subtitle_langs`), `Instant subtitlesScannedAt` (`subtitles_scanned_at`). `DownloadQueueItem`: `String outputSubtitleLangs` (`output_subtitle_langs`), `Instant outputSubtitlesScannedAt` (`output_subtitles_scanned_at`). All nullable.

- [ ] **Step 1: Add fields to `Movie.java`** (after `imdbId`):
```java
    @Column(name = "subtitle_langs", columnDefinition = "TEXT")
    private String subtitleLangs;
    @Column(name = "subtitles_scanned_at")
    private java.time.Instant subtitlesScannedAt;
```
- [ ] **Step 2: Add the same two fields to `Episode.java`.**
- [ ] **Step 3: Add to `DownloadQueueItem.java`** (near compressionRatio):
```java
    @Column(name = "output_subtitle_langs", columnDefinition = "TEXT")
    private String outputSubtitleLangs;
    @Column(name = "output_subtitles_scanned_at")
    private java.time.Instant outputSubtitlesScannedAt;
```
- [ ] **Step 4: Create `017-source-subtitles.yaml`**
```yaml
databaseChangeLog:
  - changeSet:
      id: 017-source-subtitles
      author: plexdownloader
      changes:
        - sql: { sql: "ALTER TABLE movie ADD COLUMN subtitle_langs TEXT" }
        - sql: { sql: "ALTER TABLE movie ADD COLUMN subtitles_scanned_at TIMESTAMP" }
        - sql: { sql: "ALTER TABLE episode ADD COLUMN subtitle_langs TEXT" }
        - sql: { sql: "ALTER TABLE episode ADD COLUMN subtitles_scanned_at TIMESTAMP" }
```
(Verify the table names are `movie`/`episode` — check an existing changelog or `@Table`; adjust if they differ.)

- [ ] **Step 5: Create `018-output-subtitles.yaml`**
```yaml
databaseChangeLog:
  - changeSet:
      id: 018-output-subtitles
      author: plexdownloader
      changes:
        - sql: { sql: "ALTER TABLE download_queue ADD COLUMN output_subtitle_langs TEXT" }
        - sql: { sql: "ALTER TABLE download_queue ADD COLUMN output_subtitles_scanned_at TIMESTAMP" }
```
- [ ] **Step 6: Run the suite** — `./gradlew test` → green (Liquibase applies on H2). Fix table names if a changeset fails.
- [ ] **Step 7: Commit** — `git add backend/src/main/java/org/lolobored/plexdownloader/model/Movie.java backend/src/main/java/org/lolobored/plexdownloader/model/Episode.java backend/src/main/java/org/lolobored/plexdownloader/model/DownloadQueueItem.java backend/src/main/resources/db/changelog/yaml/017-source-subtitles.yaml backend/src/main/resources/db/changelog/yaml/018-output-subtitles.yaml && git commit -m "feat: subtitle-langs + scanned-at columns on movie/episode/download_queue

Refs #77"`

---

### Task 4: Capture output subtitles on transcode DONE

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeService.java` (the DONE block where sizes/ratio are set)
- Test: `backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeServiceTest.java`

**Interfaces:**
- Consumes: `SubtitleProbe` (Task 2), `SubtitleLangs` (Task 1). Inject `SubtitleProbe subtitleProbe` into `TranscodeService` (add to the constructor / `@RequiredArgsConstructor` final field).
- Produces: on DONE, `item.setOutputSubtitleLangs(SubtitleLangs.toCsv(probe.langs()))` + `item.setOutputSubtitlesScannedAt(Instant.now())` when `probe.ok()`; if probe not ok, leave both null (unscanned).

- [ ] **Step 1: Write the failing test** (extend the success test — stub the new probe)

```java
@Test
void done_capturesOutputSubtitleLangs(@TempDir Path tmp) throws Exception {
    // ... existing success-path setup that reaches DONE, with the dest file present ...
    // stub: when(subtitleProbe.probe(anyString())).thenReturn(new SubtitleProbe.ProbeResult(true, List.of("eng","fra")));
    // after service.transcode(id):
    assertThat(it.getOutputSubtitleLangs()).isEqualTo(",eng,fra,");
    assertThat(it.getOutputSubtitlesScannedAt()).isNotNull();
}
```
(Add `@Mock SubtitleProbe subtitleProbe;` to the test and pass it into the `TranscodeService` under test. For the existing success test, also stub `subtitleProbe.probe(...)` returning `new SubtitleProbe.ProbeResult(true, List.of())` so it doesn't NPE.)

- [ ] **Step 2: Run, verify FAIL** — `./gradlew test --tests 'org.lolobored.plexdownloader.transcode.TranscodeServiceTest'` → FAIL (field null / no setter call).

- [ ] **Step 3: Implement** — in the DONE branch of `transcode()`, after setting sizes/ratio and before/with `queueRepo.save(fresh)`:
```java
            SubtitleProbe.ProbeResult subs = subtitleProbe.probe(fresh.getDestFilePath());
            if (subs.ok()) {
                fresh.setOutputSubtitleLangs(org.lolobored.plexdownloader.util.SubtitleLangs.toCsv(subs.langs()));
                fresh.setOutputSubtitlesScannedAt(java.time.Instant.now());
            }
```
Add `private final SubtitleProbe subtitleProbe;` to the class fields (constructor injection).

- [ ] **Step 4: Run, verify PASS** (and the rest of TranscodeServiceTest green).
- [ ] **Step 5: Commit** — `git add backend/src/main/java/org/lolobored/plexdownloader/transcode/TranscodeService.java backend/src/test/java/org/lolobored/plexdownloader/transcode/TranscodeServiceTest.java && git commit -m "feat: capture output subtitle languages on transcode DONE

Refs #77"`

---

### Task 5: Unscanned-item finders + SubtitleScanService

**Files:**
- Modify: `repository/MovieRepository.java`, `EpisodeRepository.java`, `DownloadQueueRepository.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/service/SubtitleScanService.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/dto/SubtitleScanStatus.java`
- Test: `backend/src/test/java/org/lolobored/plexdownloader/service/SubtitleScanServiceTest.java`

**Interfaces:**
- Repos add: `List<Movie> findBySubtitlesScannedAtIsNull()` + `List<Movie> findAll()` (exists); `List<Episode> findBySubtitlesScannedAtIsNull()`; `List<DownloadQueueItem> findByStatusAndOutputSubtitlesScannedAtIsNull(Status status)`. (Force mode uses `findAll()` for movies/episodes and `findByStatus(DONE)` for queue.)
- Produces: `SubtitleScanService.scan(boolean force)` (sets the source/output subtitle fields per item via `SubtitleProbe` + `SubtitleLangs`; sequential, light `Thread.sleep` throttle; single-run guard via an `AtomicBoolean`; updates an in-memory `SubtitleScanStatus`); `SubtitleScanService.status() -> SubtitleScanStatus`; `boolean isRunning()`.
- `record SubtitleScanStatus(boolean running, Instant lastRunAt, int scanned, int failed, int remainingUnknown)`.

- [ ] **Step 1: Add the repo finders** (as above).
- [ ] **Step 2: Write the failing test** (Mockito — stub probe + repos)

```java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.transcode.SubtitleProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubtitleScanServiceTest {
    @Mock MovieRepository movieRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock DownloadQueueRepository queueRepo;
    @Mock SubtitleProbe subtitleProbe;
    @InjectMocks SubtitleScanService service;

    @Test void scan_unknowns_setsSourceLangsAndScannedAt() {
        Movie m = new Movie(); m.setId(1L); m.setFilePath("/nas/m.mkv");
        when(movieRepo.findBySubtitlesScannedAtIsNull()).thenReturn(List.of(m));
        when(episodeRepo.findBySubtitlesScannedAtIsNull()).thenReturn(List.of());
        when(queueRepo.findByStatusAndOutputSubtitlesScannedAtIsNull(DownloadQueueItem.Status.DONE)).thenReturn(List.of());
        when(subtitleProbe.probe("/nas/m.mkv")).thenReturn(new SubtitleProbe.ProbeResult(true, List.of("eng")));

        service.scan(false);

        verify(movieRepo).save(argThat(x -> ",eng,".equals(x.getSubtitleLangs()) && x.getSubtitlesScannedAt() != null));
        assertThat(service.status().scanned()).isEqualTo(1);
    }

    @Test void scan_failedProbe_leavesUnscanned_countsFailed() {
        Movie m = new Movie(); m.setId(1L); m.setFilePath("/nas/bad.mkv");
        when(movieRepo.findBySubtitlesScannedAtIsNull()).thenReturn(List.of(m));
        when(episodeRepo.findBySubtitlesScannedAtIsNull()).thenReturn(List.of());
        when(queueRepo.findByStatusAndOutputSubtitlesScannedAtIsNull(any())).thenReturn(List.of());
        when(subtitleProbe.probe("/nas/bad.mkv")).thenReturn(new SubtitleProbe.ProbeResult(false, List.of()));

        service.scan(false);

        verify(movieRepo, never()).save(any());
        assertThat(service.status().failed()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run, verify FAIL.**
- [ ] **Step 4: Implement `SubtitleScanStatus` + `SubtitleScanService`**

```java
// SubtitleScanStatus.java
package org.lolobored.plexdownloader.dto;
import java.time.Instant;
public record SubtitleScanStatus(boolean running, Instant lastRunAt, int scanned, int failed, int remainingUnknown) {}
```

```java
// SubtitleScanService.java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.dto.SubtitleScanStatus;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.transcode.SubtitleProbe;
import org.lolobored.plexdownloader.util.SubtitleLangs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubtitleScanService {

    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final DownloadQueueRepository queueRepo;
    private final SubtitleProbe subtitleProbe;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastRunAt;
    private volatile int scanned, failed;

    public boolean isRunning() { return running.get(); }

    public SubtitleScanStatus status() {
        int remaining = movieRepo.findBySubtitlesScannedAtIsNull().size()
                      + episodeRepo.findBySubtitlesScannedAtIsNull().size()
                      + queueRepo.findByStatusAndOutputSubtitlesScannedAtIsNull(DownloadQueueItem.Status.DONE).size();
        return new SubtitleScanStatus(running.get(), lastRunAt, scanned, failed, remaining);
    }

    /** Scans source (movie/episode) + output (DONE queue) files. force=true re-scans all. */
    public void scan(boolean force) {
        if (!running.compareAndSet(false, true)) {
            log.info("Subtitle scan already running, skipping");
            return;
        }
        scanned = 0; failed = 0;
        try {
            List<Movie> movies = force ? movieRepo.findAll() : movieRepo.findBySubtitlesScannedAtIsNull();
            for (Movie m : movies) {
                if (m.getFilePath() == null) continue;
                SubtitleProbe.ProbeResult r = subtitleProbe.probe(m.getFilePath());
                if (r.ok()) { m.setSubtitleLangs(SubtitleLangs.toCsv(r.langs())); m.setSubtitlesScannedAt(Instant.now()); movieRepo.save(m); scanned++; }
                else failed++;
                throttle();
            }
            List<Episode> eps = force ? episodeRepo.findAll() : episodeRepo.findBySubtitlesScannedAtIsNull();
            for (Episode e : eps) {
                if (e.getFilePath() == null) continue;
                SubtitleProbe.ProbeResult r = subtitleProbe.probe(e.getFilePath());
                if (r.ok()) { e.setSubtitleLangs(SubtitleLangs.toCsv(r.langs())); e.setSubtitlesScannedAt(Instant.now()); episodeRepo.save(e); scanned++; }
                else failed++;
                throttle();
            }
            List<DownloadQueueItem> outs = force
                ? queueRepo.findByStatus(DownloadQueueItem.Status.DONE)
                : queueRepo.findByStatusAndOutputSubtitlesScannedAtIsNull(DownloadQueueItem.Status.DONE);
            for (DownloadQueueItem q : outs) {
                if (q.getDestFilePath() == null) continue;
                SubtitleProbe.ProbeResult r = subtitleProbe.probe(q.getDestFilePath());
                if (r.ok()) { q.setOutputSubtitleLangs(SubtitleLangs.toCsv(r.langs())); q.setOutputSubtitlesScannedAt(Instant.now()); queueRepo.save(q); scanned++; }
                else failed++;
                throttle();
            }
            lastRunAt = Instant.now();
            log.info("Subtitle scan done: scanned={} failed={}", scanned, failed);
        } finally {
            running.set(false);
        }
    }

    private void throttle() {
        try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```
(`findByStatus(Status)` already exists on `DownloadQueueRepository`. Add the two `...IsNull` finders.)

- [ ] **Step 5: Run, verify PASS.**
- [ ] **Step 6: Commit** — stage the repos, service, status record, test → `git commit -m "feat: SubtitleScanService scans source + output subtitle languages

Refs #77"`

---

### Task 6: Scan scheduler + admin endpoints

**Files:**
- Create: `service/SubtitleScanScheduler.java`, `controller/SubtitleScanController.java`
- Test: `controller/SubtitleScanControllerTest.java` (match the existing controller-test style — plain Mockito like `QualityProfileControllerTest`)

**Interfaces:**
- Consumes: `SubtitleScanService`, `SettingsService`.
- Produces: `SubtitleScanScheduler` — `SchedulingConfigurer` cron from setting `subtitles.scan.cron` (default `"0 0 4 * * *"`, blank disables); `@Async void triggerManual(boolean force)`. `SubtitleScanController` (class `@PreAuthorize("hasRole('ADMIN')")`): `POST /api/admin/subtitles/scan?force=` → 202 (409 if running); `GET /api/admin/subtitles/scan/status` → `SubtitleScanStatus`.

- [ ] **Step 1: Write the failing controller test** — assert `scan` returns the status, `scan?force=true` triggers a force run, running → 409, status endpoint returns the record. (Mock `SubtitleScanService` + `SubtitleScanScheduler`.) Mirror `QualityProfileControllerTest` annotations.
- [ ] **Step 2: Run, verify FAIL.**
- [ ] **Step 3: Implement scheduler**
```java
package org.lolobored.plexdownloader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

@Slf4j @Component @RequiredArgsConstructor
public class SubtitleScanScheduler implements SchedulingConfigurer {
    private static final String DEFAULT_CRON = "0 0 4 * * *";
    private final SubtitleScanService scanService;
    private final SettingsService settings;

    @Override public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            () -> scanService.scan(false),
            ctx -> {
                String cron = settings.get("subtitles.scan.cron").filter(s -> !s.isBlank()).orElse(DEFAULT_CRON);
                if (cron.isBlank()) return null; // disabled
                return new CronTrigger(cron).nextExecution(ctx);
            });
    }

    @Async public void triggerManual(boolean force) {
        log.info("Manual subtitle scan triggered (force={})", force);
        scanService.scan(force);
    }
}
```
- [ ] **Step 4: Implement controller**
```java
package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.SubtitleScanStatus;
import org.lolobored.plexdownloader.service.SubtitleScanScheduler;
import org.lolobored.plexdownloader.service.SubtitleScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/subtitles")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class SubtitleScanController {
    private final SubtitleScanService scanService;
    private final SubtitleScanScheduler scheduler;

    @PostMapping("/scan")
    public ResponseEntity<Void> scan(@RequestParam(defaultValue = "false") boolean force) {
        if (scanService.isRunning()) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        scheduler.triggerManual(force);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/scan/status")
    public SubtitleScanStatus status() { return scanService.status(); }
}
```
- [ ] **Step 5: Run, verify PASS + full suite green.**
- [ ] **Step 6: Commit** — `git commit -m "feat: subtitle scan scheduler + admin scan/status endpoints

Refs #77"`

---

## Phase 2 — Filters + DTOs

### Task 7: Subtitle filter predicates on the list queries

**Files:**
- Modify: `MovieRepository.java`, `EpisodeRepository.java`, `DownloadQueueRepository.java`
- Modify: the Movies list service/controller + the episode list path + `DownloadService.getQueue`
- Test: a repository `@DataJpaTest`-style test OR `@SpringBootTest @Transactional` (roll back) for the filter queries: `subtitles=none` / `hasLang` / `missingLang` and the comma-padding (`eng` ≠ `enga`).

**Interfaces:**
- Filter param model (shared): a query param trio `subtitles` (`none`|null), `hasLang` (code|null), `missingLang` (code|null). For Movies/Episodes it filters `subtitle_langs`; for Queue, separate `sourceSubtitles`/`outputSubtitles`/`hasLang`/`missingLang` targeting the linked-source vs output.
- Repo queries use comma-padded `LIKE`:
  - none: `subtitle_langs = ','` (scanned-empty). (Unknown rows excluded.)
  - hasLang X: `subtitle_langs LIKE %,X,%`.
  - missingLang X: `subtitle_langs IS NOT NULL AND subtitle_langs NOT LIKE %,X,%` (scanned & lacking).
- Provide `SubtitleLangs.token(x)` to build the `,x,` needle (Task 1).

- [ ] **Step 1: Write a failing repo filter test** — persist movies with `subtitle_langs` of `","`, `",eng,"`, `",enga,"` (a tricky one), `null` (unknown); assert: none→only `","`; hasLang `eng`→only `",eng,"` (NOT `",enga,"`); missingLang `eng`→`","` and `",enga,"` (scanned, lacking eng) but NOT `null` (unknown). Use `@SpringBootTest @ActiveProfiles("test") @Transactional`.
- [ ] **Step 2: Run, verify FAIL.**
- [ ] **Step 3: Implement** — add to `MovieRepository` (mirror for Episode):
```java
    @Query("SELECT m FROM Movie m WHERE " +
           "(:none = false OR m.subtitleLangs = ',') AND " +
           "(:has IS NULL OR m.subtitleLangs LIKE %:has%) AND " +
           "(:missing IS NULL OR (m.subtitleLangs IS NOT NULL AND m.subtitleLangs NOT LIKE %:missing%))")
    List<Movie> findFilteredBySubtitles(@Param("none") boolean none,
                                        @Param("has") String hasToken,
                                        @Param("missing") String missingToken);
```
Callers pass `SubtitleLangs.token(code)` for `has`/`missing` (or null). Wire the Movies list endpoint + the episode list + `getQueue` to accept the params and call the filtered finders (Queue: filter output via the new field; filter source by joining queue→Movie/Episode subtitle_langs — add a `findQueueFilteredBySourceSubtitles`/`...OutputSubtitles` query or filter in Java after the existing `getQueue` fetch since the queue is per-user and small). For Queue source-subs, populate from the linked Movie/Episode (Task 8 adds them to the DTO; filtering can be done in `getQueue` in Java over the assembled list — acceptable given queue size).
- [ ] **Step 4: Run, verify PASS** (incl. the `enga` non-match).
- [ ] **Step 5: Commit** — `git commit -m "feat: subtitle filter queries (none/hasLang/missingLang, comma-padded)

Refs #77"`

---

### Task 8: Subtitle fields on the list response DTOs

**Files:**
- Modify: `dto/DownloadQueueItemResponse.java` + `DownloadService.getQueue` (source subs from linked Movie/Episode, output subs from the item)
- Modify: the Movies + Episode response DTOs
- Test: `DownloadServiceTest.getQueue_*` assert the subtitle fields; Movies/episode DTO mapping test.

**Interfaces:**
- `DownloadQueueItemResponse` gains: `String sourceSubtitleLangs`, `Boolean sourceSubtitlesScanned`, `String outputSubtitleLangs`, `Boolean outputSubtitlesScanned`. `getQueue` batch-loads the linked Movie/Episode subtitle fields (it already batch-loads episode metadata; extend to fetch `subtitleLangs`/`subtitlesScannedAt`, and for movies load via `movieRepo`).
- Movies/Episode list DTOs gain `subtitleLangs` + `subtitlesScanned`.
- Return the raw comma-padded CSV; the frontend parses it (or add a helper that returns a `List<String>` — pick CSV to keep it simple; frontend strips commas).

- [ ] **Step 1: Write failing tests** — `getQueue` for a DONE episode item returns `outputSubtitleLangs` from the item and `sourceSubtitleLangs` from the linked Episode; Movies DTO carries `subtitleLangs`.
- [ ] **Step 2–4: Implement + pass** — extend the DTO records + `from(...)` mappers + the `getQueue` batch joins; Movies/episode controllers map the new fields.
- [ ] **Step 5: Commit** — `git commit -m "feat: expose source+output subtitle langs in list responses

Refs #77"`

---

## Phase 3 — Frontend

### Task 9: SubtitleBadge component + api helpers

**Files:**
- Create: `frontend/src/components/SubtitleBadge.vue`
- Modify: `frontend/src/api/admin.js` (scan + status), the movies/library + download api (filter params)
- Test: `frontend/src/components/__tests__/SubtitleBadge.test.js`

**Interfaces:**
- `SubtitleBadge` props: `langs` (CSV string or null), `scanned` (bool). Renders: unknown (`scanned` false / null) → muted `sub?`; scanned-empty (`","`/empty) → `no sub`; else `SUB·<n>` with `title` = the language codes joined. Parse the CSV (strip commas, split).
- `api/admin.js`: `runSubtitleScan(force)` → POST `/api/admin/subtitles/scan?force=`; `getSubtitleScanStatus()` → GET `.../scan/status`.

- [ ] **Step 1: Write the failing test** — badge renders the three states + hover title from langs.
- [ ] **Step 2–4: Implement + pass** (Vue render fn or template; parse CSV with a small helper).
- [ ] **Step 5: Commit** — `git commit -m "feat: SubtitleBadge + subtitle-scan api helpers

Refs #77"`

---

### Task 10: Filter controls + badges in Movies / TV episodes / Queue

**Files:**
- Modify: `views/MoviesView.vue`, the TV episode list view, `views/QueueView.vue`
- Test: each view's test — filter calls the right params; badge shows.

**Interfaces:**
- Each view gets: a "No subtitles" toggle + a language input (a small select/text for `hasLang`/`missingLang`); wired to the list fetch params (`subtitles=none`, `hasLang`, `missingLang`; Queue uses `sourceSubtitles`/`outputSubtitles`). A `SubtitleBadge` per row (Queue: two badges — source + output, output only when DONE). TV: filter the episode list; show/season rows show a badge when `hasEpisodesMissingSubtitles`.

- [ ] **Step 1: Write failing tests** — toggling "No subtitles" calls fetch with `subtitles=none`; the language filter passes `hasLang`/`missingLang`; a row renders `SubtitleBadge`; Queue renders source + output badges.
- [ ] **Step 2–4: Implement + pass** in each view; keep existing filters/layout intact.
- [ ] **Step 5: Commit** — `git commit -m "feat: subtitle filter controls + badges in movies/tv/queue

Refs #77"`

---

### Task 11: Settings → Subtitles section

**Files:**
- Modify: `views/SettingsView.vue`, `api/admin.js`
- Test: `SettingsView.test.js`

**Interfaces:**
- A "Subtitles" `card-section`: the scan schedule (a cron/interval select bound to setting `subtitles.scan.cron`, saved via the existing settings PUT), **Scan now** + **Rescan all** buttons (call `runSubtitleScan(false|true)`), and a status line (poll `getSubtitleScanStatus()` while running: running / last run / scanned / failed / remaining).

- [ ] **Step 1: Write failing tests** — Scan now calls `runSubtitleScan(false)`, Rescan all `runSubtitleScan(true)`; status renders; schedule saves with the settings map.
- [ ] **Step 2–4: Implement + pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat: settings subtitles section (schedule + scan + status)

Refs #77"`

---

## Self-Review

**Spec coverage:** data model (T3) ✓; SubtitleProbe (T2) ✓; output capture on DONE (T4) ✓; background scan + schedule + endpoints (T5,T6) ✓; filters none/by-language DB-side (T7) ✓; DTO source+output, Queue both (T8) ✓; frontend badge+hover, filters in 3 views, TV episode-level + show/season badge, Settings scan (T9–T11) ✓; CSV comma-padding constraint enforced in T1/T7 ✓; unknown vs none distinction (null scanned_at) throughout ✓; failed-probe leaves unscanned (T2 ok flag, T5) ✓.

**Placeholder scan:** Phases 2–3 tasks (T7,T8,T10,T11) describe steps with representative code + exact interfaces rather than full literal code for every view edit, because they modify existing files whose exact current markup the implementer must read first; each has concrete params/queries/signatures and a TDD test to satisfy. The pure-logic/new-component tasks (T1,T2,T5,T6,T9) carry complete code. No "TBD"/"handle edge cases" placeholders.

**Type consistency:** `subtitleLangs`/`subtitlesScannedAt` (source), `outputSubtitleLangs`/`outputSubtitlesScannedAt` (output) used consistently; `SubtitleLangs.toCsv/fromCsv/token`, `SubtitleProbe.ProbeResult(ok,langs)`, `SubtitleScanService.scan(boolean)/status()/isRunning()`, `SubtitleScanStatus(running,lastRunAt,scanned,failed,remainingUnknown)`, repo finders `findBySubtitlesScannedAtIsNull`/`findByStatusAndOutputSubtitlesScannedAtIsNull`/`findFilteredBySubtitles` consistent across tasks.
