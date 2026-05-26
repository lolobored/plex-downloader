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

# ── Stage 3: runtime image with embedded PostgreSQL ───────────────────────────
FROM eclipse-temurin:25-jre-alpine
# Install PostgreSQL and su-exec (for privilege-dropping to the postgres user)
RUN apk add --no-cache postgresql postgresql-contrib su-exec

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
