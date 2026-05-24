#!/bin/sh
set -e

# ── PostgreSQL bootstrap ───────────────────────────────────────────────────────

# Initialize data directory on first run
if [ ! -f "$PGDATA/PG_VERSION" ]; then
    echo "[init] Initializing PostgreSQL database..."
    su-exec postgres initdb \
        --username=postgres \
        --encoding=UTF8 \
        --locale=C \
        "$PGDATA"

    # Temporary start to create user + database
    su-exec postgres pg_ctl -D "$PGDATA" \
        -o "-c listen_addresses='127.0.0.1'" start -w

    su-exec postgres psql -U postgres <<-EOSQL
        CREATE USER plexdl WITH PASSWORD '${POSTGRES_PASSWORD}';
        CREATE DATABASE plexdownloader OWNER plexdl;
EOSQL

    su-exec postgres pg_ctl -D "$PGDATA" stop -w
    echo "[init] PostgreSQL initialized."
fi

# Start PostgreSQL (listen only on loopback — no external exposure)
echo "[boot] Starting PostgreSQL..."
su-exec postgres pg_ctl -D "$PGDATA" \
    -o "-c listen_addresses='127.0.0.1'" start -w

# Wait until accepting connections
until su-exec postgres pg_isready -h 127.0.0.1 -U plexdl -d plexdownloader 2>/dev/null; do
    echo "[boot] Waiting for PostgreSQL..."
    sleep 1
done
echo "[boot] PostgreSQL ready."

# ── Graceful shutdown on SIGTERM / SIGINT ─────────────────────────────────────
_shutdown() {
    echo "[shutdown] Stopping PostgreSQL..."
    su-exec postgres pg_ctl -D "$PGDATA" stop -m fast
    exit 0
}
trap _shutdown TERM INT

# ── Start Spring Boot ──────────────────────────────────────────────────────────
echo "[boot] Starting plex-downloader..."
exec java -jar /app/app.jar
