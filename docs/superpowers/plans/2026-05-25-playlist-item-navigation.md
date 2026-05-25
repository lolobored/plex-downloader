# Playlist Item Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clicking an item in the playlist detail view navigates to its movie or episode detail page.

**Architecture:** Add `mediaId`/`showId`/`seasonId` to `PlaylistItemResponse` (derived from existing DB relations); annotate `getPlaylist()` with `@Transactional(readOnly=true)` to allow lazy traversal of Episode→Season→Show. In the frontend, add `@click="navigateToItem(item)"` on each row following the existing `router.push()` pattern.

**Tech Stack:** Spring Boot 3, JPA `FetchType.LAZY`, Vue 3, `vue-router` `useRouter()`, existing `MovieDetailView` / `EpisodeDetailView`

---

## File Map

| File | Change |
|------|--------|
| `backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistItemResponse.java` | Add 3 fields |
| `backend/src/main/java/org/lolobored/plexdownloader/controller/PlaylistController.java` | Populate fields; add `@Transactional` |
| `backend/src/test/java/org/lolobored/plexdownloader/controller/PlaylistControllerTest.java` | Add 2 tests |
| `frontend/src/views/PlaylistDetailView.vue` | Click handler + style |
| `frontend/src/views/__tests__/PlaylistDetailView.test.js` | Add 3 tests; update fixture |

---

### Task 1: Backend — add navigation fields to `PlaylistItemResponse`

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistItemResponse.java`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/controller/PlaylistController.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/controller/PlaylistControllerTest.java`

**Context:** `PlaylistItemResponse` is a Java record. `PlaylistController.toItemResponse()` already fetches `Movie` and `Episode` objects. `Episode.season` and `Season.show` are `FetchType.LAZY` — they must be accessed inside a transaction. Routes used by the frontend: `/movies/:id` and `/tv/:showId/seasons/:seasonId/episodes/:episodeId`.

- [ ] **Step 1: Write failing tests**

Add to `PlaylistControllerTest.java` after the existing `getPlaylist_returnsDetailWithItems` test:

```java
@Test
void getPlaylist_movieItem_includesMediaId() throws Exception {
    Playlist p = new Playlist();
    p.setId(1L); p.setPlexId("pl1"); p.setTitle("Action"); p.setPlaylistType("video"); p.setLeafCount(1);
    when(playlistRepo.findById(1L)).thenReturn(Optional.of(p));
    when(subRepo.existsByUserIdAndPlaylistId(1L, 1L)).thenReturn(false);
    when(itemRepo.findTop4ByPlaylistIdOrderByOrdinalAsc(1L)).thenReturn(List.of());

    PlaylistItem pi = new PlaylistItem();
    pi.setId(10L); pi.setPlexId("m1"); pi.setMediaType("MOVIE"); pi.setOrdinal(0);
    when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(1L)).thenReturn(List.of(pi));

    Movie m = new Movie(); m.setId(100L); m.setTitle("Inception"); m.setYear(2010);
    when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
    when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L))
        .thenReturn(Optional.empty());

    mockMvc.perform(get("/api/playlists/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].mediaId").value(100))
        .andExpect(jsonPath("$.items[0].showId").doesNotExist())
        .andExpect(jsonPath("$.items[0].seasonId").doesNotExist());
}

@Test
void getPlaylist_episodeItem_includesMediaIdShowIdSeasonId() throws Exception {
    Playlist p = new Playlist();
    p.setId(2L); p.setPlexId("pl2"); p.setTitle("TV"); p.setPlaylistType("video"); p.setLeafCount(1);
    when(playlistRepo.findById(2L)).thenReturn(Optional.of(p));
    when(subRepo.existsByUserIdAndPlaylistId(1L, 2L)).thenReturn(false);
    when(itemRepo.findTop4ByPlaylistIdOrderByOrdinalAsc(2L)).thenReturn(List.of());

    PlaylistItem pi = new PlaylistItem();
    pi.setId(20L); pi.setPlexId("ep1"); pi.setMediaType("EPISODE"); pi.setOrdinal(0);
    when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(2L)).thenReturn(List.of(pi));

    TvShow show = new TvShow(); show.setId(5L);
    Season season = new Season(); season.setId(12L); season.setShow(show);
    Episode ep = new Episode();
    ep.setId(99L); ep.setTitle("Pilot"); ep.setSeason(season);

    when(episodeRepo.findByPlexId("ep1")).thenReturn(Optional.of(ep));
    when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.EPISODE, 99L))
        .thenReturn(Optional.empty());

    mockMvc.perform(get("/api/playlists/2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].mediaId").value(99))
        .andExpect(jsonPath("$.items[0].showId").value(5))
        .andExpect(jsonPath("$.items[0].seasonId").value(12));
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java $(cat .sdkmanrc | grep java | cut -d= -f2) && ./gradlew test --tests "*PlaylistControllerTest*" --no-daemon 2>&1 | tail -15
```

Expected: FAIL — `mediaId`/`showId`/`seasonId` missing from response

- [ ] **Step 3: Update `PlaylistItemResponse.java`**

Replace entire file content:

```java
package org.lolobored.plexdownloader.dto;

public record PlaylistItemResponse(
    Long id,
    String plexId,
    String mediaType,
    Integer ordinal,
    String title,
    Integer year,
    String queueStatus,
    String tdarrStatus,
    Long mediaId,
    Long showId,
    Long seasonId
) {}
```

- [ ] **Step 4: Update `PlaylistController.java`**

Add import at the top of the imports block:

```java
import org.springframework.transaction.annotation.Transactional;
```

Add `@Transactional(readOnly = true)` to `getPlaylist()`:

```java
@GetMapping("/{id}")
@Transactional(readOnly = true)
public PlaylistDetailResponse getPlaylist(@PathVariable Long id, @AuthenticationPrincipal User user) {
```

In `toItemResponse()`, add `Long showId = null; Long seasonId = null;` after `Long mediaId = null;`:

```java
private PlaylistItemResponse toItemResponse(PlaylistItem pi, Long userId) {
    String title = null;
    Integer year = null;
    Long mediaId = null;
    Long showId = null;
    Long seasonId = null;

    if ("MOVIE".equals(pi.getMediaType())) {
        Optional<Movie> mOpt = movieRepo.findByPlexId(pi.getPlexId());
        if (mOpt.isPresent()) {
            Movie m = mOpt.get();
            title = m.getTitle();
            year = m.getYear();
            mediaId = m.getId();
        }
    } else if ("EPISODE".equals(pi.getMediaType())) {
        Optional<Episode> epOpt = episodeRepo.findByPlexId(pi.getPlexId());
        if (epOpt.isPresent()) {
            Episode ep = epOpt.get();
            title = ep.getTitle();
            year = ep.getAirDate() != null ? ep.getAirDate().getYear() : null;
            mediaId = ep.getId();
            seasonId = ep.getSeason().getId();
            showId = ep.getSeason().getShow().getId();
        }
    }

    String queueStatus = null;
    String tdarrStatus = null;
    if (mediaId != null) {
        DownloadQueueItem.MediaType type = DownloadQueueItem.MediaType.valueOf(pi.getMediaType());
        Optional<DownloadQueueItem> qiOpt =
            queueRepo.findByUser_IdAndMediaTypeAndMediaId(userId, type, mediaId);
        if (qiOpt.isPresent()) {
            queueStatus = qiOpt.get().getStatus().name();
            tdarrStatus = qiOpt.get().getTdarrStatus() != null ? qiOpt.get().getTdarrStatus().name() : null;
        }
    }

    return new PlaylistItemResponse(pi.getId(), pi.getPlexId(), pi.getMediaType(),
        pi.getOrdinal(), title, year, queueStatus, tdarrStatus, mediaId, showId, seasonId);
}
```

Note: `ep.getSeason()` and `ep.getSeason().getShow()` are `FetchType.LAZY`. They work here because `getPlaylist()` is now `@Transactional(readOnly = true)`, keeping the Hibernate session open. In tests, `Season` and `TvShow` are plain objects (not Hibernate proxies) so lazy loading is not an issue.

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew test --tests "*PlaylistControllerTest*" --no-daemon 2>&1 | tail -10
```

Expected: all PASS

- [ ] **Step 6: Run full backend test suite**

```bash
./gradlew test --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add \
  backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistItemResponse.java \
  backend/src/main/java/org/lolobored/plexdownloader/controller/PlaylistController.java \
  backend/src/test/java/org/lolobored/plexdownloader/controller/PlaylistControllerTest.java
git commit -m "feat: add mediaId/showId/seasonId to PlaylistItemResponse for navigation refs #22"
```

---

### Task 2: Frontend — clickable item rows in `PlaylistDetailView.vue`

**Files:**
- Modify: `frontend/src/views/PlaylistDetailView.vue`
- Modify: `frontend/src/views/__tests__/PlaylistDetailView.test.js`

**Context:** Existing navigation in this codebase uses `router.push(url)` via `@click` (see `MoviesView.vue` line 30 and `TvView.vue` line 18). The vue-router mock in the test file already stubs `useRouter` — extend it with `push: vi.fn()`. Routes: `/movies/:mediaId` for movies, `/tv/:showId/seasons/:seasonId/episodes/:mediaId` for episodes. Items without `mediaId` (not yet synced) must not be clickable.

- [ ] **Step 1: Update test fixtures and write failing tests**

In `PlaylistDetailView.test.js`, update the `fakePlaylist` fixture to include the new fields and add an episode item:

```js
const fakePlaylist = {
  id: 1, plexId: 'pl1', title: 'Action Movies', playlistType: 'video',
  leafCount: 3, subscribed: false, posterPlexIds: [],
  items: [
    { id: 10, plexId: 'm1', mediaType: 'MOVIE', ordinal: 0,
      title: 'The Dark Knight', year: 2008, queueStatus: 'DONE', tdarrStatus: 'TRANSCODED',
      mediaId: 42, showId: null, seasonId: null },
    { id: 11, plexId: 'm2', mediaType: 'MOVIE', ordinal: 1,
      title: 'Inception', year: 2010, queueStatus: null, tdarrStatus: null,
      mediaId: null, showId: null, seasonId: null },
    { id: 12, plexId: 'ep1', mediaType: 'EPISODE', ordinal: 2,
      title: 'Pilot', year: 2020, queueStatus: null, tdarrStatus: null,
      mediaId: 99, showId: 5, seasonId: 12 }
  ]
}
```

Update the `vue-router` mock to add `push`:

```js
vi.mock('vue-router', () => ({
  useRoute:  () => ({ params: { id: '1' } }),
  useRouter: () => ({ back: vi.fn(), push: vi.fn() })
}))
```

Add a module-level `routerMock` reference after the mock declaration so tests can assert on `push`:

```js
import { useRouter } from 'vue-router'
```

Add these 3 tests at the end of the `describe` block:

```js
it('item row with mediaId has clickable class', async () => {
  getPlaylist.mockResolvedValue(fakePlaylist)
  const w = mount(PlaylistDetailView, {
    global: { plugins: [createTestingPinia()] },
    attachTo: document.body
  })
  await flushPromises()
  const rows = w.findAll('.item-row')
  expect(rows[0].classes()).toContain('clickable')   // mediaId=42
  expect(rows[1].classes()).not.toContain('clickable') // mediaId=null
})

it('clicking a movie row navigates to /movies/:mediaId', async () => {
  getPlaylist.mockResolvedValue(fakePlaylist)
  const mockPush = vi.fn()
  useRouter.mockReturnValue({ back: vi.fn(), push: mockPush })
  const w = mount(PlaylistDetailView, {
    global: { plugins: [createTestingPinia()] },
    attachTo: document.body
  })
  await flushPromises()
  await w.findAll('.item-row')[0].trigger('click')
  expect(mockPush).toHaveBeenCalledWith('/movies/42')
})

it('clicking an episode row navigates to /tv/:showId/seasons/:seasonId/episodes/:mediaId', async () => {
  getPlaylist.mockResolvedValue(fakePlaylist)
  const mockPush = vi.fn()
  useRouter.mockReturnValue({ back: vi.fn(), push: mockPush })
  const w = mount(PlaylistDetailView, {
    global: { plugins: [createTestingPinia()] },
    attachTo: document.body
  })
  await flushPromises()
  await w.findAll('.item-row')[2].trigger('click')
  expect(mockPush).toHaveBeenCalledWith('/tv/5/seasons/12/episodes/99')
})
```

Note: `useRouter` must be imported and the mock must support `mockReturnValue`. Update the top of the test file:

```js
import { useRouter } from 'vue-router'
// ... existing imports ...
vi.mock('vue-router', () => ({
  useRoute:  vi.fn(() => ({ params: { id: '1' } })),
  useRouter: vi.fn(() => ({ back: vi.fn(), push: vi.fn() }))
}))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | tail -20
```

Expected: 3 new tests FAIL

- [ ] **Step 3: Update `PlaylistDetailView.vue` — script section**

In `<script setup>`, the `router` ref is already declared via `useRouter()`. Add the `navigateToItem` function after `cancelUnsubscribe()`:

```js
function navigateToItem(item) {
  if (!item.mediaId) return
  if (item.mediaType === 'MOVIE') {
    router.push(`/movies/${item.mediaId}`)
  } else {
    router.push(`/tv/${item.showId}/seasons/${item.seasonId}/episodes/${item.mediaId}`)
  }
}
```

- [ ] **Step 4: Update `PlaylistDetailView.vue` — template section**

Replace the `<div v-for="item in playlist.items"` line (keep all inner content unchanged):

```html
<div v-for="item in playlist.items" :key="item.id"
     class="item-row"
     :class="{ clickable: item.mediaId }"
     @click="navigateToItem(item)">
```

- [ ] **Step 5: Update `PlaylistDetailView.vue` — style section**

Add after `.item-row { ... }` block:

```css
.item-row.clickable { cursor: pointer; }
.item-row.clickable:hover { background: var(--surface3, color-mix(in srgb, var(--surface2) 85%, var(--text) 15%)); }
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
npm run test -- --reporter=verbose 2>&1 | tail -20
```

Expected: all PASS (120+ tests)

- [ ] **Step 7: Commit**

```bash
git add \
  frontend/src/views/PlaylistDetailView.vue \
  frontend/src/views/__tests__/PlaylistDetailView.test.js
git commit -m "feat: clickable playlist items navigate to movie/episode detail closes #22"
```

---

## Verification

```bash
# Backend
cd backend && ./gradlew test --no-daemon  # all green

# Frontend
cd frontend && npm run test              # all green
```

Manual:
1. Open playlist detail page
2. Click a movie item → lands on `/movies/:id`
3. Click an episode item → lands on `/tv/:showId/seasons/:seasonId/episodes/:episodeId`
4. Items without `mediaId` (not synced) → click does nothing, no `clickable` class
