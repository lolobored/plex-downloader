#!/bin/sh
set -e

# ── PostgreSQL bootstrap ───────────────────────────────────────────────────────

# Ensure postgres user owns the data directory (important when volume is mounted from host)
chown -R postgres:postgres "$PGDATA" /run/postgresql

# Initialize data directory on first run
if [ ! -f "$PGDATA/PG_VERSION" ]; then
    echo "[init] Initializing PostgreSQL database..."
    su-exec postgres initdb \
        --username=postgres \
        --encoding=UTF8 \
        --locale=C \
        "$PGDATA"
    echo "[init] PostgreSQL data directory created."
fi

# Start PostgreSQL temporarily to ensure user + database exist
su-exec postgres pg_ctl -D "$PGDATA" \
    -o "-c listen_addresses='127.0.0.1'" start -w

# Idempotent: create user and database if they don't exist yet
su-exec postgres psql -U postgres <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'plexdl') THEN
            CREATE USER plexdl WITH PASSWORD 'postgres';
        END IF;
    END
    \$\$;
    SELECT 'CREATE DATABASE plexdownloader'
        WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'plexdownloader')\gexec
    ALTER DATABASE plexdownloader OWNER TO plexdl;
EOSQL

su-exec postgres pg_ctl -D "$PGDATA" stop -w
echo "[init] PostgreSQL ready."

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
