# Playlist Item Navigation Design

**Goal:** Clicking an item in `PlaylistDetailView` navigates to its movie or episode detail page.

**Architecture:** Add `mediaId`, `showId`, `seasonId` to `PlaylistItemResponse` (computed from existing DB relations at response time). Add `@Transactional(readOnly = true)` to the playlist endpoint to keep the Hibernate session open for lazy traversal. Make each item row a `RouterLink` in the frontend.

**Tech Stack:** Spring Boot 3, JPA lazy loading, Vue 3 Router, existing `MovieDetailView` / `EpisodeDetailView`

---

## Data Model (no changes)

No DB migration needed. New fields are derived at query time:

| Field | Source | When |
|-------|--------|------|
| `mediaId` | `Movie.id` / `Episode.id` | always (null if not synced) |
| `showId` | `Episode.season.show.id` | EPISODE only, null for MOVIE |
| `seasonId` | `Episode.season.id` | EPISODE only, null for MOVIE |

---

## Backend

### `PlaylistItemResponse` DTO

Add three nullable fields:

```java
public record PlaylistItemResponse(
    Long id,
    String plexId,
    String mediaType,
    Integer ordinal,
    String title,
    Integer year,
    String queueStatus,
    String tdarrStatus,
    Long mediaId,      // NEW: movie or episode DB id, null if not synced
    Long showId,       // NEW: episode only, null for movies
    Long seasonId      // NEW: episode only, null for movies
) {}
```

### `PlaylistController.toItemResponse()`

Populate new fields:

```java
// MOVIE branch
mediaId = m.getId();
// showId = null, seasonId = null (implicit)

// EPISODE branch
mediaId = ep.getId();
seasonId = ep.getSeason().getId();
showId = ep.getSeason().getShow().getId();
```

### `@Transactional(readOnly = true)` on `getPlaylist()`

`Episode.season` and `Season.show` are `FetchType.LAZY`. Without a transaction, traversal throws `LazyInitializationException`. Fix: annotate the `GET /api/playlists/:id` handler method with `@Transactional(readOnly = true)`.

---

## Frontend

### Navigation logic

```
MOVIE   → /movies/:mediaId
EPISODE → /tv/:showId/seasons/:seasonId/episodes/:mediaId
```

Items with `mediaId === null` (not synced to local DB) remain non-clickable.

### `PlaylistDetailView.vue` — item row

Replace the plain `<div class="item-row">` with a conditional `<RouterLink>`:

```html
<component
  :is="item.mediaId ? RouterLink : 'div'"
  :to="item.mediaId ? itemRoute(item) : undefined"
  class="item-row"
  :class="{ clickable: item.mediaId }"
>
  <!-- existing content unchanged -->
</component>
```

```js
function itemRoute(item) {
  if (item.mediaType === 'MOVIE') return `/movies/${item.mediaId}`
  return `/tv/${item.showId}/seasons/${item.seasonId}/episodes/${item.mediaId}`
}
```

Style: `.item-row.clickable:hover` adds subtle hover highlight.

---

## Files Changed

| File | Change |
|------|--------|
| `backend/.../dto/PlaylistItemResponse.java` | add `mediaId`, `showId`, `seasonId` |
| `backend/.../controller/PlaylistController.java` | populate new fields; add `@Transactional(readOnly=true)` |
| `backend/.../controller/PlaylistControllerTest.java` | assert new fields in response |
| `frontend/src/views/PlaylistDetailView.vue` | `itemRoute()` function; conditional RouterLink |
| `frontend/src/views/__tests__/PlaylistDetailView.test.js` | tests for link presence and correct href |
