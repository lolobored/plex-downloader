-- liquibase formatted sql

-- changeset plexdownloader:002-users-plex-token
ALTER TABLE users ADD COLUMN plex_token TEXT;

-- changeset plexdownloader:002-movies-extra-fields
ALTER TABLE movies ADD COLUMN tmdb_id BIGINT;
ALTER TABLE movies ADD COLUMN imdb_id VARCHAR(50);
ALTER TABLE movies ADD COLUMN rating FLOAT;
ALTER TABLE movies ADD COLUMN studio VARCHAR(255);

-- changeset plexdownloader:002-movie-directors
CREATE TABLE movie_directors (
    movie_id BIGINT NOT NULL REFERENCES movies(id) ON DELETE CASCADE,
    director VARCHAR(255) NOT NULL
);

-- changeset plexdownloader:002-tvshows-extra-fields
ALTER TABLE tv_shows ADD COLUMN tmdb_id BIGINT;
ALTER TABLE tv_shows ADD COLUMN tvdb_id BIGINT;
ALTER TABLE tv_shows ADD COLUMN rating FLOAT;
ALTER TABLE tv_shows ADD COLUMN total_seasons INT;

-- changeset plexdownloader:002-download-queue-paths
ALTER TABLE download_queue ADD COLUMN source_file_path TEXT;
ALTER TABLE download_queue ADD COLUMN dest_file_path TEXT;
