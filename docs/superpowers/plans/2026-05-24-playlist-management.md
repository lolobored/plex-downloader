# Playlist Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sync Plex playlists, let users subscribe, auto-queue playlist additions for Tdarr conversion, and cancel + delete files on removal.

**Architecture:** `PlaylistSyncService` diffs Plex playlists against DB on each library sync run. Subscribing immediately queues all current items via existing `DownloadService.enqueueMovie/enqueueEpisode`. Removals delete the queue item, the dest file, and the Tdarr record. Frontend adds a Playlists tab with a 2×2 composite poster grid and a list-style detail view.

**Tech Stack:** Spring Boot 3, Spring Data JPA, Liquibase, PostgreSQL, Lombok, AssertJ, Mockito, Vue 3 + Vitest + Vue Test Utils.

---

## File Structure

**New backend files:**
- `model/Playlist.java` — JPA entity for `playlists` table
- `model/PlaylistItem.java` — JPA entity for `playlist_items` table
- `model/PlaylistSubscription.java` — JPA entity for `playlist_subscriptions` table
- `repository/PlaylistRepository.java`
- `repository/PlaylistItemRepository.java`
- `repository/PlaylistSubscriptionRepository.java`
- `client/dto/PlexPlaylist.java` — Plex API playlist DTO
- `service/PlaylistSyncService.java` — sync + enqueue/cancel logic
- `controller/PlaylistController.java` — 4 REST endpoints
- `dto/PlaylistResponse.java` — list response
- `dto/PlaylistDetailResponse.java` — detail response (includes items)
- `dto/PlaylistItemResponse.java` — per-item response

**Modified backend files:**
- `repository/DownloadQueueRepository.java` — add 2 query methods
- `client/TdarrClient.java` — add `deleteFile()` + package-private `callDelete()`
- `client/PlexMediaServerClient.java` — add `getPlaylists()`, `getPlaylistItems()`
- `service/LibrarySyncService.java` — inject + call `PlaylistSyncService.syncAll()`

**New test files:**
- `client/TdarrClientTest.java` — add 3 tests (modify existing file)
- `service/PlaylistSyncServiceTest.java`
- `controller/PlaylistControllerTest.java`
- `service/LibrarySyncServiceTest.java` — add 1 test (modify existing file)

**New frontend files:**
- `src/api/playlists.js`
- `src/views/PlaylistsView.vue`
- `src/views/PlaylistDetailView.vue`
- `src/views/__tests__/PlaylistsView.test.js`
- `src/views/__tests__/PlaylistDetailView.test.js`

**Modified frontend files:**
- `src/components/NavBar.vue`
- `src/router/index.js`

---

## Gradle / Java setup (run before any backend command)

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
```

---

### Task 1: JPA entities + repositories

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/model/Playlist.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/model/PlaylistItem.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/model/PlaylistSubscription.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/repository/PlaylistRepository.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/repository/PlaylistItemRepository.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/repository/PlaylistSubscriptionRepository.java`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java`

- [ ] **Step 1: Create Playlist entity**

```java
// backend/src/main/java/org/lolobored/plexdownloader/model/Playlist.java
package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity @Table(name = "playlists")
public class Playlist {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @Column(nullable = false)
    private String title;
    @Column(name = "playlist_type")
    private String playlistType;
    @Column(name = "leaf_count")
    private int leafCount;
    @Column(name = "synced_at")
    private Instant syncedAt;
}
```

- [ ] **Step 2: Create PlaylistItem entity**

`mediaType` stores `"MOVIE"` or `"EPISODE"` matching `DownloadQueueItem.MediaType` enum names.

```java
// backend/src/main/java/org/lolobored/plexdownloader/model/PlaylistItem.java
package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;

@Data @Entity @Table(name = "playlist_items")
public class PlaylistItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "playlist_id", nullable = false)
    private Long playlistId;
    @Column(name = "plex_id", nullable = false)
    private String plexId;
    @Column(name = "media_type", nullable = false)
    private String mediaType;  // "MOVIE" or "EPISODE"
    private Integer ordinal;
}
```

- [ ] **Step 3: Create PlaylistSubscription entity**

```java
// backend/src/main/java/org/lolobored/plexdownloader/model/PlaylistSubscription.java
package org.lolobored.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.time.Instant;

@Data @ToString(exclude = {"user", "playlist"}) @Entity @Table(name = "playlist_subscriptions")
public class PlaylistSubscription {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

- [ ] **Step 4: Create PlaylistRepository**

```java
// backend/src/main/java/org/lolobored/plexdownloader/repository/PlaylistRepository.java
package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PlaylistRepository extends JpaRepository<Playlist, Long> {
    Optional<Playlist> findByPlexId(String plexId);
}
```

- [ ] **Step 5: Create PlaylistItemRepository**

```java
// backend/src/main/java/org/lolobored/plexdownloader/repository/PlaylistItemRepository.java
package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.PlaylistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Set;

public interface PlaylistItemRepository extends JpaRepository<PlaylistItem, Long> {
    List<PlaylistItem> findByPlaylistIdOrderByOrdinalAsc(Long playlistId);
    List<PlaylistItem> findTop4ByPlaylistIdOrderByOrdinalAsc(Long playlistId);
    void deleteByPlaylistIdAndPlexId(Long playlistId, String plexId);

    @Query("SELECT i.plexId FROM PlaylistItem i WHERE i.playlistId = :playlistId")
    Set<String> findPlexIdsByPlaylistId(@Param("playlistId") Long playlistId);
}
```

- [ ] **Step 6: Create PlaylistSubscriptionRepository**

```java
// backend/src/main/java/org/lolobored/plexdownloader/repository/PlaylistSubscriptionRepository.java
package org.lolobored.plexdownloader.repository;

import org.lolobored.plexdownloader.model.PlaylistSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface PlaylistSubscriptionRepository extends JpaRepository<PlaylistSubscription, Long> {

    @Query("SELECT s FROM PlaylistSubscription s JOIN FETCH s.user WHERE s.playlist.id = :playlistId")
    List<PlaylistSubscription> findByPlaylistIdWithUser(@Param("playlistId") Long playlistId);

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END " +
           "FROM PlaylistSubscription s WHERE s.user.id = :userId AND s.playlist.id = :playlistId")
    boolean existsByUserIdAndPlaylistId(@Param("userId") Long userId, @Param("playlistId") Long playlistId);

    @Modifying
    @Transactional
    @Query("DELETE FROM PlaylistSubscription s WHERE s.user.id = :userId AND s.playlist.id = :playlistId")
    void deleteByUserIdAndPlaylistId(@Param("userId") Long userId, @Param("playlistId") Long playlistId);
}
```

- [ ] **Step 7: Add 2 methods to DownloadQueueRepository**

Add these two methods to the end of `DownloadQueueRepository.java` (before the closing `}`):

```java
    boolean existsByUser_IdAndMediaTypeAndMediaId(Long userId, DownloadQueueItem.MediaType type, Long mediaId);

    Optional<DownloadQueueItem> findByUser_IdAndMediaTypeAndMediaId(
        Long userId, DownloadQueueItem.MediaType type, Long mediaId);
```

Also add `import java.util.Optional;` if not already present (it already is — check existing file).

- [ ] **Step 8: Verify compilation**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew compileJava --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/model/Playlist.java \
        backend/src/main/java/org/lolobored/plexdownloader/model/PlaylistItem.java \
        backend/src/main/java/org/lolobored/plexdownloader/model/PlaylistSubscription.java \
        backend/src/main/java/org/lolobored/plexdownloader/repository/PlaylistRepository.java \
        backend/src/main/java/org/lolobored/plexdownloader/repository/PlaylistItemRepository.java \
        backend/src/main/java/org/lolobored/plexdownloader/repository/PlaylistSubscriptionRepository.java \
        backend/src/main/java/org/lolobored/plexdownloader/repository/DownloadQueueRepository.java
git commit -m "feat: add Playlist/PlaylistItem/PlaylistSubscription entities and repositories"
```

---

### Task 2: TdarrClient.deleteFile()

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java`

- [ ] **Step 1: Write 3 failing tests**

Add to the end of `TdarrClientTest.java` (inside the class, before closing `}`):

```java
    @Test
    void deleteFile_doesNotCallDelete_whenUrlBlank() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.empty());
        client.deleteFile("/some/file.mkv");
        verify(client, never()).callDelete(anyString(), anyString());
    }

    @Test
    void deleteFile_callsDeleteWithCorrectArgs_whenUrlSet() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        doNothing().when(client).callDelete(anyString(), anyString());
        client.deleteFile("/some/file.mkv");
        verify(client).callDelete("http://tdarr:8265", "/some/file.mkv");
    }

    @Test
    void deleteFile_doesNotThrow_whenRestClientException() {
        when(settings.get("tdarr.server.url")).thenReturn(Optional.of("http://tdarr:8265"));
        doThrow(new RestClientException("conn refused")).when(client).callDelete(anyString(), anyString());
        client.deleteFile("/some/file.mkv");  // must not throw
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew test --tests "*TdarrClientTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `deleteFile` method and `callDelete` method not found.

- [ ] **Step 3: Add callDelete + deleteFile to TdarrClient**

Add these two methods to `TdarrClient.java` after `getFileStatus()`:

```java
    /** Package-private so tests can stub it with @Spy. */
    void callDelete(String baseUrl, String filePath) {
        Map<String, Object> body = Map.of(
            "collection", "FileJSONDB",
            "mode",       "deleteOne",
            "docID",      filePath
        );
        RestClient.create().post()
            .uri(baseUrl + "/api/v2/cruddb")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    public void deleteFile(String filePath) {
        String baseUrl = settings.get("tdarr.server.url").orElse("").trim();
        if (baseUrl.isBlank()) {
            log.warn("Tdarr URL not configured, skipping deleteFile for {}", filePath);
            return;
        }
        try {
            callDelete(baseUrl, filePath);
        } catch (RestClientException e) {
            log.warn("Tdarr deleteFile failed for {}: {}", filePath, e.getMessage());
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "*TdarrClientTest*" --no-daemon 2>&1 | tail -10
```

Expected: all 11 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/client/TdarrClient.java \
        backend/src/test/java/org/lolobored/plexdownloader/client/TdarrClientTest.java
git commit -m "feat: add TdarrClient.deleteFile() with fire-and-forget error handling"
```

---

### Task 3: PlexPlaylist DTO + PlexMediaServerClient additions

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/client/dto/PlexPlaylist.java`
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/client/PlexMediaServerClient.java`

- [ ] **Step 1: Create PlexPlaylist DTO**

```java
// backend/src/main/java/org/lolobored/plexdownloader/client/dto/PlexPlaylist.java
package org.lolobored.plexdownloader.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class PlexPlaylist {
    @JsonProperty("ratingKey")    private String ratingKey;
    @JsonProperty("title")        private String title;
    @JsonProperty("playlistType") private String playlistType;  // "video", "audio", "photo"
    @JsonProperty("leafCount")    private int leafCount;
    @JsonProperty("thumb")        private String thumb;
}
```

- [ ] **Step 2: Add PlexPlaylistsResponse inner class to PlexMediaServerClient**

Add this static inner class after the existing `PlexLibraryContentsResponse` class (before the last `}`):

```java
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class PlexPlaylistsResponse {
        @JsonProperty("MediaContainer")
        private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Container {
            @JsonProperty("Metadata")
            private List<PlexPlaylist> metadata;
        }
    }
```

Also add import at the top of the file:
```java
import org.lolobored.plexdownloader.client.dto.PlexPlaylist;
```

- [ ] **Step 3: Add getPlaylists() and getPlaylistItems() to PlexMediaServerClient**

Add after `getChildren()` method:

```java
    /**
     * Returns all Plex playlists of type "video".
     * Returns empty list if Plex is unreachable or has no playlists.
     */
    public List<PlexPlaylist> getPlaylists() {
        PlexPlaylistsResponse resp = buildClient().get()
            .uri("/playlists/all")
            .retrieve()
            .body(PlexPlaylistsResponse.class);
        if (resp == null || resp.getMediaContainer() == null) return List.of();
        List<PlexPlaylist> items = resp.getMediaContainer().getMetadata();
        if (items == null) return List.of();
        return items.stream()
            .filter(p -> "video".equals(p.getPlaylistType()))
            .toList();
    }

    /**
     * Returns items inside a Plex playlist. Each item is a movie or episode PlexItem.
     */
    public List<PlexItem> getPlaylistItems(String ratingKey) {
        PlexLibraryContentsResponse resp = buildClient().get()
            .uri("/playlists/{key}/items", ratingKey)
            .retrieve()
            .body(PlexLibraryContentsResponse.class);
        if (resp == null || resp.getMediaContainer() == null) return List.of();
        List<PlexItem> items = resp.getMediaContainer().getMetadata();
        return items != null ? items : List.of();
    }
```

- [ ] **Step 4: Verify compilation**

```bash
./gradlew compileJava --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/client/dto/PlexPlaylist.java \
        backend/src/main/java/org/lolobored/plexdownloader/client/PlexMediaServerClient.java
git commit -m "feat: add PlexPlaylist DTO and getPlaylists/getPlaylistItems to PlexMediaServerClient"
```

---

### Task 4: PlaylistSyncService

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/service/PlaylistSyncService.java`
- Create: `backend/src/test/java/org/lolobored/plexdownloader/service/PlaylistSyncServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
// backend/src/test/java/org/lolobored/plexdownloader/service/PlaylistSyncServiceTest.java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.client.dto.PlexItem;
import org.lolobored.plexdownloader.client.dto.PlexPlaylist;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistSyncServiceTest {

    @Mock PlexMediaServerClient plexClient;
    @Mock PlaylistRepository playlistRepo;
    @Mock PlaylistItemRepository itemRepo;
    @Mock PlaylistSubscriptionRepository subRepo;
    @Mock MovieRepository movieRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock DownloadQueueRepository queueRepo;
    @Mock DownloadService downloadService;
    @Mock TdarrClient tdarrClient;
    @InjectMocks PlaylistSyncService service;

    private PlexPlaylist plexPlaylist(String key) {
        PlexPlaylist p = new PlexPlaylist();
        p.setRatingKey(key); p.setTitle("Test"); p.setPlaylistType("video");
        return p;
    }

    private Playlist localPlaylist(Long id, String plexId) {
        Playlist p = new Playlist(); p.setId(id); p.setPlexId(plexId);
        return p;
    }

    private PlexItem plexItem(String key, String type) {
        PlexItem i = new PlexItem(); i.setRatingKey(key); i.setType(type);
        return i;
    }

    @Test
    void syncAll_queuesAddedMovieForSubscriber() {
        when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));
        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of()); // nothing old
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("m1", "movie")));

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));

        Movie m = new Movie(); m.setId(100L);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L)).thenReturn(false);
        when(downloadService.enqueueMovie(100L, user)).thenReturn(List.of(1L));

        service.syncAll();

        verify(downloadService).enqueueMovie(100L, user);
        verify(itemRepo).save(argThat(i -> "m1".equals(i.getPlexId()) && "MOVIE".equals(i.getMediaType())));
    }

    @Test
    void syncAll_cancelsRemovedItem_deletesFileAndTdarr(@TempDir Path tempDir) throws Exception {
        when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));
        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);

        PlaylistItem oldItem = new PlaylistItem();
        oldItem.setPlexId("m1"); oldItem.setMediaType("MOVIE"); oldItem.setPlaylistId(10L);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(oldItem));
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of()); // item removed

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));

        Path destFile = tempDir.resolve("movie.mkv");
        Files.writeString(destFile, "data");
        Movie m = new Movie(); m.setId(100L);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        DownloadQueueItem qi = new DownloadQueueItem();
        qi.setDestFilePath(destFile.toString());
        when(queueRepo.findByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L))
            .thenReturn(Optional.of(qi));

        service.syncAll();

        verify(queueRepo).delete(qi);
        verify(tdarrClient).deleteFile(destFile.toString());
        assertThat(destFile).doesNotExist();
    }

    @Test
    void syncAll_skipsEnqueue_whenAlreadyQueued() {
        when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));
        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of());
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("m1", "movie")));

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));

        Movie m = new Movie(); m.setId(100L);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L)).thenReturn(true);

        service.syncAll();

        verify(downloadService, never()).enqueueMovie(anyLong(), any());
    }

    @Test
    void syncAll_skipsItem_whenNotInLibrary() {
        when(plexClient.getPlaylists()).thenReturn(List.of(plexPlaylist("pl1")));
        Playlist local = localPlaylist(10L, "pl1");
        when(playlistRepo.findByPlexId("pl1")).thenReturn(Optional.of(local));
        when(playlistRepo.save(any())).thenReturn(local);
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of());
        when(plexClient.getPlaylistItems("pl1")).thenReturn(List.of(plexItem("unknown", "movie")));

        User user = new User(); user.setId(1L);
        PlaylistSubscription sub = new PlaylistSubscription(); sub.setUser(user);
        when(subRepo.findByPlaylistIdWithUser(10L)).thenReturn(List.of(sub));
        when(movieRepo.findByPlexId("unknown")).thenReturn(Optional.empty());

        service.syncAll();

        verify(downloadService, never()).enqueueMovie(anyLong(), any());
    }

    @Test
    void syncAll_doesNotThrow_whenPlexUnreachable() {
        when(plexClient.getPlaylists()).thenThrow(new RuntimeException("Plex unreachable"));
        service.syncAll();  // must not throw
    }

    @Test
    void enqueueForSubscription_queuesAllCurrentItems() {
        PlaylistItem pi1 = new PlaylistItem(); pi1.setPlexId("m1"); pi1.setMediaType("MOVIE");
        PlaylistItem pi2 = new PlaylistItem(); pi2.setPlexId("e1"); pi2.setMediaType("EPISODE");
        when(itemRepo.findByPlaylistIdOrderByOrdinalAsc(10L)).thenReturn(List.of(pi1, pi2));

        User user = new User(); user.setId(1L);
        Movie m = new Movie(); m.setId(100L);
        Episode ep = new Episode(); ep.setId(200L);
        when(movieRepo.findByPlexId("m1")).thenReturn(Optional.of(m));
        when(episodeRepo.findByPlexId("e1")).thenReturn(Optional.of(ep));
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.MOVIE, 100L)).thenReturn(false);
        when(queueRepo.existsByUser_IdAndMediaTypeAndMediaId(1L, DownloadQueueItem.MediaType.EPISODE, 200L)).thenReturn(false);
        when(downloadService.enqueueMovie(100L, user)).thenReturn(List.of(1L));
        when(downloadService.enqueueEpisode(200L, user)).thenReturn(List.of(2L));

        service.enqueueForSubscription(10L, user);

        verify(downloadService).enqueueMovie(100L, user);
        verify(downloadService).enqueueEpisode(200L, user);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "*PlaylistSyncServiceTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `PlaylistSyncService` not found.

- [ ] **Step 3: Create PlaylistSyncService**

```java
// backend/src/main/java/org/lolobored/plexdownloader/service/PlaylistSyncService.java
package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.client.dto.PlexItem;
import org.lolobored.plexdownloader.client.dto.PlexPlaylist;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaylistSyncService {

    private final PlexMediaServerClient plexClient;
    private final PlaylistRepository playlistRepo;
    private final PlaylistItemRepository itemRepo;
    private final PlaylistSubscriptionRepository subRepo;
    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final DownloadQueueRepository queueRepo;
    private final DownloadService downloadService;
    private final TdarrClient tdarrClient;

    public void syncAll() {
        List<PlexPlaylist> plexPlaylists;
        try {
            plexPlaylists = plexClient.getPlaylists();
        } catch (Exception e) {
            log.warn("Failed to fetch playlists from Plex: {}", e.getMessage());
            return;
        }
        for (PlexPlaylist pp : plexPlaylists) {
            try {
                syncPlaylist(pp);
            } catch (Exception e) {
                log.warn("Failed to sync playlist {}: {}", pp.getRatingKey(), e.getMessage());
            }
        }
    }

    private void syncPlaylist(PlexPlaylist pp) {
        // Upsert Playlist row
        Playlist local = playlistRepo.findByPlexId(pp.getRatingKey()).orElseGet(Playlist::new);
        local.setPlexId(pp.getRatingKey());
        local.setTitle(pp.getTitle());
        local.setPlaylistType(pp.getPlaylistType());
        local.setLeafCount(pp.getLeafCount());
        local.setSyncedAt(Instant.now());
        local = playlistRepo.save(local);

        // Snapshot old items before update
        List<PlaylistItem> oldItems = itemRepo.findByPlaylistIdOrderByOrdinalAsc(local.getId());
        Set<String> oldPlexIds = oldItems.stream().map(PlaylistItem::getPlexId).collect(Collectors.toSet());
        Map<String, PlaylistItem> oldByPlexId = oldItems.stream()
            .collect(Collectors.toMap(PlaylistItem::getPlexId, i -> i));

        // Fetch new state from Plex
        List<PlexItem> fetched = plexClient.getPlaylistItems(pp.getRatingKey());
        Map<String, PlexItem> fetchedByKey = fetched.stream()
            .collect(Collectors.toMap(PlexItem::getRatingKey, i -> i, (a, b) -> a));
        Set<String> newPlexIds = fetchedByKey.keySet();

        Set<String> added   = new HashSet<>(newPlexIds); added.removeAll(oldPlexIds);
        Set<String> removed = new HashSet<>(oldPlexIds); removed.removeAll(newPlexIds);

        // Persist removals
        for (String plexId : removed) {
            itemRepo.deleteByPlaylistIdAndPlexId(local.getId(), plexId);
        }

        // Persist additions
        int ordinalBase = oldItems.size();
        int ordinalOffset = 0;
        for (String plexId : added) {
            PlexItem pi = fetchedByKey.get(plexId);
            if (pi == null) continue;
            PlaylistItem item = new PlaylistItem();
            item.setPlaylistId(local.getId());
            item.setPlexId(pi.getRatingKey());
            item.setMediaType(mapMediaType(pi.getType()));
            item.setOrdinal(ordinalBase + ordinalOffset++);
            itemRepo.save(item);
        }

        // React to diffs for each subscriber
        List<PlaylistSubscription> subs = subRepo.findByPlaylistIdWithUser(local.getId());
        if (subs.isEmpty()) return;

        for (PlaylistSubscription sub : subs) {
            User user = sub.getUser();
            for (String plexId : added) {
                PlexItem pi = fetchedByKey.get(plexId);
                if (pi != null) enqueueItem(user, pi.getRatingKey(), mapMediaType(pi.getType()));
            }
            for (String plexId : removed) {
                PlaylistItem pi = oldByPlexId.get(plexId);
                if (pi != null) cancelItem(user, pi.getPlexId(), pi.getMediaType());
            }
        }
    }

    /** Called when user subscribes — queues all current items immediately. */
    public void enqueueForSubscription(Long playlistId, User user) {
        List<PlaylistItem> items = itemRepo.findByPlaylistIdOrderByOrdinalAsc(playlistId);
        for (PlaylistItem pi : items) {
            enqueueItem(user, pi.getPlexId(), pi.getMediaType());
        }
    }

    // Package-private for testing
    void enqueueItem(User user, String plexId, String mediaType) {
        if ("MOVIE".equals(mediaType)) {
            movieRepo.findByPlexId(plexId).ifPresent(m -> {
                if (!queueRepo.existsByUser_IdAndMediaTypeAndMediaId(
                        user.getId(), DownloadQueueItem.MediaType.MOVIE, m.getId())) {
                    try { downloadService.enqueueMovie(m.getId(), user); }
                    catch (Exception e) { log.warn("Failed to enqueue movie {}: {}", m.getId(), e.getMessage()); }
                }
            });
        } else if ("EPISODE".equals(mediaType)) {
            episodeRepo.findByPlexId(plexId).ifPresent(ep -> {
                if (!queueRepo.existsByUser_IdAndMediaTypeAndMediaId(
                        user.getId(), DownloadQueueItem.MediaType.EPISODE, ep.getId())) {
                    try { downloadService.enqueueEpisode(ep.getId(), user); }
                    catch (Exception e) { log.warn("Failed to enqueue episode {}: {}", ep.getId(), e.getMessage()); }
                }
            });
        }
    }

    // Package-private for testing
    void cancelItem(User user, String plexId, String mediaType) {
        Long mediaId = resolveLocalId(plexId, mediaType);
        if (mediaId == null) return;
        DownloadQueueItem.MediaType type = DownloadQueueItem.MediaType.valueOf(mediaType);
        queueRepo.findByUser_IdAndMediaTypeAndMediaId(user.getId(), type, mediaId).ifPresent(qi -> {
            String destPath = qi.getDestFilePath();
            queueRepo.delete(qi);
            if (destPath != null) {
                try { Files.deleteIfExists(Path.of(destPath)); }
                catch (IOException e) { log.warn("Could not delete file {}: {}", destPath, e.getMessage()); }
                tdarrClient.deleteFile(destPath);
            }
        });
    }

    private Long resolveLocalId(String plexId, String mediaType) {
        if ("MOVIE".equals(mediaType)) return movieRepo.findByPlexId(plexId).map(Movie::getId).orElse(null);
        if ("EPISODE".equals(mediaType)) return episodeRepo.findByPlexId(plexId).map(Episode::getId).orElse(null);
        return null;
    }

    private String mapMediaType(String plexType) {
        return "movie".equals(plexType) ? "MOVIE" : "EPISODE";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "*PlaylistSyncServiceTest*" --no-daemon 2>&1 | tail -10
```

Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/PlaylistSyncService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/PlaylistSyncServiceTest.java
git commit -m "feat: add PlaylistSyncService — sync, enqueue on add, cancel+delete on remove"
```

---

### Task 5: PlaylistController + DTOs

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistResponse.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistDetailResponse.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistItemResponse.java`
- Create: `backend/src/main/java/org/lolobored/plexdownloader/controller/PlaylistController.java`
- Create: `backend/src/test/java/org/lolobored/plexdownloader/controller/PlaylistControllerTest.java`

- [ ] **Step 1: Create DTO records**

```java
// backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistResponse.java
package org.lolobored.plexdownloader.dto;

import java.util.List;

public record PlaylistResponse(
    Long id,
    String plexId,
    String title,
    String playlistType,
    int leafCount,
    boolean subscribed,
    List<String> posterPlexIds
) {}
```

```java
// backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistItemResponse.java
package org.lolobored.plexdownloader.dto;

public record PlaylistItemResponse(
    Long id,
    String plexId,
    String mediaType,
    Integer ordinal,
    String title,
    Integer year,
    String queueStatus,   // null if not queued
    String tdarrStatus    // null if not queued
) {}
```

```java
// backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistDetailResponse.java
package org.lolobored.plexdownloader.dto;

import java.util.List;

public record PlaylistDetailResponse(
    Long id,
    String plexId,
    String title,
    String playlistType,
    int leafCount,
    boolean subscribed,
    List<String> posterPlexIds,
    List<PlaylistItemResponse> items
) {}
```

- [ ] **Step 2: Write failing controller tests**

```java
// backend/src/test/java/org/lolobored/plexdownloader/controller/PlaylistControllerTest.java
package org.lolobored.plexdownloader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.lolobored.plexdownloader.config.JwtAuthFilter;
import org.lolobored.plexdownloader.config.SecurityConfig;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.service.PlaylistSyncService;
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
import org.lolobored.plexdownloader.service.JwtService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PlaylistController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class PlaylistControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean PlaylistRepository playlistRepo;
    @MockBean PlaylistItemRepository itemRepo;
    @MockBean PlaylistSubscriptionRepository subRepo;
    @MockBean MovieRepository movieRepo;
    @MockBean EpisodeRepository episodeRepo;
    @MockBean DownloadQueueRepository queueRepo;
    @MockBean PlaylistSyncService playlistSyncService;
    @MockBean JwtService jwtService;
    @MockBean org.lolobored.plexdownloader.repository.UserRepository userRepository;
    @MockBean JwtAuthFilter jwtAuthFilter;

    User user;

    @BeforeEach
    void setupAuth() throws Exception {
        user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole(User.Role.USER);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req   = inv.getArgument(0);
            HttpServletResponse res  = inv.getArgument(1);
            FilterChain chain        = inv.getArgument(2);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void getPlaylists_returnsList_withSubscribedFlag() throws Exception {
        Playlist p = new Playlist();
        p.setId(1L); p.setPlexId("pl1"); p.setTitle("Action"); p.setPlaylistType("video"); p.setLeafCount(5);
        when(playlistRepo.findAll()).thenReturn(List.of(p));
        when(itemRepo.findTop4ByPlaylistIdOrderByOrdinalAsc(1L)).thenReturn(List.of());
        when(subRepo.existsByUserIdAndPlaylistId(1L, 1L)).thenReturn(true);

        mockMvc.perform(get("/api/playlists"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].title").value("Action"))
            .andExpect(jsonPath("$[0].subscribed").value(true))
            .andExpect(jsonPath("$[0].posterPlexIds").isArray());
    }

    @Test
    void getPlaylist_returnsDetailWithItems() throws Exception {
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
            .andExpect(jsonPath("$.title").value("Action"))
            .andExpect(jsonPath("$.items[0].title").value("Inception"))
            .andExpect(jsonPath("$.items[0].year").value(2010))
            .andExpect(jsonPath("$.items[0].mediaType").value("MOVIE"));
    }

    @Test
    void getPlaylist_returns404_whenNotFound() throws Exception {
        when(playlistRepo.findById(99L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/playlists/99")).andExpect(status().isNotFound());
    }

    @Test
    void subscribe_createsSubscription_andQueuesItems() throws Exception {
        Playlist p = new Playlist(); p.setId(1L); p.setPlexId("pl1"); p.setTitle("A");
        when(playlistRepo.findById(1L)).thenReturn(Optional.of(p));
        when(subRepo.existsByUserIdAndPlaylistId(1L, 1L)).thenReturn(false);
        doNothing().when(playlistSyncService).enqueueForSubscription(anyLong(), any());

        mockMvc.perform(post("/api/playlists/1/subscribe"))
            .andExpect(status().isNoContent());

        verify(subRepo).save(argThat(s -> s.getPlaylist().getId().equals(1L) && s.getUser().getId().equals(1L)));
        verify(playlistSyncService).enqueueForSubscription(1L, user);
    }

    @Test
    void subscribe_isIdempotent_whenAlreadySubscribed() throws Exception {
        Playlist p = new Playlist(); p.setId(1L);
        when(playlistRepo.findById(1L)).thenReturn(Optional.of(p));
        when(subRepo.existsByUserIdAndPlaylistId(1L, 1L)).thenReturn(true);

        mockMvc.perform(post("/api/playlists/1/subscribe")).andExpect(status().isNoContent());

        verify(subRepo, never()).save(any());
        verify(playlistSyncService, never()).enqueueForSubscription(anyLong(), any());
    }

    @Test
    void unsubscribe_deletesSubscription_doesNotCancelFiles() throws Exception {
        mockMvc.perform(delete("/api/playlists/1/subscribe")).andExpect(status().isNoContent());
        verify(subRepo).deleteByUserIdAndPlaylistId(1L, 1L);
        verify(playlistSyncService, never()).enqueueForSubscription(anyLong(), any());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew test --tests "*PlaylistControllerTest*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `PlaylistController` not found.

- [ ] **Step 4: Create PlaylistController**

```java
// backend/src/main/java/org/lolobored/plexdownloader/controller/PlaylistController.java
package org.lolobored.plexdownloader.controller;

import org.lolobored.plexdownloader.dto.*;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.service.PlaylistSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistRepository playlistRepo;
    private final PlaylistItemRepository itemRepo;
    private final PlaylistSubscriptionRepository subRepo;
    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final DownloadQueueRepository queueRepo;
    private final PlaylistSyncService playlistSyncService;

    @GetMapping
    public List<PlaylistResponse> getPlaylists(@AuthenticationPrincipal User user) {
        return playlistRepo.findAll().stream()
            .map(p -> toResponse(p, user.getId()))
            .toList();
    }

    @GetMapping("/{id}")
    public PlaylistDetailResponse getPlaylist(@PathVariable Long id, @AuthenticationPrincipal User user) {
        Playlist playlist = playlistRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<PlaylistItemResponse> items = itemRepo.findByPlaylistIdOrderByOrdinalAsc(id).stream()
            .map(pi -> toItemResponse(pi, user.getId()))
            .toList();
        List<String> posterPlexIds = itemRepo.findTop4ByPlaylistIdOrderByOrdinalAsc(id).stream()
            .map(PlaylistItem::getPlexId).toList();
        boolean subscribed = subRepo.existsByUserIdAndPlaylistId(user.getId(), id);
        return new PlaylistDetailResponse(playlist.getId(), playlist.getPlexId(), playlist.getTitle(),
            playlist.getPlaylistType(), playlist.getLeafCount(), subscribed, posterPlexIds, items);
    }

    @PostMapping("/{id}/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@PathVariable Long id, @AuthenticationPrincipal User user) {
        Playlist playlist = playlistRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!subRepo.existsByUserIdAndPlaylistId(user.getId(), id)) {
            PlaylistSubscription sub = new PlaylistSubscription();
            sub.setUser(user);
            sub.setPlaylist(playlist);
            subRepo.save(sub);
            playlistSyncService.enqueueForSubscription(id, user);
        }
    }

    @DeleteMapping("/{id}/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@PathVariable Long id, @AuthenticationPrincipal User user) {
        subRepo.deleteByUserIdAndPlaylistId(user.getId(), id);
    }

    private PlaylistResponse toResponse(Playlist p, Long userId) {
        List<String> posterPlexIds = itemRepo.findTop4ByPlaylistIdOrderByOrdinalAsc(p.getId()).stream()
            .map(PlaylistItem::getPlexId).toList();
        boolean subscribed = subRepo.existsByUserIdAndPlaylistId(userId, p.getId());
        return new PlaylistResponse(p.getId(), p.getPlexId(), p.getTitle(),
            p.getPlaylistType(), p.getLeafCount(), subscribed, posterPlexIds);
    }

    private PlaylistItemResponse toItemResponse(PlaylistItem pi, Long userId) {
        String title = null;
        Integer year = null;
        Long mediaId = null;

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
                tdarrStatus = qiOpt.get().getTdarrStatus().name();
            }
        }

        return new PlaylistItemResponse(pi.getId(), pi.getPlexId(), pi.getMediaType(),
            pi.getOrdinal(), title, year, queueStatus, tdarrStatus);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew test --tests "*PlaylistControllerTest*" --no-daemon 2>&1 | tail -10
```

Expected: all 6 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistResponse.java \
        backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistDetailResponse.java \
        backend/src/main/java/org/lolobored/plexdownloader/dto/PlaylistItemResponse.java \
        backend/src/main/java/org/lolobored/plexdownloader/controller/PlaylistController.java \
        backend/src/test/java/org/lolobored/plexdownloader/controller/PlaylistControllerTest.java
git commit -m "feat: add PlaylistController with GET/subscribe/unsubscribe endpoints"
```

---

### Task 6: Wire PlaylistSyncService into LibrarySyncService

**Files:**
- Modify: `backend/src/main/java/org/lolobored/plexdownloader/service/LibrarySyncService.java`
- Modify: `backend/src/test/java/org/lolobored/plexdownloader/service/LibrarySyncServiceTest.java`

- [ ] **Step 1: Write failing test**

Add to the end of `LibrarySyncServiceTest.java` (inside the class):

```java
    @Mock PlaylistSyncService playlistSyncService;

    @Test
    void syncAll_callsPlaylistSyncAfterLibraries() {
        when(plexClient.getLibraries()).thenReturn(List.of());
        service.syncAll();
        verify(playlistSyncService).syncAll();
    }
```

Note: `@Mock PlaylistSyncService playlistSyncService` must be added as a field alongside the other `@Mock` fields at the top of the class. Add it there, not inside the test method.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "*LibrarySyncServiceTest*syncAll_callsPlaylistSyncAfterLibraries*" --no-daemon 2>&1 | tail -20
```

Expected: FAIL — `PlaylistSyncService` not injected.

- [ ] **Step 3: Add PlaylistSyncService to LibrarySyncService**

In `LibrarySyncService.java`:

Add field (with existing `@RequiredArgsConstructor`, just adding the field is enough):
```java
    private final PlaylistSyncService playlistSyncService;
```

Add the call inside the `try` block in `syncAll()`, after the for-loop that marks libraries done and before `lastSyncAt = Instant.now();`:

```java
            playlistSyncService.syncAll();
            lastSyncAt = Instant.now();
```

Replace `lastSyncAt = Instant.now();` with the two lines above (i.e., `playlistSyncService.syncAll()` goes right before `lastSyncAt = Instant.now()`).

- [ ] **Step 4: Run all LibrarySyncService tests**

```bash
./gradlew test --tests "*LibrarySyncServiceTest*" --no-daemon 2>&1 | tail -10
```

Expected: all tests PASS.

- [ ] **Step 5: Run full backend test suite**

```bash
./gradlew test --no-daemon 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/service/LibrarySyncService.java \
        backend/src/test/java/org/lolobored/plexdownloader/service/LibrarySyncServiceTest.java
git commit -m "feat: call PlaylistSyncService.syncAll() at end of library sync"
```

---

### Task 7: Frontend API + NavBar + Router

**Files:**
- Create: `frontend/src/api/playlists.js`
- Modify: `frontend/src/components/NavBar.vue`
- Modify: `frontend/src/router/index.js`

- [ ] **Step 1: Create api/playlists.js**

```js
// frontend/src/api/playlists.js
import http from './axios.js'

export async function getPlaylists() {
  const { data } = await http.get('/api/playlists')
  return data  // PlaylistResponse[]
}

export async function getPlaylist(id) {
  const { data } = await http.get(`/api/playlists/${id}`)
  return data  // PlaylistDetailResponse
}

export async function subscribe(id) {
  await http.post(`/api/playlists/${id}/subscribe`)
}

export async function unsubscribe(id) {
  await http.delete(`/api/playlists/${id}/subscribe`)
}
```

- [ ] **Step 2: Add Playlists link to NavBar**

In `frontend/src/components/NavBar.vue`, add after `<RouterLink to="/movies">Movies</RouterLink>`:

```html
      <RouterLink to="/playlists">Playlists</RouterLink>
```

The `links` div should read:
```html
    <div class="links">
      <RouterLink to="/movies">Movies</RouterLink>
      <RouterLink to="/playlists">Playlists</RouterLink>
      <RouterLink to="/tv">TV Shows</RouterLink>
      <RouterLink to="/queue" class="queue-link">
        Queue
        <span v-if="pendingCount > 0" class="badge">{{ pendingCount }}</span>
      </RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/settings">Settings</RouterLink>
    </div>
```

- [ ] **Step 3: Add routes to router/index.js**

Add these two routes after the `/movies/:id` route (before `/tv`):

```js
  { path: '/playlists',     component: () => import('@/views/PlaylistsView.vue') },
  { path: '/playlists/:id', component: () => import('@/views/PlaylistDetailView.vue') },
```

- [ ] **Step 4: Verify frontend compiles**

```bash
cd frontend && npm run build 2>&1 | tail -20
```

Expected: build succeeds (ignore any missing component warnings — views don't exist yet).

Actually, the router imports will fail at runtime (not build) if the view files don't exist. Skip this step and verify in Task 9 after views are created.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/playlists.js \
        frontend/src/components/NavBar.vue \
        frontend/src/router/index.js
git commit -m "feat: add playlists API, NavBar link, and router routes"
```

---

### Task 8: PlaylistsView

**Files:**
- Create: `frontend/src/views/PlaylistsView.vue`
- Create: `frontend/src/views/__tests__/PlaylistsView.test.js`

- [ ] **Step 1: Write failing tests**

```js
// frontend/src/views/__tests__/PlaylistsView.test.js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import PlaylistsView from '../PlaylistsView.vue'

vi.mock('../../api/playlists.js', () => ({
  getPlaylists: vi.fn(),
  subscribe: vi.fn(),
  unsubscribe: vi.fn()
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' }
}))

import { getPlaylists } from '../../api/playlists.js'

const fakePlaylists = [
  { id: 1, plexId: 'pl1', title: 'Action Movies', leafCount: 5, subscribed: true,  posterPlexIds: ['m1','m2','m3','m4'] },
  { id: 2, plexId: 'pl2', title: 'Sci-Fi Picks',  leafCount: 8, subscribed: false, posterPlexIds: ['m5'] }
]

describe('PlaylistsView', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('fetches playlists on mount and renders titles', async () => {
    getPlaylists.mockResolvedValue(fakePlaylists)
    const w = mount(PlaylistsView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(getPlaylists).toHaveBeenCalledOnce()
    expect(w.text()).toContain('Action Movies')
    expect(w.text()).toContain('Sci-Fi Picks')
  })

  it('shows loading state before data arrives', () => {
    getPlaylists.mockReturnValue(new Promise(() => {}))  // never resolves
    const w = mount(PlaylistsView, { global: { plugins: [createTestingPinia()] } })
    expect(w.text()).toContain('Loading')
  })

  it('shows empty state when no playlists', async () => {
    getPlaylists.mockResolvedValue([])
    const w = mount(PlaylistsView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(w.text()).toContain('No playlists')
  })

  it('renders 4 composite tiles for subscribed playlist', async () => {
    getPlaylists.mockResolvedValue(fakePlaylists)
    const w = mount(PlaylistsView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    const tiles = w.findAll('.composite-tile')
    expect(tiles.length).toBeGreaterThanOrEqual(4)
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd frontend && npm run test -- --reporter=verbose 2>&1 | grep -A5 "PlaylistsView"
```

Expected: FAIL — `PlaylistsView` not found.

- [ ] **Step 3: Create PlaylistsView.vue**

```vue
<!-- frontend/src/views/PlaylistsView.vue -->
<template>
  <div class="playlists-root">
    <div class="toolbar">
      <h2>Playlists</h2>
    </div>

    <div v-if="loading" class="loading">Loading…</div>
    <div v-else-if="playlists.length === 0" class="empty">No playlists found.</div>
    <div v-else class="grid">
      <div
        v-for="p in playlists"
        :key="p.id"
        class="poster-card"
        @click="router.push(`/playlists/${p.id}`)"
      >
        <div class="img-wrap">
          <div class="composite-grid">
            <div
              v-for="(plexId, idx) in p.posterPlexIds.slice(0, 4)"
              :key="idx"
              class="composite-tile"
            >
              <img :src="`/api/posters/${plexId}.jpg`" :alt="p.title" loading="lazy" />
            </div>
            <div
              v-for="i in Math.max(0, 4 - p.posterPlexIds.length)"
              :key="'ph-' + i"
              class="composite-tile placeholder"
            />
          </div>
          <div v-if="p.subscribed" class="subscribed-badge" title="Subscribed">●</div>
        </div>
        <div class="info">
          <p class="title">{{ p.title }}</p>
          <p class="subtitle">{{ p.leafCount }} items</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getPlaylists } from '@/api/playlists.js'

const router    = useRouter()
const playlists = ref([])
const loading   = ref(true)

onMounted(async () => {
  try {
    playlists.value = await getPlaylists()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 24px; margin-bottom: 24px; }
h2 { font-size: 1.5rem; font-weight: 600; }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 16px;
}

.poster-card { cursor: pointer; }
.poster-card:hover .img-wrap .composite-tile img { transform: scale(1.03); }

.img-wrap {
  position: relative;
  border-radius: 6px;
  overflow: hidden;
  background: var(--surface2);
  aspect-ratio: 2/3;
}

.composite-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  height: 100%;
  gap: 2px;
}

.composite-tile { overflow: hidden; }
.composite-tile img {
  width: 100%; height: 100%;
  object-fit: cover;
  display: block;
  transition: transform .2s ease;
}
.composite-tile.placeholder { background: var(--surface); }

.subscribed-badge {
  position: absolute; top: 6px; right: 6px;
  width: 16px; height: 16px; border-radius: 50%;
  background: var(--accent); color: #fff;
  font-size: .55rem; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 1px 4px rgba(0,0,0,.5);
}

.info { padding: 6px 2px 0; }
.title { font-size: .9rem; font-weight: 500; white-space: nowrap;
         overflow: hidden; text-overflow: ellipsis; }
.subtitle { font-size: .8rem; color: var(--text-muted); margin-top: 2px; }
.loading, .empty { color: var(--text-muted); padding: 40px 0; text-align: center; }
</style>
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
npm run test -- --reporter=verbose 2>&1 | grep -A10 "PlaylistsView"
```

Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/PlaylistsView.vue \
        frontend/src/views/__tests__/PlaylistsView.test.js
git commit -m "feat: add PlaylistsView with 2x2 composite poster grid"
```

---

### Task 9: PlaylistDetailView

**Files:**
- Create: `frontend/src/views/PlaylistDetailView.vue`
- Create: `frontend/src/views/__tests__/PlaylistDetailView.test.js`

- [ ] **Step 1: Write failing tests**

```js
// frontend/src/views/__tests__/PlaylistDetailView.test.js
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import PlaylistDetailView from '../PlaylistDetailView.vue'

vi.mock('../../api/playlists.js', () => ({
  getPlaylist:  vi.fn(),
  subscribe:    vi.fn(),
  unsubscribe:  vi.fn()
}))
vi.mock('vue-router', () => ({
  useRoute:  () => ({ params: { id: '1' } }),
  useRouter: () => ({ back: vi.fn() })
}))

import { getPlaylist, subscribe, unsubscribe } from '../../api/playlists.js'

const fakePlaylist = {
  id: 1, plexId: 'pl1', title: 'Action Movies', playlistType: 'video',
  leafCount: 2, subscribed: false, posterPlexIds: [],
  items: [
    { id: 10, plexId: 'm1', mediaType: 'MOVIE', ordinal: 0,
      title: 'The Dark Knight', year: 2008, queueStatus: 'DONE', tdarrStatus: 'TRANSCODED' },
    { id: 11, plexId: 'm2', mediaType: 'MOVIE', ordinal: 1,
      title: 'Inception', year: 2010, queueStatus: null, tdarrStatus: null }
  ]
}

describe('PlaylistDetailView', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders title and items', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(w.text()).toContain('Action Movies')
    expect(w.text()).toContain('The Dark Knight')
    expect(w.text()).toContain('Inception')
  })

  it('shows transcoded badge for first item', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(w.find('.status-done').exists()).toBe(true)
  })

  it('shows "not queued" for item with null queueStatus', async () => {
    getPlaylist.mockResolvedValue(fakePlaylist)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    expect(w.text()).toContain('not queued')
  })

  it('subscribe button calls subscribe API and updates state', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: false })
    subscribe.mockResolvedValue(undefined)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await flushPromises()
    expect(subscribe).toHaveBeenCalledWith(1)
    expect(w.find('[data-testid="subscribe-btn"]').text()).toContain('Unsubscribe')
  })

  it('unsubscribe button calls unsubscribe API', async () => {
    getPlaylist.mockResolvedValue({ ...fakePlaylist, subscribed: true })
    unsubscribe.mockResolvedValue(undefined)
    const w = mount(PlaylistDetailView, { global: { plugins: [createTestingPinia()] } })
    await flushPromises()
    await w.find('[data-testid="subscribe-btn"]').trigger('click')
    await flushPromises()
    expect(unsubscribe).toHaveBeenCalledWith(1)
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
npm run test -- --reporter=verbose 2>&1 | grep -A10 "PlaylistDetailView"
```

Expected: FAIL — `PlaylistDetailView` not found.

- [ ] **Step 3: Create PlaylistDetailView.vue**

```vue
<!-- frontend/src/views/PlaylistDetailView.vue -->
<template>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="playlist">
    <button class="back" @click="router.back()">← Back</button>

    <div class="detail-header">
      <div>
        <h1>{{ playlist.title }}</h1>
        <p class="meta">{{ playlist.items.length }} items · {{ playlist.playlistType }}</p>
      </div>
      <button
        class="btn-subscribe"
        :class="{ subscribed: subscribed }"
        data-testid="subscribe-btn"
        :disabled="subscribing"
        @click="toggleSubscription"
      >
        {{ subscribed ? '✓ Unsubscribe' : 'Subscribe' }}
      </button>
    </div>

    <div class="item-list">
      <div v-for="item in playlist.items" :key="item.id" class="item-row">
        <img
          class="item-thumb"
          :src="`/api/posters/${item.plexId}.jpg`"
          :alt="item.title || item.plexId"
          loading="lazy"
        />
        <div class="item-info">
          <div class="item-title">{{ item.title || item.plexId }}</div>
          <div class="item-sub">
            {{ item.mediaType.toLowerCase() }}
            <template v-if="item.year">· {{ item.year }}</template>
          </div>
        </div>
        <span class="status-badge" :class="statusClass(item)">{{ statusLabel(item) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getPlaylist, subscribe, unsubscribe } from '@/api/playlists.js'

const route  = useRoute()
const router = useRouter()

const playlist   = ref(null)
const loading    = ref(true)
const subscribed = ref(false)
const subscribing = ref(false)

onMounted(async () => {
  try {
    const data = await getPlaylist(Number(route.params.id))
    playlist.value   = data
    subscribed.value = data.subscribed
  } finally {
    loading.value = false
  }
})

async function toggleSubscription() {
  subscribing.value = true
  try {
    if (subscribed.value) {
      await unsubscribe(Number(route.params.id))
      subscribed.value = false
    } else {
      await subscribe(Number(route.params.id))
      subscribed.value = true
    }
  } finally {
    subscribing.value = false
  }
}

function statusClass(item) {
  if (!item.queueStatus) return 'status-none'
  if (item.tdarrStatus === 'TRANSCODED') return 'status-done'
  if (item.queueStatus === 'IN_PROGRESS' || item.tdarrStatus === 'PROCESSING') return 'status-processing'
  if (item.queueStatus === 'ERROR' || item.tdarrStatus === 'TDARR_ERROR') return 'status-error'
  return 'status-queued'
}

function statusLabel(item) {
  if (!item.queueStatus) return 'not queued'
  if (item.tdarrStatus === 'TRANSCODED') return 'transcoded'
  if (item.queueStatus === 'IN_PROGRESS' || item.tdarrStatus === 'PROCESSING') return 'processing'
  if (item.queueStatus === 'ERROR') return 'error'
  if (item.tdarrStatus === 'TDARR_ERROR') return 'tdarr error'
  return 'queued'
}
</script>

<style scoped>
.back { background: transparent; border: 1px solid var(--border); color: var(--text-muted);
        border-radius: 6px; padding: 6px 14px; margin-bottom: 24px; cursor: pointer; }

.detail-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  margin-bottom: 24px; gap: 16px;
}
h1 { font-size: 1.6rem; font-weight: 700; margin-bottom: 4px; }
.meta { color: var(--text-muted); font-size: .9rem; }

.btn-subscribe {
  padding: 8px 20px; border-radius: 6px; font-size: .9rem; font-weight: 600;
  border: 1px solid var(--accent); color: var(--accent); background: transparent;
  cursor: pointer; white-space: nowrap; flex-shrink: 0;
  transition: background .15s, color .15s;
}
.btn-subscribe.subscribed { background: var(--accent); color: #fff; }
.btn-subscribe:disabled { opacity: .5; cursor: default; }

.item-list { display: flex; flex-direction: column; gap: 4px; }

.item-row {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 12px; background: var(--surface2); border-radius: 6px;
}
.item-thumb {
  width: 32px; height: 48px; object-fit: cover;
  border-radius: 3px; flex-shrink: 0; background: var(--surface);
}
.item-info { flex: 1; min-width: 0; }
.item-title { font-size: .85rem; font-weight: 500; white-space: nowrap;
              overflow: hidden; text-overflow: ellipsis; }
.item-sub { font-size: .75rem; color: var(--text-muted); margin-top: 2px; }

.status-badge {
  border-radius: 4px; padding: 3px 8px; font-size: .7rem;
  white-space: nowrap; flex-shrink: 0; font-weight: 600;
}
.status-done       { background: var(--green, #22c55e); color: #fff; }
.status-processing { background: #f59e0b; color: #fff; }
.status-error      { background: var(--red, #ef4444); color: #fff; }
.status-queued     { background: var(--surface); color: var(--text-muted); border: 1px solid var(--border); }
.status-none       { background: var(--surface); color: var(--text-muted); border: 1px solid var(--border); }

.loading { color: var(--text-muted); padding: 40px; text-align: center; }
</style>
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
npm run test -- --reporter=verbose 2>&1 | grep -A10 "PlaylistDetailView"
```

Expected: all 5 tests PASS.

- [ ] **Step 5: Run full frontend test suite**

```bash
npm run test 2>&1 | tail -20
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/PlaylistDetailView.vue \
        frontend/src/views/__tests__/PlaylistDetailView.test.js
git commit -m "feat: add PlaylistDetailView with subscribe/unsubscribe and Tdarr status badges"
```

---

## End-to-End Verification

1. `cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew test --no-daemon` — all green
2. `cd frontend && npm run test` — all green
3. `docker compose up --build` — services healthy
4. Browse to `http://localhost:3615` → login → click **Playlists** in nav
5. Playlists grid loads with 2×2 composite poster cards; gold dot on subscribed playlists
6. Click playlist → detail view shows items with status badges
7. Click **Subscribe** → button flips to "✓ Unsubscribe"; next library sync auto-queues all items
8. In Settings → Sync Now → after sync completes, re-visit playlist detail → status badges show `queued` / `processing`
9. Remove item from playlist in Plex → trigger sync → item disappears from detail view; queue item cancelled, file deleted
