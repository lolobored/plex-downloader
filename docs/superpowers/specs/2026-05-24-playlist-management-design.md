# Playlist Management Design

## Goal

Sync Plex playlists into the app, let users subscribe to playlists, automatically queue subscribed playlist items for Tdarr conversion, and cancel + delete when items are removed.

## Architecture

`PlaylistSyncService` runs inside the existing `LibrarySyncService.syncAll()`. On each sync it fetches all Plex video playlists, diffs them against the stored `playlist_items`, and drives `DownloadService.enqueueMovie/enqueueEpisode` for additions and a new cancel+delete flow for removals. Subscribing to a playlist immediately queues all current items.

## Tech Stack

Spring Boot 3, JPA/Hibernate, Liquibase YAML, PostgreSQL, Vue 3 + Pinia. Existing: `PlexMediaServerClient`, `TdarrClient`, `DownloadService`, `DownloadQueueRepository`.

---

## Data Model

Tables already created in `006-playlists.yaml`:

```sql
playlists (
  id            BIGINT PK,
  plex_id       VARCHAR(255) UNIQUE NOT NULL,   -- Plex ratingKey
  title         TEXT NOT NULL,
  playlist_type VARCHAR(20),                    -- "video" (only video synced)
  leaf_count    INT NOT NULL DEFAULT 0,
  synced_at     TIMESTAMP WITH TIME ZONE
)

playlist_items (
  id          BIGINT PK,
  playlist_id BIGINT NOT NULL → playlists(id) ON DELETE CASCADE,
  plex_id     VARCHAR(255) NOT NULL,            -- Plex ratingKey of movie/episode
  media_type  VARCHAR(20) NOT NULL,             -- "MOVIE" or "EPISODE"
  ordinal     INT,
  UNIQUE (playlist_id, plex_id)
)

playlist_subscriptions (
  id          BIGINT PK,
  user_id     BIGINT NOT NULL → users(id) ON DELETE CASCADE,
  playlist_id BIGINT NOT NULL → playlists(id) ON DELETE CASCADE,
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (user_id, playlist_id)
)
```

No new tables needed.

---

## Backend

### New Plex API methods in `PlexMediaServerClient`

```java
// GET /playlists/all  — returns only playlistType="video" playlists
public List<PlexPlaylist> getPlaylists()

// GET /playlists/{ratingKey}/items  — reuses existing PlexItem DTO
public List<PlexItem> getPlaylistItems(String ratingKey)
```

New DTO `client/dto/PlexPlaylist.java`:
```java
@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class PlexPlaylist {
    @JsonProperty("ratingKey") private String ratingKey;
    @JsonProperty("title")     private String title;
    @JsonProperty("playlistType") private String playlistType;  // "video"
    @JsonProperty("leafCount") private int leafCount;
}
```

Plex response wraps both in `MediaContainer > Metadata[]`.

### New `TdarrClient.deleteFile(String filePath)`

```java
public void deleteFile(String filePath) {
    // POST /api/v2/cruddb  {"collection":"FileJSONDB","mode":"deleteOne","docID":filePath}
    // fire-and-forget — log warning on RestClientException, do not throw
}
```

### New entities

`model/Playlist.java` — maps to `playlists`  
`model/PlaylistItem.java` — maps to `playlist_items`  
`model/PlaylistSubscription.java` — maps to `playlist_subscriptions`

### New repositories

`PlaylistRepository`:
- `Optional<Playlist> findByPlexId(String plexId)`

`PlaylistItemRepository`:
- `List<PlaylistItem> findByPlaylistId(Long playlistId)`
- `Set<String> findPlexIdsByPlaylistId(Long playlistId)` — JPQL: `SELECT i.plexId FROM PlaylistItem i WHERE i.playlistId = :id`
- `void deleteByPlaylistIdAndPlexId(Long playlistId, String plexId)`

`PlaylistSubscriptionRepository`:
- `List<PlaylistSubscription> findByPlaylistId(Long playlistId)` — returns with user eagerly loaded
- `boolean existsByUserIdAndPlaylistId(Long userId, Long playlistId)`
- `void deleteByUserIdAndPlaylistId(Long userId, Long playlistId)`

`DownloadQueueRepository` (modify — add):
- `boolean existsByUserIdAndMediaTypeAndMediaId(Long userId, DownloadQueueItem.MediaType type, Long mediaId)`
- `Optional<DownloadQueueItem> findByUserIdAndMediaTypeAndMediaId(Long userId, DownloadQueueItem.MediaType type, Long mediaId)`

### `PlaylistSyncService`

New service called from `LibrarySyncService.syncAll()` at end of the method (after all libraries sync).

```
syncAll():
  List<PlexPlaylist> plexPlaylists = plexClient.getPlaylists()  // filtered: playlistType="video"
  for each plexPlaylist:
    Playlist local = playlistRepo.findByPlexId(ratingKey).orElseGet(() -> new Playlist())
    // upsert Playlist row
    local.setPlexId / title / playlistType / leafCount / syncedAt = now()
    playlistRepo.save(local)

    Set<String> oldPlexIds = playlistItemRepo.findPlexIdsByPlaylistId(local.getId())
    List<PlexItem> fetched  = plexClient.getPlaylistItems(plexPlaylist.ratingKey)
    Set<String> newPlexIds  = fetched.stream().map(PlexItem::getRatingKey).collect(toSet())

    Set<String> added   = newPlexIds - oldPlexIds
    Set<String> removed = oldPlexIds - newPlexIds

    // Persist item changes
    for each removed plex_id: playlistItemRepo.deleteByPlaylistIdAndPlexId(local.getId(), plexId)
    for each added PlexItem:
      PlaylistItem item = new PlaylistItem()
      item.setPlaylistId(local.getId())
      item.setPlexId(plexItem.getRatingKey())
      item.setMediaType(mapType(plexItem.getType()))   // "movie"→MOVIE, "episode"→EPISODE
      item.setOrdinal(index)
      playlistItemRepo.save(item)

    // React to diffs for subscribers
    List<PlaylistSubscription> subs = subscriptionRepo.findByPlaylistId(local.getId())
    for each subscription:
      User user = subscription.getUser()
      for each added PlexItem:
        enqueueItem(user, plexItem)        // see below
      for each removed plex_id:
        cancelItem(user, plexId, oldItems) // see below

enqueueItem(User user, PlexItem item):
  if item.type == "movie":
    Movie movie = movieRepo.findByPlexId(item.ratingKey).orElse(null)
    if movie == null: log.warn and skip
    if queueRepo.existsByUserIdAndMediaTypeAndMediaId(user.id, MOVIE, movie.id): skip (already queued)
    downloadService.enqueueMovie(movie.getId(), user)
  elif item.type == "episode":
    Episode ep = episodeRepo.findByPlexId(item.ratingKey).orElse(null)
    if ep == null: log.warn and skip
    if queueRepo.existsByUserIdAndMediaTypeAndMediaId(user.id, EPISODE, ep.id): skip
    downloadService.enqueueEpisode(ep.getId(), user)

cancelItem(User user, String plexId, Map<String,PlaylistItem> oldItemsByPlexId):
  PlaylistItem pi = oldItemsByPlexId.get(plexId)
  if pi == null: return
  MediaType type = pi.getMediaType()  // MOVIE or EPISODE
  Long mediaId = resolveLocalId(plexId, type)  // movieRepo or episodeRepo by plexId
  if mediaId == null: return
  Optional<DownloadQueueItem> qi = queueRepo.findByUserIdAndMediaTypeAndMediaId(user.id, type, mediaId)
  if qi.isPresent():
    String destPath = qi.get().getDestFilePath()
    queueRepo.delete(qi.get())
    if destPath != null:
      try Files.deleteIfExists(Path.of(destPath))
      tdarrClient.deleteFile(destPath)  // fire-and-forget
```

### `PlaylistController`

`@RestController @RequestMapping("/api/playlists")`

```
GET  /api/playlists
  → List<PlaylistResponse>
  PlaylistResponse: { id, plexId, title, playlistType, leafCount, subscribed, posterPlexIds[] }
  posterPlexIds = first 4 playlist_items' plexIds (for 2×2 composite in frontend)
  subscribed = subscriptionRepo.existsByUserIdAndPlaylistId(currentUser.id, playlist.id)

GET  /api/playlists/{id}
  → PlaylistDetailResponse: { ...PlaylistResponse, items: List<PlaylistItemResponse> }
  PlaylistItemResponse: { id, plexId, mediaType, ordinal, title, year, queueStatus, tdarrStatus }
  title/year: resolved from Movie or Episode by plexId (null if not in library)
  queueStatus: from DownloadQueueItem.Status for this user+media (null if no queue item)
  tdarrStatus: from DownloadQueueItem.TdarrStatus (null if no queue item)

POST /api/playlists/{id}/subscribe
  → 204 No Content (idempotent — no-op if already subscribed)
  1. Create PlaylistSubscription if not exists
  2. Load all current playlist_items for this playlist
  3. For each item: call enqueueItem(currentUser, plexItem) — same logic as sync

DELETE /api/playlists/{id}/subscribe
  → 204 No Content
  Removes subscription only — does NOT cancel existing queue items or delete files.
  (User manually decided to unsubscribe; items already queued continue unaffected)
```

### `LibrarySyncService` modification

At the end of `syncAll()`, after all libraries finish:
```java
playlistSyncService.syncAll();
```

---

## Frontend

### `NavBar.vue` — add Playlists link

Between Movies and TV Shows:
```html
<RouterLink to="/playlists">Playlists</RouterLink>
```

### `router/index.js` — add routes

```js
{ path: '/playlists',     component: () => import('@/views/PlaylistsView.vue') },
{ path: '/playlists/:id', component: () => import('@/views/PlaylistDetailView.vue') },
```

### `api/playlists.js`

```js
export const getPlaylists    = ()     => http.get('/api/playlists').then(r => r.data)
export const getPlaylist     = (id)   => http.get(`/api/playlists/${id}`).then(r => r.data)
export const subscribe       = (id)   => http.post(`/api/playlists/${id}/subscribe`)
export const unsubscribe     = (id)   => http.delete(`/api/playlists/${id}/subscribe`)
```

### `PlaylistsView.vue`

- Grid layout matching MoviesView (`repeat(auto-fill, minmax(140px, 1fr))`)
- Each card: 2×2 composite from `posterPlexIds` (up to 4 `/api/posters/{plexId}.jpg`)  
- Gold dot badge (top-right) when `subscribed = true`
- Click → `router.push('/playlists/' + playlist.id)`
- Load all playlists on mount (no pagination — playlist count typically small)

### `PlaylistDetailView.vue`

- Header: title, item count, Subscribe/Unsubscribe button (toggles on click)
- List of items: small poster thumbnail (48px height, `aspect-ratio: 2/3`), title, year, media type, status badge
- Status badge colours:
  - `transcoded` → green (`var(--green)`)
  - `processing` / `IN_PROGRESS` → amber (`#f59e0b`)
  - `queued` / `PENDING` → muted (`var(--text-muted)`)
  - `ERROR` / `TDARR_ERROR` → red (`var(--red)`)
  - `not queued` (null queueStatus) → surface2 chip, muted text
- Back button → `router.back()`

---

## Error Handling

- Plex playlist fetch fails → log warning, skip playlist sync (don't fail library sync)
- `movieRepo.findByPlexId` returns empty (item not in library yet) → log warning, skip enqueue
- Tdarr `deleteFile` fails → log warning only, queue item and local file still deleted
- Local file delete fails → log warning only (`Files.deleteIfExists`)
- Subscribe on already-subscribed playlist → no-op (idempotent)

---

## Not In Scope

- Per-item manual unqueue from playlist detail view
- Playlist ordering / drag reorder
- Playlist creation / editing (Plex-side only)
- Push notifications when new playlist items are queued
