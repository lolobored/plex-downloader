# DevOps Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate frontend + backend into a single Docker container, publish to Docker Hub as `lolobored/plex-downloader`, create GitHub repo with Dependabot and CalVer (`year.quarter.patch`) release automation, and write README.

**Architecture:** Multi-stage root `Dockerfile` builds Vue frontend (Node 20) then copies the `dist/` into Spring Boot's `src/main/resources/static/` before building the fat JAR. A `SpaController` handles Vue Router history-mode routes. Docker Compose drops to two services: `app` + `db`. A GitHub Actions workflow on `v*.*.*` tags builds and pushes `lolobored/plex-downloader:{version}` and `lolobored/plex-downloader:latest` to Docker Hub.

**Tech Stack:** Docker multi-stage build, docker/build-push-action v6, GitHub Actions, Dependabot, Docker Hub, CalVer `2026.2.0`.

---

## File Structure

**New files:**
- `Dockerfile` — root-level multi-stage build (node → jdk → jre)
- `.dockerignore` — root level, keeps build context lean
- `backend/src/main/java/org/lolobored/plexdownloader/controller/SpaController.java` — Vue Router SPA fallback
- `.github/dependabot.yml` — auto dependency PRs for gradle/npm/docker/actions
- `.github/workflows/release.yml` — tag-triggered Docker Hub publish
- `README.md` — project documentation

**Modified files:**
- `docker-compose.yml` — remove `frontend` service, rename `backend` → `app`, expose port `3615:8080`
- `backend/build.gradle` — version `2026.2.0`

**Kept unchanged** (still useful for local dev):
- `backend/Dockerfile`
- `frontend/Dockerfile`

---

### Task 1: Root multi-stage Dockerfile

**Files:**
- Create: `Dockerfile`

- [ ] **Step 1: Create root Dockerfile**

```dockerfile
# ── Stage 1: build Vue frontend ───────────────────────────────────────────────
FROM node:20-alpine AS frontend-build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

# ── Stage 2: build Spring Boot JAR (with embedded frontend) ──────────────────
FROM eclipse-temurin:21-jdk-alpine AS backend-build
WORKDIR /app
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true
COPY backend/src ./src
# Embed the Vue build so Spring Boot serves it as static content
COPY --from=frontend-build /app/dist ./src/main/resources/static
RUN ./gradlew bootJar --no-daemon -x test -q

# ── Stage 3: minimal runtime image ────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Verify Docker build succeeds**

```bash
cd /path/to/plex-downloader
docker build -t plex-downloader:test . 2>&1 | tail -5
```

Expected: `Successfully built ...` and `Successfully tagged plex-downloader:test`.

If Gradle fails with "permission denied on gradlew": the `chmod +x` in the Dockerfile handles it — ensure `backend/gradlew` has the execute bit in the repo (`git update-index --chmod=+x backend/gradlew`).

- [ ] **Step 3: Commit**

```bash
git add Dockerfile
git commit -m "feat: add root multi-stage Dockerfile for single-container build"
```

---

### Task 2: .dockerignore

**Files:**
- Create: `.dockerignore`

- [ ] **Step 1: Create .dockerignore**

```
# Git and CI
.git
.github
.superpowers

# Documentation
docs
*.md
LICENSE

# Local dev data (not needed in image)
volumes
dev-data

# Backend test sources (saves ~10% of build context)
backend/src/test
backend/build
backend/.gradle

# Frontend test and node_modules
frontend/node_modules
frontend/dist
frontend/src/views/__tests__
frontend/src/__tests__

# Individual service Dockerfiles (root one is used)
backend/.sdkmanrc
```

- [ ] **Step 2: Verify build context is smaller**

```bash
docker build --no-cache --progress=plain -t plex-downloader:test . 2>&1 | grep "Sending build context"
```

Expected: build context is kilobytes, not megabytes.

- [ ] **Step 3: Commit**

```bash
git add .dockerignore
git commit -m "chore: add .dockerignore to keep Docker build context lean"
```

---

### Task 3: SPA fallback controller

Spring Boot serves `src/main/resources/static/` as static content by default, but Vue Router uses HTML5 history mode — navigating directly to `/movies/1` returns 404 unless Spring Boot returns `index.html` for unmatched routes.

**Files:**
- Create: `backend/src/main/java/org/lolobored/plexdownloader/controller/SpaController.java`

- [ ] **Step 1: Create SpaController**

```java
// backend/src/main/java/org/lolobored/plexdownloader/controller/SpaController.java
package org.lolobored.plexdownloader.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards all unmatched non-asset routes to index.html so Vue Router's
 * history mode works when users navigate directly to a deep URL.
 *
 * The regex [^\\.]*  matches path segments WITHOUT a dot, so static assets
 * like /app.abc123.js, /favicon.ico are NOT matched (Spring serves them
 * directly from classpath:/static/).
 *
 * More-specific REST endpoints (/api/**, /actuator/**) take precedence
 * because Spring MVC picks the most specific handler first.
 */
@Controller
public class SpaController {

    // Handles single-segment routes: /, /movies, /playlists, /queue, /settings, /login
    @GetMapping(value = {"/{path:[^\\.]*}"})
    public String spaDepth1() {
        return "forward:/index.html";
    }

    // Handles two-segment routes: /movies/123, /playlists/456, /tv/789
    @GetMapping(value = {"/{path1:[^\\.]*}/{path2:[^\\.]*}"})
    public String spaDepth2() {
        return "forward:/index.html";
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew compileJava --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/org/lolobored/plexdownloader/controller/SpaController.java
git commit -m "feat: add SpaController for Vue Router history-mode fallback"
```

---

### Task 4: Update docker-compose.yml

Remove the `frontend` service (nginx now gone) and update `backend` → `app` with the new port mapping.

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Replace docker-compose.yml**

```yaml
services:
  app:
    build:
      context: .
      dockerfile: Dockerfile
    image: lolobored/plex-downloader:latest
    ports:
      - "3615:8080"
    restart: unless-stopped
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/plexdownloader
      SPRING_DATASOURCE_USERNAME: plexdl
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
    volumes:
      - ${MOVIES_PATH:-./dev-data/movies}:/movies:ro
      - ${TVSHOWS_PATH:-./dev-data/tvshows}:/tvshows:ro
      - ${CONVERSION_PATH:-./dev-data/conversion}:/conversion:rw
      - ./volumes/posters:/posters:rw
    extra_hosts:
      - "lolobored.local:192.168.4.100"
    depends_on:
      db:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 60s

  db:
    image: postgres:16
    restart: unless-stopped
    environment:
      POSTGRES_DB: plexdownloader
      POSTGRES_USER: plexdl
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - ./volumes/postgres:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U plexdl -d plexdownloader"]
      interval: 5s
      timeout: 3s
      retries: 10
```

- [ ] **Step 2: Validate config**

```bash
docker compose config 2>&1 | head -20
```

Expected: no errors, prints resolved config.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: update docker-compose to single-container app (remove nginx service)"
```

---

### Task 5: Set initial version to 2026.2.0

**Files:**
- Modify: `backend/build.gradle`

- [ ] **Step 1: Update version in build.gradle**

In `backend/build.gradle`, change:
```groovy
version = '0.0.1-SNAPSHOT'
```
to:
```groovy
version = '2026.2.0'
```

- [ ] **Step 2: Verify**

```bash
cd backend && source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 21.0.4-tem && ./gradlew properties --no-daemon 2>&1 | grep "^version:"
```

Expected: `version: 2026.2.0`

- [ ] **Step 3: Commit**

```bash
git add backend/build.gradle
git commit -m "chore: set initial version to 2026.2.0"
```

---

### Task 6: Create GitHub repository and push

Prerequisites: `gh` CLI installed and authenticated (`gh auth login`).

**Files:** none — git config only

- [ ] **Step 1: Ensure gradlew has execute bit**

```bash
git update-index --chmod=+x backend/gradlew
git status  # should show backend/gradlew as modified if not already executable
```

If modified, commit it:
```bash
git commit -am "chore: ensure gradlew has executable permission"
```

- [ ] **Step 2: Create GitHub repo**

```bash
gh repo create lolobored/plex-downloader \
  --public \
  --description "Self-hosted Plex media downloader and Tdarr conversion manager" \
  --homepage "" \
  --disable-wiki
```

Expected: `✓ Created repository lolobored/plex-downloader on GitHub`

- [ ] **Step 3: Add remote and push**

```bash
git remote add origin https://github.com/lolobored/plex-downloader.git
git branch -M master main
git push -u origin main
```

Expected: all commits pushed to `main`.

- [ ] **Step 4: Add GitHub Actions secrets**

The release workflow needs `DOCKERHUB_TOKEN`. Add it via GitHub web UI or CLI:

```bash
# Generate a Docker Hub access token at https://hub.docker.com/settings/security
# then:
gh secret set DOCKERHUB_TOKEN --body "<your-token-here>"
```

The token needs Read, Write, Delete permissions for the `lolobored/plex-downloader` repository on Docker Hub (create the repository on Docker Hub first if it doesn't exist, or push will create it automatically).

---

### Task 7: Dependabot configuration

**Files:**
- Create: `.github/dependabot.yml`

- [ ] **Step 1: Create .github/dependabot.yml**

```yaml
version: 2
updates:
  # Spring Boot and all Gradle dependencies
  - package-ecosystem: "gradle"
    directory: "/backend"
    schedule:
      interval: "weekly"
      day: "monday"
    labels:
      - "dependencies"
      - "java"
    open-pull-requests-limit: 5

  # Vue, Vite, Vitest, and all npm dependencies
  - package-ecosystem: "npm"
    directory: "/frontend"
    schedule:
      interval: "weekly"
      day: "monday"
    labels:
      - "dependencies"
      - "javascript"
    open-pull-requests-limit: 5

  # Dockerfile base images (node:20-alpine, eclipse-temurin:21-*, postgres:16)
  - package-ecosystem: "docker"
    directory: "/"
    schedule:
      interval: "weekly"
      day: "monday"
    labels:
      - "dependencies"
      - "docker"

  # GitHub Actions versions (actions/checkout, docker/*, softprops/*)
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
      day: "monday"
    labels:
      - "dependencies"
      - "ci"
```

- [ ] **Step 2: Commit and push**

```bash
mkdir -p .github
git add .github/dependabot.yml
git commit -m "ci: add Dependabot for gradle/npm/docker/actions auto-updates"
git push
```

Expected: within 24 hours Dependabot creates PRs for any outdated dependencies.

---

### Task 8: Release GitHub Actions workflow

Triggers on push of tags matching `v2026.2.0`, `v2026.3.0`, `v2026.3.1`, etc. Builds the Docker image and pushes to Docker Hub with both the versioned tag and `latest`. Creates a GitHub Release with auto-generated notes.

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create .github/workflows/release.yml**

```yaml
name: Release

on:
  push:
    tags:
      - 'v[0-9]+.[0-9]+.[0-9]+'

jobs:
  release:
    name: Build & publish Docker image
    runs-on: ubuntu-latest
    permissions:
      contents: write    # needed to create GitHub Releases

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Extract version from tag (strips leading v)
        id: version
        run: echo "VERSION=${GITHUB_REF_NAME#v}" >> $GITHUB_OUTPUT

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to Docker Hub
        uses: docker/login-action@v3
        with:
          username: lolobored
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: |
            lolobored/plex-downloader:${{ steps.version.outputs.VERSION }}
            lolobored/plex-downloader:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ github.ref_name }}
          generate_release_notes: true
          body: |
            ## Docker
            ```
            docker pull lolobored/plex-downloader:${{ steps.version.outputs.VERSION }}
            ```
            See [Docker Hub](https://hub.docker.com/r/lolobored/plex-downloader) for all tags.
```

- [ ] **Step 2: Commit and push**

```bash
mkdir -p .github/workflows
git add .github/workflows/release.yml
git commit -m "ci: add release workflow — build and push to Docker Hub on version tag"
git push
```

- [ ] **Step 3: Create and push first release tag**

```bash
git tag v2026.2.0
git push origin v2026.2.0
```

Expected: GitHub Actions runs the `Release` workflow. Check progress at `https://github.com/lolobored/plex-downloader/actions`. After ~5-10 minutes:
- `lolobored/plex-downloader:2026.2.0` appears on Docker Hub
- `lolobored/plex-downloader:latest` updated
- GitHub Release `v2026.2.0` created at `https://github.com/lolobored/plex-downloader/releases`

---

### Task 9: README.md

**Files:**
- Create: `README.md`

- [ ] **Step 1: Create README.md**

```markdown
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

Or pin a specific version:

```bash
# docker-compose.yml
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
```

- [ ] **Step 2: Commit and push**

```bash
git add README.md
git commit -m "docs: add README with quick start, config reference, and architecture"
git push
```

---

## End-to-End Verification

1. `docker build -t plex-downloader:local .` — image builds successfully
2. `docker compose up -d` — app starts, health check passes (`docker compose ps`)
3. Navigate to `http://localhost:3615` — Vue SPA loads
4. Navigate directly to `http://localhost:3615/movies` — page loads (SPA fallback works)
5. Login with Plex — redirects back correctly
6. Push tag `v2026.2.0` → GitHub Actions builds and pushes to Docker Hub within ~10 min
7. `docker pull lolobored/plex-downloader:2026.2.0` — pulls successfully
