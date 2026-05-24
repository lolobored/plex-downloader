# Watched Subscriptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users subscribe to a TV show with a "keep N unwatched episodes ahead" buffer that auto-replenishes when episodes are watched, backed by per-user Plex watch-history sync.

**Architecture:** Per-user watch status is fetched from Plex (using each user's stored OAuth token), cached in `user_episode_watched`, and kept fresh by a configurable background scheduler. `ShowSubscription` stores the target buffer size per user per show; `SubscriptionService` computes the deficit and enqueues episodes. Frontend uses a `watchedStore` (Pinia) and a `SubscribeButton` component that replaces the stub `DownloadButton` on TV show and season pages.

**Tech Stack:** Spring Boot 3 · JPA/Hibernate · Liquibase · Mockito/JUnit 5 · Vue 3 Composition API · Pinia 2 · Vitest 4

---

## File Map

```
backend/src/main/resources/db/changelog/sql/
  003-watched-subscriptions.sql                      NEW — migration

backend/src/main/java/com/plexdownloader/
  model/
    UserEpisodeWatched.java                          NEW — JPA entity
    ShowSubscription.java                            NEW — JPA entity
  repository/
    UserEpisodeWatchedRepository.java                NEW — repo
    ShowSubscriptionRepository.java                  NEW — repo
    DownloadQueueRepository.java                     MODIFY — add findActiveEpisodeIdsForShow
  dto/
    SubscriptionRequest.java                         NEW — { showId, targetCount }
    SubscriptionResponse.java                        NEW — { id, showId, targetCount, updatedAt }
    WatchedResponse.java                             NEW — { watchedEpisodeIds }
    UnwatchedEnqueueRequest.java                     NEW — { limit }
  service/
    WatchedSyncService.java                          NEW — fetch+upsert watched status from Plex
    SubscriptionService.java                         NEW — upsert/cancel/replenish/enqueueUnwatched
    WatchedSyncScheduler.java                        NEW — background cron
  controller/
    WatchedController.java                           NEW — GET /api/tv/{showId}/watched
    SubscriptionController.java                      NEW — CRUD /api/subscriptions
    DownloadController.java                          MODIFY — add POST /show/{showId}/unwatched

backend/src/test/java/com/plexdownloader/
  service/
    WatchedSyncServiceTest.java                      NEW
    SubscriptionServiceTest.java                     NEW
  controller/
    WatchedControllerTest.java                       NEW
    SubscriptionControllerTest.java                  NEW

frontend/src/
  api/watched.js                                     NEW — 6 thin axios calls
  stores/watched.js                                  NEW — Pinia store
  stores/__tests__/watched.test.js                   NEW
  components/SubscribeButton.vue                     NEW
  components/__tests__/SubscribeButton.test.js       NEW
  views/TvShowDetailView.vue                         MODIFY — SubscribeButton + fetchWatched
  views/SeasonDetailView.vue                         MODIFY — SubscribeButton + watched badges
  views/TvView.vue                                   MODIFY — remove DownloadButton badge slot
  views/SettingsView.vue                             MODIFY — add watched.sync.cron field
```

---

## Run Commands

**Backend tests** (run from repo root):
```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test
```

**Frontend tests** (run from repo root):
```bash
cd frontend && npm test -- --run
```

---

## Task 1: DB Migration

**Files:**
- Create: `backend/src/main/resources/db/changelog/sql/003-watched-subscriptions.sql`

> Note: `users.plex_token` already exists (added in `002-library-sync-schema.sql`). No users table change needed.

- [ ] **Step 1: Create the migration file**

```sql
-- liquibase formatted sql

-- changeset plexdownloader:003-user-episode-watched
CREATE TABLE user_episode_watched (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id  BIGINT NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
    watched_at  TIMESTAMP,
    synced_at   TIMESTAMP NOT NULL,
    CONSTRAINT uq_user_episode UNIQUE (user_id, episode_id)
);
CREATE INDEX idx_uew_user_episode ON user_episode_watched (user_id, episode_id);

-- changeset plexdownloader:003-show-subscriptions
CREATE TABLE show_subscriptions (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    show_id       BIGINT NOT NULL REFERENCES tv_shows(id) ON DELETE CASCADE,
    target_count  INT NOT NULL CHECK (target_count IN (5, 10, 15, 20)),
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_show_sub UNIQUE (user_id, show_id)
);

-- changeset plexdownloader:003-watched-sync-cron-setting
INSERT INTO settings (key, value) VALUES ('watched.sync.cron', '0 */15 * * * *')
ON CONFLICT (key) DO NOTHING;
```

- [ ] **Step 2: Verify migration compiles (Liquibase runs on app startup)**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/sql/003-watched-subscriptions.sql
git commit -m "feat: add DB migration for user_episode_watched and show_subscriptions"
```

---

## Task 2: Backend Models

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/model/UserEpisodeWatched.java`
- Create: `backend/src/main/java/com/plexdownloader/model/ShowSubscription.java`

- [ ] **Step 1: Create `UserEpisodeWatched.java`**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "user_episode_watched",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "episode_id"}))
public class UserEpisodeWatched {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "episode_id", nullable = false)
    private Episode episode;

    @Column(name = "watched_at")
    private Instant watchedAt;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
```

- [ ] **Step 2: Create `ShowSubscription.java`**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.time.Instant;

@Data
@ToString(exclude = {"user", "show"})
@Entity
@Table(name = "show_subscriptions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "show_id"}))
public class ShowSubscription {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "show_id", nullable = false)
    private TvShow show;

    @Column(name = "target_count", nullable = false)
    private Integer targetCount;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
```

- [ ] **Step 3: Compile**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/model/UserEpisodeWatched.java \
        backend/src/main/java/com/plexdownloader/model/ShowSubscription.java
git commit -m "feat: add UserEpisodeWatched and ShowSubscription JPA models"
```

---

## Task 3: Repositories

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/repository/UserEpisodeWatchedRepository.java`
- Create: `backend/src/main/java/com/plexdownloader/repository/ShowSubscriptionRepository.java`
- Modify: `backend/src/main/java/com/plexdownloader/repository/DownloadQueueRepository.java`

- [ ] **Step 1: Create `UserEpisodeWatchedRepository.java`**

```java
package com.plexdownloader.repository;

import com.plexdownloader.model.UserEpisodeWatched;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface UserEpisodeWatchedRepository extends JpaRepository<UserEpisodeWatched, Long> {

    Optional<UserEpisodeWatched> findByUserIdAndEpisodeId(Long userId, Long episodeId);

    @Query("SELECT w.episode.id FROM UserEpisodeWatched w " +
           "WHERE w.user.id = :userId AND w.episode.season.show.id = :showId")
    Set<Long> findWatchedEpisodeIds(@Param("userId") Long userId, @Param("showId") Long showId);

    @Query("SELECT MAX(w.syncedAt) FROM UserEpisodeWatched w " +
           "WHERE w.user.id = :userId AND w.episode.season.show.id = :showId")
    Optional<Instant> findLastSyncAt(@Param("userId") Long userId, @Param("showId") Long showId);
}
```

- [ ] **Step 2: Create `ShowSubscriptionRepository.java`**

```java
package com.plexdownloader.repository;

import com.plexdownloader.model.ShowSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ShowSubscriptionRepository extends JpaRepository<ShowSubscription, Long> {

    List<ShowSubscription> findByUserId(Long userId);

    Optional<ShowSubscription> findByUserIdAndShowId(Long userId, Long showId);

    @Query("SELECT s FROM ShowSubscription s JOIN FETCH s.user JOIN FETCH s.show")
    List<ShowSubscription> findAllWithUserAndShow();
}
```

- [ ] **Step 3: Add method to `DownloadQueueRepository.java`**

Current file is at `backend/src/main/java/com/plexdownloader/repository/DownloadQueueRepository.java`. Add this method:

```java
    @Query("SELECT i.mediaId FROM DownloadQueueItem i " +
           "WHERE i.user.id = :userId AND i.mediaType = 'EPISODE' " +
           "AND i.status IN ('PENDING', 'IN_PROGRESS', 'DONE') " +
           "AND i.mediaId IN (SELECT e.id FROM Episode e WHERE e.season.show.id = :showId)")
    Set<Long> findActiveEpisodeIdsForShow(@Param("userId") Long userId, @Param("showId") Long showId);
```

Also add the import `import java.util.Set;` and `import org.springframework.data.repository.query.Param;` if not present.

The full updated `DownloadQueueRepository.java`:

```java
package com.plexdownloader.repository;

import com.plexdownloader.model.DownloadQueueItem;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface DownloadQueueRepository extends JpaRepository<DownloadQueueItem, Long> {

    List<DownloadQueueItem> findAllByOrderByQueuePositionAsc();

    @Query("SELECT i FROM DownloadQueueItem i WHERE i.status = 'IN_PROGRESS'")
    Optional<DownloadQueueItem> findInProgress();

    @Query("SELECT MAX(i.queuePosition) FROM DownloadQueueItem i WHERE i.status = 'PENDING'")
    Optional<Integer> findMaxQueuePosition();

    @Query("SELECT i.mediaId FROM DownloadQueueItem i " +
           "WHERE i.user.id = :userId AND i.mediaType = 'EPISODE' " +
           "AND i.status IN ('PENDING', 'IN_PROGRESS', 'DONE') " +
           "AND i.mediaId IN (SELECT e.id FROM Episode e WHERE e.season.show.id = :showId)")
    Set<Long> findActiveEpisodeIdsForShow(@Param("userId") Long userId, @Param("showId") Long showId);
}
```

- [ ] **Step 4: Compile**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/repository/UserEpisodeWatchedRepository.java \
        backend/src/main/java/com/plexdownloader/repository/ShowSubscriptionRepository.java \
        backend/src/main/java/com/plexdownloader/repository/DownloadQueueRepository.java
git commit -m "feat: add UserEpisodeWatched and ShowSubscription repositories, extend queue repo"
```

---

## Task 4: DTOs

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/dto/SubscriptionRequest.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/SubscriptionResponse.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/WatchedResponse.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/UnwatchedEnqueueRequest.java`

- [ ] **Step 1: Create `SubscriptionRequest.java`**

```java
package com.plexdownloader.dto;

public record SubscriptionRequest(Long showId, Integer targetCount) {}
```

- [ ] **Step 2: Create `SubscriptionResponse.java`**

```java
package com.plexdownloader.dto;

import com.plexdownloader.model.ShowSubscription;
import java.time.Instant;

public record SubscriptionResponse(Long id, Long showId, Integer targetCount, Instant updatedAt) {
    public static SubscriptionResponse from(ShowSubscription s) {
        return new SubscriptionResponse(
            s.getId(), s.getShow().getId(), s.getTargetCount(), s.getUpdatedAt());
    }
}
```

- [ ] **Step 3: Create `WatchedResponse.java`**

```java
package com.plexdownloader.dto;

import java.util.Set;

public record WatchedResponse(Set<Long> watchedEpisodeIds) {}
```

- [ ] **Step 4: Create `UnwatchedEnqueueRequest.java`**

```java
package com.plexdownloader.dto;

public record UnwatchedEnqueueRequest(Integer limit) {}
```

- [ ] **Step 5: Compile**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/dto/SubscriptionRequest.java \
        backend/src/main/java/com/plexdownloader/dto/SubscriptionResponse.java \
        backend/src/main/java/com/plexdownloader/dto/WatchedResponse.java \
        backend/src/main/java/com/plexdownloader/dto/UnwatchedEnqueueRequest.java
git commit -m "feat: add subscription and watched DTOs"
```

---

## Task 5: WatchedSyncService

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/service/WatchedSyncService.java`
- Test: `backend/src/test/java/com/plexdownloader/service/WatchedSyncServiceTest.java`

- [ ] **Step 1: Write failing tests**

Create `backend/src/test/java/com/plexdownloader/service/WatchedSyncServiceTest.java`:

```java
package com.plexdownloader.service;

import com.plexdownloader.model.*;
import com.plexdownloader.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WatchedSyncServiceTest {

    @Mock UserRepository userRepo;
    @Mock UserEpisodeWatchedRepository watchedRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock TvShowRepository showRepo;
    @Mock SettingsService settings;
    @Spy @InjectMocks WatchedSyncService service;

    User user;
    TvShow show;

    @BeforeEach
    void setup() {
        user = new User();
        user.setId(1L);
        user.setPlexToken("tok123");

        show = new TvShow();
        show.setId(10L);
        show.setPlexId("plex-show-1");
    }

    @Test
    void syncShow_skipsWhenUserHasNoToken() {
        user.setPlexToken(null);
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));

        service.syncShow(1L, 10L);

        verify(showRepo, never()).findById(any());
        verify(watchedRepo, never()).save(any());
    }

    @Test
    void syncShow_upsertsWatchedEpisodes() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(showRepo.findById(10L)).thenReturn(Optional.of(show));
        when(settings.getRequired("plex.server.url")).thenReturn("http://plex:32400");

        WatchedSyncService.AllLeavesResponse mockResp = new WatchedSyncService.AllLeavesResponse();
        WatchedSyncService.AllLeavesResponse.Container container =
            new WatchedSyncService.AllLeavesResponse.Container();
        WatchedSyncService.PlexEpisodeWatchStatus item =
            new WatchedSyncService.PlexEpisodeWatchStatus();
        item.setRatingKey("ep-plex-1");
        item.setViewCount(2);
        item.setLastViewedAt(1000000L);
        container.setMetadata(java.util.List.of(item));
        mockResp.setMediaContainer(container);

        doReturn(mockResp).when(service).fetchAllLeaves("http://plex:32400", "tok123", "plex-show-1");

        Episode ep = new Episode();
        ep.setId(5L);
        ep.setPlexId("ep-plex-1");
        when(episodeRepo.findByPlexId("ep-plex-1")).thenReturn(Optional.of(ep));
        when(watchedRepo.findByUserIdAndEpisodeId(1L, 5L)).thenReturn(Optional.empty());
        when(watchedRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncShow(1L, 10L);

        verify(watchedRepo).save(argThat(w ->
            w.getUser().getId().equals(1L) &&
            w.getEpisode().getId().equals(5L) &&
            w.getWatchedAt().equals(Instant.ofEpochSecond(1000000L))
        ));
    }

    @Test
    void syncShow_skipsUnwatchedEpisodes() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(showRepo.findById(10L)).thenReturn(Optional.of(show));
        when(settings.getRequired("plex.server.url")).thenReturn("http://plex:32400");

        WatchedSyncService.AllLeavesResponse mockResp = new WatchedSyncService.AllLeavesResponse();
        WatchedSyncService.AllLeavesResponse.Container container =
            new WatchedSyncService.AllLeavesResponse.Container();
        WatchedSyncService.PlexEpisodeWatchStatus item =
            new WatchedSyncService.PlexEpisodeWatchStatus();
        item.setRatingKey("ep-plex-2");
        item.setViewCount(0);
        container.setMetadata(java.util.List.of(item));
        mockResp.setMediaContainer(container);

        doReturn(mockResp).when(service).fetchAllLeaves(anyString(), anyString(), anyString());

        service.syncShow(1L, 10L);

        verify(watchedRepo, never()).save(any());
    }

    @Test
    void syncIfStale_callsSyncWhenNeverSynced() {
        when(watchedRepo.findLastSyncAt(1L, 10L)).thenReturn(Optional.empty());
        doNothing().when(service).syncShow(1L, 10L);

        service.syncIfStale(1L, 10L);

        verify(service).syncShow(1L, 10L);
    }

    @Test
    void syncIfStale_doesNotSyncWhenFresh() {
        when(watchedRepo.findLastSyncAt(1L, 10L))
            .thenReturn(Optional.of(Instant.now().minus(30, ChronoUnit.MINUTES)));
        doNothing().when(service).syncShow(anyLong(), anyLong());

        service.syncIfStale(1L, 10L);

        verify(service, never()).syncShow(anyLong(), anyLong());
    }

    @Test
    void getWatchedEpisodeIds_delegatesToRepo() {
        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(5L, 6L));

        Set<Long> result = service.getWatchedEpisodeIds(1L, 10L);

        assertThat(result).containsExactlyInAnyOrder(5L, 6L);
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.WatchedSyncServiceTest"
```

Expected: FAIL (WatchedSyncService does not exist)

- [ ] **Step 3: Create `WatchedSyncService.java`**

```java
package com.plexdownloader.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.plexdownloader.model.*;
import com.plexdownloader.repository.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchedSyncService {

    private final UserRepository userRepo;
    private final UserEpisodeWatchedRepository watchedRepo;
    private final EpisodeRepository episodeRepo;
    private final TvShowRepository showRepo;
    private final SettingsService settings;

    @Transactional
    public void syncShow(Long userId, Long showId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null || user.getPlexToken() == null || user.getPlexToken().isBlank()) {
            log.warn("User {} has no Plex token, skipping watched sync for show {}", userId, showId);
            return;
        }
        TvShow show = showRepo.findById(showId).orElse(null);
        if (show == null) {
            log.warn("Show {} not found, skipping watched sync", showId);
            return;
        }

        String plexUrl = settings.getRequired("plex.server.url");
        AllLeavesResponse resp = fetchAllLeaves(plexUrl, user.getPlexToken(), show.getPlexId());

        if (resp == null || resp.getMediaContainer() == null
                || resp.getMediaContainer().getMetadata() == null) {
            log.warn("Empty allLeaves response for show {}", showId);
            return;
        }

        Instant now = Instant.now();
        for (PlexEpisodeWatchStatus item : resp.getMediaContainer().getMetadata()) {
            if (item.getViewCount() == null || item.getViewCount() == 0) continue;
            episodeRepo.findByPlexId(item.getRatingKey()).ifPresent(episode -> {
                UserEpisodeWatched watched = watchedRepo
                    .findByUserIdAndEpisodeId(userId, episode.getId())
                    .orElseGet(() -> {
                        UserEpisodeWatched w = new UserEpisodeWatched();
                        w.setUser(user);
                        w.setEpisode(episode);
                        return w;
                    });
                if (item.getLastViewedAt() != null) {
                    watched.setWatchedAt(Instant.ofEpochSecond(item.getLastViewedAt()));
                }
                watched.setSyncedAt(now);
                watchedRepo.save(watched);
            });
        }
        log.info("Watched sync complete for user={} show={}", userId, showId);
    }

    public void syncIfStale(Long userId, Long showId) {
        Optional<Instant> lastSync = watchedRepo.findLastSyncAt(userId, showId);
        if (lastSync.isEmpty()
                || lastSync.get().isBefore(Instant.now().minus(1, ChronoUnit.HOURS))) {
            syncShow(userId, showId);
        }
    }

    public Set<Long> getWatchedEpisodeIds(Long userId, Long showId) {
        return watchedRepo.findWatchedEpisodeIds(userId, showId);
    }

    // Protected for mocking in tests
    protected AllLeavesResponse fetchAllLeaves(String plexUrl, String plexToken, String showPlexId) {
        RestClient client = RestClient.builder()
            .baseUrl(plexUrl)
            .defaultHeader("X-Plex-Token", plexToken)
            .defaultHeader("X-Plex-Client-Identifier", "plex-downloader-app")
            .defaultHeader("Accept", "application/json")
            .build();
        return client.get()
            .uri("/library/metadata/{ratingKey}/allLeaves", showPlexId)
            .retrieve()
            .body(AllLeavesResponse.class);
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AllLeavesResponse {
        @JsonProperty("MediaContainer")
        private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Container {
            @JsonProperty("Metadata")
            private List<PlexEpisodeWatchStatus> metadata;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexEpisodeWatchStatus {
        private String ratingKey;
        private Integer viewCount;
        private Long lastViewedAt;
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.WatchedSyncServiceTest"
```

Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/service/WatchedSyncService.java \
        backend/src/test/java/com/plexdownloader/service/WatchedSyncServiceTest.java
git commit -m "feat: add WatchedSyncService with per-user Plex watch history sync"
```

---

## Task 6: SubscriptionService

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/service/SubscriptionService.java`
- Test: `backend/src/test/java/com/plexdownloader/service/SubscriptionServiceTest.java`

- [ ] **Step 1: Write failing tests**

Create `backend/src/test/java/com/plexdownloader/service/SubscriptionServiceTest.java`:

```java
package com.plexdownloader.service;

import com.plexdownloader.model.*;
import com.plexdownloader.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock ShowSubscriptionRepository subscriptionRepo;
    @Mock UserEpisodeWatchedRepository watchedRepo;
    @Mock DownloadQueueRepository queueRepo;
    @Mock DownloadService downloadService;
    @Mock EpisodeRepository episodeRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock UserRepository userRepo;
    @Mock TvShowRepository showRepo;
    @InjectMocks SubscriptionService service;

    User user;
    TvShow show;
    Season season;
    Episode ep1, ep2, ep3;

    @BeforeEach
    void setup() {
        user = new User(); user.setId(1L);
        show = new TvShow(); show.setId(10L);
        season = new Season(); season.setId(100L); season.setSeasonNumber(1);

        ep1 = new Episode(); ep1.setId(1L); ep1.setEpisodeNumber(1);
        ep2 = new Episode(); ep2.setId(2L); ep2.setEpisodeNumber(2);
        ep3 = new Episode(); ep3.setId(3L); ep3.setEpisodeNumber(3);
    }

    @Test
    void upsert_createsNewSubscription() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(showRepo.findById(10L)).thenReturn(Optional.of(show));
        when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.empty());
        when(subscriptionRepo.save(any())).thenAnswer(inv -> {
            ShowSubscription s = inv.getArgument(0);
            s.setId(99L);
            return s;
        });

        var resp = service.upsert(1L, 10L, 5);

        assertThat(resp.targetCount()).isEqualTo(5);
        assertThat(resp.showId()).isEqualTo(10L);
    }

    @Test
    void upsert_updatesExistingSubscription() {
        ShowSubscription existing = new ShowSubscription();
        existing.setId(5L); existing.setUser(user); existing.setShow(show);
        existing.setTargetCount(10); existing.setUpdatedAt(Instant.now());

        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(showRepo.findById(10L)).thenReturn(Optional.of(show));
        when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.of(existing));
        when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var resp = service.upsert(1L, 10L, 20);

        assertThat(resp.targetCount()).isEqualTo(20);
    }

    @Test
    void cancel_deletesSubscription() {
        ShowSubscription sub = new ShowSubscription();
        sub.setId(5L);
        when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.of(sub));

        service.cancel(1L, 10L);

        verify(subscriptionRepo).delete(sub);
    }

    @Test
    void replenish_enqueuesUpToTarget() {
        ShowSubscription sub = new ShowSubscription();
        sub.setUser(user); sub.setShow(show); sub.setTargetCount(2);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of());
        when(queueRepo.findActiveEpisodeIdsForShow(1L, 10L)).thenReturn(Set.of());
        when(seasonRepo.findByShowIdOrderBySeasonNumber(10L)).thenReturn(List.of(season));
        when(episodeRepo.findBySeasonIdOrderByEpisodeNumber(100L))
            .thenReturn(List.of(ep1, ep2, ep3));
        when(downloadService.enqueueEpisode(anyLong(), any())).thenReturn(List.of(99L));

        service.replenish(sub);

        verify(downloadService, times(2)).enqueueEpisode(anyLong(), eq(user));
        verify(downloadService).enqueueEpisode(1L, user);
        verify(downloadService).enqueueEpisode(2L, user);
    }

    @Test
    void replenish_doesNothingWhenBufferFull() {
        ShowSubscription sub = new ShowSubscription();
        sub.setUser(user); sub.setShow(show); sub.setTargetCount(2);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of());
        when(queueRepo.findActiveEpisodeIdsForShow(1L, 10L)).thenReturn(Set.of(1L, 2L));

        service.replenish(sub);

        verify(downloadService, never()).enqueueEpisode(anyLong(), any());
    }

    @Test
    void replenish_skipsWatchedEpisodes() {
        ShowSubscription sub = new ShowSubscription();
        sub.setUser(user); sub.setShow(show); sub.setTargetCount(1);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(1L));
        when(queueRepo.findActiveEpisodeIdsForShow(1L, 10L)).thenReturn(Set.of());
        when(seasonRepo.findByShowIdOrderBySeasonNumber(10L)).thenReturn(List.of(season));
        when(episodeRepo.findBySeasonIdOrderByEpisodeNumber(100L))
            .thenReturn(List.of(ep1, ep2));
        when(downloadService.enqueueEpisode(anyLong(), any())).thenReturn(List.of(99L));

        service.replenish(sub);

        verify(downloadService).enqueueEpisode(2L, user); // ep1 is watched, so ep2 is first
        verify(downloadService, never()).enqueueEpisode(1L, any());
    }

    @Test
    void enqueueUnwatched_queuesFirstNUnwatched() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(1L));
        when(queueRepo.findActiveEpisodeIdsForShow(1L, 10L)).thenReturn(Set.of());
        when(seasonRepo.findByShowIdOrderBySeasonNumber(10L)).thenReturn(List.of(season));
        when(episodeRepo.findBySeasonIdOrderByEpisodeNumber(100L))
            .thenReturn(List.of(ep1, ep2, ep3));
        when(downloadService.enqueueEpisode(anyLong(), any())).thenReturn(List.of(99L));

        List<Long> jobIds = service.enqueueUnwatched(1L, 10L, 2);

        assertThat(jobIds).hasSize(2);
        verify(downloadService).enqueueEpisode(2L, user); // ep1 watched, ep2 and ep3 queued
        verify(downloadService).enqueueEpisode(3L, user);
    }

    @Test
    void listSubscriptions_returnsUserSubs() {
        ShowSubscription sub = new ShowSubscription();
        sub.setId(1L); sub.setUser(user); sub.setShow(show);
        sub.setTargetCount(10); sub.setUpdatedAt(Instant.now());
        when(subscriptionRepo.findByUserId(1L)).thenReturn(List.of(sub));

        var result = service.listSubscriptions(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).targetCount()).isEqualTo(10);
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.SubscriptionServiceTest"
```

Expected: FAIL

- [ ] **Step 3: Create `SubscriptionService.java`**

```java
package com.plexdownloader.service;

import com.plexdownloader.dto.SubscriptionResponse;
import com.plexdownloader.model.*;
import com.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final ShowSubscriptionRepository subscriptionRepo;
    private final UserEpisodeWatchedRepository watchedRepo;
    private final DownloadQueueRepository queueRepo;
    private final DownloadService downloadService;
    private final EpisodeRepository episodeRepo;
    private final SeasonRepository seasonRepo;
    private final UserRepository userRepo;
    private final TvShowRepository showRepo;

    public List<SubscriptionResponse> listSubscriptions(Long userId) {
        return subscriptionRepo.findByUserId(userId).stream()
            .map(SubscriptionResponse::from).toList();
    }

    @Transactional
    public SubscriptionResponse upsert(Long userId, Long showId, int targetCount) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        TvShow show = showRepo.findById(showId)
            .orElseThrow(() -> new IllegalArgumentException("Show not found: " + showId));

        ShowSubscription sub = subscriptionRepo.findByUserIdAndShowId(userId, showId)
            .orElseGet(() -> {
                ShowSubscription s = new ShowSubscription();
                s.setUser(user);
                s.setShow(show);
                return s;
            });
        sub.setTargetCount(targetCount);
        sub.setUpdatedAt(Instant.now());
        subscriptionRepo.save(sub);
        return SubscriptionResponse.from(sub);
    }

    @Transactional
    public void cancel(Long userId, Long showId) {
        subscriptionRepo.findByUserIdAndShowId(userId, showId)
            .ifPresent(subscriptionRepo::delete);
    }

    @Transactional
    public void replenish(ShowSubscription sub) {
        Long userId = sub.getUser().getId();
        Long showId = sub.getShow().getId();

        Set<Long> watchedIds = watchedRepo.findWatchedEpisodeIds(userId, showId);
        Set<Long> activeIds  = queueRepo.findActiveEpisodeIdsForShow(userId, showId);

        long activeUnwatched = activeIds.stream().filter(id -> !watchedIds.contains(id)).count();
        int deficit = sub.getTargetCount() - (int) activeUnwatched;
        if (deficit <= 0) return;

        List<Long> toEnqueue = nextUnwatchedEpisodeIds(showId, watchedIds, activeIds, deficit);
        for (Long episodeId : toEnqueue) {
            try {
                downloadService.enqueueEpisode(episodeId, sub.getUser());
                log.info("Auto-enqueued episode {} for user {} (subscription)", episodeId, userId);
            } catch (Exception e) {
                log.error("Failed to auto-enqueue episode {} for user {}: {}",
                    episodeId, userId, e.getMessage());
            }
        }
    }

    public List<Long> enqueueUnwatched(Long userId, Long showId, int limit) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Set<Long> watchedIds    = watchedRepo.findWatchedEpisodeIds(userId, showId);
        Set<Long> alreadyQueued = queueRepo.findActiveEpisodeIdsForShow(userId, showId);

        List<Long> toEnqueue = nextUnwatchedEpisodeIds(showId, watchedIds, alreadyQueued, limit);
        List<Long> jobIds = new ArrayList<>();
        for (Long episodeId : toEnqueue) {
            jobIds.addAll(downloadService.enqueueEpisode(episodeId, user));
        }
        return jobIds;
    }

    private List<Long> nextUnwatchedEpisodeIds(Long showId, Set<Long> watchedIds,
                                                Set<Long> skipIds, int limit) {
        List<Long> result = new ArrayList<>();
        for (Season season : seasonRepo.findByShowIdOrderBySeasonNumber(showId)) {
            if (result.size() >= limit) break;
            for (Episode ep : episodeRepo.findBySeasonIdOrderByEpisodeNumber(season.getId())) {
                if (result.size() >= limit) break;
                if (!watchedIds.contains(ep.getId()) && !skipIds.contains(ep.getId())) {
                    result.add(ep.getId());
                }
            }
        }
        return result;
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.SubscriptionServiceTest"
```

Expected: 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/service/SubscriptionService.java \
        backend/src/test/java/com/plexdownloader/service/SubscriptionServiceTest.java
git commit -m "feat: add SubscriptionService (upsert, cancel, replenish, enqueueUnwatched)"
```

---

## Task 7: WatchedSyncScheduler

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/service/WatchedSyncScheduler.java`

- [ ] **Step 1: Create `WatchedSyncScheduler.java`**

Follows the same `SchedulingConfigurer` pattern as the existing `LibrarySyncScheduler`.

```java
package com.plexdownloader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WatchedSyncScheduler implements SchedulingConfigurer {

    private static final String DEFAULT_CRON = "0 */15 * * * *";

    private final WatchedSyncService watchedSyncService;
    private final SubscriptionService subscriptionService;
    private final ShowSubscriptionRepository showSubscriptionRepository;
    private final SettingsService settings;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            this::syncAll,
            ctx -> {
                String cron = settings.get("watched.sync.cron").orElse(DEFAULT_CRON);
                return new CronTrigger(cron).nextExecution(ctx);
            }
        );
    }

    void syncAll() {
        log.info("Watched sync starting for all subscriptions");
        showSubscriptionRepository.findAllWithUserAndShow().forEach(sub -> {
            try {
                watchedSyncService.syncShow(sub.getUser().getId(), sub.getShow().getId());
                subscriptionService.replenish(sub);
            } catch (Exception e) {
                log.error("Watched sync failed for user={} show={}: {}",
                    sub.getUser().getId(), sub.getShow().getId(), e.getMessage());
            }
        });
    }
}
```

> Note: `ShowSubscriptionRepository` needs to be imported. Add the missing import at the top of the file.

- [ ] **Step 2: Compile**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/service/WatchedSyncScheduler.java
git commit -m "feat: add WatchedSyncScheduler (configurable cron, syncs all subscriptions)"
```

---

## Task 8: WatchedController

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/controller/WatchedController.java`
- Test: `backend/src/test/java/com/plexdownloader/controller/WatchedControllerTest.java`

- [ ] **Step 1: Write failing test**

Create `backend/src/test/java/com/plexdownloader/controller/WatchedControllerTest.java`:

```java
package com.plexdownloader.controller;

import com.plexdownloader.config.JwtAuthFilter;
import com.plexdownloader.config.SecurityConfig;
import com.plexdownloader.model.User;
import com.plexdownloader.repository.UserRepository;
import com.plexdownloader.service.JwtService;
import com.plexdownloader.service.WatchedSyncService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WatchedController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class WatchedControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean WatchedSyncService watchedSyncService;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;
    @MockBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupAuth() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole(User.Role.USER);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req  = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain       = inv.getArgument(2);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void getWatched_returnsSyncedIds() throws Exception {
        doNothing().when(watchedSyncService).syncIfStale(1L, 10L);
        when(watchedSyncService.getWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(3L, 7L));

        mockMvc.perform(get("/api/tv/10/watched"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watchedEpisodeIds").isArray());
    }

    @Test
    void getWatched_requires401WhenNoAuth() throws Exception {
        doAnswer(inv -> {
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());

        mockMvc.perform(get("/api/tv/10/watched"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.controller.WatchedControllerTest"
```

Expected: FAIL

- [ ] **Step 3: Create `WatchedController.java`**

```java
package com.plexdownloader.controller;

import com.plexdownloader.dto.WatchedResponse;
import com.plexdownloader.model.User;
import com.plexdownloader.service.WatchedSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class WatchedController {

    private final WatchedSyncService watchedSyncService;

    @GetMapping("/api/tv/{showId}/watched")
    public WatchedResponse getWatched(@PathVariable Long showId,
                                       @AuthenticationPrincipal User user) {
        watchedSyncService.syncIfStale(user.getId(), showId);
        return new WatchedResponse(watchedSyncService.getWatchedEpisodeIds(user.getId(), showId));
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.controller.WatchedControllerTest"
```

Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/controller/WatchedController.java \
        backend/src/test/java/com/plexdownloader/controller/WatchedControllerTest.java
git commit -m "feat: add WatchedController (GET /api/tv/{showId}/watched)"
```

---

## Task 9: SubscriptionController + DownloadController Update

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/controller/SubscriptionController.java`
- Modify: `backend/src/main/java/com/plexdownloader/controller/DownloadController.java`
- Test: `backend/src/test/java/com/plexdownloader/controller/SubscriptionControllerTest.java`

- [ ] **Step 1: Write failing tests**

Create `backend/src/test/java/com/plexdownloader/controller/SubscriptionControllerTest.java`:

```java
package com.plexdownloader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plexdownloader.config.JwtAuthFilter;
import com.plexdownloader.config.SecurityConfig;
import com.plexdownloader.dto.SubscriptionRequest;
import com.plexdownloader.dto.SubscriptionResponse;
import com.plexdownloader.dto.WatchedResponse;
import com.plexdownloader.model.User;
import com.plexdownloader.repository.UserRepository;
import com.plexdownloader.service.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SubscriptionController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class SubscriptionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean SubscriptionService subscriptionService;
    @MockBean WatchedSyncService watchedSyncService;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;
    @MockBean JwtAuthFilter jwtAuthFilter;

    User user;

    @BeforeEach
    void setupAuth() throws Exception {
        user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole(User.Role.USER);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req  = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain       = inv.getArgument(2);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void getSubscriptions_returnsUserList() throws Exception {
        when(subscriptionService.listSubscriptions(1L))
            .thenReturn(List.of(new SubscriptionResponse(1L, 10L, 5, Instant.now())));

        mockMvc.perform(get("/api/subscriptions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].showId").value(10))
            .andExpect(jsonPath("$[0].targetCount").value(5));
    }

    @Test
    void subscribe_createsSubscription() throws Exception {
        SubscriptionRequest req = new SubscriptionRequest(10L, 5);
        when(subscriptionService.upsert(1L, 10L, 5))
            .thenReturn(new SubscriptionResponse(1L, 10L, 5, Instant.now()));
        doNothing().when(watchedSyncService).syncShow(anyLong(), anyLong());
        doNothing().when(subscriptionService).replenish(any());

        mockMvc.perform(post("/api/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targetCount").value(5));
    }

    @Test
    void subscribe_returns400ForInvalidTargetCount() throws Exception {
        SubscriptionRequest req = new SubscriptionRequest(10L, 7); // 7 is invalid

        mockMvc.perform(post("/api/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unsubscribe_returns204() throws Exception {
        doNothing().when(subscriptionService).cancel(1L, 10L);

        mockMvc.perform(delete("/api/subscriptions/10"))
            .andExpect(status().isNoContent());
    }

    @Test
    void syncNow_returnsWatchedIds() throws Exception {
        doNothing().when(watchedSyncService).syncShow(1L, 10L);
        doNothing().when(subscriptionService).replenish(any());
        when(watchedSyncService.getWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(1L, 2L));

        mockMvc.perform(post("/api/subscriptions/10/sync"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watchedEpisodeIds").isArray());
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.controller.SubscriptionControllerTest"
```

Expected: FAIL

- [ ] **Step 3: Create `SubscriptionController.java`**

```java
package com.plexdownloader.controller;

import com.plexdownloader.dto.*;
import com.plexdownloader.model.User;
import com.plexdownloader.repository.ShowSubscriptionRepository;
import com.plexdownloader.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final WatchedSyncService watchedSyncService;
    private final ShowSubscriptionRepository showSubscriptionRepository;

    @GetMapping
    public List<SubscriptionResponse> getSubscriptions(@AuthenticationPrincipal User user) {
        return subscriptionService.listSubscriptions(user.getId());
    }

    @PostMapping
    public SubscriptionResponse subscribe(@RequestBody SubscriptionRequest req,
                                           @AuthenticationPrincipal User user) {
        if (!List.of(5, 10, 15, 20).contains(req.targetCount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "targetCount must be 5, 10, 15, or 20");
        }
        SubscriptionResponse resp = subscriptionService.upsert(
            user.getId(), req.showId(), req.targetCount());
        watchedSyncService.syncShow(user.getId(), req.showId());
        showSubscriptionRepository.findByUserIdAndShowId(user.getId(), req.showId())
            .ifPresent(subscriptionService::replenish);
        return resp;
    }

    @DeleteMapping("/{showId}")
    public ResponseEntity<Void> unsubscribe(@PathVariable Long showId,
                                             @AuthenticationPrincipal User user) {
        subscriptionService.cancel(user.getId(), showId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{showId}/sync")
    public WatchedResponse syncNow(@PathVariable Long showId,
                                    @AuthenticationPrincipal User user) {
        watchedSyncService.syncShow(user.getId(), showId);
        showSubscriptionRepository.findByUserIdAndShowId(user.getId(), showId)
            .ifPresent(subscriptionService::replenish);
        return new WatchedResponse(watchedSyncService.getWatchedEpisodeIds(user.getId(), showId));
    }
}
```

- [ ] **Step 4: Add `enqueueUnwatched` to `DownloadController.java`**

Add the new endpoint to the existing file. Full updated `DownloadController.java`:

```java
package com.plexdownloader.controller;

import com.plexdownloader.dto.DownloadRequest;
import com.plexdownloader.dto.DownloadResponse;
import com.plexdownloader.dto.UnwatchedEnqueueRequest;
import com.plexdownloader.model.DownloadQueueItem;
import com.plexdownloader.model.User;
import com.plexdownloader.service.DownloadService;
import com.plexdownloader.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadService downloadService;
    private final SubscriptionService subscriptionService;

    @PostMapping
    public DownloadResponse download(@RequestBody DownloadRequest req,
                                     @AuthenticationPrincipal User user) {
        List<Long> jobIds = switch (req.type()) {
            case "MOVIE"   -> downloadService.enqueueMovie(req.id(), user);
            case "EPISODE" -> downloadService.enqueueEpisode(req.id(), user);
            case "SEASON"  -> downloadService.enqueueSeason(req.id(), user);
            case "SHOW"    -> downloadService.enqueueShow(req.id(), user);
            default -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Unknown type: " + req.type());
        };
        return new DownloadResponse(jobIds, "QUEUED");
    }

    @PostMapping("/show/{showId}/unwatched")
    public DownloadResponse enqueueUnwatched(@PathVariable Long showId,
                                              @RequestBody UnwatchedEnqueueRequest req,
                                              @AuthenticationPrincipal User user) {
        if (req.limit() == null || !List.of(5, 10, 15, 20).contains(req.limit())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "limit must be 5, 10, 15, or 20");
        }
        List<Long> jobIds = subscriptionService.enqueueUnwatched(user.getId(), showId, req.limit());
        return new DownloadResponse(jobIds, "QUEUED");
    }

    @GetMapping("/queue")
    public List<DownloadQueueItem> getQueue() {
        return downloadService.getQueue();
    }
}
```

- [ ] **Step 5: Run all tests — expect PASS**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test
```

Expected: all tests PASS (including SubscriptionControllerTest)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/controller/SubscriptionController.java \
        backend/src/main/java/com/plexdownloader/controller/DownloadController.java \
        backend/src/test/java/com/plexdownloader/controller/SubscriptionControllerTest.java
git commit -m "feat: add SubscriptionController, extend DownloadController with unwatched enqueue"
```

---

## Task 10: Frontend API Module

**Files:**
- Create: `frontend/src/api/watched.js`

- [ ] **Step 1: Create `frontend/src/api/watched.js`**

```js
import api from './axios.js'

export const getWatched        = (showId)              => api.get(`/api/tv/${showId}/watched`).then(r => r.data)
export const getSubscriptions  = ()                    => api.get('/api/subscriptions').then(r => r.data)
export const subscribe         = (showId, targetCount) => api.post('/api/subscriptions', { showId, targetCount }).then(r => r.data)
export const unsubscribe       = (showId)              => api.delete(`/api/subscriptions/${showId}`)
export const syncNow           = (showId)              => api.post(`/api/subscriptions/${showId}/sync`).then(r => r.data)
export const enqueueUnwatched  = (showId, limit)       => api.post(`/api/download/show/${showId}/unwatched`, { limit }).then(r => r.data)
```

- [ ] **Step 2: Run existing frontend tests to confirm nothing broken**

```bash
cd frontend && npm test -- --run
```

Expected: all 40 tests PASS

- [ ] **Step 3: Commit**

```bash
git add frontend/src/api/watched.js
git commit -m "feat(frontend): add watched API module"
```

---

## Task 11: Frontend Watched Store

**Files:**
- Create: `frontend/src/stores/watched.js`
- Test: `frontend/src/stores/__tests__/watched.test.js`

- [ ] **Step 1: Write failing tests**

Create `frontend/src/stores/__tests__/watched.test.js`:

```js
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useWatchedStore } from '../watched.js'

vi.mock('../../api/watched.js', () => ({
  getWatched:       vi.fn(),
  getSubscriptions: vi.fn(),
  subscribe:        vi.fn(),
  unsubscribe:      vi.fn(),
  syncNow:          vi.fn(),
  enqueueUnwatched: vi.fn()
}))

import {
  getWatched, getSubscriptions, subscribe as apiSubscribe,
  unsubscribe as apiUnsubscribe, syncNow as apiSyncNow
} from '../../api/watched.js'

describe('watched store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('fetchWatched populates watchedByShow', async () => {
    getWatched.mockResolvedValue({ watchedEpisodeIds: [1, 2, 3] })
    const store = useWatchedStore()
    await store.fetchWatched(10)
    expect(store.isWatched(10, 1)).toBe(true)
    expect(store.isWatched(10, 99)).toBe(false)
  })

  it('fetchSubscriptions populates subscriptions map', async () => {
    getSubscriptions.mockResolvedValue([
      { showId: 10, targetCount: 5, id: 1, updatedAt: '2026-01-01' }
    ])
    const store = useWatchedStore()
    await store.fetchSubscriptions()
    expect(store.getSubscription(10)).toBe(5)
    expect(store.getSubscription(99)).toBeNull()
  })

  it('subscribe calls API and updates map', async () => {
    apiSubscribe.mockResolvedValue({ showId: 10, targetCount: 10, id: 1, updatedAt: '2026-01-01' })
    getWatched.mockResolvedValue({ watchedEpisodeIds: [] })
    const store = useWatchedStore()
    await store.subscribe(10, 10)
    expect(apiSubscribe).toHaveBeenCalledWith(10, 10)
    expect(store.getSubscription(10)).toBe(10)
  })

  it('unsubscribe calls API and removes from map', async () => {
    apiUnsubscribe.mockResolvedValue({})
    const store = useWatchedStore()
    store.subscriptions.set(10, 5) // seed
    await store.unsubscribe(10)
    expect(apiUnsubscribe).toHaveBeenCalledWith(10)
    expect(store.getSubscription(10)).toBeNull()
  })

  it('syncNow updates watchedByShow', async () => {
    apiSyncNow.mockResolvedValue({ watchedEpisodeIds: [7, 8] })
    const store = useWatchedStore()
    await store.syncNow(10)
    expect(store.isWatched(10, 7)).toBe(true)
  })
})
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd frontend && npm test -- --run src/stores/__tests__/watched.test.js
```

Expected: FAIL

- [ ] **Step 3: Create `frontend/src/stores/watched.js`**

```js
import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  getWatched       as apiGetWatched,
  getSubscriptions as apiGetSubscriptions,
  subscribe        as apiSubscribe,
  unsubscribe      as apiUnsubscribe,
  syncNow          as apiSyncNow,
  enqueueUnwatched as apiEnqueueUnwatched
} from '@/api/watched.js'

export const useWatchedStore = defineStore('watched', () => {
  const watchedByShow = ref(new Map())   // Map<showId, Set<episodeId>>
  const subscriptions = ref(new Map())   // Map<showId, targetCount>

  async function fetchWatched(showId) {
    const data = await apiGetWatched(showId)
    watchedByShow.value.set(showId, new Set(data.watchedEpisodeIds))
  }

  async function fetchSubscriptions() {
    const list = await apiGetSubscriptions()
    subscriptions.value = new Map(list.map(s => [s.showId, s.targetCount]))
  }

  async function subscribe(showId, targetCount) {
    await apiSubscribe(showId, targetCount)
    subscriptions.value.set(showId, targetCount)
    await fetchWatched(showId)
  }

  async function unsubscribe(showId) {
    await apiUnsubscribe(showId)
    subscriptions.value.delete(showId)
  }

  async function syncNow(showId) {
    const data = await apiSyncNow(showId)
    watchedByShow.value.set(showId, new Set(data.watchedEpisodeIds))
  }

  async function enqueueUnwatched(showId, limit) {
    return apiEnqueueUnwatched(showId, limit)
  }

  function isWatched(showId, episodeId) {
    return watchedByShow.value.get(showId)?.has(episodeId) ?? false
  }

  function getSubscription(showId) {
    return subscriptions.value.get(showId) ?? null
  }

  return {
    watchedByShow, subscriptions,
    fetchWatched, fetchSubscriptions, subscribe, unsubscribe,
    syncNow, enqueueUnwatched, isWatched, getSubscription
  }
})
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd frontend && npm test -- --run src/stores/__tests__/watched.test.js
```

Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/stores/watched.js frontend/src/stores/__tests__/watched.test.js
git commit -m "feat(frontend): add watched store with subscription and watch-status management"
```

---

## Task 12: SubscribeButton Component

**Files:**
- Create: `frontend/src/components/SubscribeButton.vue`
- Test: `frontend/src/components/__tests__/SubscribeButton.test.js`

- [ ] **Step 1: Write failing tests**

Create `frontend/src/components/__tests__/SubscribeButton.test.js`:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useWatchedStore } from '../../stores/watched.js'
import SubscribeButton from '../SubscribeButton.vue'

describe('SubscribeButton', () => {
  function factory(subscription = null) {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.getSubscription = vi.fn().mockReturnValue(subscription)
    store.subscribe       = vi.fn()
    store.unsubscribe     = vi.fn()
    store.enqueueUnwatched = vi.fn()
    return { wrapper: mount(SubscribeButton, {
      props: { showId: 10 },
      global: { plugins: [pinia] }
    }), store }
  }

  it('shows Download when no subscription', () => {
    const { wrapper } = factory(null)
    expect(wrapper.text()).toContain('Download')
    expect(wrapper.text()).not.toContain('Next')
  })

  it('shows active subscription target', () => {
    const { wrapper } = factory(10)
    expect(wrapper.text()).toContain('Next 10')
  })

  it('opens picker on click', async () => {
    const { wrapper } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    expect(wrapper.find('.picker').exists()).toBe(true)
  })

  it('calls subscribe with chosen target on subscribe option click', async () => {
    const { wrapper, store } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    await wrapper.findAll('button.picker-opt')[0].trigger('click') // first subscribe option = 5
    expect(store.subscribe).toHaveBeenCalledWith(10, 5)
  })

  it('calls enqueueUnwatched on one-time option click', async () => {
    const { wrapper, store } = factory(null)
    await wrapper.find('button.sub-btn').trigger('click')
    // one-time buttons come after subscribe buttons (4 subscribe + 4 one-time)
    const opts = wrapper.findAll('button.picker-opt')
    await opts[4].trigger('click') // first one-time option = 5
    expect(store.enqueueUnwatched).toHaveBeenCalledWith(10, 5)
  })
})
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd frontend && npm test -- --run src/components/__tests__/SubscribeButton.test.js
```

Expected: FAIL

- [ ] **Step 3: Create `frontend/src/components/SubscribeButton.vue`**

```vue
<template>
  <div class="subscribe-wrap" :class="{ small }">
    <button class="sub-btn" :class="statusClass" @click.stop="toggle" :disabled="loading">
      <template v-if="loading">⏳</template>
      <template v-else-if="current">📥 Next {{ current }}</template>
      <template v-else>⬇ Download</template>
    </button>

    <div v-if="open" class="picker" @click.stop>
      <div class="picker-section">
        <p class="picker-label">{{ current ? 'Change subscription' : 'Subscribe: keep ahead' }}</p>
        <button v-for="n in COUNTS" :key="`sub-${n}`"
                class="picker-opt" :class="{ active: current === n }"
                @click="doSubscribe(n)">
          📥 Next {{ n }}
        </button>
      </div>
      <div class="picker-section">
        <p class="picker-label">One-time download</p>
        <button v-for="n in COUNTS" :key="`once-${n}`"
                class="picker-opt"
                @click="doOneTime(n)">
          ⬇ Download {{ n }}
        </button>
      </div>
      <div v-if="current" class="picker-section">
        <button class="picker-opt cancel-opt" @click="doCancel">✕ Cancel subscription</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useWatchedStore } from '@/stores/watched.js'

const COUNTS = [5, 10, 15, 20]

const props = defineProps({
  showId: { type: Number, required: true },
  small:  { type: Boolean, default: false }
})

const watchedStore = useWatchedStore()
const open    = ref(false)
const loading = ref(false)

const current    = computed(() => watchedStore.getSubscription(props.showId))
const statusClass = computed(() => current.value ? 'active-sub' : 'idle')

function toggle() { open.value = !open.value }

async function doSubscribe(n) {
  open.value = false
  loading.value = true
  try { await watchedStore.subscribe(props.showId, n) }
  finally { loading.value = false }
}

async function doOneTime(n) {
  open.value = false
  loading.value = true
  try { await watchedStore.enqueueUnwatched(props.showId, n) }
  finally { loading.value = false }
}

async function doCancel() {
  open.value = false
  loading.value = true
  try { await watchedStore.unsubscribe(props.showId) }
  finally { loading.value = false }
}
</script>

<style scoped>
.subscribe-wrap { position: relative; display: inline-block; }
.sub-btn { display: inline-flex; align-items: center; gap: 6px; border: none;
           border-radius: 6px; padding: 8px 16px; font-size: .9rem; font-weight: 600;
           transition: opacity .15s; cursor: pointer; }
.subscribe-wrap.small .sub-btn { padding: 4px 8px; font-size: .8rem; border-radius: 4px; }
.sub-btn:disabled { cursor: default; opacity: .6; }
.idle      { background: var(--accent); color: #000; }
.idle:hover { opacity: .85; }
.active-sub { background: var(--accent-blue); color: #fff; }

.picker { position: absolute; top: calc(100% + 6px); left: 0; z-index: 100;
          background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
          padding: 12px; min-width: 220px; box-shadow: 0 4px 16px rgba(0,0,0,.4); }
.picker-section { margin-bottom: 12px; }
.picker-section:last-child { margin-bottom: 0; }
.picker-label { font-size: .75rem; color: var(--text-muted); text-transform: uppercase;
                letter-spacing: .05em; margin-bottom: 6px; }
.picker-opt { display: block; width: 100%; text-align: left; background: transparent;
              border: 1px solid var(--border); border-radius: 6px; padding: 6px 10px;
              color: var(--text); font-size: .85rem; margin-bottom: 4px; cursor: pointer; }
.picker-opt:hover { background: var(--surface2); }
.picker-opt.active { border-color: var(--accent-blue); color: var(--accent-blue); }
.cancel-opt { color: var(--red); border-color: var(--red); }
.cancel-opt:hover { background: rgba(231,76,60,.1); }
</style>
```

- [ ] **Step 4: Run all frontend tests — expect PASS**

```bash
cd frontend && npm test -- --run
```

Expected: all tests PASS (40 existing + 5 new = 45)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/SubscribeButton.vue \
        frontend/src/components/__tests__/SubscribeButton.test.js
git commit -m "feat(frontend): add SubscribeButton component (subscription + one-time download)"
```

---

## Task 13: Update TvShowDetailView + SeasonDetailView

**Files:**
- Modify: `frontend/src/views/TvShowDetailView.vue`
- Modify: `frontend/src/views/SeasonDetailView.vue`
- Test: `frontend/src/views/__tests__/TvShowDetailView.test.js`

- [ ] **Step 1: Write failing test**

Create `frontend/src/views/__tests__/TvShowDetailView.test.js`:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useWatchedStore } from '../../stores/watched.js'
import TvShowDetailView from '../TvShowDetailView.vue'

vi.mock('../../api/library.js', () => ({
  getShow:    vi.fn(),
  getSeasons: vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute:  () => ({ params: { showId: '10' } }),
  useRouter: () => ({ back: vi.fn(), push: vi.fn() })
}))
import { getShow, getSeasons } from '../../api/library.js'

describe('TvShowDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getShow.mockResolvedValue({
      id: 10, plexId: 'show-1', title: 'Breaking Bad',
      year: 2008, totalSeasons: 2, summary: 'Chemistry teacher.', rating: 9.5
    })
    getSeasons.mockResolvedValue([
      { id: 100, plexId: 's1', seasonNumber: 1, title: 'Season 1', episodeCount: 7 }
    ])
  })

  it('renders show title', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const store = useWatchedStore(pinia)
    store.fetchWatched = vi.fn()
    store.fetchSubscriptions = vi.fn()
    const w = mount(TvShowDetailView, {
      global: {
        plugins: [pinia],
        stubs: { SubscribeButton: { template: '<div class="sb" />', props: ['showId', 'small'] },
                 PosterCard: { template: '<div class="pc" />', props: ['plexId','title','subtitle'] } }
      }
    })
    await flushPromises()
    expect(w.text()).toContain('Breaking Bad')
    expect(store.fetchWatched).toHaveBeenCalledWith(10)
    expect(store.fetchSubscriptions).toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd frontend && npm test -- --run src/views/__tests__/TvShowDetailView.test.js
```

Expected: FAIL (imports SubscribeButton which doesn't exist in the view yet)

- [ ] **Step 3: Update `frontend/src/views/TvShowDetailView.vue`**

```vue
<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="show">
    <button class="back" @click="router.back()">← Back</button>

    <div class="hero">
      <img class="poster" :src="`/api/posters/${show.plexId}.jpg`" :alt="show.title" />
      <div class="meta">
        <h1>{{ show.title }}</h1>
        <p class="year-seasons">{{ show.year }} · {{ show.totalSeasons }} season{{ show.totalSeasons === 1 ? '' : 's' }}</p>
        <p class="genres">{{ show.genres?.join(', ') }}</p>
        <p class="rating">★ {{ show.rating?.toFixed(1) }}</p>
        <p class="summary">{{ show.summary }}</p>
        <SubscribeButton :showId="show.id" />
      </div>
    </div>

    <div v-if="seasons.length" class="seasons-section">
      <h3>Seasons</h3>
      <div class="seasons-grid">
        <PosterCard
          v-for="s in seasons"
          :key="s.id"
          :plexId="s.plexId"
          :title="s.title || `Season ${s.seasonNumber}`"
          :subtitle="`${s.episodeCount} episodes`"
          @click="router.push(`/tv/${show.id}/seasons/${s.id}`)"
        >
          <template #badge>
            <SubscribeButton :showId="show.id" small />
          </template>
        </PosterCard>
      </div>
    </div>

    <div v-if="show.actors?.length" class="cast-section">
      <h3>Cast</h3>
      <div class="cast-list">
        <span v-for="a in show.actors" :key="a.id" class="actor">{{ a.name }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getShow, getSeasons } from '@/api/library.js'
import { useWatchedStore } from '@/stores/watched.js'
import PosterCard from '@/components/PosterCard.vue'
import SubscribeButton from '@/components/SubscribeButton.vue'

const route        = useRoute()
const router       = useRouter()
const watchedStore = useWatchedStore()
const show         = ref(null)
const seasons      = ref([])
const loading      = ref(true)

async function load() {
  try {
    const showId = Number(route.params.showId)
    const [s, ss] = await Promise.all([
      getShow(showId),
      getSeasons(showId)
    ])
    show.value    = s
    seasons.value = ss
    watchedStore.fetchWatched(showId)
    watchedStore.fetchSubscriptions()
  } finally {
    loading.value = false
  }
}
load()
</script>

<style scoped>
.back { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 6px; padding: 6px 14px; margin-bottom: 24px; }
.hero { display: grid; grid-template-columns: 220px 1fr; gap: 32px; align-items: start; }
.poster { width: 220px; border-radius: 8px; object-fit: cover; aspect-ratio: 2/3; }
h1 { font-size: 2rem; font-weight: 700; margin-bottom: 8px; }
.year-seasons { color: var(--text-muted); }
.genres { color: var(--accent-blue); font-size: .9rem; margin: 6px 0; }
.rating { color: var(--accent); font-size: .9rem; margin-bottom: 6px; }
.summary { line-height: 1.6; color: var(--text-muted); max-width: 680px; margin: 16px 0 24px; }
.seasons-section { margin-top: 32px; }
h3 { font-size: 1.1rem; margin-bottom: 12px; }
.seasons-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 16px; }
.cast-section { margin-top: 32px; }
.cast-list { display: flex; flex-wrap: wrap; gap: 8px; }
.actor { background: var(--surface2); border-radius: 16px; padding: 4px 14px; font-size: .85rem; }
.loading { color: var(--text-muted); padding: 40px; text-align: center; }
</style>
```

- [ ] **Step 4: Update `frontend/src/views/SeasonDetailView.vue`**

```vue
<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="season && show">
    <button class="back" @click="router.back()">← Back</button>

    <div class="hero">
      <img class="poster" :src="`/api/posters/${season.plexId}.jpg`" :alt="season.title" />
      <div class="meta">
        <h1>{{ show.title }}</h1>
        <h2>{{ season.title || `Season ${season.seasonNumber}` }}</h2>
        <p class="ep-count">{{ season.episodeCount }} episodes</p>
        <SubscribeButton :showId="show.id" />
      </div>
    </div>

    <div class="episodes-section">
      <h3>Episodes</h3>
      <div class="episodes-grid">
        <div v-for="ep in episodes" :key="ep.id" class="ep-card"
             @click="router.push(`/tv/${show.id}/seasons/${season.id}/episodes/${ep.id}`)">
          <div class="ep-thumb">
            <img v-if="ep.thumbnailUrl" :src="`/api/posters/${ep.plexId}.jpg`" :alt="ep.title" />
            <div v-else class="ep-thumb-placeholder">▶</div>
          </div>
          <div class="ep-info">
            <p class="ep-num">E{{ ep.episodeNumber }}</p>
            <p class="ep-title">{{ ep.title }}</p>
            <p class="ep-air">{{ ep.airDate }}</p>
          </div>
          <span v-if="watchedStore.isWatched(show.id, ep.id)" class="watched-badge">✓</span>
          <DownloadButton v-else type="EPISODE" :mediaId="ep.id" small />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getShow, getSeason, getEpisodes } from '@/api/library.js'
import { useWatchedStore } from '@/stores/watched.js'
import SubscribeButton from '@/components/SubscribeButton.vue'
import DownloadButton from '@/components/DownloadButton.vue'

const route        = useRoute()
const router       = useRouter()
const watchedStore = useWatchedStore()
const show         = ref(null)
const season       = ref(null)
const episodes     = ref([])
const loading      = ref(true)

async function load() {
  const { showId, seasonId } = route.params
  try {
    const [sh, se, eps] = await Promise.all([
      getShow(showId),
      getSeason(showId, seasonId),
      getEpisodes(showId, seasonId)
    ])
    show.value     = sh
    season.value   = se
    episodes.value = eps
    watchedStore.fetchWatched(Number(showId))
  } finally {
    loading.value  = false
  }
}
load()
</script>

<style scoped>
.back { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 6px; padding: 6px 14px; margin-bottom: 24px; }
.hero { display: grid; grid-template-columns: 160px 1fr; gap: 24px; align-items: start; margin-bottom: 32px; }
.poster { width: 160px; border-radius: 8px; aspect-ratio: 2/3; object-fit: cover; }
h1 { font-size: 1.6rem; font-weight: 700; }
h2 { font-size: 1.2rem; color: var(--text-muted); margin: 4px 0 8px; }
.ep-count { color: var(--text-muted); font-size: .9rem; margin-bottom: 16px; }
h3 { font-size: 1.1rem; margin-bottom: 12px; }
.episodes-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.ep-card { display: flex; align-items: center; gap: 12px; background: var(--surface2);
           border-radius: 8px; padding: 10px; cursor: pointer; }
.ep-card:hover { background: var(--border); }
.ep-thumb { width: 80px; min-width: 80px; aspect-ratio: 16/9; border-radius: 4px;
            background: var(--border); overflow: hidden; }
.ep-thumb img { width: 100%; height: 100%; object-fit: cover; }
.ep-thumb-placeholder { display: flex; align-items: center; justify-content: center;
                        height: 100%; color: var(--text-muted); font-size: 1.2rem; }
.ep-info { flex: 1; min-width: 0; }
.ep-num  { font-size: .75rem; color: var(--text-muted); }
.ep-title { font-size: .9rem; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ep-air  { font-size: .75rem; color: var(--text-muted); margin-top: 2px; }
.watched-badge { font-size: .8rem; color: var(--green); font-weight: 700; padding: 2px 8px;
                 background: rgba(39,174,96,.15); border-radius: 10px; white-space: nowrap; }
.loading { color: var(--text-muted); padding: 40px; text-align: center; }
</style>
```

- [ ] **Step 5: Run all frontend tests — expect PASS**

```bash
cd frontend && npm test -- --run
```

Expected: all tests PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/TvShowDetailView.vue \
        frontend/src/views/SeasonDetailView.vue \
        frontend/src/views/__tests__/TvShowDetailView.test.js
git commit -m "feat(frontend): SubscribeButton + watched badges on TV show and season views"
```

---

## Task 14: TvView + SettingsView Updates

**Files:**
- Modify: `frontend/src/views/TvView.vue`
- Modify: `frontend/src/views/SettingsView.vue`

- [ ] **Step 1: Update `frontend/src/views/TvView.vue`** — remove DownloadButton from badge slot

Remove the `<template #badge>` block entirely from the PosterCard in TvView. The full updated script+template (style unchanged):

```vue
<template>
  <div>
    <div class="toolbar">
      <h2>TV Shows</h2>
      <SearchFilter v-model:search="search" v-model:year="year" />
    </div>

    <div v-if="loading" class="loading">Loading…</div>
    <div v-else-if="shows.length === 0" class="empty">No TV shows found.</div>
    <div v-else class="grid">
      <PosterCard
        v-for="s in shows"
        :key="s.id"
        :plexId="s.plexId"
        :title="s.title"
        :subtitle="`${s.totalSeasons} season${s.totalSeasons === 1 ? '' : 's'}`"
        @click="router.push(`/tv/${s.id}`)"
      />
    </div>

    <div v-if="totalPages > 1" class="pagination">
      <button :disabled="page === 0" @click="page--">‹ Prev</button>
      <span>{{ page + 1 }} / {{ totalPages }}</span>
      <button :disabled="page >= totalPages - 1" @click="page++">Next ›</button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getShows } from '@/api/library.js'
import PosterCard from '@/components/PosterCard.vue'
import SearchFilter from '@/components/SearchFilter.vue'

const router     = useRouter()
const shows      = ref([])
const loading    = ref(false)
const page       = ref(0)
const totalPages = ref(0)
const search     = ref('')
const year       = ref(null)

async function load() {
  loading.value = true
  try {
    const data = await getShows({ search: search.value || undefined, year: year.value || undefined, page: page.value })
    shows.value      = data.content
    totalPages.value = data.totalPages
  } finally {
    loading.value = false
  }
}

watch([search, year], () => { page.value = 0; load() })
watch(page, load)
load()
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 24px; margin-bottom: 24px; }
h2 { font-size: 1.5rem; font-weight: 600; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 16px; }
.loading, .empty { color: var(--text-muted); padding: 40px 0; text-align: center; }
.pagination { display: flex; align-items: center; gap: 16px; margin-top: 32px; justify-content: center; }
.pagination button { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
                     border-radius: 6px; padding: 6px 16px; }
.pagination button:disabled { opacity: 0.4; }
</style>
```

- [ ] **Step 2: Update `frontend/src/views/SettingsView.vue`** — add watched.sync.cron field

In the Library Sync section, add the watched sync cron field. In the `form` reactive object, add `watchedSyncCron: ''`. In `onMounted`, populate it: `form.watchedSyncCron = s['watched.sync.cron'] ?? ''`. In `save()` payload, add `'watched.sync.cron': form.watchedSyncCron`. In the template, add the input inside the Library Sync `card-section` before the sync-actions div.

Full updated `SettingsView.vue`:

```vue
<template>
  <div v-if="!authStore.isAdmin" class="error">Access denied.</div>
  <div v-else>
    <h2>Settings</h2>

    <section class="card-section">
      <h3>Plex Connection</h3>
      <div class="field">
        <label>Server URL</label>
        <input name="plexUrl" v-model="form.plexUrl" type="url" placeholder="http://localhost:32400" />
      </div>
      <div class="field">
        <label>Plex Token</label>
        <input name="plexToken" v-model="form.plexToken" type="password" placeholder="xxxxxxxxxxxxxxxxxxxx" />
      </div>
      <button class="btn-save" @click="save" :disabled="saving">
        {{ saving ? 'Saving…' : 'Save' }}
      </button>
      <p v-if="saveOk" class="ok">Saved.</p>
    </section>

    <section class="card-section">
      <h3>Path Mapping</h3>
      <div class="field">
        <label>Plex path prefix</label>
        <input name="plexPrefix" v-model="form.plexPathPrefixPlex" type="text" />
      </div>
      <div class="field">
        <label>App path prefix</label>
        <input name="appPrefix" v-model="form.plexPathPrefixApp" type="text" />
      </div>
      <div class="field">
        <label>Poster directory</label>
        <input name="posterDir" v-model="form.plexPosterDir" type="text" readonly class="readonly" />
      </div>
      <div class="field">
        <label>Conversion directory</label>
        <input name="conversionDir" v-model="form.plexConversionDir" type="text" readonly class="readonly" />
      </div>
      <button class="btn-save" @click="save" :disabled="saving">
        {{ saving ? 'Saving…' : 'Save' }}
      </button>
    </section>

    <section class="card-section">
      <h3>Library Sync</h3>
      <div class="field">
        <label>Library sync cron expression</label>
        <input name="syncCron" v-model="form.syncCron" type="text" placeholder="0 0 */6 * * *" />
      </div>
      <div class="field">
        <label>Watched status sync cron</label>
        <input name="watchedSyncCron" v-model="form.watchedSyncCron" type="text" placeholder="0 */15 * * * *" />
      </div>
      <div class="sync-status" v-if="syncStatus">
        <span :class="['state', syncStatus.state.toLowerCase()]">{{ syncStatus.state }}</span>
        <span v-if="syncStatus.lastSyncAt" class="last-sync">
          Last sync: {{ new Date(syncStatus.lastSyncAt).toLocaleString() }}
          ({{ syncStatus.itemsSynced }} items)
        </span>
        <span v-if="syncStatus.error" class="sync-error">{{ syncStatus.error }}</span>
      </div>
      <div class="sync-actions">
        <button class="btn-save" @click="save" :disabled="saving">Save cron</button>
        <button class="btn-sync" data-testid="sync-btn" @click="sync" :disabled="syncing">
          {{ syncing ? 'Syncing…' : '↻ Sync Now' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth.js'
import { getSettings, putSettings, getSyncStatus, triggerSync } from '@/api/admin.js'

const authStore  = useAuthStore()
const saving     = ref(false)
const saveOk     = ref(false)
const syncing    = ref(false)
const syncStatus = ref(null)
let saveOkTimer  = null
onUnmounted(() => clearTimeout(saveOkTimer))

const form = reactive({
  plexUrl:            '',
  plexToken:          '',
  plexPathPrefixPlex: '',
  plexPathPrefixApp:  '',
  plexPosterDir:      '',
  plexConversionDir:  '',
  syncCron:           '',
  watchedSyncCron:    ''
})

onMounted(async () => {
  const [s, ss] = await Promise.all([getSettings(), getSyncStatus()])
  form.plexUrl            = s['plex.server.url']        ?? ''
  form.plexToken          = ''
  form.plexPathPrefixPlex = s['plex.path.prefix.plex']  ?? ''
  form.plexPathPrefixApp  = s['plex.path.prefix.app']   ?? ''
  form.plexPosterDir      = s['plex.poster.dir']        ?? ''
  form.plexConversionDir  = s['plex.conversion.dir']    ?? ''
  form.syncCron           = s['plex.sync.cron']         ?? ''
  form.watchedSyncCron    = s['watched.sync.cron']      ?? ''
  syncStatus.value = ss
})

async function save() {
  saving.value = true
  saveOk.value = false
  const payload = {
    'plex.server.url':        form.plexUrl,
    'plex.path.prefix.plex':  form.plexPathPrefixPlex,
    'plex.path.prefix.app':   form.plexPathPrefixApp,
    'plex.sync.cron':         form.syncCron,
    'watched.sync.cron':      form.watchedSyncCron
  }
  if (form.plexToken) payload['plex.server.token'] = form.plexToken
  try {
    await putSettings(payload)
    form.plexToken = ''
    saveOk.value = true
    saveOkTimer = setTimeout(() => { saveOk.value = false }, 2000)
  } finally {
    saving.value = false
  }
}

async function sync() {
  syncing.value = true
  try {
    await triggerSync()
    syncStatus.value = await getSyncStatus()
  } finally {
    syncing.value = false
  }
}
</script>

<style scoped>
h2 { font-size: 1.5rem; font-weight: 600; margin-bottom: 24px; }
h3 { font-size: 1rem; font-weight: 600; margin-bottom: 16px; }
.card-section { background: var(--surface); border: 1px solid var(--border); border-radius: 10px;
                padding: 24px; max-width: 600px; margin-bottom: 24px; }
.field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
label { font-size: .85rem; color: var(--text-muted); }
input { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
        border-radius: 6px; padding: 8px 12px; font-size: .9rem; }
input:focus { outline: none; border-color: var(--accent-blue); }
input.readonly { opacity: 0.6; cursor: default; }
.btn-save { background: var(--accent); color: #000; border: none; border-radius: 6px;
            padding: 8px 20px; font-weight: 600; }
.btn-save:disabled { opacity: 0.6; }
.ok { color: var(--green); font-size: .85rem; margin-top: 8px; }
.sync-status { margin: 12px 0; display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.state { padding: 3px 10px; border-radius: 12px; font-size: .8rem; font-weight: 600; text-transform: uppercase; }
.state.idle    { background: var(--surface2); color: var(--text-muted); }
.state.running { background: var(--accent-blue); color: #fff; }
.state.done    { background: var(--green); color: #fff; }
.state.error   { background: var(--red); color: #fff; }
.last-sync  { font-size: .85rem; color: var(--text-muted); }
.sync-error { font-size: .85rem; color: var(--red); }
.sync-actions { display: flex; gap: 12px; align-items: center; margin-top: 4px; }
.btn-sync { background: var(--surface2); border: 1px solid var(--border); color: var(--text);
            border-radius: 6px; padding: 8px 16px; }
.btn-sync:hover:not(:disabled) { border-color: var(--accent-blue); }
.error { color: var(--red); padding: 40px; text-align: center; }
</style>
```

- [ ] **Step 3: Run all frontend tests — expect PASS**

```bash
cd frontend && npm test -- --run
```

Expected: all tests PASS

- [ ] **Step 4: Run all backend tests**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test
```

Expected: all tests PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/TvView.vue frontend/src/views/SettingsView.vue
git commit -m "feat(frontend): remove DownloadButton from TV grid, add watched cron to Settings"
```
