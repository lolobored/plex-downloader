# plex-downloader

Self-hosted web app to browse your Plex library, queue media for download, and transcode natively with an embedded ffmpeg + Intel QuickSync (QSV) transcoder — no external service required.

## Features

- Browse Plex movies and TV shows
- Subscribe to Plex playlists — items are automatically queued for transcoding
- Download queue with real-time transcode progress
- Configurable quality profiles (codec, container, quality, resolution, audio)
- Admin settings: Plex server connection, library selection, sync schedules
- Single Docker container — no separate proxy or transcoding service required

## Quick Start

### Prerequisites

- Docker and Docker Compose
- A running Plex Media Server
- An Intel CPU with QuickSync Video (QSV) support and a host `/dev/dri` render device
  - The compose file passes `/dev/dri` through via `devices` and adds the container to the `render` group — no extra setup needed on the host beyond having an Intel GPU with QSV enabled

### 1. Create a `.env` file

```env
POSTGRES_PASSWORD=change_me_strong_password
JWT_SECRET=change_me_minimum_32_characters_long

# Paths on the Docker host
MOVIES_PATH=/your/plex/movies
TV_PATH=/your/plex/tvshows
PLEX_CONVERSION_PATH=/your/conversion/output
```

### 2. Run

```bash
docker compose up -d
```

The app is available at **http://localhost:3615**.

### 3. First login

Navigate to `http://localhost:3615` → click **Login with Plex** → authorize the app. The first user to log in is promoted to admin.

Then go to **Settings** to configure:
- Plex server URL (e.g. `http://192.168.1.10:32400`)
- Which libraries to sync
- Sync schedule (cron expression)
- Transcoding: quality profiles (codec, container, quality, resolution, audio) and max concurrent transcodes

## Configuration

All runtime configuration is stored in the database via the Settings page. The following environment variables are required at container startup:

| Variable | Description |
|---|---|
| `POSTGRES_PASSWORD` | Password for the PostgreSQL `plexdl` user |
| `JWT_SECRET` | Secret for signing JWTs — minimum 32 characters |
| `MOVIES_PATH` | Host path to movies directory (mounted at `/movies`, read-only) |
| `TV_PATH` | Host path to TV shows directory (mounted at `/tv`, read-only) |
| `PLEX_CONVERSION_PATH` | Host path for transcoded output (mounted at `/plex-conversion`, read-write); finished files are written to `libraries/<subdir>/<name>.<ext>` and retrievable from this host path |

## Updating

```bash
docker compose pull
docker compose up -d
```

Or pin a specific version in `docker-compose.yml`:

```yaml
image: lolobored/plex-downloader:2026.2.0
```

## Versioning

Releases follow **CalVer**: `YYYY.QUARTER.PATCH`

| Segment | Example | Meaning |
|---|---|---|
| `YYYY` | `2026` | Calendar year |
| `QUARTER` | `2` | Quarter (1–4) |
| `PATCH` | `0` | Patch within the quarter |

Latest tags: see [Docker Hub](https://hub.docker.com/r/lolobored/plex-downloader/tags) and [GitHub Releases](https://github.com/lolobored/plex-downloader/releases).

## Development

### Prerequisites

- Java 21 (via SDKMAN: `sdk use java 21.0.4-tem`)
- Node 20
- Docker (for the Postgres database)

### Run locally

```bash
# Start database
docker compose up db -d

# Backend (http://localhost:8080)
cd backend
./gradlew bootRun

# Frontend dev server with HMR (http://localhost:5173 → proxies /api to :8080)
cd frontend
npm install
npm run dev
```

### Run tests

```bash
# Backend
cd backend
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem
./gradlew test --no-daemon

# Frontend
cd frontend
npm run test
```

### Build production image locally

```bash
docker build -t plex-downloader:local .
```

## Releasing

1. Decide the next version (e.g. `2026.3.0`)
2. Create and push a tag:
   ```bash
   git tag v2026.3.0
   git push origin v2026.3.0
   ```
3. GitHub Actions builds the image and publishes to Docker Hub automatically.

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  plex-downloader container  (lolobored/plex-downloader)              │
│                                                                      │
│  Spring Boot :8080                                                   │
│  ├── /api/**           REST API (JWT auth)                           │
│  ├── /actuator/health  Health probe                                  │
│  └── /**               Vue 3 SPA (embedded in JAR)                  │
│                                                                      │
│  PostgreSQL (embedded — listens on 127.0.0.1 only, not exposed)      │
│                                                                      │
│  ffmpeg + Intel QuickSync (QSV) transcoder (embedded)                │
│  ├── reads source files directly from /movies or /tv                 │
│  ├── transcodes in-container via /dev/dri (QSV hardware accel.)      │
│  └── writes output to /plex-conversion/libraries/<subdir>/<file>     │
│                                                                      │
│  Volume mounts                                                       │
│  ├── /movies           read-only  ← your Plex movies directory       │
│  ├── /tv               read-only  ← your Plex TV directory           │
│  └── /plex-conversion  read-write ← transcoded output (host-visible) │
│                                                                      │
│  Device passthrough                                                  │
│  └── /dev/dri          Intel GPU — required for QSV acceleration     │
└──────────┬───────────────────────────────────────────────────────────┘
           │
     REST API (Plex token)
           │
           ▼
┌──────────────────────┐
│  Plex Media Server   │
│                      │
│  Reports file paths  │
│  e.g. /movies/...    │
│  plex-downloader     │
│  reads those same    │
│  paths directly      │
│  (no mapping needed) │
└──────────────────────┘
```

### How transcoding works

When an item is queued for transcoding, plex-downloader reads the source file directly from the read-only Plex mount (`/movies` or `/tv`) and transcodes it in-container using ffmpeg with Intel QuickSync Video hardware acceleration via `/dev/dri`. The finished file is written to `/plex-conversion/libraries/<subdir>/<name>.<ext>` and is immediately retrievable from the host at `PLEX_CONVERSION_PATH/libraries/...`.

There is no external transcoding service, no intermediate copy step, and no shared-mount constraint between multiple containers.

Plex reports file paths like `/movies/Inception/Inception.mkv`. plex-downloader mounts the same directory at `/movies` so it can read that file directly without any path translation.

| Host path (`.env`) | Container path | Access | Purpose |
|---|---|---|---|
| `MOVIES_PATH` | `/movies` | read-only | Plex movies source |
| `TV_PATH` | `/tv` | read-only | Plex TV source |
| `PLEX_CONVERSION_PATH` | `/plex-conversion` | read-write | Transcoded output under `libraries/` |

## License

MIT
