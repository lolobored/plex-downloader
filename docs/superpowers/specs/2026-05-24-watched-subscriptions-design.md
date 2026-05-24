# Watched Subscriptions — Design Spec

**Date:** 2026-05-24
**Stack:** Spring Boot · Vue 3 · PostgreSQL

---

## 1. Purpose

Allow users to subscribe to a TV show with a target "unwatched buffer" (5 / 10 / 15 / 20 episodes). A background scheduler polls each user's Plex watch history; when an episode is marked watched, the system auto-enqueues the next unwatched episode to top the buffer back up. Users can also trigger a one-time download of the next N unwatched episodes without creating a standing subscription.

Watched status is per-user (each user's own Plex token). Watched indicators appear on episode cards.

**Out of scope:** Physical file deletion after watching (deferred to Tdarr integration spec).

---

## 2. Architecture

```
WatchedSyncScheduler  (cron: watched.sync.cron, default every 15 min)
  └── WatchedSyncService.syncShow(userId, showId)
        └── GET /library/metadata/{plexId}/allLeaves?X-Plex-Token={userToken}
        └── upsert → user_episode_watched
  └── SubscriptionService.replenish(subscription)
        └── count active buffer (PENDING/IN_PROGRESS/DONE, not watched)
        └── enqueue next N unwatched episodes if buffer < target_count

TvShowDetailView / SeasonDetailView (on mount)
  └── GET /api/tv/{showId}/watched
        └── WatchedSyncService.syncIfStale(userId, showId)  (stale = last sync > 1h)
        └── return Set<episodeId> watched by current user
```

---

## 3. Data Model

All migrations via Liquibase in `backend/src/main/resources/db/changelog/`.

### `users` — add column

```sql
ALTER TABLE users ADD COLUMN plex_token VARCHAR(255);
```

Populated at login: `AuthService` must save the Plex auth token returned by `GET https://plex.tv/users/account` (the `authToken` field) into `users.plex_token` when creating or updating the user record. Required for per-user Plex API calls.

### New table: `user_episode_watched`

```sql
CREATE TABLE user_episode_watched (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id  BIGINT NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
    watched_at  TIMESTAMP,           -- from Plex viewedAt, nullable
    synced_at   TIMESTAMP NOT NULL,  -- when we last fetched from Plex
    UNIQUE (user_id, episode_id)
);
```

### New table: `show_subscriptions`

```sql
CREATE TABLE show_subscriptions (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    show_id       BIGINT NOT NULL REFERENCES tv_shows(id) ON DELETE CASCADE,
    target_count  INT NOT NULL CHECK (target_count IN (5, 10, 15, 20)),
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (user_id, show_id)
);
```

---

## 4. Backend

### Package additions

```
service/
  WatchedSyncService.java      # fetch + upsert watched status from Plex
  WatchedSyncScheduler.java    # @Scheduled, iterates subscriptions
  SubscriptionService.java     # buffer check + auto-enqueue logic
controller/
  WatchedController.java       # GET /api/tv/{showId}/watched
  SubscriptionController.java  # CRUD /api/subscriptions + sync + one-time enqueue
repository/
  UserEpisodeWatchedRepository.java
  ShowSubscriptionRepository.java
dto/
  SubscriptionRequest.java     # { showId, targetCount }
  SubscriptionResponse.java    # { id, showId, targetCount, updatedAt }
  WatchedResponse.java         # { watchedEpisodeIds: List<Long> }
```

### `WatchedSyncService`

```java
// Fetch all episodes for a show from Plex, upsert user_episode_watched
void syncShow(Long userId, Long showId);

// Sync only if last synced > 1 hour ago (lazy path from controller)
void syncIfStale(Long userId, Long showId);

// Return watched episode IDs for a user+show (from DB, after sync)
Set<Long> getWatchedEpisodeIds(Long userId, Long showId);
```

Calls: `GET {plexUrl}/library/metadata/{show.plexId}/allLeaves?X-Plex-Token={user.plexToken}`

Response field used: `viewCount > 0` → watched. `lastViewedAt` → `watched_at`.

### `SubscriptionService`

```java
// Create or update subscription
SubscriptionResponse upsert(Long userId, Long showId, int targetCount);

// Cancel subscription
void cancel(Long userId, Long showId);

// Check buffer and enqueue missing episodes — called after every sync
void replenish(ShowSubscription sub);

// One-time enqueue (no subscription created)
List<Long> enqueueUnwatched(Long userId, Long showId, int limit);
```

**Buffer computation in `replenish`:**
1. Fetch all episodes for `showId` ordered by `(season_number ASC, episode_number ASC)`
2. Exclude episodes in `user_episode_watched` for this user (watched)
3. Exclude episodes already in `download_queue` with status PENDING / IN_PROGRESS / DONE for this user
4. Take the first `(target_count - active_buffer_size)` remaining episodes
5. Enqueue each via existing `DownloadService`

**Active buffer size** = count of episodes for this show in `download_queue` (PENDING/IN_PROGRESS/DONE) that are NOT in `user_episode_watched` for this user.

### `WatchedSyncScheduler`

```java
@Scheduled(cron = "${watched.sync.cron:0 */15 * * * *}")
void syncAll() {
    subscriptionRepo.findAll().forEach(sub -> {
        watchedSyncService.syncShow(sub.getUserId(), sub.getShowId());
        subscriptionService.replenish(sub);
    });
}
```

Cron expression stored in `settings` table under key `watched.sync.cron`. Default: `0 */15 * * * *` (every 15 min).

### REST API

```
# Watched status (lazy sync on call)
GET  /api/tv/{showId}/watched
     → 200 { watchedEpisodeIds: [1, 2, 5, 8] }

# Subscriptions
GET    /api/subscriptions
       → 200 [ { id, showId, targetCount, updatedAt } ]

POST   /api/subscriptions
       body: { showId, targetCount }
       → 200 SubscriptionResponse  (create or update)

DELETE /api/subscriptions/{showId}
       → 204

POST   /api/subscriptions/{showId}/sync
       → 200 { watchedEpisodeIds, enqueuedCount }
       (force sync + replenish now)

# One-time bulk enqueue (no subscription)
POST   /api/queue/show/{showId}/unwatched
       body: { limit }   (5 | 10 | 15 | 20)
       → 200 { jobIds: [...] }
```

All endpoints require JWT auth. Subscription endpoints operate on `currentUser` from JWT — users cannot read/modify other users' subscriptions.

---

## 5. Frontend

### New API functions (`src/api/watched.js`)

```js
getWatched(showId)              // GET /api/tv/{showId}/watched
getSubscriptions()              // GET /api/subscriptions
subscribe(showId, targetCount)  // POST /api/subscriptions
unsubscribe(showId)             // DELETE /api/subscriptions/{showId}
syncNow(showId)                 // POST /api/subscriptions/{showId}/sync
enqueueUnwatched(showId, limit) // POST /api/queue/show/{showId}/unwatched
```

### New Pinia store (`src/stores/watched.js`)

```js
export const useWatchedStore = defineStore('watched', () => {
  const watchedByShow    = ref(new Map())  // Map<showId, Set<episodeId>>
  const subscriptions    = ref(new Map())  // Map<showId, targetCount>

  async function fetchWatched(showId) { ... }      // populates watchedByShow
  async function fetchSubscriptions() { ... }       // populates subscriptions
  async function subscribe(showId, n) { ... }
  async function unsubscribe(showId) { ... }
  async function syncNow(showId) { ... }
  async function enqueueUnwatched(showId, limit) { ... }

  function isWatched(showId, episodeId) {
    return watchedByShow.value.get(showId)?.has(episodeId) ?? false
  }

  return { watchedByShow, subscriptions, fetchWatched, fetchSubscriptions,
           subscribe, unsubscribe, syncNow, enqueueUnwatched, isWatched }
})
```

### Modified views

**`TvShowDetailView.vue`**
- On mount: `watchedStore.fetchWatched(showId)` + `watchedStore.fetchSubscriptions()`
- Replace `<DownloadButton type="SHOW" :mediaId="show.id" />` with `<SubscribeButton :showId="show.id" />`
- Replace season card `<DownloadButton type="SEASON" :mediaId="s.id" small />` with `<SubscribeButton :showId="show.id" small />`

**`SeasonDetailView.vue`**
- On mount: `watchedStore.fetchWatched(showId)`
- Replace `<DownloadButton type="SEASON" :mediaId="season.id" />` with `<SubscribeButton :showId="show.id" />`
- Add watched badge on each episode card: `<span v-if="watchedStore.isWatched(showId, ep.id)" class="watched-badge">✓ Watched</span>`

**`TvView.vue`**
- Remove `<DownloadButton type="SHOW" :mediaId="s.id" small />` from poster badge slot (show-level grid: no subscription UI needed here, too small)

**`SettingsView.vue`**
- Add `watched.sync.cron` field under Library Sync section (text input, same style as `plex.sync.cron`)

### New component: `SubscribeButton.vue`

Props: `{ showId: Number, small: Boolean }`

States:

| State | Display |
|-------|---------|
| No subscription, idle | `⬇ Download` → opens picker dropdown |
| Picker open | Radio options: Next 5 / 10 / 15 / 20 / One-time only + Confirm |
| Active subscription | `📥 Next {n} unwatched` → click opens edit/cancel |
| Syncing | `⏳ Syncing…` (disabled) |

"One-time only" in picker calls `enqueueUnwatched` (no subscription). Choosing 5/10/15/20 calls `subscribe` (standing subscription + immediate replenish via `/sync`).

---

## 6. Settings

New setting key added to `settings` table default data (Liquibase):

| Key | Default | Description |
|-----|---------|-------------|
| `watched.sync.cron` | `0 */15 * * * *` | Cron for background watched sync |

Displayed on Settings page under Library Sync section alongside existing `plex.sync.cron`.

---

## 7. Error Handling

- Plex token missing for user → `WatchedSyncService` logs warning, skips that user, does not throw
- Plex API returns 401 (token expired/revoked) → log warning, skip; user must re-login to refresh token
- Enqueue fails (file not found, disk full) → existing `DownloadService` error handling; `SubscriptionService` logs, moves on
- `GET /api/tv/{showId}/watched` → if sync fails, return last cached data (don't 500)

---

## 8. Out of Scope

- Physical file deletion after watching (Tdarr integration spec)
- Movies (no sequential "next unwatched" concept)
- Per-season subscriptions (show-level only)
- Notifications when buffer replenishes
