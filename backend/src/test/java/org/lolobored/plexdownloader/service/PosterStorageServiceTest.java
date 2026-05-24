package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PosterStorageServiceTest {

    @Mock PlexMediaServerClient plexClient;
    @Mock SettingsService settings;
    @InjectMocks PosterStorageService service;

    @TempDir Path tempDir;

    @Test
    void downloadsPosterWhenNotYetOnDisk() {
        when(settings.getRequired("plex.poster.dir")).thenReturn(tempDir.toString());

        service.downloadIfNeeded("12345", "/library/metadata/12345/thumb", 1000L);

        verify(plexClient).downloadThumb(eq("/library/metadata/12345/thumb"), any(Path.class));
    }

    @Test
    void skipsDownloadWhenFileExistsAndSyncedAtIsNewer() throws IOException {
        when(settings.getRequired("plex.poster.dir")).thenReturn(tempDir.toString());
        Path poster = tempDir.resolve("12345.jpg");
        Files.writeString(poster, "fake-image");

        // plexUpdatedAt = 1000, our syncedAt = 2000 (newer) → skip
        service.downloadIfNeeded("12345", "/library/metadata/12345/thumb", 1000L);

        // syncedAt is derived from file last-modified; we set it by writing the file.
        // If file exists and its modified time > plexUpdatedAt, skip download.
        verify(plexClient, never()).downloadThumb(any(), any());
    }
}
