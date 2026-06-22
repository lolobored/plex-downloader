# ── Stage 1: build Vue frontend ───────────────────────────────────────────────
FROM node:26-alpine AS frontend-build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

# ── Stage 2: build Spring Boot JAR (with embedded frontend) ──────────────────
FROM eclipse-temurin:25-jdk-alpine AS backend-build
WORKDIR /app
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true
COPY backend/src ./src
# Embed the Vue build so Spring Boot serves it as static content
COPY --from=frontend-build /app/dist ./src/main/resources/static
RUN ./gradlew bootJar --no-daemon -x test -q

# ── Stage 3: runtime image (Debian) with PostgreSQL + ffmpeg + Intel QSV ──────
FROM eclipse-temurin:25-jre-jammy

# PostgreSQL, ffmpeg, VAAPI tools, gosu for privilege drop
# Intel QSV runtime packages (intel-media-va-driver-non-free, libmfx-gen1.2, libvpl2)
# are x86_64-only; install them conditionally so the image builds on arm64 too.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql postgresql-contrib \
        ffmpeg \
        vainfo \
        gosu \
        wget \
    && if [ "$(dpkg --print-architecture)" = "amd64" ]; then \
           apt-get install -y --no-install-recommends \
               intel-media-va-driver-non-free \
               libmfx-gen1.2 libvpl2; \
       fi \
    && rm -rf /var/lib/apt/lists/*

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
