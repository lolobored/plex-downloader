# ── Stage 1: build Vue frontend ───────────────────────────────────────────────
FROM node:26-alpine AS frontend-build
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

# ── Stage 3: runtime image (Debian) with PostgreSQL + jellyfin-ffmpeg (Intel QSV) ──
FROM eclipse-temurin:21-jre-jammy

# PostgreSQL + gosu (privilege drop) + diagnostics, then jellyfin-ffmpeg7:
# a self-contained latest-ffmpeg build with oneVPL + the Intel iHD driver bundled —
# the reliable path for Intel QuickSync transcode (distro ffmpeg 4.4 ships legacy
# libmfx/MSDK, which fails on newer Intel Gen graphics: "set display handle -17").
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql postgresql-contrib \
        gosu wget vainfo \
        ca-certificates gnupg curl \
    && install -d /etc/apt/keyrings \
    && curl -fsSL https://repo.jellyfin.org/jellyfin_team.gpg.key \
         | gpg --dearmor -o /etc/apt/keyrings/jellyfin.gpg \
    && ARCH="$(dpkg --print-architecture)" \
    && printf 'Types: deb\nURIs: https://repo.jellyfin.org/ubuntu\nSuites: jammy\nComponents: main\nArchitectures: %s\nSigned-By: /etc/apt/keyrings/jellyfin.gpg\n' "$ARCH" \
         > /etc/apt/sources.list.d/jellyfin.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends jellyfin-ffmpeg7 \
    && if [ "$ARCH" = "amd64" ]; then \
           apt-get install -y --no-install-recommends intel-media-va-driver-non-free; \
       fi \
    && rm -rf /var/lib/apt/lists/*

# Use the jellyfin-ffmpeg binaries (latest ffmpeg + bundled oneVPL/iHD) for transcode.
ENV TRANSCODE_FFMPEG_BIN=/usr/lib/jellyfin-ffmpeg/ffmpeg \
    TRANSCODE_FFPROBE_BIN=/usr/lib/jellyfin-ffmpeg/ffprobe

# On Debian/Ubuntu, PostgreSQL binaries are under a versioned path — add to PATH
ENV PATH="/usr/lib/postgresql/14/bin:${PATH}"

# Postgres data directory — will be populated on first boot
ENV PGDATA=/var/lib/postgresql/data
RUN mkdir -p "$PGDATA" /run/postgresql \
    && chown -R postgres:postgres "$PGDATA" /run/postgresql

WORKDIR /app
COPY --from=backend-build /app/build/libs/*.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

VOLUME ["/var/lib/postgresql/data"]
EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint.sh"]
