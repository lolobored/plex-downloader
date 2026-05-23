# Library Sync & Browse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sync the full Plex library (movies + TV shows) into PostgreSQL, serve a browse API from the DB, and copy selected files to the Tdarr conversion folder.

**Architecture:** A scheduled `LibrarySyncService` calls the local Plex Media Server REST API, downloads poster images to disk, and upserts entities into PostgreSQL. Browse endpoints read from the DB only — no Plex at request time. A `DownloadService` translates stored Plex paths to app-accessible paths and copies files asynchronously.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Data JPA, Liquibase (SQL changesets), RestClient, JUnit 5 + Mockito + AssertJ, H2 (tests), PostgreSQL (prod)

---

## File Map

### New files
```
backend/src/main/resources/db/changelog/sql/
  002-library-sync-schema.sql

backend/src/main/java/com/plexdownloader/
  client/
    PlexMediaServerClient.java
    dto/
      PlexLibrary.java
      PlexItem.java
      PlexLibraryPage.java
      PlexRole.java
  config/
    AsyncConfig.java
  service/
    SettingsService.java
    PathMappingService.java
    PosterStorageService.java
    LibrarySyncService.java
    LibrarySyncScheduler.java
    DownloadService.java
  controller/
    AdminController.java
    LibraryController.java
    PosterController.java
    DownloadController.java
  dto/
    SyncStatusResponse.java
    MovieResponse.java
    TvShowResponse.java
    SeasonResponse.java
    EpisodeResponse.java
    DownloadRequest.java
    DownloadResponse.java

backend/src/test/java/com/plexdownloader/
  service/
    SettingsServiceTest.java
    PathMappingServiceTest.java
    PosterStorageServiceTest.java
    LibrarySyncServiceTest.java
    DownloadServiceTest.java
  controller/
    AdminControllerTest.java
    LibraryControllerTest.java
```

### Modified files
```
backend/src/main/resources/db/changelog/sql/001-initial-schema.sql  (DO NOT TOUCH — add changeset in 002)
backend/src/main/java/com/plexdownloader/model/User.java             add plexToken
backend/src/main/java/com/plexdownloader/model/Movie.java            add tmdbId, imdbId, rating, studio, directors, actors
backend/src/main/java/com/plexdownloader/model/TvShow.java           add tmdbId, tvdbId, rating, totalSeasons, actors
backend/src/main/java/com/plexdownloader/model/DownloadQueueItem.java add sourceFilePath, destFilePath
backend/src/main/java/com/plexdownloader/service/AuthService.java    store plexToken on login
```

---

## Task 1: DB Migration

**Files:**
- Create: `backend/src/main/resources/db/changelog/sql/002-library-sync-schema.sql`

- [ ] **Step 1: Write the migration file**

```sql
-- liquibase formatted sql

-- changeset plexdownloader:002-users-plex-token
ALTER TABLE users ADD COLUMN plex_token TEXT;

-- changeset plexdownloader:002-movies-extra-fields
ALTER TABLE movies ADD COLUMN tmdb_id BIGINT;
ALTER TABLE movies ADD COLUMN imdb_id VARCHAR(50);
ALTER TABLE movies ADD COLUMN rating FLOAT;
ALTER TABLE movies ADD COLUMN studio VARCHAR(255);

-- changeset plexdownloader:002-movie-directors
CREATE TABLE movie_directors (
    movie_id BIGINT NOT NULL REFERENCES movies(id) ON DELETE CASCADE,
    director VARCHAR(255) NOT NULL
);

-- changeset plexdownloader:002-tvshows-extra-fields
ALTER TABLE tv_shows ADD COLUMN tmdb_id BIGINT;
ALTER TABLE tv_shows ADD COLUMN tvdb_id BIGINT;
ALTER TABLE tv_shows ADD COLUMN rating FLOAT;
ALTER TABLE tv_shows ADD COLUMN total_seasons INT;

-- changeset plexdownloader:002-download-queue-paths
ALTER TABLE download_queue ADD COLUMN source_file_path TEXT;
ALTER TABLE download_queue ADD COLUMN dest_file_path TEXT;
```

- [ ] **Step 2: Verify migration runs**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.PlexDownloaderApplicationTests" -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL (Liquibase applies the migration against H2 on startup)

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/sql/002-library-sync-schema.sql
git commit -m "feat: add migration for library sync columns"
```

---

## Task 2: Model Updates + AuthService plexToken

**Files:**
- Modify: `backend/src/main/java/com/plexdownloader/model/User.java`
- Modify: `backend/src/main/java/com/plexdownloader/model/Movie.java`
- Modify: `backend/src/main/java/com/plexdownloader/model/TvShow.java`
- Modify: `backend/src/main/java/com/plexdownloader/model/DownloadQueueItem.java`
- Modify: `backend/src/main/java/com/plexdownloader/service/AuthService.java`

- [ ] **Step 1: Update User model**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity @Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_account_id", unique = true, nullable = false)
    private String plexAccountId;
    @Column(nullable = false)
    private String username;
    @Column(name = "avatar_url")
    private String avatarUrl;
    @Column(name = "plex_token")
    private String plexToken;
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;
    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    public enum Role { ADMIN, USER }
}
```

- [ ] **Step 2: Update Movie model**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data @Entity @Table(name = "movies")
public class Movie {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @Column(nullable = false)
    private String title;
    private Integer year;
    @Column(columnDefinition = "TEXT")
    private String summary;
    @Column(name = "poster_url", columnDefinition = "TEXT")
    private String posterUrl;
    @Column(name = "file_path", columnDefinition = "TEXT")
    private String filePath;
    @Column(name = "duration_ms")
    private Long durationMs;
    @Column(name = "synced_at")
    private Instant syncedAt;
    @Column(name = "tmdb_id")
    private Long tmdbId;
    @Column(name = "imdb_id")
    private String imdbId;
    private Float rating;
    private String studio;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "movie_genres", joinColumns = @JoinColumn(name = "movie_id"))
    @Column(name = "genre")
    private List<String> genres = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "movie_directors", joinColumns = @JoinColumn(name = "movie_id"))
    @Column(name = "director")
    private List<String> directors = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "movie_actors",
        joinColumns = @JoinColumn(name = "movie_id"),
        inverseJoinColumns = @JoinColumn(name = "actor_id"))
    private List<Actor> actors = new ArrayList<>();
}
```

- [ ] **Step 3: Update TvShow model**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data @Entity @Table(name = "tv_shows")
public class TvShow {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @Column(nullable = false)
    private String title;
    private Integer year;
    @Column(columnDefinition = "TEXT")
    private String summary;
    @Column(name = "poster_url", columnDefinition = "TEXT")
    private String posterUrl;
    @Column(name = "synced_at")
    private Instant syncedAt;
    @Column(name = "tmdb_id")
    private Long tmdbId;
    @Column(name = "tvdb_id")
    private Long tvdbId;
    private Float rating;
    @Column(name = "total_seasons")
    private Integer totalSeasons;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "show_genres", joinColumns = @JoinColumn(name = "show_id"))
    @Column(name = "genre")
    private List<String> genres = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "show_actors",
        joinColumns = @JoinColumn(name = "show_id"),
        inverseJoinColumns = @JoinColumn(name = "actor_id"))
    private List<Actor> actors = new ArrayList<>();
}
```

- [ ] **Step 4: Update DownloadQueueItem model**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import java.time.Instant;

@Data @ToString(exclude = "user") @Entity @Table(name = "download_queue")
public class DownloadQueueItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private MediaType mediaType;
    @Column(name = "media_id", nullable = false)
    private Long mediaId;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    @Column(name = "queue_position")
    private Integer queuePosition;
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    @Column(name = "source_file_path", columnDefinition = "TEXT")
    private String sourceFilePath;
    @Column(name = "dest_file_path", columnDefinition = "TEXT")
    private String destFilePath;
    @Column(name = "requested_at", updatable = false)
    private Instant requestedAt = Instant.now();
    @Column(name = "completed_at")
    private Instant completedAt;

    public enum MediaType { MOVIE, EPISODE }
    public enum Status { PENDING, IN_PROGRESS, DONE, ERROR }
}
```

- [ ] **Step 5: Update AuthService to store plexToken**

In `upsertUser`, update both the `map` branch (existing user) and `orElseGet` branch (new user):

```java
private User upsertUser(PlexUserInfo info, String authToken) {
    return userRepository.findByPlexAccountId(info.id())
        .map(existing -> {
            existing.setUsername(info.username());
            existing.setAvatarUrl(info.thumb());
            existing.setPlexToken(authToken);
            existing.setLastLoginAt(Instant.now());
            return userRepository.save(existing);
        })
        .orElseGet(() -> {
            User u = new User();
            u.setPlexAccountId(info.id());
            u.setUsername(info.username());
            u.setAvatarUrl(info.thumb());
            u.setPlexToken(authToken);
            u.setLastLoginAt(Instant.now());
            u.setRole(userRepository.count() == 0 ? User.Role.ADMIN : User.Role.USER);
            return userRepository.save(u);
        });
}
```

Update `checkPin` to pass `authToken` to `upsertUser`:

```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public Optional<JwtResponse> checkPin(Long pinId) {
    String authToken = plexPinClient.pollPin(pinId);
    if (authToken == null || authToken.isBlank()) {
        return Optional.empty();
    }
    PlexUserInfo info = plexPinClient.getUserInfo(authToken);
    User user = upsertUser(info, authToken);
    return Optional.of(new JwtResponse(
        jwtService.generateToken(user),
        user.getUsername(),
        user.getRole().name()
    ));
}
```

- [ ] **Step 6: Verify tests still pass**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/model/ backend/src/main/java/com/plexdownloader/service/AuthService.java
git commit -m "feat: add plexToken + extra metadata fields to models"
```

---

## Task 3: SettingsService

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/service/SettingsService.java`
- Create: `backend/src/test/java/com/plexdownloader/service/SettingsServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.plexdownloader.service;

import com.plexdownloader.model.Setting;
import com.plexdownloader.repository.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock SettingRepository repo;
    @InjectMocks SettingsService service;

    @Test
    void getReturnsValueFromRepo() {
        Setting s = new Setting();
        s.setKey("plex.server.url");
        s.setValue("http://localhost:32400");
        when(repo.findById("plex.server.url")).thenReturn(Optional.of(s));

        assertThat(service.get("plex.server.url")).contains("http://localhost:32400");
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        when(repo.findById("missing")).thenReturn(Optional.empty());
        assertThat(service.get("missing")).isEmpty();
    }

    @Test
    void setSavesToRepo() {
        service.set("plex.server.url", "http://plex:32400");
        Setting expected = new Setting();
        expected.setKey("plex.server.url");
        expected.setValue("http://plex:32400");
        verify(repo).save(expected);
    }

    @Test
    void getRequiredThrowsWhenMissing() {
        when(repo.findById("plex.server.url")).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> service.getRequired("plex.server.url")
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.SettingsServiceTest" -i 2>&1 | tail -20
```
Expected: FAIL — `SettingsService` not found

- [ ] **Step 3: Implement SettingsService**

```java
package com.plexdownloader.service;

import com.plexdownloader.model.Setting;
import com.plexdownloader.repository.SettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingRepository repo;

    public Optional<String> get(String key) {
        return repo.findById(key).map(Setting::getValue);
    }

    public String getRequired(String key) {
        return get(key).orElseThrow(() ->
            new IllegalStateException("Required setting missing: " + key));
    }

    public void set(String key, String value) {
        Setting s = new Setting();
        s.setKey(key);
        s.setValue(value);
        repo.save(s);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.SettingsServiceTest" -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/service/SettingsService.java \
        backend/src/test/java/com/plexdownloader/service/SettingsServiceTest.java
git commit -m "feat: add SettingsService (typed key-value wrapper)"
```

---

## Task 4: PathMappingService

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/service/PathMappingService.java`
- Create: `backend/src/test/java/com/plexdownloader/service/PathMappingServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.plexdownloader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PathMappingServiceTest {

    @Mock SettingsService settings;
    @InjectMocks PathMappingService service;

    @Test
    void translatesPlexPrefixToAppPrefix() {
        when(settings.getRequired("plex.path.prefix.plex")).thenReturn("/data/media");
        when(settings.getRequired("plex.path.prefix.app")).thenReturn("/mnt");

        String result = service.translate("/data/media/Movies/Inception.mkv");

        assertThat(result).isEqualTo("/mnt/Movies/Inception.mkv");
    }

    @Test
    void throwsWhenPathDoesNotMatchPrefix() {
        when(settings.getRequired("plex.path.prefix.plex")).thenReturn("/data/media");
        when(settings.getRequired("plex.path.prefix.app")).thenReturn("/mnt");

        assertThatThrownBy(() -> service.translate("/other/path/file.mkv"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/data/media");
    }

    @Test
    void handlesTrailingSlashOnPrefix() {
        when(settings.getRequired("plex.path.prefix.plex")).thenReturn("/data/media/");
        when(settings.getRequired("plex.path.prefix.app")).thenReturn("/mnt");

        String result = service.translate("/data/media/Movies/film.mkv");

        assertThat(result).isEqualTo("/mnt/Movies/film.mkv");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.PathMappingServiceTest" -i 2>&1 | tail -20
```
Expected: FAIL — `PathMappingService` not found

- [ ] **Step 3: Implement PathMappingService**

```java
package com.plexdownloader.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PathMappingService {

    private final SettingsService settings;

    public String translate(String plexPath) {
        String plexPrefix = settings.getRequired("plex.path.prefix.plex").stripTrailing();
        String appPrefix  = settings.getRequired("plex.path.prefix.app").stripTrailing();

        if (!plexPath.startsWith(plexPrefix)) {
            throw new IllegalArgumentException(
                "Path '" + plexPath + "' does not start with configured Plex prefix '" + plexPrefix + "'");
        }
        return appPrefix + plexPath.substring(plexPrefix.length());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.PathMappingServiceTest" -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/service/PathMappingService.java \
        backend/src/test/java/com/plexdownloader/service/PathMappingServiceTest.java
git commit -m "feat: add PathMappingService (Plex → app path translation)"
```

---

## Task 5: PlexMediaServerClient

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/client/dto/PlexLibrary.java`
- Create: `backend/src/main/java/com/plexdownloader/client/dto/PlexItem.java`
- Create: `backend/src/main/java/com/plexdownloader/client/dto/PlexLibraryPage.java`
- Create: `backend/src/main/java/com/plexdownloader/client/dto/PlexRole.java`
- Create: `backend/src/main/java/com/plexdownloader/client/PlexMediaServerClient.java`

No test for the HTTP client itself — it's a thin wrapper; tested indirectly via `LibrarySyncServiceTest`.

- [ ] **Step 1: Create DTOs**

`PlexLibrary.java`:
```java
package com.plexdownloader.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class PlexLibrary {
    private String key;
    private String title;
    private String type;  // "movie" or "show"
    private String agent;
}
```

`PlexRole.java`:
```java
package com.plexdownloader.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class PlexRole {
    @JsonProperty("tag")
    private String name;
    private String tagKey;  // Plex's stable actor ID
    private String role;    // character name
}
```

`PlexItem.java` (used for list items and children):
```java
package com.plexdownloader.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data @JsonIgnoreProperties(ignoreUnknown = true)
public class PlexItem {
    private String ratingKey;
    private String type;   // movie | show | season | episode
    private String title;
    private Integer year;
    private Integer index; // season or episode number
    @JsonProperty("originallyAvailableAt")
    private String airDate;
    private String summary;
    private Float rating;
    private String studio;
    private String thumb;
    private Long duration;
    private Long updatedAt;
    private Integer leafCount; // episode count (on season items)

    @JsonProperty("Guid")
    private List<PlexGuidRaw> guid;

    @JsonProperty("Media")
    private List<PlexMediaRaw> media;

    @JsonProperty("Genre")
    private List<PlexTag> genre;

    @JsonProperty("Director")
    private List<PlexTag> director;

    @JsonProperty("Writer")
    private List<PlexTag> writer;

    @JsonProperty("Role")
    private List<PlexRole> role;

    public String firstFilePath() {
        if (media == null || media.isEmpty()) return null;
        var parts = media.get(0).getPart();
        if (parts == null || parts.isEmpty()) return null;
        return parts.get(0).getFile();
    }

    public String firstVideoResolution() {
        if (media == null || media.isEmpty()) return null;
        var parts = media.get(0).getPart();
        if (parts == null || parts.isEmpty()) return null;
        return parts.get(0).getVideoResolution();
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexGuidRaw {
        private String id; // e.g. "tmdb://27205"
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexMediaRaw {
        @JsonProperty("Part")
        private List<PlexPartRaw> part;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexPartRaw {
        private String file;
        private String videoResolution;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexTag {
        private String tag;
    }

    public Long parseTmdbId() {
        return parseGuid("tmdb://");
    }

    public Long parseTvdbId() {
        return parseGuid("tvdb://");
    }

    public String parseImdbId() {
        if (guid == null) return null;
        return guid.stream()
            .map(PlexGuidRaw::getId)
            .filter(id -> id != null && id.startsWith("imdb://"))
            .map(id -> id.substring("imdb://".length()))
            .findFirst().orElse(null);
    }

    private Long parseGuid(String prefix) {
        if (guid == null) return null;
        return guid.stream()
            .map(PlexGuidRaw::getId)
            .filter(id -> id != null && id.startsWith(prefix))
            .map(id -> {
                try { return Long.parseLong(id.substring(prefix.length())); }
                catch (NumberFormatException e) { return null; }
            })
            .filter(id -> id != null)
            .findFirst().orElse(null);
    }
}
```

`PlexLibraryPage.java`:
```java
package com.plexdownloader.client.dto;

import java.util.List;

public record PlexLibraryPage(int totalSize, List<PlexItem> items) {}
```

- [ ] **Step 2: Implement PlexMediaServerClient**

```java
package com.plexdownloader.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.plexdownloader.client.dto.PlexItem;
import com.plexdownloader.client.dto.PlexLibrary;
import com.plexdownloader.client.dto.PlexLibraryPage;
import com.plexdownloader.repository.UserRepository;
import com.plexdownloader.service.SettingsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PlexMediaServerClient {

    private static final int PAGE_SIZE = 50;

    private final SettingsService settings;
    private final UserRepository userRepository;

    private RestClient buildClient() {
        String baseUrl = settings.getRequired("plex.server.url");
        String token = userRepository.findById(1L)
            .map(u -> u.getPlexToken())
            .orElseThrow(() -> new IllegalStateException("No admin user found for Plex token"));

        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Plex-Token", token)
            .defaultHeader("X-Plex-Client-Identifier", "plex-downloader-app")
            .defaultHeader("X-Plex-Product", "PlexDownloader")
            .defaultHeader("Accept", "application/json")
            .build();
    }

    public List<PlexLibrary> getLibraries() {
        PlexLibrariesResponse resp = buildClient().get()
            .uri("/library/sections")
            .retrieve()
            .body(PlexLibrariesResponse.class);
        if (resp == null || resp.getMediaContainer() == null) return List.of();
        List<PlexLibrary> dirs = resp.getMediaContainer().getDirectory();
        return dirs != null ? dirs : List.of();
    }

    public PlexLibraryPage getLibraryContents(String libraryKey, int offset) {
        PlexLibraryContentsResponse resp = buildClient().get()
            .uri(u -> u.path("/library/sections/{key}/all")
                .queryParam("includeGuids", "1")
                .build(libraryKey))
            .header("X-Plex-Container-Start", String.valueOf(offset))
            .header("X-Plex-Container-Size", String.valueOf(PAGE_SIZE))
            .retrieve()
            .body(PlexLibraryContentsResponse.class);
        if (resp == null || resp.getMediaContainer() == null) return new PlexLibraryPage(0, List.of());
        var mc = resp.getMediaContainer();
        List<PlexItem> items = mc.getMetadata() != null ? mc.getMetadata() : List.of();
        return new PlexLibraryPage(mc.getTotalSize(), items);
    }

    public PlexItem getItemDetail(String ratingKey) {
        PlexLibraryContentsResponse resp = buildClient().get()
            .uri("/library/metadata/{key}", ratingKey)
            .retrieve()
            .body(PlexLibraryContentsResponse.class);
        if (resp == null || resp.getMediaContainer() == null
                || resp.getMediaContainer().getMetadata() == null
                || resp.getMediaContainer().getMetadata().isEmpty()) {
            throw new IllegalStateException("No metadata returned for ratingKey: " + ratingKey);
        }
        return resp.getMediaContainer().getMetadata().get(0);
    }

    public List<PlexItem> getChildren(String ratingKey) {
        PlexLibraryContentsResponse resp = buildClient().get()
            .uri("/library/metadata/{key}/children", ratingKey)
            .retrieve()
            .body(PlexLibraryContentsResponse.class);
        if (resp == null || resp.getMediaContainer() == null) return List.of();
        List<PlexItem> items = resp.getMediaContainer().getMetadata();
        return items != null ? items : List.of();
    }

    public void downloadThumb(String thumbPath, Path destination) {
        byte[] bytes = buildClient().get()
            .uri(thumbPath)
            .accept(MediaType.IMAGE_JPEG)
            .retrieve()
            .body(byte[].class);
        if (bytes != null && bytes.length > 0) {
            try {
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write poster to " + destination, e);
            }
        }
    }

    // --- Response wrappers ---

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class PlexLibrariesResponse {
        @JsonProperty("MediaContainer")
        private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Container {
            @JsonProperty("Directory")
            private List<PlexLibrary> directory;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class PlexLibraryContentsResponse {
        @JsonProperty("MediaContainer")
        private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Container {
            private int totalSize;
            @JsonProperty("Metadata")
            private List<PlexItem> metadata;
        }
    }
}
```

- [ ] **Step 3: Verify project compiles**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew compileJava -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/client/
git commit -m "feat: add PlexMediaServerClient and Plex response DTOs"
```

---

## Task 6: PosterStorageService

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/service/PosterStorageService.java`
- Create: `backend/src/test/java/com/plexdownloader/service/PosterStorageServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.plexdownloader.service;

import com.plexdownloader.client.PlexMediaServerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PosterStorageServiceTest {

    @Mock PlexMediaServerClient plexClient;
    @Mock SettingsService settings;
    @InjectMocks PosterStorageService service;

    @TempDir Path tempDir;

    @Test
    void downloadsPosterWhenNotYetOnDisk() {
        when(settings.getRequired("plex.poster.dir")).thenReturn(tempDir.toString());

        service.downloadIfNeeded("12345", "/library/metadata/12345/thumb", 1000L);

        verify(plexClient).downloadThumb(eq("/library/metadata/12345/thumb"), any(Path.class));
    }

    @Test
    void skipsDownloadWhenFileExistsAndSyncedAtIsNewer() throws IOException {
        when(settings.getRequired("plex.poster.dir")).thenReturn(tempDir.toString());
        Path poster = tempDir.resolve("12345.jpg");
        Files.writeString(poster, "fake-image");

        // plexUpdatedAt = 1000, our syncedAt = 2000 (newer) → skip
        service.downloadIfNeeded("12345", "/library/metadata/12345/thumb", 1000L);

        // syncedAt is derived from file last-modified; we set it by writing the file.
        // If file exists and its modified time > plexUpdatedAt, skip download.
        verify(plexClient, never()).downloadThumb(any(), any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.PosterStorageServiceTest" -i 2>&1 | tail -20
```
Expected: FAIL — `PosterStorageService` not found

- [ ] **Step 3: Implement PosterStorageService**

```java
package com.plexdownloader.service;

import com.plexdownloader.client.PlexMediaServerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

@Service
@RequiredArgsConstructor
public class PosterStorageService {

    private final PlexMediaServerClient plexClient;
    private final SettingsService settings;

    /**
     * Downloads the poster for ratingKey if the local file doesn't exist
     * or Plex's updatedAt is newer than the file's last-modified time.
     */
    public void downloadIfNeeded(String ratingKey, String thumbPath, Long plexUpdatedAtSeconds) {
        Path posterDir = Path.of(settings.getRequired("plex.poster.dir"));
        Path dest = posterDir.resolve(ratingKey + ".jpg");

        if (Files.exists(dest) && plexUpdatedAtSeconds != null) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(dest, BasicFileAttributes.class);
                long fileModifiedSeconds = attrs.lastModifiedTime().toInstant().getEpochSecond();
                if (fileModifiedSeconds >= plexUpdatedAtSeconds) {
                    return; // file is up to date
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        plexClient.downloadThumb(thumbPath, dest);
    }

    public String posterUrl(String ratingKey) {
        return "/api/posters/" + ratingKey + ".jpg";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.PosterStorageServiceTest" -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/service/PosterStorageService.java \
        backend/src/test/java/com/plexdownloader/service/PosterStorageServiceTest.java
git commit -m "feat: add PosterStorageService (download + skip logic)"
```

---

## Task 7: LibrarySyncService

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/service/LibrarySyncService.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/SyncStatusResponse.java`
- Create: `backend/src/test/java/com/plexdownloader/service/LibrarySyncServiceTest.java`

- [ ] **Step 1: Create SyncStatusResponse DTO**

```java
package com.plexdownloader.dto;

import java.time.Instant;

public record SyncStatusResponse(String state, Instant lastSyncAt, int itemsSynced, String error) {}
```

- [ ] **Step 2: Write the failing test**

```java
package com.plexdownloader.service;

import com.plexdownloader.client.PlexMediaServerClient;
import com.plexdownloader.client.dto.PlexItem;
import com.plexdownloader.client.dto.PlexLibrary;
import com.plexdownloader.client.dto.PlexLibraryPage;
import com.plexdownloader.model.Actor;
import com.plexdownloader.model.Movie;
import com.plexdownloader.model.Season;
import com.plexdownloader.model.TvShow;
import com.plexdownloader.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibrarySyncServiceTest {

    @Mock PlexMediaServerClient plexClient;
    @Mock PosterStorageService posterStorage;
    @Mock MovieRepository movieRepo;
    @Mock TvShowRepository showRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock ActorRepository actorRepo;
    @Mock SettingsService settings;
    @InjectMocks LibrarySyncService service;

    @Test
    void syncAllUpsertsSingleMovie() {
        // Arrange
        PlexLibrary movieLib = new PlexLibrary();
        movieLib.setKey("1");
        movieLib.setType("movie");
        movieLib.setAgent("tv.plex.agents.movie");

        PlexItem item = new PlexItem();
        item.setRatingKey("12345");
        item.setType("movie");
        item.setTitle("Inception");
        item.setYear(2010);
        item.setThumb("/library/metadata/12345/thumb");
        item.setUpdatedAt(1000L);
        item.setDuration(8880000L);

        PlexItem detail = new PlexItem();
        detail.setRatingKey("12345");
        detail.setType("movie");
        detail.setTitle("Inception");
        detail.setYear(2010);
        detail.setThumb("/library/metadata/12345/thumb");
        detail.setUpdatedAt(1000L);
        detail.setDuration(8880000L);
        detail.setRole(List.of());
        detail.setGenre(List.of());
        detail.setDirector(List.of());

        when(plexClient.getLibraries()).thenReturn(List.of(movieLib));
        when(plexClient.getLibraryContents("1", 0))
            .thenReturn(new PlexLibraryPage(1, List.of(item)));
        when(plexClient.getItemDetail("12345")).thenReturn(detail);
        when(movieRepo.findByPlexId("12345")).thenReturn(Optional.empty());
        when(movieRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(posterStorage.posterUrl("12345")).thenReturn("/api/posters/12345.jpg");

        // Act
        service.syncAll();

        // Assert
        ArgumentCaptor<Movie> captor = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Inception");
        assertThat(captor.getValue().getYear()).isEqualTo(2010);
    }

    @Test
    void syncAllIgnoresLibraryWithNoneAgent() {
        PlexLibrary badLib = new PlexLibrary();
        badLib.setKey("3");
        badLib.setType("movie");
        badLib.setAgent("com.plexapp.agents.none");

        when(plexClient.getLibraries()).thenReturn(List.of(badLib));

        service.syncAll();

        verify(plexClient, never()).getLibraryContents(any(), anyInt());
    }

    @Test
    void syncAllUpsertsTvShowWithSeasonAndEpisode() {
        PlexLibrary showLib = new PlexLibrary();
        showLib.setKey("2");
        showLib.setType("show");
        showLib.setAgent("tv.plex.agents.series");

        PlexItem showItem = new PlexItem();
        showItem.setRatingKey("100");
        showItem.setType("show");
        showItem.setTitle("Breaking Bad");
        showItem.setYear(2008);
        showItem.setThumb("/library/metadata/100/thumb");
        showItem.setUpdatedAt(1000L);

        PlexItem showDetail = new PlexItem();
        showDetail.setRatingKey("100");
        showDetail.setType("show");
        showDetail.setTitle("Breaking Bad");
        showDetail.setYear(2008);
        showDetail.setThumb("/library/metadata/100/thumb");
        showDetail.setUpdatedAt(1000L);
        showDetail.setRole(List.of());
        showDetail.setGenre(List.of());

        PlexItem seasonItem = new PlexItem();
        seasonItem.setRatingKey("200");
        seasonItem.setType("season");
        seasonItem.setTitle("Season 1");
        seasonItem.setIndex(1);
        seasonItem.setLeafCount(7);
        seasonItem.setThumb("/library/metadata/200/thumb");
        seasonItem.setUpdatedAt(1000L);

        PlexItem episodeItem = new PlexItem();
        episodeItem.setRatingKey("300");
        episodeItem.setType("episode");
        episodeItem.setTitle("Pilot");
        episodeItem.setIndex(1);
        episodeItem.setDuration(3600000L);
        episodeItem.setThumb("/library/metadata/300/thumb");
        episodeItem.setUpdatedAt(1000L);

        when(plexClient.getLibraries()).thenReturn(List.of(showLib));
        when(plexClient.getLibraryContents("2", 0))
            .thenReturn(new PlexLibraryPage(1, List.of(showItem)));
        when(plexClient.getItemDetail("100")).thenReturn(showDetail);
        when(plexClient.getChildren("100")).thenReturn(List.of(seasonItem));
        when(plexClient.getChildren("200")).thenReturn(List.of(episodeItem));
        when(showRepo.findByPlexId("100")).thenReturn(Optional.empty());
        when(showRepo.save(any())).thenAnswer(inv -> {
            TvShow s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        when(seasonRepo.findByPlexId("200")).thenReturn(Optional.empty());
        when(seasonRepo.save(any())).thenAnswer(inv -> {
            Season s = inv.getArgument(0);
            s.setId(10L);
            return s;
        });
        when(episodeRepo.findByPlexId("300")).thenReturn(Optional.empty());
        when(episodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(posterStorage.posterUrl(any())).thenReturn("/api/posters/x.jpg");

        service.syncAll();

        verify(showRepo).save(argThat(s -> "Breaking Bad".equals(s.getTitle())));
        verify(seasonRepo).save(argThat(s -> s.getSeasonNumber() == 1));
        verify(episodeRepo).save(argThat(e -> "Pilot".equals(e.getTitle())));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.LibrarySyncServiceTest" -i 2>&1 | tail -20
```
Expected: FAIL — `LibrarySyncService` not found

- [ ] **Step 4: Implement LibrarySyncService**

```java
package com.plexdownloader.service;

import com.plexdownloader.client.PlexMediaServerClient;
import com.plexdownloader.client.dto.PlexItem;
import com.plexdownloader.client.dto.PlexItem.PlexTag;
import com.plexdownloader.client.dto.PlexLibrary;
import com.plexdownloader.client.dto.PlexLibraryPage;
import com.plexdownloader.client.dto.PlexRole;
import com.plexdownloader.dto.SyncStatusResponse;
import com.plexdownloader.model.*;
import com.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibrarySyncService {

    private static final int PAGE_SIZE = 50;
    private static final String AGENT_NONE = "com.plexapp.agents.none";

    private final PlexMediaServerClient plexClient;
    private final PosterStorageService posterStorage;
    private final MovieRepository movieRepo;
    private final TvShowRepository showRepo;
    private final SeasonRepository seasonRepo;
    private final EpisodeRepository episodeRepo;
    private final ActorRepository actorRepo;
    private final SettingsService settings;

    public enum SyncState { IDLE, RUNNING, ERROR }

    private final AtomicReference<SyncState> state = new AtomicReference<>(SyncState.IDLE);
    private final AtomicInteger itemsSynced = new AtomicInteger(0);
    private volatile Instant lastSyncAt;
    private volatile String lastError;

    public SyncStatusResponse status() {
        return new SyncStatusResponse(state.get().name(), lastSyncAt, itemsSynced.get(), lastError);
    }

    public boolean isRunning() {
        return state.get() == SyncState.RUNNING;
    }

    public void syncAll() {
        if (!state.compareAndSet(SyncState.IDLE, SyncState.RUNNING)
                && !state.compareAndSet(SyncState.ERROR, SyncState.RUNNING)) {
            log.info("Sync already running, skipping.");
            return;
        }
        itemsSynced.set(0);
        lastError = null;

        try {
            List<PlexLibrary> libraries = plexClient.getLibraries().stream()
                .filter(l -> !AGENT_NONE.equals(l.getAgent()))
                .filter(l -> "movie".equals(l.getType()) || "show".equals(l.getType()))
                .toList();

            for (PlexLibrary lib : libraries) {
                if ("movie".equals(lib.getType())) {
                    syncMovieLibrary(lib.getKey());
                } else {
                    syncShowLibrary(lib.getKey());
                }
            }
            lastSyncAt = Instant.now();
            state.set(SyncState.IDLE);
        } catch (Exception e) {
            lastError = e.getMessage();
            state.set(SyncState.ERROR);
            log.error("Sync failed", e);
        }
    }

    private void syncMovieLibrary(String libraryKey) {
        int offset = 0;
        while (true) {
            PlexLibraryPage page = plexClient.getLibraryContents(libraryKey, offset);
            for (PlexItem item : page.items()) {
                try { upsertMovie(item); itemsSynced.incrementAndGet(); }
                catch (Exception e) { log.warn("Skipping movie {}: {}", item.getRatingKey(), e.getMessage()); }
            }
            offset += page.items().size();
            if (offset >= page.totalSize() || page.items().isEmpty()) break;
        }
    }

    private void upsertMovie(PlexItem listItem) {
        PlexItem detail = plexClient.getItemDetail(listItem.getRatingKey());
        posterStorage.downloadIfNeeded(detail.getRatingKey(), detail.getThumb(), detail.getUpdatedAt());

        Movie movie = movieRepo.findByPlexId(detail.getRatingKey()).orElseGet(Movie::new);
        movie.setPlexId(detail.getRatingKey());
        movie.setTitle(detail.getTitle());
        movie.setYear(detail.getYear());
        movie.setSummary(detail.getSummary());
        movie.setRating(detail.getRating());
        movie.setStudio(detail.getStudio());
        movie.setDurationMs(detail.getDuration());
        movie.setFilePath(detail.firstFilePath());
        movie.setTmdbId(detail.parseTmdbId());
        movie.setImdbId(detail.parseImdbId());
        movie.setPosterUrl(posterStorage.posterUrl(detail.getRatingKey()));
        movie.setGenres(tags(detail.getGenre()));
        movie.setDirectors(tags(detail.getDirector()));
        movie.setActors(upsertActors(detail.getRole()));
        movie.setSyncedAt(Instant.now());
        movieRepo.save(movie);
    }

    private void syncShowLibrary(String libraryKey) {
        int offset = 0;
        while (true) {
            PlexLibraryPage page = plexClient.getLibraryContents(libraryKey, offset);
            for (PlexItem item : page.items()) {
                try { upsertShow(item); itemsSynced.incrementAndGet(); }
                catch (Exception e) { log.warn("Skipping show {}: {}", item.getRatingKey(), e.getMessage()); }
            }
            offset += page.items().size();
            if (offset >= page.totalSize() || page.items().isEmpty()) break;
        }
    }

    private void upsertShow(PlexItem listItem) {
        PlexItem detail = plexClient.getItemDetail(listItem.getRatingKey());
        posterStorage.downloadIfNeeded(detail.getRatingKey(), detail.getThumb(), detail.getUpdatedAt());

        TvShow show = showRepo.findByPlexId(detail.getRatingKey()).orElseGet(TvShow::new);
        show.setPlexId(detail.getRatingKey());
        show.setTitle(detail.getTitle());
        show.setYear(detail.getYear());
        show.setSummary(detail.getSummary());
        show.setRating(detail.getRating());
        show.setTmdbId(detail.parseTmdbId());
        show.setTvdbId(detail.parseTvdbId());
        show.setPosterUrl(posterStorage.posterUrl(detail.getRatingKey()));
        show.setGenres(tags(detail.getGenre()));
        show.setActors(upsertActors(detail.getRole()));
        show.setSyncedAt(Instant.now());
        show = showRepo.save(show);

        List<PlexItem> seasons = plexClient.getChildren(detail.getRatingKey());
        show.setTotalSeasons(seasons.size());
        showRepo.save(show);

        for (PlexItem seasonItem : seasons) {
            upsertSeason(seasonItem, show);
        }
    }

    private void upsertSeason(PlexItem seasonItem, TvShow show) {
        posterStorage.downloadIfNeeded(seasonItem.getRatingKey(), seasonItem.getThumb(), seasonItem.getUpdatedAt());

        Season season = seasonRepo.findByPlexId(seasonItem.getRatingKey()).orElseGet(Season::new);
        season.setPlexId(seasonItem.getRatingKey());
        season.setShow(show);
        season.setSeasonNumber(seasonItem.getIndex() != null ? seasonItem.getIndex() : 0);
        season.setTitle(seasonItem.getTitle());
        season.setEpisodeCount(seasonItem.getLeafCount());
        season.setPosterUrl(posterStorage.posterUrl(seasonItem.getRatingKey()));
        season.setSyncedAt(Instant.now());
        season = seasonRepo.save(season);

        List<PlexItem> episodes = plexClient.getChildren(seasonItem.getRatingKey());
        for (PlexItem ep : episodes) {
            upsertEpisode(ep, season);
        }
    }

    private void upsertEpisode(PlexItem epItem, Season season) {
        Episode episode = episodeRepo.findByPlexId(epItem.getRatingKey()).orElseGet(Episode::new);
        episode.setPlexId(epItem.getRatingKey());
        episode.setSeason(season);
        episode.setEpisodeNumber(epItem.getIndex() != null ? epItem.getIndex() : 0);
        episode.setTitle(epItem.getTitle());
        episode.setSummary(epItem.getSummary());
        episode.setDurationMs(epItem.getDuration());
        episode.setFilePath(epItem.firstFilePath());
        episode.setVideoResolution(epItem.firstVideoResolution());
        episode.setThumbnailUrl(posterStorage.posterUrl(epItem.getRatingKey()));
        if (epItem.getAirDate() != null) {
            try { episode.setAirDate(LocalDate.parse(epItem.getAirDate())); }
            catch (Exception ignored) {}
        }
        episode.setDirector(firstTag(epItem.getDirector()));
        episode.setWriter(firstTag(epItem.getWriter()));
        episode.setSyncedAt(Instant.now());
        episodeRepo.save(episode);
    }

    private List<Actor> upsertActors(List<PlexRole> roles) {
        if (roles == null) return List.of();
        List<Actor> result = new ArrayList<>();
        for (PlexItem.PlexRole r : roles) {
            if (r.getTagKey() == null || r.getName() == null) continue;
            Actor actor = actorRepo.findByPlexId(r.getTagKey()).orElseGet(Actor::new);
            actor.setPlexId(r.getTagKey());
            actor.setName(r.getName());
            result.add(actorRepo.save(actor));
        }
        return result;
    }

    private List<String> tags(List<PlexItem.PlexTag> tags) {
        if (tags == null) return List.of();
        return tags.stream().map(PlexTag::getTag).filter(t -> t != null).toList();
    }

    private String firstTag(List<PlexItem.PlexTag> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return tags.get(0).getTag();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.LibrarySyncServiceTest" -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/service/LibrarySyncService.java \
        backend/src/main/java/com/plexdownloader/dto/SyncStatusResponse.java \
        backend/src/test/java/com/plexdownloader/service/LibrarySyncServiceTest.java
git commit -m "feat: add LibrarySyncService (full Plex library sync)"
```

---

## Task 8: Async Config + LibrarySyncScheduler

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/config/AsyncConfig.java`
- Create: `backend/src/main/java/com/plexdownloader/service/LibrarySyncScheduler.java`

No unit test for the scheduler — it delegates directly to `LibrarySyncService` which is already tested.

- [ ] **Step 1: Create AsyncConfig**

```java
package com.plexdownloader.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {}
```

- [ ] **Step 2: Create LibrarySyncScheduler**

```java
package com.plexdownloader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LibrarySyncScheduler implements SchedulingConfigurer {

    private static final String DEFAULT_CRON = "0 0 */6 * * *";

    private final LibrarySyncService syncService;
    private final SettingsService settings;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            this::runSync,
            ctx -> {
                String cron = settings.get("plex.sync.cron").orElse(DEFAULT_CRON);
                return new CronTrigger(cron).nextExecution(ctx);
            }
        );
    }

    @Async
    public void triggerManual() {
        log.info("Manual sync triggered");
        syncService.syncAll();
    }

    private void runSync() {
        log.info("Scheduled sync starting");
        syncService.syncAll();
    }
}
```

- [ ] **Step 3: Verify project compiles and all tests pass**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/config/AsyncConfig.java \
        backend/src/main/java/com/plexdownloader/service/LibrarySyncScheduler.java
git commit -m "feat: add async config and sync scheduler with dynamic cron"
```

---

## Task 9: AdminController

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/controller/AdminController.java`
- Create: `backend/src/test/java/com/plexdownloader/controller/AdminControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.plexdownloader.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plexdownloader.config.JwtAuthFilter;
import com.plexdownloader.config.SecurityConfig;
import com.plexdownloader.dto.SyncStatusResponse;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AdminControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean SettingsService settingsService;
    @MockBean LibrarySyncService syncService;
    @MockBean LibrarySyncScheduler syncScheduler;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;
    @MockBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupAdminAuth() throws Exception {
        User admin = new User();
        admin.setId(1L);
        admin.setPlexAccountId("admin-plex-id");
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            SecurityContextHolder.setContext(ctx);

            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void getSettingsReturnsMap() throws Exception {
        when(settingsService.get("plex.server.url")).thenReturn(java.util.Optional.of("http://plex:32400"));

        mockMvc.perform(get("/api/admin/settings"))
            .andExpect(status().isOk());
    }

    @Test
    void putSettingsSavesValue() throws Exception {
        mockMvc.perform(put("/api/admin/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("plex.server.url", "http://plex:32400"))))
            .andExpect(status().isNoContent());

        verify(settingsService).set("plex.server.url", "http://plex:32400");
    }

    @Test
    void getSyncStatusReturnsCurrentState() throws Exception {
        when(syncService.status()).thenReturn(new SyncStatusResponse("IDLE", null, 0, null));

        mockMvc.perform(get("/api/admin/sync/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("IDLE"));
    }

    @Test
    void postSyncTriggersWith202WhenIdle() throws Exception {
        when(syncService.isRunning()).thenReturn(false);

        mockMvc.perform(post("/api/admin/sync"))
            .andExpect(status().isAccepted());

        verify(syncScheduler).triggerManual();
    }

    @Test
    void postSyncReturnsWith409WhenAlreadyRunning() throws Exception {
        when(syncService.isRunning()).thenReturn(true);

        mockMvc.perform(post("/api/admin/sync"))
            .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.controller.AdminControllerTest" -i 2>&1 | tail -20
```
Expected: FAIL — `AdminController` not found

- [ ] **Step 3: Implement AdminController**

```java
package com.plexdownloader.controller;

import com.plexdownloader.dto.SyncStatusResponse;
import com.plexdownloader.service.LibrarySyncScheduler;
import com.plexdownloader.service.LibrarySyncService;
import com.plexdownloader.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final SettingsService settingsService;
    private final LibrarySyncService syncService;
    private final LibrarySyncScheduler syncScheduler;

    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        return Map.of(
            "plex.server.url",         settingsService.get("plex.server.url").orElse(""),
            "plex.path.prefix.plex",   settingsService.get("plex.path.prefix.plex").orElse(""),
            "plex.path.prefix.app",    settingsService.get("plex.path.prefix.app").orElse(""),
            "plex.poster.dir",         settingsService.get("plex.poster.dir").orElse(""),
            "plex.conversion.dir",     settingsService.get("plex.conversion.dir").orElse(""),
            "plex.sync.cron",          settingsService.get("plex.sync.cron").orElse("0 0 */6 * * *")
        );
    }

    @PutMapping("/settings")
    public ResponseEntity<Void> putSettings(@RequestBody Map<String, String> settings) {
        settings.forEach(settingsService::set);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sync/status")
    public SyncStatusResponse syncStatus() {
        return syncService.status();
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> triggerSync() {
        if (syncService.isRunning()) {
            return ResponseEntity.status(409).build();
        }
        syncScheduler.triggerManual();
        return ResponseEntity.accepted().build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.controller.AdminControllerTest" -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/controller/AdminController.java \
        backend/src/test/java/com/plexdownloader/controller/AdminControllerTest.java
git commit -m "feat: add AdminController (settings CRUD + sync trigger)"
```

---

## Task 10: DownloadService + DownloadController

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/dto/DownloadRequest.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/DownloadResponse.java`
- Create: `backend/src/main/java/com/plexdownloader/service/DownloadService.java`
- Create: `backend/src/main/java/com/plexdownloader/controller/DownloadController.java`
- Create: `backend/src/test/java/com/plexdownloader/service/DownloadServiceTest.java`

- [ ] **Step 1: Create DTOs**

`DownloadRequest.java`:
```java
package com.plexdownloader.dto;

public record DownloadRequest(String type, Long id) {
    // type: MOVIE | SHOW | SEASON | EPISODE
}
```

`DownloadResponse.java`:
```java
package com.plexdownloader.dto;

import java.util.List;

public record DownloadResponse(List<Long> jobIds, String status) {}
```

- [ ] **Step 2: Write the failing test**

```java
package com.plexdownloader.service;

import com.plexdownloader.model.*;
import com.plexdownloader.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {

    @Mock MovieRepository movieRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock TvShowRepository showRepo;
    @Mock DownloadQueueRepository queueRepo;
    @Mock PathMappingService pathMapping;
    @Mock SettingsService settings;
    @InjectMocks DownloadService service;

    @TempDir Path tempDir;

    @Test
    void enqueuesMovieAndReturnsJobId() throws IOException {
        Path sourceFile = tempDir.resolve("movie.mkv");
        Files.writeString(sourceFile, "fake");

        when(settings.getRequired("plex.conversion.dir")).thenReturn(tempDir.toString());
        when(pathMapping.translate("/plex/movies/movie.mkv")).thenReturn(sourceFile.toString());

        Movie movie = new Movie();
        movie.setId(1L);
        movie.setPlexId("12345");
        movie.setFilePath("/plex/movies/movie.mkv");

        User user = new User();
        user.setId(1L);

        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> {
            DownloadQueueItem item = inv.getArgument(0);
            item.setId(99L);
            return item;
        });

        List<Long> jobIds = service.enqueueMovie(1L, user);

        assertThat(jobIds).containsExactly(99L);
        verify(queueRepo).save(argThat(item ->
            item.getMediaType() == DownloadQueueItem.MediaType.MOVIE
            && item.getStatus() == DownloadQueueItem.Status.PENDING
            && "/plex/movies/movie.mkv".equals(item.getSourceFilePath())
        ));
    }

    @Test
    void throwsWhenMovieNotFound() {
        when(movieRepo.findById(99L)).thenReturn(Optional.empty());
        User user = new User();
        user.setId(1L);

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service.enqueueMovie(99L, user)
        );
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.DownloadServiceTest" -i 2>&1 | tail -20
```
Expected: FAIL — `DownloadService` not found

- [ ] **Step 4: Implement DownloadService**

```java
package com.plexdownloader.service;

import com.plexdownloader.model.*;
import com.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final SeasonRepository seasonRepo;
    private final TvShowRepository showRepo;
    private final DownloadQueueRepository queueRepo;
    private final PathMappingService pathMapping;
    private final SettingsService settings;

    public List<Long> enqueueMovie(Long movieId, User user) {
        Movie movie = movieRepo.findById(movieId)
            .orElseThrow(() -> new IllegalArgumentException("Movie not found: " + movieId));
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.MOVIE,
            movieId, movie.getFilePath());
        item = queueRepo.save(item);
        executeCopyAsync(item.getId());
        return List.of(item.getId());
    }

    public List<Long> enqueueEpisode(Long episodeId, User user) {
        Episode ep = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
            episodeId, ep.getFilePath());
        item = queueRepo.save(item);
        executeCopyAsync(item.getId());
        return List.of(item.getId());
    }

    public List<Long> enqueueSeason(Long seasonId, User user) {
        List<Episode> episodes = episodeRepo.findBySeasonIdOrderByEpisodeNumber(seasonId);
        if (episodes.isEmpty()) throw new IllegalArgumentException("Season not found or empty: " + seasonId);
        List<Long> ids = new ArrayList<>();
        for (Episode ep : episodes) {
            DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
                ep.getId(), ep.getFilePath());
            item = queueRepo.save(item);
            executeCopyAsync(item.getId());
            ids.add(item.getId());
        }
        return ids;
    }

    public List<Long> enqueueShow(Long showId, User user) {
        List<Season> seasons = seasonRepo.findByShowIdOrderBySeasonNumber(showId);
        if (seasons.isEmpty()) throw new IllegalArgumentException("Show not found or empty: " + showId);
        List<Long> ids = new ArrayList<>();
        for (Season season : seasons) {
            ids.addAll(enqueueSeason(season.getId(), user));
        }
        return ids;
    }

    public List<DownloadQueueItem> getQueue() {
        return queueRepo.findAllByOrderByQueuePositionAsc();
    }

    private DownloadQueueItem buildItem(User user, DownloadQueueItem.MediaType type,
                                        Long mediaId, String plexFilePath) {
        String appPath = pathMapping.translate(plexFilePath);
        String conversionDir = settings.getRequired("plex.conversion.dir");
        String filename = Path.of(appPath).getFileName().toString();
        String destPath = Path.of(conversionDir, filename).toString();

        int nextPos = queueRepo.findMaxQueuePosition().orElse(0) + 1;

        DownloadQueueItem item = new DownloadQueueItem();
        item.setUser(user);
        item.setMediaType(type);
        item.setMediaId(mediaId);
        item.setSourceFilePath(appPath);
        item.setDestFilePath(destPath);
        item.setQueuePosition(nextPos);
        item.setStatus(DownloadQueueItem.Status.PENDING);
        return item;
    }

    @Async
    public void executeCopyAsync(Long itemId) {
        DownloadQueueItem item = queueRepo.findById(itemId).orElse(null);
        if (item == null) return;

        item.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        queueRepo.save(item);

        try {
            Path source = Path.of(item.getSourceFilePath());
            Path dest = Path.of(item.getDestFilePath());
            if (!Files.exists(source)) {
                throw new IOException("Source file not found: " + source);
            }
            Files.createDirectories(dest.getParent());
            if (!Files.exists(dest) || Files.size(dest) != Files.size(source)) {
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            item.setStatus(DownloadQueueItem.Status.DONE);
            item.setCompletedAt(Instant.now());
        } catch (IOException e) {
            item.setStatus(DownloadQueueItem.Status.ERROR);
            item.setErrorMessage(e.getMessage());
            log.error("Copy failed for item {}: {}", itemId, e.getMessage());
        }
        queueRepo.save(item);
    }
}
```

- [ ] **Step 5: Implement DownloadController**

```java
package com.plexdownloader.controller;

import com.plexdownloader.dto.DownloadRequest;
import com.plexdownloader.dto.DownloadResponse;
import com.plexdownloader.model.DownloadQueueItem;
import com.plexdownloader.model.User;
import com.plexdownloader.service.DownloadService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadService downloadService;

    @PostMapping
    public DownloadResponse download(@RequestBody DownloadRequest req,
                                     @AuthenticationPrincipal User user) {
        List<Long> jobIds = switch (req.type()) {
            case "MOVIE"   -> downloadService.enqueueMovie(req.id(), user);
            case "EPISODE" -> downloadService.enqueueEpisode(req.id(), user);
            case "SEASON"  -> downloadService.enqueueSeason(req.id(), user);
            case "SHOW"    -> downloadService.enqueueShow(req.id(), user);
            default -> throw new IllegalArgumentException("Unknown type: " + req.type());
        };
        return new DownloadResponse(jobIds, "QUEUED");
    }

    @GetMapping("/queue")
    public List<DownloadQueueItem> getQueue() {
        return downloadService.getQueue();
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.service.DownloadServiceTest" -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/dto/DownloadRequest.java \
        backend/src/main/java/com/plexdownloader/dto/DownloadResponse.java \
        backend/src/main/java/com/plexdownloader/service/DownloadService.java \
        backend/src/main/java/com/plexdownloader/controller/DownloadController.java \
        backend/src/test/java/com/plexdownloader/service/DownloadServiceTest.java
git commit -m "feat: add DownloadService (async file copy) and DownloadController"
```

---

## Task 11: LibraryController + PosterController

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/dto/MovieResponse.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/TvShowResponse.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/SeasonResponse.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/EpisodeResponse.java`
- Create: `backend/src/main/java/com/plexdownloader/controller/LibraryController.java`
- Create: `backend/src/main/java/com/plexdownloader/controller/PosterController.java`
- Create: `backend/src/test/java/com/plexdownloader/controller/LibraryControllerTest.java`

- [ ] **Step 1: Create response DTOs**

`MovieResponse.java`:
```java
package com.plexdownloader.dto;

import com.plexdownloader.model.Actor;
import com.plexdownloader.model.Movie;
import java.util.List;

public record MovieResponse(
    Long id, String plexId, String title, Integer year, String summary,
    String posterUrl, Float rating, String studio, Long durationMs,
    List<String> genres, List<String> directors, List<ActorDto> actors
) {
    public record ActorDto(Long id, String name) {}

    public static MovieResponse from(Movie m) {
        List<ActorDto> actors = m.getActors() == null ? List.of() :
            m.getActors().stream().map(a -> new ActorDto(a.getId(), a.getName())).toList();
        return new MovieResponse(m.getId(), m.getPlexId(), m.getTitle(), m.getYear(),
            m.getSummary(), m.getPosterUrl(), m.getRating(), m.getStudio(), m.getDurationMs(),
            m.getGenres(), m.getDirectors(), actors);
    }
}
```

`TvShowResponse.java`:
```java
package com.plexdownloader.dto;

import com.plexdownloader.model.TvShow;
import java.util.List;

public record TvShowResponse(
    Long id, String plexId, String title, Integer year, String summary,
    String posterUrl, Float rating, Integer totalSeasons,
    List<String> genres, List<MovieResponse.ActorDto> actors
) {
    public static TvShowResponse from(TvShow s) {
        List<MovieResponse.ActorDto> actors = s.getActors() == null ? List.of() :
            s.getActors().stream().map(a -> new MovieResponse.ActorDto(a.getId(), a.getName())).toList();
        return new TvShowResponse(s.getId(), s.getPlexId(), s.getTitle(), s.getYear(),
            s.getSummary(), s.getPosterUrl(), s.getRating(), s.getTotalSeasons(),
            s.getGenres(), actors);
    }
}
```

`SeasonResponse.java`:
```java
package com.plexdownloader.dto;

import com.plexdownloader.model.Season;

public record SeasonResponse(
    Long id, String plexId, Integer seasonNumber, String title,
    String posterUrl, Integer episodeCount
) {
    public static SeasonResponse from(Season s) {
        return new SeasonResponse(s.getId(), s.getPlexId(), s.getSeasonNumber(),
            s.getTitle(), s.getPosterUrl(), s.getEpisodeCount());
    }
}
```

`EpisodeResponse.java`:
```java
package com.plexdownloader.dto;

import com.plexdownloader.model.Episode;
import java.time.LocalDate;

public record EpisodeResponse(
    Long id, String plexId, Integer episodeNumber, String title, String summary,
    String thumbnailUrl, Long durationMs, LocalDate airDate,
    String director, String writer, String videoResolution
) {
    public static EpisodeResponse from(Episode e) {
        return new EpisodeResponse(e.getId(), e.getPlexId(), e.getEpisodeNumber(),
            e.getTitle(), e.getSummary(), e.getThumbnailUrl(), e.getDurationMs(),
            e.getAirDate(), e.getDirector(), e.getWriter(), e.getVideoResolution());
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.plexdownloader.controller;

import com.plexdownloader.config.JwtAuthFilter;
import com.plexdownloader.config.SecurityConfig;
import com.plexdownloader.model.Movie;
import com.plexdownloader.model.User;
import com.plexdownloader.repository.*;
import com.plexdownloader.service.JwtService;
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
import org.springframework.data.domain.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LibraryController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class LibraryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean MovieRepository movieRepo;
    @MockBean TvShowRepository showRepo;
    @MockBean SeasonRepository seasonRepo;
    @MockBean EpisodeRepository episodeRepo;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;
    @MockBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void setupUserAuth() throws Exception {
        User user = new User();
        user.setId(2L);
        user.setPlexAccountId("user-plex-id");
        user.setUsername("user");
        user.setRole(User.Role.USER);

        doAnswer((InvocationOnMock inv) -> {
            HttpServletRequest req = inv.getArgument(0);
            HttpServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);

            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            SecurityContextHolder.setContext(ctx);

            chain.doFilter(req, res);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void getMoviesReturnsPagedResults() throws Exception {
        Movie movie = new Movie();
        movie.setId(1L);
        movie.setPlexId("12345");
        movie.setTitle("Inception");
        movie.setYear(2010);
        movie.setActors(new ArrayList<>());
        movie.setGenres(new ArrayList<>());
        movie.setDirectors(new ArrayList<>());

        Page<Movie> page = new PageImpl<>(List.of(movie));
        when(movieRepo.search(isNull(), isNull(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/movies"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].title").value("Inception"));
    }

    @Test
    void getMovieByIdReturns404WhenNotFound() throws Exception {
        when(movieRepo.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/movies/99"))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.controller.LibraryControllerTest" -i 2>&1 | tail -20
```
Expected: FAIL — `LibraryController` not found

- [ ] **Step 4: Implement LibraryController**

```java
package com.plexdownloader.controller;

import com.plexdownloader.dto.*;
import com.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class LibraryController {

    private final MovieRepository movieRepo;
    private final TvShowRepository showRepo;
    private final SeasonRepository seasonRepo;
    private final EpisodeRepository episodeRepo;

    @GetMapping("/api/movies")
    public Page<MovieResponse> getMovies(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("title"));
        return movieRepo.search(search, year, pageable).map(MovieResponse::from);
    }

    @GetMapping("/api/movies/{id}")
    public ResponseEntity<MovieResponse> getMovie(@PathVariable Long id) {
        return movieRepo.findById(id)
            .map(m -> ResponseEntity.ok(MovieResponse.from(m)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/tv")
    public Page<TvShowResponse> getShows(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("title"));
        return showRepo.search(search, year, pageable).map(TvShowResponse::from);
    }

    @GetMapping("/api/tv/{showId}")
    public ResponseEntity<TvShowResponse> getShow(@PathVariable Long showId) {
        return showRepo.findById(showId)
            .map(s -> ResponseEntity.ok(TvShowResponse.from(s)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/tv/{showId}/seasons")
    public List<SeasonResponse> getSeasons(@PathVariable Long showId) {
        return seasonRepo.findByShowIdOrderBySeasonNumber(showId)
            .stream().map(SeasonResponse::from).toList();
    }

    @GetMapping("/api/tv/{showId}/seasons/{seasonId}")
    public ResponseEntity<SeasonResponse> getSeason(@PathVariable Long showId,
                                                     @PathVariable Long seasonId) {
        return seasonRepo.findById(seasonId)
            .map(s -> ResponseEntity.ok(SeasonResponse.from(s)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/tv/{showId}/seasons/{seasonId}/episodes")
    public List<EpisodeResponse> getEpisodes(@PathVariable Long showId,
                                              @PathVariable Long seasonId) {
        return episodeRepo.findBySeasonIdOrderByEpisodeNumber(seasonId)
            .stream().map(EpisodeResponse::from).toList();
    }

    @GetMapping("/api/tv/{showId}/seasons/{seasonId}/episodes/{episodeId}")
    public ResponseEntity<EpisodeResponse> getEpisode(@PathVariable Long showId,
                                                       @PathVariable Long seasonId,
                                                       @PathVariable Long episodeId) {
        return episodeRepo.findById(episodeId)
            .map(e -> ResponseEntity.ok(EpisodeResponse.from(e)))
            .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 5: Implement PosterController**

```java
package com.plexdownloader.controller;

import com.plexdownloader.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/posters")
@RequiredArgsConstructor
public class PosterController {

    private final SettingsService settings;

    @GetMapping("/{ratingKey}.jpg")
    public ResponseEntity<byte[]> getPoster(@PathVariable String ratingKey) {
        Path posterDir = Path.of(settings.getRequired("plex.poster.dir"));
        Path poster = posterDir.resolve(ratingKey + ".jpg");

        if (!Files.exists(poster)) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] bytes = Files.readAllBytes(poster);
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(24)))
                .body(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test --tests "com.plexdownloader.controller.LibraryControllerTest" -i 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Run all tests**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test -i 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL — all tests pass

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/dto/ \
        backend/src/main/java/com/plexdownloader/controller/LibraryController.java \
        backend/src/main/java/com/plexdownloader/controller/PosterController.java \
        backend/src/test/java/com/plexdownloader/controller/LibraryControllerTest.java
git commit -m "feat: add LibraryController (browse API) and PosterController"
```

---

## Task 12: Final Verification

- [ ] **Step 1: Run full test suite**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew test -i 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL — all tests pass

- [ ] **Step 2: Verify all new classes compile cleanly (no warnings)**

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && cd backend && ./gradlew compileJava 2>&1 | grep -i "warn\|error" | head -20
```
Expected: no output (clean compile)

- [ ] **Step 3: Final commit**

```bash
git add -A
git status  # verify only expected files are staged
git commit -m "chore: library sync feature complete"
```
