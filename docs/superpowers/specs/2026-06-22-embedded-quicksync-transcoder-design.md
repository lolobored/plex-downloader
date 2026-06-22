# Embedded QuickSync Transcoder — Design

**Date:** 2026-06-22
**Status:** Approved (design), pending implementation plan

## 1. Goal

Replace the external Tdarr dependency with an in-container ffmpeg transcoder that
uses Intel QuickSync (QSV) hardware acceleration on the NUC. Provide:

- Native transcoding inside the existing Docker container (no separate Tdarr instance).
- Live transcode **progress** surfaced in the download queue.
- Configurable transcode **quality** via reusable profiles — a global default,
  overridable per queued item.

This is a full replacement: the Tdarr client, scheduler, settings, and UI are removed.

## 2. Decisions (from brainstorming)

| Decision | Choice |
| --- | --- |
| Engine strategy | Full replace of Tdarr |
| HW accel path | QSV (`hevc_qsv` / `h264_qsv`) via oneVPL |
| Quality control | Global default profile + per-item override |
| Output format | Configurable codec + container (per profile) |
| Profile knobs | Quality level, resolution cap, codec+container, audio handling |
| Concurrency | 2 simultaneous transcodes, configurable |
| Failure handling | Mark `ERROR`, manual retry from UI |
| Runtime base image | Switch Alpine → Debian for reliable QSV |

## 3. Current architecture (baseline)

- Spring Boot (Java 21) + Vue, single Docker container, embedded PostgreSQL.
- Enqueue (`DownloadService`) creates a `DownloadQueueItem` with `sourceFilePath`
  (Plex original, read-only mount) and `destFilePath`
  (`/plex-conversion/in-flight/<subdir>/<filename>`).
- `executeCopyAsync` copies the source file into the in-flight directory.
- External Tdarr watches the conversion dir, transcodes, writes to
  `/plex-conversion/libraries/...`.
- `TdarrSyncScheduler` polls the Tdarr API every 30 min, mapping Tdarr fields to a
  `TdarrStatus` enum (`NONE → PROCESSING → TRANSCODED | TDARR_ERROR`).
- Two-enum lifecycle: `Status` (copy phase: `PENDING/IN_PROGRESS/DONE/ERROR`) +
  `TdarrStatus` (transcode phase).
- Frontend `QueueView` already polls `/queue` every 2 s.

## 4. Target architecture

```
enqueue ──> DownloadQueueItem(status=QUEUED, qualityProfile)
                     │
        TranscodeQueueRunner (dispatcher)
                     │  submit when a Semaphore(maxConcurrent) permit is free
                     ▼
            TranscodeService.transcode(item)
                     │  ffprobe source (duration, resolution)
                     │  FfmpegCommandBuilder(profile, src, dest) -> args
                     │  ProcessRunner.run(args, progressSink)
                     │     ├─ ProgressParser -> percent -> throttled DB write
                     │     └─ exit 0 -> DONE ; non-zero -> ERROR(stderr tail)
                     ▼
        /plex-conversion/libraries/<subdir>/<name>.<ext>
```

ffmpeg reads the source **directly** from the read-only Plex mount and writes the
transcoded output straight to the conversion dir. The intermediate raw copy and
`executeCopyAsync` copy logic are removed (eliminates a full-size file copy per item).

### 4.1 Docker image

Runtime stage switches from `eclipse-temurin:21-jre-alpine` to a Debian base
(`eclipse-temurin:21-jre-jammy`):

- Install via `apt`: `ffmpeg`, `intel-media-va-driver-non-free`, `vainfo`, `onevpl`
  (oneVPL runtime), `postgresql`, `postgresql-contrib`, `gosu` (replaces `su-exec`).
- `docker-entrypoint.sh`: replace `su-exec` calls with `gosu`; postgres paths/init
  adjusted for the Debian package layout (`PGDATA` still
  `/var/lib/postgresql/data`).
- `VAAPI`/QSV device node `/dev/dri` must be present at runtime.

`docker-compose.yml`:

```yaml
plex-downloader:
  devices:
    - /dev/dri:/dev/dri
  group_add:
    - "render"
  # JWT_SECRET unchanged; remove any Tdarr-related env
  volumes:
    - ${MOVIES_PATH}:/movies:ro
    - ${TV_PATH}:/tv:ro
    - ${PLEX_CONVERSION_PATH}:/plex-conversion:rw
    - ./volumes/posters:/posters:rw
    - ./volumes/postgres:/var/lib/postgresql/data
```

A startup self-check logs `vainfo` output (or a warning) so QSV availability is
visible in logs; it does not block startup (software fallback is out of scope —
QSV-only per decision).

### 4.2 Data model

**Collapse the two-enum lifecycle into one `Status`:**

```
QUEUED → TRANSCODING → DONE | ERROR
```

Cancellation deletes the row (as today), so no `CANCELLED` state is needed.

`DownloadQueueItem` changes:

- `status` enum becomes `QUEUED, TRANSCODING, DONE, ERROR`.
- Remove `tdarrStatus`, `tdarrError`.
- Add `progressPercent` (Integer, 0–100, nullable).
- Add `transcodeError` (TEXT, nullable).
- Add `transcodeStartedAt` (Instant, nullable).
- Add `qualityProfile` (`@ManyToOne`, nullable → falls back to global default).
- `destFilePath` now points at the final output under
  `/plex-conversion/libraries/<subdir>/<name>.<ext>` (extension from the profile's
  container). The `in-flight` subtree is no longer used.

New entity **`QualityProfile`**:

| Field | Type | Notes |
| --- | --- | --- |
| `id` | Long | PK |
| `name` | String | unique, user-facing |
| `codec` | enum `HEVC_QSV` \| `H264_QSV` | |
| `container` | enum `MKV` \| `MP4` | drives output extension |
| `qualityLevel` | int | QSV `-global_quality` (ICQ), lower = better, ~18–28 |
| `resolutionCap` | enum `KEEP` \| `UHD_4K` \| `P1080` \| `P720` | downscale only, never upscale |
| `audioMode` | enum `COPY` \| `AAC` | |
| `isDefault` | boolean | exactly one row true |

**Liquibase** (new changelog files, following the existing
`db/changelog/yaml/NNN-*.yaml` numbering):

- Create `quality_profile` table; seed one default
  (`HEVC_QSV`, `MKV`, qualityLevel 23, `KEEP`, `COPY`, `isDefault=true`).
- Alter `download_queue`: add `progress_percent`, `transcode_error`,
  `transcode_started_at`, `quality_profile_id` (FK).
- Data-migrate `status`: `PENDING`/`IN_PROGRESS` → `QUEUED`,
  `DONE` (with `tdarr_status` not yet `TRANSCODED`) → `QUEUED`,
  `DONE` + `TRANSCODED` → `DONE`, `ERROR` → `ERROR`. (Exact mapping finalized in the
  plan; principle: anything not finished becomes `QUEUED` for re-transcode.)
- Drop `tdarr_status`, `tdarr_error` columns.

**Settings** (`SettingsService` keys): add `transcode.max.concurrent` (default `2`).
Remove all `tdarr.*` keys. The default profile is identified by the
`QualityProfile.isDefault` flag (no separate setting needed).

### 4.3 Transcode engine

Small, single-purpose units with explicit interfaces:

- **`FfmpegCommandBuilder`** (pure function): `(QualityProfile, sourcePath, destPath,
  sourceProbe) → List<String>` ffmpeg args.
  - QSV init: `-hwaccel qsv -hwaccel_output_format qsv` (+ `-init_hw_device qsv=hw`
    as needed).
  - Resolution cap: `scale_qsv=w:h` only when source exceeds the cap; preserve aspect.
  - Video: `-c:v hevc_qsv|h264_qsv -global_quality <qualityLevel>`.
  - Audio: `-c:a copy` or `-c:a aac`.
  - Output container by profile extension (`.mkv`/`.mp4`).
  - Adds `-progress pipe:1 -nostats` for machine-readable progress.
  - *Testable in isolation: assert the arg list for given profiles.*
- **`MediaProbe` / ffprobe wrapper**: returns source duration (seconds) and
  resolution. Behind a `ProcessRunner` so tests stub it.
- **`ProgressParser`** (pure): consumes `-progress` key/value lines
  (`out_time_us`/`out_time_ms`, `progress=continue|end`) and, given total duration,
  yields integer percent (clamped 0–100). *Testable in isolation.*
- **`ProcessRunner`** (interface): `run(List<String> args, Consumer<String> lineSink)
  → exitCode`, plus the running `Process` handle exposed for cancellation. Real impl
  uses `ProcessBuilder`. Tests inject a fake that emits canned progress lines and a
  chosen exit code — **no real ffmpeg in tests.**
- **`TranscodeService`**: orchestrates probe → build → run; throttles DB
  `progressPercent` writes (e.g. only on integer-percent change); on exit 0 sets
  `DONE`+`completedAt`, on non-zero sets `ERROR`+`transcodeError` (tail of stderr) and
  deletes the partial output; maintains a `Map<itemId, Process>` registry for cancel.

### 4.4 Worker / concurrency

- **`TranscodeQueueRunner`** replaces `TdarrSyncScheduler`.
- A dispatcher pulls `QUEUED` items (FIFO by `queuePosition`) and submits each to an
  executor guarded by `Semaphore(transcode.max.concurrent)`. Changing the setting
  resizes available permits (adjustable without restart).
- **Startup recovery:** reset any `TRANSCODING` items (interrupted by a restart) back
  to `QUEUED` and resubmit; resubmit existing `QUEUED` items.
- **Retry:** endpoint resets an `ERROR` item to `QUEUED` (and clears
  `transcodeError`/`progressPercent`), resubmitting it — mirrors today's requeue
  button.
- **Cancel:** if `TRANSCODING`, `destroyForcibly()` the registered process and delete
  the partial output, then delete the row; otherwise just delete the row. Replaces the
  Tdarr eviction logic in `doCancelItem`.

### 4.5 API + frontend

- `DownloadQueueItemResponse` gains `progressPercent` and `qualityProfileName`.
  `QueueView` (already polling every 2 s) renders a per-row progress bar during
  `TRANSCODING`.
- **Settings page:** new section — CRUD for quality profiles, choose the default, set
  max concurrent transcodes. Replaces the Tdarr URL / API-key / sync-cron section.
- **Per-item profile picker:** optional dropdown on `DownloadButton`,
  `SubscribeButton`, and season/show enqueue actions; defaults to the global default
  when unset. Playlist subscriptions may carry an optional profile (secondary; default
  applies when unset).
- New endpoints (under existing admin/download controllers):
  - `GET/POST/PUT/DELETE /api/admin/quality-profiles`
  - `PUT /api/admin/settings` extended with `transcode.max.concurrent`
  - `POST /api/download/queue/{id}/retry`
  - enqueue endpoints accept an optional `qualityProfileId`.

### 4.6 Removed

- `client/TdarrClient.java` + `TdarrClientTest`.
- `service/TdarrSyncScheduler.java` + `TdarrSyncSchedulerTest`.
- `db/changelog/yaml/004-tdarr-status.yaml` superseded by new migrations (kept in
  history; columns dropped by a new changelog).
- Tdarr settings keys and Settings-page UI.
- README Tdarr references / prerequisites; add QSV + `/dev/dri` notes.
- `executeCopyAsync` raw-copy logic and the `in-flight` directory usage.

## 5. Testing strategy (TDD)

Pure units first (no I/O, fast):

1. `FfmpegCommandBuilder` — arg list per profile permutation (codec, container,
   resolution cap, audio mode, quality level).
2. `ProgressParser` — `-progress` lines → percent, including `end`, clamping, missing
   duration.
3. Profile defaulting — item with null profile resolves to `isDefault` profile.
4. Status migration mapping — old `(status, tdarrStatus)` → new `status`.

Then collaborator-level:

5. `TranscodeService` with a fake `ProcessRunner` (canned progress + exit codes):
   success → `DONE`, failure → `ERROR` + partial-output deletion, progress throttling.
6. `TranscodeQueueRunner` — semaphore concurrency limit, startup recovery, retry,
   cancel-during-transcode.
7. Adapted `DownloadService` tests — enqueue creates `QUEUED` items with output path /
   profile, no copy step.

Real-ffmpeg/QSV behavior is validated manually on the NUC (out of automated-test
scope; QSV-only with no software fallback).

## 6. Out of scope

- Software (non-QSV) transcode fallback.
- Auto-retry on failure (manual retry only).
- Re-introducing or supporting Tdarr.
- Multi-GPU scheduling beyond a simple concurrency count.

## 7. Suggested implementation phases

1. **Image/infra:** Debian runtime, ffmpeg + Intel drivers, entrypoint (`gosu`,
   postgres), compose `/dev/dri` passthrough, `vainfo` startup log.
2. **Data model:** `QualityProfile` entity + Liquibase, collapsed `Status`, new queue
   columns + migration, settings keys.
3. **Engine:** `FfmpegCommandBuilder`, `ProgressParser`, `MediaProbe`,
   `ProcessRunner`, `TranscodeService` (all TDD).
4. **Worker:** `TranscodeQueueRunner`, startup recovery, retry, cancel.
5. **API:** profile CRUD, settings, retry endpoint, enqueue `qualityProfileId`,
   response progress fields; remove Tdarr classes.
6. **Frontend:** queue progress bar, settings profiles UI, per-item profile picker;
   remove Tdarr settings UI.
7. **Docs:** README QSV/`/dev/dri` setup, remove Tdarr prerequisites.
