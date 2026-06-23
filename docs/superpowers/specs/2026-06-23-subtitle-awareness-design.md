# Subtitle Awareness — Design

**Date:** 2026-06-23
**Status:** Approved (design), pending implementation plan

## Goal

Know which files have subtitles — both the **source** (Plex library files) and the
**transcoded output** — with the subtitle **languages** recorded, and filter by subtitle
status in the **Movies**, **TV Shows**, and **Queue** views. The driving workflow: spot a
transcode (or source) that has no subtitles, add subtitles to the source externally, then
**Transcode again** to produce an output with subtitles.

## Decisions (from brainstorming)

| Decision | Choice |
| --- | --- |
| Detail stored | Subtitle **languages** per file (not just a boolean) |
| Detection | A dedicated **background scan job** (throttled), scheduled + manual; backfills existing files |
| Output subs | Captured at transcode time (probe the dest on DONE) |
| Filter | "No subtitles" **and** by-language (missing / has) |
| TV granularity | Episode level (season/show rows get a "has episodes missing subs" badge) |
| Display | Subtitle badge per row; languages on hover |
| Views | Movies (source), TV episodes (source), Queue (**both** source + transcoded output) |

"Unknown" (not yet scanned) is a distinct state from "no subtitles".

## Data model

Subtitle languages are stored as a CSV string of language codes (e.g. `"eng,fra"`,
`und` for an untagged subtitle stream). **Empty string + non-null scanned-at = scanned,
no subtitles.** **Null scanned-at = unknown / not yet scanned.**

- `Movie`: add `subtitle_langs` (TEXT), `subtitles_scanned_at` (TIMESTAMP, nullable).
- `Episode`: add `subtitle_langs` (TEXT), `subtitles_scanned_at` (TIMESTAMP, nullable).
- `DownloadQueueItem`: add `output_subtitle_langs` (TEXT), `output_subtitles_scanned_at`
  (TIMESTAMP, nullable). This is the **transcoded output**'s subtitles. The queue item's
  **source** subtitles are not duplicated — they are read from the linked `Movie`/`Episode`
  via `mediaType` + `mediaId`.
- Liquibase changelogs `017-source-subtitles.yaml` (movie + episode), `018-output-subtitles.yaml`
  (download_queue).

## Detection — `SubtitleProbe`

A small probe (alongside `FfprobeMediaProbe`, behind the existing `ProcessRunner` so tests
don't spawn ffprobe): runs
`ffprobe -v error -select_streams s -show_entries stream_tags=language -of csv=p=0 <file>`
and parses the output into a list of language codes (one per subtitle stream; an empty/missing
tag → `und`). Pure parse method, unit-tested in isolation. Returns an ordered de-dupe-preserving
list joined to CSV for storage. A file the probe can't read (missing/permission) → treated as
"scan failed" (leave unscanned, log) rather than "no subs".

### Output capture at transcode time
In `TranscodeService`, on a successful DONE (where sizes/ratio are already computed), probe the
**dest** file with `SubtitleProbe` and set `output_subtitle_langs` + `output_subtitles_scanned_at`.
Cheap (one extra ffprobe). On a re-transcode (Transcode again), this re-runs and refreshes the
output subtitles automatically.

## Background scan — `SubtitleScanService`

- A throttled, **sequential** scan (I/O-bound over the NAS): probes `Movie` + `Episode` source
  files where `subtitles_scanned_at` is null, and `DownloadQueueItem` (status DONE) output files
  where `output_subtitles_scanned_at` is null. **Force mode** re-scans everything (for after the
  user adds subtitles to source files).
- Triggered by: a schedule (`subtitles.scan.cron` setting, default a nightly off-peak cron, can
  be blank to disable) **and** an admin **"Scan now"** (unknowns only) / **"Rescan all"** (force).
- Runs as a single background task (a dedicated executor / `@Async`), guarded so only one scan
  runs at a time; light throttle (small delay between probes) to avoid hammering the NAS; logs
  progress and writes a scan status (last run time, counts scanned/failed, running flag).
- On a successful transcode the output is captured inline (above), so the scan's main job is
  source files + backfilling pre-existing outputs.

### Endpoints
- `POST /api/admin/subtitles/scan` (ADMIN) → start a scan of unknowns; `?force=true` → rescan all.
  409 if a scan is already running.
- `GET /api/admin/subtitles/scan/status` (ADMIN) → `{ running, lastRunAt, scanned, failed, remainingUnknown }`.

## API + filters

Subtitle filtering is applied in the existing list queries (DB-side) so it works with pagination:

- **Movies list**: filter params `subtitles=none` (source has no subs), `missingLang=<code>`
  (source lacks that language), `hasLang=<code>` (source has it). Applied to `Movie.subtitle_langs`
  (CSV `LIKE`/empty checks). Response includes `subtitleLangs` + `subtitlesScanned`.
- **TV Shows**: the same filters at **episode** level (on `Episode.subtitle_langs`). Show/season
  browse rows expose a `hasEpisodesMissingSubtitles` flag (aggregate: any scanned episode with no
  subs) for a badge.
- **Queue** (`getQueue`): each item carries **source** subs (joined from the linked Movie/Episode)
  and **output** subs (`output_subtitle_langs`). Filters: `sourceSubtitles=none` / `outputSubtitles=none`
  and by-language on either side. DTO gains `sourceSubtitleLangs`, `outputSubtitleLangs`, and the
  two scanned flags.

CSV matching: "has lang X" = the CSV contains the token `X`; "missing X" = scanned and does not
contain `X`; "none" = scanned and CSV is empty. (A small normalized helper builds the predicates;
exact JPQL/`LIKE` form decided in the plan, padding tokens with commas to avoid substring matches,
e.g. matching `,eng,` in `,eng,fra,`.)

## Frontend

- **Subtitle badge** per row: `SUB·<n>` when subtitles exist (n = count), `no sub` when scanned-empty,
  and a muted `sub?` when unscanned/unknown. Hover shows the language codes. In the **Queue**, two
  badges: source and output (output only once DONE).
- **Filter controls** in Movies, TV (episode list), Queue: a "No subtitles" toggle plus a language
  filter (missing / has a code). Queue offers source-vs-output as the filter target. Wire to the
  list query params above.
- **Settings → Subtitles** section: the scan schedule, **Scan now** / **Rescan all** buttons, and
  last-scan status (running / last run / counts).
- API helpers: `getSubtitleScanStatus()`, `runSubtitleScan(force)`, and the new list filter params
  threaded through the Movies / TV / Queue fetches.

## Testing

- `SubtitleProbe` parse: multiple subtitle streams + languages, untagged → `und`, no subtitle
  streams → empty, unreadable → failure (not "empty").
- Output capture: a DONE transcode sets `output_subtitle_langs` from the dest probe; re-transcode
  refreshes it.
- `SubtitleScanService`: scans only unknowns; force re-scans all; single-run guard; status updates;
  a failed probe is counted and doesn't mark the file "no subs".
- Filter queries: `none` / `missingLang` / `hasLang` on Movie, Episode, and Queue (source + output);
  the comma-padding avoids `eng` matching `enga`.
- Frontend: badge states (subs / none / unknown), hover languages, the filters call the right
  params, the Queue source+output badges, Settings scan controls + status.

## Suggested implementation phases

1. **Detection core**: data model + migrations, `SubtitleProbe`, output capture on DONE,
   `SubtitleScanService` + scan/status endpoints + schedule setting. (Backend, no UI.)
2. **Filters + DTOs**: list-query filter params + the subtitle fields on Movies / TV-episode /
   Queue responses (incl. the source-subs join for Queue).
3. **Frontend**: badges + hover, the filter controls in the three views, the Settings subtitles
   section.

## Out of scope

- Editing / downloading / embedding subtitles in-app (the user adds them to the source externally).
- Per-stream subtitle metadata beyond language (forced/SDH flags, codec).
- Re-probing source on every sync (the scan job + manual rescan own that).
