# Plex Downloader — Plan 1a: Backend Foundation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring Boot backend scaffold: project structure, PostgreSQL schema via Liquibase, JPA entities, JWT auth, and Plex OAuth pin flow.

**Architecture:** Spring Boot 3.3 stateless REST API. Spring Security with JWT (jjwt 0.12). Liquibase manages all schema migrations via SQL changelogs. Auth uses Plex pin-based OAuth — backend creates a pin at plex.tv, user authorizes in browser, backend polls for the auth token then issues a local JWT. First user to authenticate becomes ADMIN.

**Tech Stack:** Java 21 · Spring Boot 3.3 · Spring Data JPA · Spring Security · Liquibase · PostgreSQL 16 · jjwt 0.12 · Lombok · H2 (tests) · JUnit 5 · MockMvc

---

## File Structure

```
backend/
  .sdkmanrc
  settings.gradle
  build.gradle
  src/
    main/
      java/com/plexdownloader/
        PlexDownloaderApplication.java
        config/
          SecurityConfig.java
          JwtAuthFilter.java
        model/
          User.java
          Movie.java
          TvShow.java
          Season.java
          Episode.java
          Actor.java
          Setting.java
          DownloadQueueItem.java
        repository/
          UserRepository.java
          MovieRepository.java
          TvShowRepository.java
          SeasonRepository.java
          EpisodeRepository.java
          ActorRepository.java
          SettingRepository.java
          DownloadQueueRepository.java
        service/
          JwtService.java
          AuthService.java
          PlexPinClient.java
        controller/
          AuthController.java
        dto/
          PlexPinInitResponse.java
          PlexPinStatusResponse.java
          PlexUserInfo.java
          JwtResponse.java
      resources/
        application.yml
        db/changelog/
          db.changelog-master.yaml
          sql/001-initial-schema.sql
    test/
      java/com/plexdownloader/
        PlexDownloaderApplicationTests.java
        service/JwtServiceTest.java
        controller/AuthControllerTest.java
        repository/UserRepositoryTest.java
      resources/
        application-test.yml
```

---

### Task 1: Project Scaffold

**Files:**
- Create: `backend/.sdkmanrc`
- Create: `backend/settings.gradle`
- Create: `backend/build.gradle`
- Create: `backend/src/main/java/com/plexdownloader/PlexDownloaderApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/com/plexdownloader/PlexDownloaderApplicationTests.java`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p backend/src/main/java/com/plexdownloader/{config,model,repository,service,controller,dto}
mkdir -p backend/src/main/resources/db/changelog/sql
mkdir -p backend/src/test/java/com/plexdownloader/{service,controller,repository}
mkdir -p backend/src/test/resources
```

- [ ] **Step 2: Create `backend/.sdkmanrc`**

```
java=21.0.4-tem
```

- [ ] **Step 3: Create `backend/settings.gradle`**

```groovy
rootProject.name = 'plex-downloader-backend'
```

- [ ] **Step 4: Create `backend/build.gradle`**

```groovy
plugins {
    id 'org.springframework.boot' version '3.3.4'
    id 'io.spring.dependency-management' version '1.1.6'
    id 'java'
}

group = 'com.plexdownloader'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly { extendsFrom annotationProcessor }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.liquibase:liquibase-core'
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    runtimeOnly 'org.postgresql:postgresql'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testRuntimeOnly 'com.h2database:h2'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

- [ ] **Step 5: Create `PlexDownloaderApplication.java`**

```java
package com.plexdownloader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlexDownloaderApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlexDownloaderApplication.class, args);
    }
}
```

- [ ] **Step 6: Create `application.yml`**

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/plexdownloader}
    username: ${SPRING_DATASOURCE_USERNAME:plexdl}
    password: ${SPRING_DATASOURCE_PASSWORD:plexdl}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

app:
  jwt:
    secret: ${JWT_SECRET:dev-secret-minimum-32-chars-long-change-in-prod}
    expiration-ms: 86400000
```

- [ ] **Step 7: Create `src/test/resources/application-test.yml`**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    username: sa
    password:
    driver-class-name: org.h2.Driver
  jpa:
    database-platform: org.hibernate.dialect.H2Dialect
    hibernate:
      ddl-auto: validate
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

app:
  jwt:
    secret: test-secret-minimum-32-chars-long-for-tests
    expiration-ms: 86400000
```

- [ ] **Step 8: Write context-loads test**

```java
// src/test/java/com/plexdownloader/PlexDownloaderApplicationTests.java
package com.plexdownloader;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PlexDownloaderApplicationTests {
    @Test
    void contextLoads() {}
}
```

- [ ] **Step 9: Activate Java 21 and run test — expect FAIL (no schema yet)**

```bash
cd backend
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
./gradlew test --tests "com.plexdownloader.PlexDownloaderApplicationTests"
```

Expected: FAIL — Liquibase cannot find `db.changelog-master.yaml`.

- [ ] **Step 10: Commit scaffold**

```bash
git add backend/
git commit -m "chore: add Spring Boot backend scaffold"
```

---

### Task 2: Liquibase Schema & JPA Entities

**Files:**
- Create: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `backend/src/main/resources/db/changelog/sql/001-initial-schema.sql`
- Create: all 8 entity files in `model/`
- Create: all 8 repository files in `repository/`
- Test: `backend/src/test/java/com/plexdownloader/repository/UserRepositoryTest.java`

- [ ] **Step 1: Write `UserRepositoryTest` (failing)**

```java
// src/test/java/com/plexdownloader/repository/UserRepositoryTest.java
package com.plexdownloader.repository;

import com.plexdownloader.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired UserRepository repo;

    @Test
    void saveAndFindByPlexAccountId() {
        User u = new User();
        u.setPlexAccountId("plex-123");
        u.setUsername("Laurent");
        u.setRole(User.Role.ADMIN);
        repo.save(u);

        Optional<User> found = repo.findByPlexAccountId("plex-123");
        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("Laurent");
        assertThat(found.get().getRole()).isEqualTo(User.Role.ADMIN);
    }

    @Test
    void countReturnsZeroOnEmptyTable() {
        assertThat(repo.count()).isZero();
    }
}
```

- [ ] **Step 2: Run test — expect FAIL (no schema)**

```bash
cd backend && ./gradlew test --tests "com.plexdownloader.repository.UserRepositoryTest"
```

Expected: FAIL — `db.changelog-master.yaml` not found.

- [ ] **Step 3: Create `db.changelog-master.yaml`**

```yaml
databaseChangeLog:
  - includeAll:
      path: db/changelog/sql/
      relativeToChangelogFile: false
```

- [ ] **Step 4: Create `001-initial-schema.sql`**

```sql
-- liquibase formatted sql

-- changeset plexdownloader:001-users
CREATE TABLE users (
    id         BIGSERIAL PRIMARY KEY,
    plex_account_id VARCHAR(255) NOT NULL UNIQUE,
    username   VARCHAR(255) NOT NULL,
    avatar_url TEXT,
    role       VARCHAR(20) NOT NULL DEFAULT 'USER',
    last_login_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- changeset plexdownloader:001-settings
CREATE TABLE settings (
    key   VARCHAR(100) PRIMARY KEY,
    value TEXT
);

-- changeset plexdownloader:001-movies
CREATE TABLE movies (
    id          BIGSERIAL PRIMARY KEY,
    plex_id     VARCHAR(100) NOT NULL UNIQUE,
    title       VARCHAR(500) NOT NULL,
    year        INT,
    summary     TEXT,
    poster_url  TEXT,
    file_path   TEXT,
    duration_ms BIGINT,
    synced_at   TIMESTAMP
);
CREATE TABLE movie_genres (
    movie_id BIGINT NOT NULL REFERENCES movies(id) ON DELETE CASCADE,
    genre    VARCHAR(100) NOT NULL
);

-- changeset plexdownloader:001-tvshows
CREATE TABLE tv_shows (
    id         BIGSERIAL PRIMARY KEY,
    plex_id    VARCHAR(100) NOT NULL UNIQUE,
    title      VARCHAR(500) NOT NULL,
    year       INT,
    summary    TEXT,
    poster_url TEXT,
    synced_at  TIMESTAMP
);
CREATE TABLE show_genres (
    show_id BIGINT NOT NULL REFERENCES tv_shows(id) ON DELETE CASCADE,
    genre   VARCHAR(100) NOT NULL
);

-- changeset plexdownloader:001-seasons
CREATE TABLE seasons (
    id             BIGSERIAL PRIMARY KEY,
    plex_id        VARCHAR(100) NOT NULL UNIQUE,
    show_id        BIGINT NOT NULL REFERENCES tv_shows(id) ON DELETE CASCADE,
    season_number  INT NOT NULL,
    title          VARCHAR(255),
    poster_url     TEXT,
    episode_count  INT,
    synced_at      TIMESTAMP
);

-- changeset plexdownloader:001-episodes
CREATE TABLE episodes (
    id               BIGSERIAL PRIMARY KEY,
    plex_id          VARCHAR(100) NOT NULL UNIQUE,
    season_id        BIGINT NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
    episode_number   INT NOT NULL,
    title            VARCHAR(500),
    summary          TEXT,
    thumbnail_url    TEXT,
    file_path        TEXT,
    duration_ms      BIGINT,
    air_date         DATE,
    director         VARCHAR(500),
    writer           VARCHAR(500),
    video_resolution VARCHAR(50),
    synced_at        TIMESTAMP
);

-- changeset plexdownloader:001-actors
CREATE TABLE actors (
    id        BIGSERIAL PRIMARY KEY,
    plex_id   VARCHAR(100) NOT NULL UNIQUE,
    name      VARCHAR(255) NOT NULL,
    photo_url TEXT
);
CREATE TABLE movie_actors (
    movie_id BIGINT NOT NULL REFERENCES movies(id)  ON DELETE CASCADE,
    actor_id BIGINT NOT NULL REFERENCES actors(id)  ON DELETE CASCADE,
    role     VARCHAR(255),
    PRIMARY KEY (movie_id, actor_id)
);
CREATE TABLE show_actors (
    show_id  BIGINT NOT NULL REFERENCES tv_shows(id) ON DELETE CASCADE,
    actor_id BIGINT NOT NULL REFERENCES actors(id)   ON DELETE CASCADE,
    role     VARCHAR(255),
    PRIMARY KEY (show_id, actor_id)
);

-- changeset plexdownloader:001-download-queue
CREATE TABLE download_queue (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users(id),
    media_type     VARCHAR(20) NOT NULL,
    media_id       BIGINT NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    queue_position INT,
    error_message  TEXT,
    requested_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMP
);
CREATE INDEX idx_download_queue_status ON download_queue(status);
```

- [ ] **Step 5: Create `model/User.java`**

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
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;
    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    public enum Role { ADMIN, USER }
}
```

- [ ] **Step 6: Create `model/Movie.java`**

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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "movie_genres", joinColumns = @JoinColumn(name = "movie_id"))
    @Column(name = "genre")
    private List<String> genres = new ArrayList<>();
}
```

- [ ] **Step 7: Create `model/TvShow.java`**

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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "show_genres", joinColumns = @JoinColumn(name = "show_id"))
    @Column(name = "genre")
    private List<String> genres = new ArrayList<>();
}
```

- [ ] **Step 8: Create `model/Season.java`**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity @Table(name = "seasons")
public class Season {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "show_id", nullable = false)
    private TvShow show;
    @Column(name = "season_number", nullable = false)
    private Integer seasonNumber;
    private String title;
    @Column(name = "poster_url", columnDefinition = "TEXT")
    private String posterUrl;
    @Column(name = "episode_count")
    private Integer episodeCount;
    @Column(name = "synced_at")
    private Instant syncedAt;
}
```

- [ ] **Step 9: Create `model/Episode.java`**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.time.LocalDate;

@Data @Entity @Table(name = "episodes")
public class Episode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;
    @Column(name = "episode_number", nullable = false)
    private Integer episodeNumber;
    private String title;
    @Column(columnDefinition = "TEXT")
    private String summary;
    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;
    @Column(name = "file_path", columnDefinition = "TEXT")
    private String filePath;
    @Column(name = "duration_ms")
    private Long durationMs;
    @Column(name = "air_date")
    private LocalDate airDate;
    private String director;
    private String writer;
    @Column(name = "video_resolution")
    private String videoResolution;
    @Column(name = "synced_at")
    private Instant syncedAt;
}
```

- [ ] **Step 10: Create `model/Actor.java`**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;

@Data @Entity @Table(name = "actors")
public class Actor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "plex_id", unique = true, nullable = false)
    private String plexId;
    @Column(nullable = false)
    private String name;
    @Column(name = "photo_url", columnDefinition = "TEXT")
    private String photoUrl;
}
```

- [ ] **Step 11: Create `model/Setting.java`**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;

@Data @Entity @Table(name = "settings")
public class Setting {
    @Id
    private String key;
    @Column(columnDefinition = "TEXT")
    private String value;
}
```

- [ ] **Step 12: Create `model/DownloadQueueItem.java`**

```java
package com.plexdownloader.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity @Table(name = "download_queue")
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
    @Column(name = "requested_at", updatable = false)
    private Instant requestedAt = Instant.now();
    @Column(name = "completed_at")
    private Instant completedAt;

    public enum MediaType { MOVIE, EPISODE }
    public enum Status { PENDING, IN_PROGRESS, DONE, ERROR }
}
```

- [ ] **Step 13: Create all repositories**

```java
// repository/UserRepository.java
package com.plexdownloader.repository;
import com.plexdownloader.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPlexAccountId(String plexAccountId);
}
```

```java
// repository/MovieRepository.java
package com.plexdownloader.repository;
import com.plexdownloader.model.Movie;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    Optional<Movie> findByPlexId(String plexId);

    @Query("SELECT m FROM Movie m WHERE " +
           "(:search IS NULL OR LOWER(m.title) LIKE LOWER(CONCAT('%',:search,'%'))) AND " +
           "(:year IS NULL OR m.year = :year)")
    Page<Movie> search(@Param("search") String search,
                       @Param("year") Integer year,
                       Pageable pageable);
}
```

```java
// repository/TvShowRepository.java
package com.plexdownloader.repository;
import com.plexdownloader.model.TvShow;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface TvShowRepository extends JpaRepository<TvShow, Long> {
    Optional<TvShow> findByPlexId(String plexId);

    @Query("SELECT t FROM TvShow t WHERE " +
           "(:search IS NULL OR LOWER(t.title) LIKE LOWER(CONCAT('%',:search,'%'))) AND " +
           "(:year IS NULL OR t.year = :year)")
    Page<TvShow> search(@Param("search") String search,
                        @Param("year") Integer year,
                        Pageable pageable);
}
```

```java
// repository/SeasonRepository.java
package com.plexdownloader.repository;
import com.plexdownloader.model.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface SeasonRepository extends JpaRepository<Season, Long> {
    Optional<Season> findByPlexId(String plexId);
    List<Season> findByShowIdOrderBySeasonNumber(Long showId);
}
```

```java
// repository/EpisodeRepository.java
package com.plexdownloader.repository;
import com.plexdownloader.model.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    Optional<Episode> findByPlexId(String plexId);
    List<Episode> findBySeasonIdOrderByEpisodeNumber(Long seasonId);
}
```

```java
// repository/ActorRepository.java
package com.plexdownloader.repository;
import com.plexdownloader.model.Actor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ActorRepository extends JpaRepository<Actor, Long> {
    Optional<Actor> findByPlexId(String plexId);
}
```

```java
// repository/SettingRepository.java
package com.plexdownloader.repository;
import com.plexdownloader.model.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<Setting, String> {}
```

```java
// repository/DownloadQueueRepository.java
package com.plexdownloader.repository;
import com.plexdownloader.model.DownloadQueueItem;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface DownloadQueueRepository extends JpaRepository<DownloadQueueItem, Long> {
    List<DownloadQueueItem> findAllByOrderByQueuePositionAsc();

    @Query("SELECT i FROM DownloadQueueItem i WHERE i.status = 'IN_PROGRESS'")
    Optional<DownloadQueueItem> findInProgress();

    @Query("SELECT MAX(i.queuePosition) FROM DownloadQueueItem i WHERE i.status = 'PENDING'")
    Optional<Integer> findMaxQueuePosition();
}
```

- [ ] **Step 14: Run UserRepositoryTest — expect PASS**

```bash
cd backend && ./gradlew test --tests "com.plexdownloader.repository.UserRepositoryTest"
```

Expected: PASS

- [ ] **Step 15: Run full suite**

```bash
cd backend && ./gradlew test
```

Expected: 3 tests pass (contextLoads + 2 UserRepositoryTest).

- [ ] **Step 16: Commit**

```bash
git add backend/src/
git commit -m "feat: add Liquibase schema and JPA entities"
```

---

### Task 3: JWT Infrastructure

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/service/JwtService.java`
- Create: `backend/src/main/java/com/plexdownloader/config/JwtAuthFilter.java`
- Create: `backend/src/main/java/com/plexdownloader/config/SecurityConfig.java`
- Test: `backend/src/test/java/com/plexdownloader/service/JwtServiceTest.java`

- [ ] **Step 1: Write `JwtServiceTest` (failing)**

```java
// src/test/java/com/plexdownloader/service/JwtServiceTest.java
package com.plexdownloader.service;

import com.plexdownloader.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret",
            "test-secret-minimum-32-chars-long-for-tests");
        ReflectionTestUtils.setField(jwtService, "expirationMs", 86400000L);
    }

    @Test
    void generateAndValidateToken() {
        User user = new User();
        user.setPlexAccountId("plex-123");
        user.setUsername("Laurent");
        user.setRole(User.Role.ADMIN);

        String token = jwtService.generateToken(user);

        assertThat(token).isNotBlank();
        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractPlexAccountId(token)).isEqualTo("plex-123");
    }

    @Test
    void invalidTokenReturnsFalse() {
        assertThat(jwtService.isValid("not.a.token")).isFalse();
    }

    @Test
    void expiredTokenReturnsFalse() {
        JwtService shortLived = new JwtService();
        ReflectionTestUtils.setField(shortLived, "secret",
            "test-secret-minimum-32-chars-long-for-tests");
        ReflectionTestUtils.setField(shortLived, "expirationMs", -1L);

        User user = new User();
        user.setPlexAccountId("plex-x");
        user.setUsername("x");
        user.setRole(User.Role.USER);

        String token = shortLived.generateToken(user);
        assertThat(shortLived.isValid(token)).isFalse();
    }
}
```

- [ ] **Step 2: Run — expect FAIL (JwtService not found)**

```bash
cd backend && ./gradlew test --tests "com.plexdownloader.service.JwtServiceTest"
```

Expected: FAIL — `JwtService` not found.

- [ ] **Step 3: Create `service/JwtService.java`**

```java
package com.plexdownloader.service;

import com.plexdownloader.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    public String generateToken(User user) {
        return Jwts.builder()
            .subject(user.getPlexAccountId())
            .claim("role", user.getRole().name())
            .claim("username", user.getUsername())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(getKey())
            .compact();
    }

    public String extractPlexAccountId(String token) {
        return getClaims(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
            .verifyWith(getKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Create `config/JwtAuthFilter.java`**

```java
package com.plexdownloader.config;

import com.plexdownloader.repository.UserRepository;
import com.plexdownloader.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtService.isValid(token)) {
                String plexId = jwtService.extractPlexAccountId(token);
                userRepository.findByPlexAccountId(plexId).ifPresent(user -> {
                    var auth = new UsernamePasswordAuthenticationToken(
                        user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 5: Create `config/SecurityConfig.java`**

```java
package com.plexdownloader.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

- [ ] **Step 6: Run JWT tests — expect PASS**

```bash
cd backend && ./gradlew test --tests "com.plexdownloader.service.JwtServiceTest"
```

Expected: 3 tests pass.

- [ ] **Step 7: Run full suite**

```bash
cd backend && ./gradlew test
```

Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/config/ \
        backend/src/main/java/com/plexdownloader/service/JwtService.java \
        backend/src/test/java/com/plexdownloader/service/JwtServiceTest.java
git commit -m "feat: add JWT service and Spring Security config"
```

---

### Task 4: Plex OAuth & Auth Controller

**Files:**
- Create: `backend/src/main/java/com/plexdownloader/dto/PlexPinInitResponse.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/PlexUserInfo.java`
- Create: `backend/src/main/java/com/plexdownloader/dto/JwtResponse.java`
- Create: `backend/src/main/java/com/plexdownloader/service/PlexPinClient.java`
- Create: `backend/src/main/java/com/plexdownloader/service/AuthService.java`
- Create: `backend/src/main/java/com/plexdownloader/controller/AuthController.java`
- Test: `backend/src/test/java/com/plexdownloader/controller/AuthControllerTest.java`

- [ ] **Step 1: Write `AuthControllerTest` (failing)**

```java
// src/test/java/com/plexdownloader/controller/AuthControllerTest.java
package com.plexdownloader.controller;

import com.plexdownloader.config.JwtAuthFilter;
import com.plexdownloader.dto.JwtResponse;
import com.plexdownloader.dto.PlexPinInitResponse;
import com.plexdownloader.repository.UserRepository;
import com.plexdownloader.service.AuthService;
import com.plexdownloader.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean AuthService authService;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;
    @MockBean JwtAuthFilter jwtAuthFilter;

    @Test
    void initPinReturnsIdAndCode() throws Exception {
        when(authService.initPin()).thenReturn(new PlexPinInitResponse(42L, "abc123"));

        mockMvc.perform(post("/api/auth/plex/pin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pinId").value(42))
            .andExpect(jsonPath("$.code").value("abc123"));
    }

    @Test
    void checkPinReturnsPendingWhenNotYetAuthorized() throws Exception {
        when(authService.checkPin(42L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/auth/plex/pin/42"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void checkPinReturnsJwtWhenAuthorized() throws Exception {
        when(authService.checkPin(42L))
            .thenReturn(Optional.of(new JwtResponse("jwt-token", "Laurent", "ADMIN")));

        mockMvc.perform(get("/api/auth/plex/pin/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("jwt-token"))
            .andExpect(jsonPath("$.username").value("Laurent"));
    }
}
```

- [ ] **Step 2: Run — expect FAIL (missing classes)**

```bash
cd backend && ./gradlew test --tests "com.plexdownloader.controller.AuthControllerTest"
```

Expected: FAIL — `AuthController`, `AuthService`, `PlexPinInitResponse` not found.

- [ ] **Step 3: Create DTOs**

```java
// dto/PlexPinInitResponse.java
package com.plexdownloader.dto;
public record PlexPinInitResponse(Long pinId, String code) {}
```

```java
// dto/PlexUserInfo.java
package com.plexdownloader.dto;
public record PlexUserInfo(String id, String username, String thumb) {}
```

```java
// dto/JwtResponse.java
package com.plexdownloader.dto;
public record JwtResponse(String token, String username, String role) {}
```

- [ ] **Step 4: Create `service/PlexPinClient.java`**

```java
package com.plexdownloader.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.plexdownloader.dto.PlexPinInitResponse;
import com.plexdownloader.dto.PlexUserInfo;
import lombok.Data;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PlexPinClient {

    private static final String PLEX_TV       = "https://plex.tv";
    private static final String CLIENT_ID     = "plex-downloader-app";
    private static final String PRODUCT       = "PlexDownloader";

    private final RestClient restClient = RestClient.builder()
        .baseUrl(PLEX_TV)
        .defaultHeader("X-Plex-Client-Identifier", CLIENT_ID)
        .defaultHeader("X-Plex-Product", PRODUCT)
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build();

    public PlexPinInitResponse createPin() {
        PlexPinApiResponse resp = restClient.post()
            .uri("/api/v2/pins?strong=true")
            .retrieve()
            .body(PlexPinApiResponse.class);
        assert resp != null;
        return new PlexPinInitResponse(resp.getId(), resp.getCode());
    }

    /** Returns authToken string, or null if user hasn't authorized yet. */
    public String pollPin(Long pinId) {
        PlexPinApiResponse resp = restClient.get()
            .uri("/api/v2/pins/{id}", pinId)
            .retrieve()
            .body(PlexPinApiResponse.class);
        return (resp != null) ? resp.getAuthToken() : null;
    }

    public PlexUserInfo getUserInfo(String authToken) {
        PlexUserApiResponse resp = restClient.get()
            .uri("/api/v2/user")
            .header("X-Plex-Token", authToken)
            .retrieve()
            .body(PlexUserApiResponse.class);
        assert resp != null;
        return new PlexUserInfo(String.valueOf(resp.getId()), resp.getUsername(), resp.getThumb());
    }

    public String buildAuthUrl(String code) {
        return "https://app.plex.tv/auth#?clientID=" + CLIENT_ID
            + "&code=" + code
            + "&context[device][product]=" + PRODUCT;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class PlexPinApiResponse {
        private Long id;
        private String code;
        @JsonProperty("authToken")
        private String authToken;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class PlexUserApiResponse {
        private Long id;
        private String username;
        private String thumb;
    }
}
```

- [ ] **Step 5: Create `service/AuthService.java`**

```java
package com.plexdownloader.service;

import com.plexdownloader.dto.JwtResponse;
import com.plexdownloader.dto.PlexPinInitResponse;
import com.plexdownloader.dto.PlexUserInfo;
import com.plexdownloader.model.User;
import com.plexdownloader.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final PlexPinClient plexPinClient;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public PlexPinInitResponse initPin() {
        return plexPinClient.createPin();
    }

    public String getAuthUrl(String code) {
        return plexPinClient.buildAuthUrl(code);
    }

    @Transactional
    public Optional<JwtResponse> checkPin(Long pinId) {
        String authToken = plexPinClient.pollPin(pinId);
        if (authToken == null || authToken.isBlank()) {
            return Optional.empty();
        }
        PlexUserInfo info = plexPinClient.getUserInfo(authToken);
        User user = upsertUser(info);
        return Optional.of(new JwtResponse(
            jwtService.generateToken(user),
            user.getUsername(),
            user.getRole().name()
        ));
    }

    private User upsertUser(PlexUserInfo info) {
        return userRepository.findByPlexAccountId(info.id())
            .map(existing -> {
                existing.setUsername(info.username());
                existing.setAvatarUrl(info.thumb());
                existing.setLastLoginAt(Instant.now());
                return userRepository.save(existing);
            })
            .orElseGet(() -> {
                User u = new User();
                u.setPlexAccountId(info.id());
                u.setUsername(info.username());
                u.setAvatarUrl(info.thumb());
                u.setLastLoginAt(Instant.now());
                // First user to authenticate becomes ADMIN
                u.setRole(userRepository.count() == 0 ? User.Role.ADMIN : User.Role.USER);
                return userRepository.save(u);
            });
    }
}
```

- [ ] **Step 6: Create `controller/AuthController.java`**

```java
package com.plexdownloader.controller;

import com.plexdownloader.dto.JwtResponse;
import com.plexdownloader.dto.PlexPinInitResponse;
import com.plexdownloader.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/plex/pin")
    public PlexPinInitResponse initPin() {
        return authService.initPin();
    }

    @GetMapping("/plex/pin/{pinId}")
    public ResponseEntity<?> checkPin(@PathVariable Long pinId) {
        Optional<JwtResponse> result = authService.checkPin(pinId);
        if (result.isPresent()) {
            return ResponseEntity.ok(result.get());
        }
        return ResponseEntity.accepted().body(Map.of("status", "pending"));
    }
}
```

- [ ] **Step 7: Run auth controller tests — expect PASS**

```bash
cd backend && ./gradlew test --tests "com.plexdownloader.controller.AuthControllerTest"
```

Expected: 3 tests pass.

- [ ] **Step 8: Run full suite**

```bash
cd backend && ./gradlew test
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/plexdownloader/{dto,service/PlexPinClient.java,service/AuthService.java,controller/AuthController.java}
git add backend/src/test/java/com/plexdownloader/controller/AuthControllerTest.java
git commit -m "feat: add Plex OAuth pin flow and auth controller"
```

---

**Plan 1a complete.** Backend foundation is done: scaffold, full DB schema, all JPA entities + repos, JWT, Plex OAuth.

**Next:** Plan 1b — Settings service, Plex library sync, Movies API, TV Shows API.
