-- liquibase formatted sql

-- changeset plexdownloader:004-tdarr-status-columns
ALTER TABLE download_queue ADD COLUMN tdarr_status VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE download_queue ADD COLUMN tdarr_error TEXT;

-- changeset plexdownloader:004-tdarr-settings
INSERT INTO settings (key, value) VALUES ('tdarr.server.url', '');
INSERT INTO settings (key, value) VALUES ('tdarr.sync.cron',  '0 */30 * * * *');
