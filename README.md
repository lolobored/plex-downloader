# plex-downloader

Self-hosted web app to browse your Plex library, queue media for download, and automatically transcode with Tdarr.

## Features

- Browse Plex movies and TV shows
- Subscribe to Plex playlists — items are automatically queued for Tdarr conversion
- Download queue with real-time Tdarr transcoding status
- Admin settings: Plex server connection, library selection, sync schedules
- Single Docker container — no separate proxy required

## Quick Start

### Prerequisites

- Docker and Docker Compose
- A running Plex Media Server
- A running [Tdarr](https://home.tdarr.io/) instance (optional — for transcoding)

### 1. Create a `.env` file

```env
POSTGRES_PASSWORD=change_me_strong_password
JWT_SECRET=change_me_minimum_32_characters_long

# Paths on the Docker host
MOVIES_PATH=/your/plex/movies
TVSHOWS_PATH=/your/plex/tvshows
CONVERSION_PATH=/your/conversion/output
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
- Tdarr server URL

## Configuration

All runtime configuration is stored in the database via the Settings page. The following environment variables are required at container startup:

| Variable | Description |
|---|---|
| `POSTGRES_PASSWORD` | Password for the PostgreSQL `plexdl` user |
| `JWT_SECRET` | Secret for signing JWTs — minimum 32 characters |
| `MOVIES_PATH` | Host path to movies directory (read-only mount) |
| `TVSHOWS_PATH` | Host path to TV shows directory (read-only mount) |
| `CONVERSION_PATH` | Host path for Tdarr conversion output (read-write) |

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
┌────────────────────────────────────────────────────────┐
│  Docker container (lolobored/plex-downloader)          │
│                                                        │
│  Spring Boot :8080                                     │
│  ├── /api/**          REST API (JWT auth)              │
│  ├── /actuator/health Health probe                     │
│  └── /**              Vue 3 SPA (embedded in JAR)      │
└────────────────────────────────────────────────────────┘
          │
          │ jdbc
          ▼
┌─────────────────────┐
│  PostgreSQL :5432   │
└─────────────────────┘
```

## License

MIT
