-- liquibase formatted sql

-- changeset plexdownloader:003-user-episode-watched
CREATE TABLE user_episode_watched (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    episode_id  BIGINT NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
    watched_at  TIMESTAMP,
    synced_at   TIMESTAMP NOT NULL,
    CONSTRAINT uq_user_episode UNIQUE (user_id, episode_id)
);
CREATE INDEX idx_uew_user_episode ON user_episode_watched (user_id, episode_id);

-- changeset plexdownloader:003-show-subscriptions
CREATE TABLE show_subscriptions (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    show_id       BIGINT NOT NULL REFERENCES tv_shows(id) ON DELETE CASCADE,
    target_count  INT NOT NULL CHECK (target_count IN (5, 10, 15, 20)),
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_show_sub UNIQUE (user_id, show_id)
);

-- changeset plexdownloader:003-watched-sync-cron-setting
INSERT INTO settings (key, value) VALUES ('watched.sync.cron', '0 */15 * * * *')
ON CONFLICT (key) DO NOTHING;
