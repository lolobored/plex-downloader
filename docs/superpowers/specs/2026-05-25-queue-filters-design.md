# Queue Filter Bar Design

**Goal:** Add an always-visible filter bar to `QueueView` so users can narrow queue items by type, status, and free text without leaving the page.

**Architecture:** Pure client-side filtering via Vue `computed()`. Filter state lives in `QueueView` as local `ref`s. No backend changes, no store changes.

**Tech Stack:** Vue 3 Composition API, existing `useDownloadStore`, Vitest

---

## Filter Controls

Three controls rendered above the queue sections:

### 1. Type chips (single-select)
| Label | Matches |
|-------|---------|
| All (default) | no filter |
| Movie | `mediaType === 'MOVIE'` |
| TV | `mediaType` in `['EPISODE', 'SEASON', 'SHOW']` |

Clicking the active chip resets to `All`.

### 2. Status chips (multi-select)
| Label | Matches |
|-------|---------|
| Pending | `status === 'PENDING'` |
| Copying | `status === 'IN_PROGRESS'` |
| Done | `status === 'DONE' && tdarrStatus === 'NONE'` |
| Transcoding | `tdarrStatus === 'PROCESSING'` |
| Transcoded | `tdarrStatus === 'TRANSCODED'` |
| Error | `status === 'ERROR' \|\| tdarrStatus === 'TDARR_ERROR'` |

No chips active = show all. Clicking an active chip deactivates it. Multiple active chips show the union (OR logic).

### 3. Text search input
Searches across all text visible on each row: `title`, `mediaType`, `errorMessage`, `tdarrError`. Case-insensitive substring match.

---

## Filtering Logic

Each section uses a filtered view of its items:

```
filteredInProgress = inProgress filtered by type + text
filteredPending    = pending    filtered by type + text + status
filteredDone       = done       filtered by type + text + status
```

`inProgress` items always have `status === 'IN_PROGRESS'` — the Copying chip controls whether they appear, but the "In Progress" section header only shows when `filteredInProgress.length > 0`.

Sections already hide when their list is empty (via `v-if`) — no change needed there.

---

## Visual Behaviour

- Active type chip: filled accent-blue background
- Active status chip: filled accent-blue background
- Inactive chip: surface2 background with border
- Text input: standard input field, placeholder "Search…"
- All controls on one row; wraps on narrow screens

---

## Files Changed

| File | Change |
|------|--------|
| `frontend/src/views/QueueView.vue` | Add filter bar (template + script + style) |
| `frontend/src/views/__tests__/QueueView.test.js` | Add filter tests |
