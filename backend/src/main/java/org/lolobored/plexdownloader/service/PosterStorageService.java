package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

@Service
@RequiredArgsConstructor
public class PosterStorageService {

    private final PlexMediaServerClient plexClient;
    private final SettingsService settings;

    public void downloadIfNeeded(String ratingKey, String thumbPath, Long plexUpdatedAtSeconds) {
        Path posterDir = Path.of(settings.get("plex.poster.dir").filter(s -> !s.isBlank()).orElse("/posters"));
        Path dest = posterDir.resolve(ratingKey + ".jpg");

        if (Files.exists(dest) && plexUpdatedAtSeconds != null) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(dest, BasicFileAttributes.class);
                long fileModifiedSeconds = attrs.lastModifiedTime().toInstant().getEpochSecond();
                if (fileModifiedSeconds >= plexUpdatedAtSeconds) {
                    return; // file is up to date
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        plexClient.downloadThumb(thumbPath, dest);
    }

    public String posterUrl(String ratingKey) {
        return "/api/posters/" + ratingKey + ".jpg";
    }
}
