# Plex Downloader — Design Spec

**Date:** 2026-05-23  
**Stack:** Spring Boot · Vue 3 · PostgreSQL · Docker Compose  
**Deployment:** Synology NAS (Docker Compose on-device)

---

## 1. Purpose

Web app that browses a Plex library and queues selected content (movies, TV show episodes, seasons, or whole shows) for local file copy into a Tdarr conversion folder. Multi-user, authenticated via Plex OAuth.

---

## 2. Architecture

```
Browser → Vue 3 (Nginx, :80)
             ↕ REST/JSON /api/*
         Spring Boot (:8080)
           ├── Plex API client    → Plex Media Server (URL from settings table)
           ├── Plex Sync Job      → scheduled, caches library to PostgreSQL
           ├── Download Service   → Java NIO file copy: source volume → /conversion
           └── Auth Service       → Plex OAuth pin flow → JWT
         PostgreSQL (:5432)
           └── users · settings · movies · tv_shows · episodes · actors · download_queue

Volume mounts (docker-compose):
  ${MOVIES_PATH}    → /movies     (read-only)
  ${TVSHOWS_PATH}   → /tvshows    (read-only)
  ${CONVERSION_PATH}→ /conversion (read-write, watched by Tdarr)
```

Plex Media Server runs independently on the same Synology. The app connects to it via configurable URL (default `http://localhost:32400`).

---

## 3. Data Model

All DB migrations managed by **Liquibase** (`src/main/resources/db/changelog/`).

### Tables

**`users`**
- `id`, `plex_account_id` (unique), `username`, `avatar_url`, `role` (ADMIN | USER), `last_login_at`, `created_at`
- First user to authenticate is automatically assigned ADMIN role.

**`settings`**
- `key` (PK), `value` — stores: `plex_url`, `plex_token`, `sync_interval_minutes`

**`movies`**
- `id`, `plex_id` (unique), `title`, `year`, `summary`, `poster_url`, `file_path`, `duration_ms`, `synced_at`

**`movie_genres`** — join: `movie_id`, `genre`

**`tv_shows`**
- `id`, `plex_id` (unique), `title`, `year`, `summary`, `poster_url`, `synced_at`

**`show_genres`** — join: `show_id`, `genre`

**`seasons`**
- `id`, `plex_id` (unique), `show_id` (FK), `season_number`, `title`, `poster_url`, `episode_count`, `synced_at`

**`episodes`**
- `id`, `plex_id` (unique), `season_id` (FK), `episode_number`, `title`, `summary`, `thumbnail_url`, `file_path`, `duration_ms`, `air_date`, `director`, `writer`, `video_resolution`, `synced_at`

**`actors`**
- `id`, `plex_id` (unique), `name`, `photo_url`

**`movie_actors`** — join: `movie_id`, `actor_id`, `role`

**`show_actors`** — join: `show_id`, `actor_id`, `role`

**`download_queue`**
- `id`, `user_id` (FK), `media_type` (MOVIE | EPISODE), `media_id`, `status` (PENDING | IN_PROGRESS | DONE | ERROR), `queue_position`, `error_message`, `requested_at`, `completed_at`

---

## 4. Backend (Spring Boot)

### Package structure

```
com.plexdownloader/
  config/        # SecurityConfig, PlexClientConfig, LiquibaseConfig
  controller/    # AuthController, MoviesController, TvShowsController,
                 # QueueController, SettingsController
  service/       # AuthService, PlexSyncService, DownloadService, QueueService
  repository/    # JPA repos per entity
  model/         # JPA entities
  dto/           # request/response objects
  scheduler/     # PlexSyncScheduler (@Scheduled, interval from settings)
```

### Auth flow

1. Frontend calls `POST /api/auth/plex/pin` → backend requests pin from `plex.tv/pins`
2. Frontend redirects user to `app.plex.tv/auth?code=<pin>`
3. Frontend polls `GET /api/auth/plex/pin/{pinId}` until token received
4. Backend validates token against `plex.tv/users/account`
5. Backend creates/updates user in DB, issues JWT
6. All subsequent requests use `Authorization: Bearer <jwt>`

### Key services

**PlexSyncService** — fetches `/library/sections` from Plex, upserts movies/shows/seasons/episodes/actors into DB. Runs on schedule (configurable) and on-demand via settings page.

**DownloadService** — resolves file path from DB, uses `java.nio.file.Files.copy()` to copy from `/movies` or `/tvshows` volume to `/conversion`. Updates queue status during copy.

**QueueService** — sequential processing (one copy at a time), updates `queue_position` on enqueue/dequeue. Frontend polls `GET /api/queue` every 2 seconds on the queue page for live status.

### REST API surface

```
POST   /api/auth/plex/pin
GET    /api/auth/plex/pin/{pinId}

GET    /api/movies?search=&genre=&year=&status=&page=&size=
GET    /api/movies/{id}

GET    /api/tv?search=&genre=&year=&page=&size=
GET    /api/tv/{showId}
GET    /api/tv/{showId}/seasons/{seasonId}
GET    /api/tv/{showId}/seasons/{seasonId}/episodes/{episodeId}

POST   /api/queue          { mediaType, mediaId }  # enqueue one item
POST   /api/queue/season   { seasonId }            # enqueue all episodes in season
POST   /api/queue/show     { showId }              # enqueue all episodes in show
GET    /api/queue          # all items (all users visible to all)
DELETE /api/queue/{id}     # cancel (own item or admin)

GET    /api/settings                    # admin only
PUT    /api/settings                    # admin only
POST   /api/settings/sync              # trigger manual sync
GET    /api/settings/users             # admin only
DELETE /api/settings/users/{userId}    # admin only
```

---

## 5. Frontend (Vue 3)

**Build tool:** Vite  
**Routing:** Vue Router  
**HTTP:** Axios  
**State:** Pinia

### Routes

```
/login                                  # Plex OAuth entry point
/movies                                 # Movie grid + search/filter
/movies/:id                             # Movie detail (full page)
/tv                                     # TV show grid + search/filter
/tv/:showId                             # Show detail (poster, synopsis, seasons grid, cast)
/tv/:showId/seasons/:seasonId           # Season detail (episode grid)
/tv/:showId/seasons/:seasonId/episodes/:episodeId  # Episode detail
/queue                                  # Download queue (all users)
/settings                               # Admin only
```

### UI decisions

- **Top navigation bar:** PlexDL logo · Movies · TV Shows · Queue (badge) · Settings · User avatar
- **Library grids:** poster cards, 6 columns (movies), title + season count below (TV shows). Search + genre/year/status filters.
- **Movie detail:** full page — poster left, title/year/genres/synopsis right, download button (shows file format + size), cast row
- **TV show detail:** full page — show hero section (poster + synopsis + cast), seasons grid (poster per season + episode count)
- **Season detail:** full page — season poster + info, episodes as landscape thumbnail grid (3 columns), download whole season button
- **Episode detail:** full page — thumbnail + title/air date/duration/director/video info/synopsis, cast row, download episode button
- **Download states on cards/buttons:** `⬇ Download` (amber), `⏳ In Queue` (blue), `✓ Done` (green)
- **Queue page:** three sections — In Progress (with copy progress bar + ETA), Pending (queue position + requester name), Completed history
- **Settings page (admin):** Plex URL + token (with test button + connection status), sync interval dropdown + manual sync button, volume paths (read-only display), user list with remove

---

## 6. Docker Compose

```yaml
services:
  frontend:
    build: ./frontend
    ports: ["80:80"]
    depends_on: [backend]

  backend:
    build: ./backend
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/plexdownloader
      SPRING_DATASOURCE_USERNAME: plexdl
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
    volumes:
      - ${MOVIES_PATH}:/movies:ro
      - ${TVSHOWS_PATH}:/tvshows:ro
      - ${CONVERSION_PATH}:/conversion:rw
    depends_on: [db]

  db:
    image: postgres:16
    environment:
      POSTGRES_DB: plexdownloader
      POSTGRES_USER: plexdl
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
```

**.env file** (user-provided, not committed):
```
MOVIES_PATH=/volume1/media/movies
TVSHOWS_PATH=/volume1/media/tvshows
CONVERSION_PATH=/volume1/conversion
POSTGRES_PASSWORD=changeme
JWT_SECRET=changeme
```

Nginx in frontend container proxies `/api/*` → `http://backend:8080/api/*`.

---

## 7. Out of Scope

- Video conversion (handled by Tdarr watching `/conversion`)
- Content discovery / downloading new content from the internet
- Plex playback within the app
- Mobile app
