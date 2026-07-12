# Heal Orphaned Queue Items on Plex ratingKey Reissue

**Date:** 2026-07-12
**Status:** Approved — pending implementation

## Problem

When Radarr (or Sonarr) upgrades a media file that already has an item in the
transcode queue, transcoding fails permanently with `ffmpeg exit 254` /
`No such file or directory`, and no amount of retry, re-sync, or container
restart recovers it.

### Root cause

1. Radarr replaces the file on disk (e.g. `webdl-2160p.m4v` → `remux-2160p.m4v`).
2. Plex issues a **new ratingKey** for the upgraded item.
3. `LibrarySyncService.upsertMovie` matches existing rows by `plex_id`
   (the ratingKey). A changed ratingKey is treated as a new movie: a new
   `movies` row is inserted with a **new primary key**, and the old row is
   deleted by the prune step (`movieRepo.findByPlexIdNotIn(...)` →
   `deleteAll`).
4. The queue item created before the upgrade still references the **old
   primary key** in `download_queue.media_id`. That row no longer exists.
5. `TranscodeService.refreshSourcePath` (added in `dc5435b` to self-heal renamed
   files) does `movieRepo.findById(oldPk)`, gets an empty `Optional`, and its
   `current != null` guard causes it to **silently no-op**. ffmpeg then runs
   against the stale snapshot path and fails. This repeats on every retry.

### Observed instance

- `movies`: `id=2167, plex_id=52365, file_path=.../obsession [2026] remux-2160p.m4v`
- `download_queue`:
  - `id=716, media_id=2167, status=DONE, source=...remux-2160p.m4v` (new row, succeeded)
  - `id=713, media_id=2166, status=ERROR, source=...webdl-2160p.m4v` (orphan; 2166 pruned)

Item 713 can never succeed: `findById(2166)` is always empty. The upgraded file
was already transcoded successfully via the separately-enqueued item 716.

## Goal

Stop the infinite-failure loop and surface a clear, actionable error when a
queue item is orphaned by a ratingKey reissue. The upgraded file is expected to
be picked up by the normal enqueue path (as item 716 was); this design does
**not** auto-follow the upgrade.

## Design

Two changes. Change A is the core fix; Change B is proactive UX.

### Change A — `TranscodeService.refreshSourcePath` fails fast on missing media

Currently `refreshSourcePath` returns `void` and cannot distinguish
"media record gone" from "media record present but path unset" — both leave
`current == null` and no-op.

Change it to return `boolean` and handle the three cases explicitly:

- `mediaType` or `mediaId` is null → return `true` (nothing to resolve; preserve
  current behavior, let ffmpeg surface any issue).
- Media record **absent** (`findById` empty) → call
  `failItem(item, "Source media no longer in Plex — likely replaced by an
  upgrade; re-enqueue from library")` and return `false`.
- Media record **present**:
  - path drifted (`current != null && !current.equals(source)`) → re-point
    snapshot + save (existing self-heal, unchanged).
  - path present + equal, or path null → return `true` (unchanged).

`transcode()` calls it as a guard **before** setting status `TRANSCODING`:

```java
DownloadQueueItem item = queueRepo.findByIdWithProfile(itemId).orElse(null);
if (item == null) { log.warn("Transcode skipped, item {} gone", itemId); return; }
if (!refreshSourcePath(item)) return;   // failItem already set inside
item.setStatus(DownloadQueueItem.Status.TRANSCODING);
...
```

Effect: a retry on an orphaned item now yields an **instant, clear error**
instead of a ~2-minute ffmpeg attempt against a dead path. Covers both movies
and episodes (the method already checks `episodeRepo`).

### Change B — prune marks QUEUED orphans ERROR proactively

In `LibrarySyncService.syncAll`, immediately **before** each existing
`deleteAll(orphans)` call, mark orphaned queue items so they surface as failed
at sync time rather than lingering as `QUEUED` until they run.

- Scope: only `status = QUEUED` items. A running `TRANSCODING` item is left
  alone — it holds its own path snapshot and will finish or fail naturally, and
  Change A catches it on any later re-run.
- Movies: new `DownloadQueueRepository` method, bulk update:
  `status = ERROR`, `errorMessage = "Source removed from Plex — likely replaced
  by an upgrade; re-enqueue from library"`
  where `mediaType = MOVIE AND mediaId IN :orphanMovieIds AND status = 'QUEUED'`.
- Shows: same update keyed on episodes under pruned shows, reusing the existing
  subquery pattern
  `mediaId IN (SELECT e.id FROM Episode e WHERE e.season.show.id IN :orphanShowIds)`
  with `mediaType = EPISODE AND status = 'QUEUED'`.
- Log the affected count:
  `Failed N orphaned queue item(s) for pruned media`.
- Runs only inside the existing `!orphans.isEmpty()` guards.

The error message text is identical to Change A so the two paths are
indistinguishable to the user.

## Components touched

- `transcode/TranscodeService.java` — `refreshSourcePath` signature + logic;
  `transcode()` guard call.
- `service/LibrarySyncService.java` — prune blocks for movies and shows.
- `repository/DownloadQueueRepository.java` — one bulk-update for movies, one for
  episodes-under-shows (`@Modifying @Query`).

## Data flow

```
Radarr upgrade → new ratingKey
  → sync: insert new movies row (new PK) + prune old row
      → [Change B] mark QUEUED items on old PK → ERROR (clear message)
  → any transcode attempt on an orphaned item
      → [Change A] findById empty → failItem(clear message), no ffmpeg
```

## Error handling

- Both changes converge on the same terminal state (`status = ERROR`) and the
  same message.
- No exception escapes `transcode()` — `refreshSourcePath` returns a boolean;
  `failItem` persists the error. (This matters because `refreshSourcePath` is
  called **outside** the `try/catch` in `transcode()`.)
- Change B bulk-update is inside `syncAll`'s existing try; a failure there is
  logged and does not abort the sync (consistent with current prune behavior).

## Testing

`TranscodeServiceTest` (Change A):
- media record **gone** → item ends `ERROR` with the message; `processRunner`
  **never invoked**; `transcode()` returns early.
- record present + **drifted path** → snapshot re-pointed; transcode proceeds.
- record present + **same path** → proceeds unchanged.
- record present + **null path** → returns true; ffmpeg surfaces original error.

`LibrarySyncServiceTest` (Change B):
- prune of a movie with a `QUEUED` item → that item is `ERROR` after sync;
  a `DONE` item for the same old id is untouched; a `TRANSCODING` item untouched.
- prune of a show with a `QUEUED` episode item → that item is `ERROR`.

## Non-goals

- **No auto-follow / re-point** to the upgraded file. The new version rides the
  normal enqueue path.
- **No dedup** of queue items.
- **No change** to the `dc5435b` drift-heal for the normal (PK-stable) rename
  case.

## Operational note (out of band)

The already-stuck item observed during diagnosis (713) is a zombie for an
already-transcoded movie and can be deleted directly:
`DELETE FROM download_queue WHERE id = 713;`
This design prevents the class of failure going forward; it does not
retroactively clean pre-existing orphans (there is only the one known).
