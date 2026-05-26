# Queue Tree View — Design Spec

## Goal

Replace the flat IN PROGRESS / PENDING / DONE queue view with a collapsible tree grouped by source: **Subscribed Playlists** on top, **Individual Downloads** below (episodes nested by show → season, movies flat). Filters are retained and applied across the tree.

---

## Layout

### Top level (collapsed by default)

```
Filter bar  [ALL | MOVIE | TV]  [PENDING | COPYING | DONE | ERROR]  [search…]

── SUBSCRIBED PLAYLISTS ─────────────────────────────────────────────
▶ 📋 Action Movies     3 items · 1 in progress     [Unsubscribe]
▶ 📋 Sci-Fi Classics   5 items · 5 done            [Unsubscribe]

── INDIVIDUAL DOWNLOADS ─────────────────────────────────────────────
▶ 📺 Breaking Bad      2 items
▶ 📺 The Wire          10 items
  🎬 Interstellar      pending                      [✕]
  🎬 The Matrix        done                         [✕]
```

- Both top-level sections always render (hidden only if empty after filtering).
- Playlist and show groups start **collapsed**. Click header to expand/collapse.
- Standalone movies in Individual Downloads are never wrapped in a group — they render flat.

### Expanded playlist group

```
▼ 📋 Action Movies                                  [Unsubscribe]
    IN PROGRESS
      🎬 Inception             ⏳  [✕]
    PENDING
      📺 S01E01 Pilot               [✕]
    DONE
      🎬 The Dark Knight       ✓   [✕]
      🎬 The Matrix  ⚠ Tdarr error  [⟳ Retry] [✕]
```

### Expanded show group

```
▼ 📺 Breaking Bad
  ▼ Season 1
      PENDING
        E02 Cat's in the Bag         [✕]
        E03 …and the Bag's in River  [✕]
  ▼ Season 2
      DONE
        E01 Seven Thirty-Seven  ✓    [✕]
```

Season sub-groups also start collapsed. Click to expand.

---

## Status Model (simplified)

| Internal state | Display label | Section |
|---|---|---|
| `status=PENDING` | pending | PENDING |
| `status=IN_PROGRESS` | copying… | IN PROGRESS |
| `status=DONE` (any tdarrStatus except TDARR_ERROR) | done | DONE |
| `status=DONE`, `tdarrStatus=TDARR_ERROR` | tdarr error + retry btn | DONE |
| `status=ERROR` | error | DONE |

**Key simplification:** `tdarrStatus=TRANSCODED` and `tdarrStatus=NONE` (Tdarr queued) are both displayed as plain "done". There is no separate "Transcoded" or "Queued in Tdarr" label. TDARR_ERROR still surfaces a retry button.

---

## Filter Bar

Retained as-is with one change: **remove the TRANSCODED chip** (merged into DONE).

Final status chips: `PENDING` · `COPYING` · `DONE` · `ERROR`

Filter behaviour in tree:
- Items that don't match are hidden.
- Sub-sections (IN PROGRESS / PENDING / DONE) with zero matching items are hidden.
- Groups (playlist or show) with zero matching items are hidden.
- Top-level sections hide if no groups/items survive filtering.
- Count badge on each group header shows the **filtered** count.

---

## Group Header Actions

| Group type | Header action |
|---|---|
| Playlist | **Unsubscribe** button (reuses existing confirm-modal flow from `PlaylistDetailView`) |
| Show | None |
| Season | None |

Individual item ✕ (cancel/remove) and ⟳ Retry buttons work identically to current queue.

---

## Backend Changes

### 1. DB migration
Add nullable `playlist_id` column to `download_queue`:
```sql
ALTER TABLE download_queue
  ADD COLUMN playlist_id BIGINT REFERENCES playlist(id) ON DELETE SET NULL;
```

### 2. `DownloadQueueItem` entity
Add field:
```java
@Column(name = "playlist_id")
private Long playlistId;
```

### 3. `DownloadService` — enqueue methods
Add `playlistId`-accepting overloads. Existing 2-arg signatures delegate to 3-arg:
```java
public List<Long> enqueueMovie(Long movieId, User user)               // delegates → playlistId=null
public List<Long> enqueueMovie(Long movieId, User user, Long playlistId)
public List<Long> enqueueEpisode(Long episodeId, User user)            // delegates → playlistId=null
public List<Long> enqueueEpisode(Long episodeId, User user, Long playlistId)
```
`enqueueSeason` and `enqueueShow` (bulk, never from playlists) always pass `playlistId=null`.

### 4. `PlaylistSyncService`
`enqueueItem(User user, String plexId, String mediaType)` becomes  
`enqueueItem(User user, String plexId, String mediaType, Long playlistId)`.

Both call sites (`syncPlaylist` and `enqueueForSubscription`) already know the playlist's local DB id and pass it through.

### 5. `DownloadQueueItemResponse`
Add fields:
```java
Long playlistId,
String playlistTitle,
String showTitle,
Integer seasonNumber
```

### 6. `DownloadService.getQueue()`
Current flow already join-fetches `Episode → Season → Show`. Extend:
- Collect distinct `playlistId` values from queue items (non-null).
- Batch-load `Playlist` rows by those ids.
- Map `playlistId → title`.
- For episode items, expose `ep.getSeason().getShow().getTitle()` as `showTitle` and `ep.getSeason().getSeasonNumber()` as `seasonNumber`.
- Populate all four new fields in `DownloadQueueItemResponse.from(...)`.

### 7. `PlaylistRepository` (or inline JPQL)
No new method needed — use `playlistRepo.findAllById(ids)`.

---

## Frontend Changes

### `QueueView.vue` — full rewrite

**State:**
```js
const openGroups = ref(new Set())   // Set of group keys like "playlist-5", "show-10", "season-100"
function toggleGroup(key) { ... }
function isOpen(key) { return openGroups.value.has(key) }
```

**Tree computation** (`computed` from `dlStore.queueItems` + active filters):

```
filteredItems = queueItems filtered by matchesType + matchesStatus + matchesText

playlistGroups = group filteredItems by playlistId (non-null)
  → [{ playlistId, playlistTitle, items }]  sorted by playlistTitle

individualItems = filteredItems where playlistId == null
  showGroups = group individualItems where mediaType==EPISODE by showId
    → [{ showId, showTitle, seasons: [{ seasonId, seasonNumber, items }] }]  sorted by showTitle
  soloMovies  = individualItems where mediaType==MOVIE  sorted by title
```

**Status bucket helper:**
```js
function statusBucket(item) {
  if (item.status === 'IN_PROGRESS') return 'IN_PROGRESS'
  if (item.status === 'PENDING')     return 'PENDING'
  return 'DONE'   // DONE (any tdarrStatus) + ERROR all land here
}
```

**Status display label:**
```js
function statusLabel(item) {
  if (item.status === 'IN_PROGRESS') return 'copying…'
  if (item.status === 'PENDING')     return 'pending'
  if (item.status === 'ERROR' || item.tdarrStatus === 'TDARR_ERROR') return 'error'
  return 'done'
}
```

**Count badge** on group header: `items.length` (already filtered).

**Status chips:** Replace existing 6 chips with 4: `PENDING`, `COPYING`, `DONE`, `ERROR`.
- `matchesStatus` updated: DONE chip matches `status === 'DONE' || status === 'ERROR'`; COPYING matches `IN_PROGRESS`.

**Unsubscribe** in playlist group header: calls existing `unsubscribe(playlistId)` API + triggers `ConfirmModal` (same as `PlaylistDetailView`).

**`totalVisible`** computed (for h2 badge): `filteredItems.length`.

### `frontend/src/api/playlists.js`
No changes — `unsubscribe(id)` already exists.

### `frontend/src/views/__tests__/QueueView.test.js`
Update substantially:
- Remove pagination / section-heading tests that no longer apply.
- Add: tree groups render correctly, collapse/expand, filter hides empty groups, DONE and TRANSCODED both appear in Done section, Unsubscribe button present on playlist group.

---

## What Does NOT Change

- Individual item ✕ and Retry buttons — behaviour identical to current.
- `DownloadController`, `DownloadQueueRepository`, Tdarr sync — untouched.
- `NavBar` pending badge — untouched.
- `PlaylistSyncService.cancelAllForUser` — untouched (called by Unsubscribe flow).

---

## Files Touched

| File | Action |
|---|---|
| `backend/src/main/resources/db/migration/VX__add_playlist_id_to_queue.sql` | Create |
| `backend/…/model/DownloadQueueItem.java` | Modify |
| `backend/…/dto/DownloadQueueItemResponse.java` | Modify |
| `backend/…/service/DownloadService.java` | Modify |
| `backend/…/service/PlaylistSyncService.java` | Modify |
| `frontend/src/views/QueueView.vue` | Rewrite |
| `frontend/src/views/__tests__/QueueView.test.js` | Rewrite |
