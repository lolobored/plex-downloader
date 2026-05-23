package com.plexdownloader.service;

import com.plexdownloader.model.*;
import com.plexdownloader.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {

    @Mock MovieRepository movieRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock TvShowRepository showRepo;
    @Mock DownloadQueueRepository queueRepo;
    @Mock PathMappingService pathMapping;
    @Mock SettingsService settings;
    @Spy @InjectMocks DownloadService service;

    @BeforeEach
    void injectSelf() throws Exception {
        Field selfField = DownloadService.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(service, service);
    }

    @TempDir Path tempDir;

    @Test
    void enqueuesMovieAndReturnsJobId() throws IOException {
        Path sourceFile = tempDir.resolve("movie.mkv");
        Files.writeString(sourceFile, "fake");

        when(settings.getRequired("plex.conversion.dir")).thenReturn(tempDir.toString());
        when(pathMapping.translate("/plex/movies/movie.mkv")).thenReturn(sourceFile.toString());

        Movie movie = new Movie();
        movie.setId(1L);
        movie.setPlexId("12345");
        movie.setFilePath("/plex/movies/movie.mkv");

        User user = new User();
        user.setId(1L);

        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> {
            DownloadQueueItem item = inv.getArgument(0);
            item.setId(99L);
            return item;
        });

        List<Long> jobIds = service.enqueueMovie(1L, user);

        assertThat(jobIds).containsExactly(99L);
        verify(queueRepo).save(argThat(item ->
            item.getMediaType() == DownloadQueueItem.MediaType.MOVIE
            && item.getStatus() == DownloadQueueItem.Status.PENDING
            && "/plex/movies/movie.mkv".equals(item.getSourceFilePath())
        ));
    }

    @Test
    void throwsWhenMovieNotFound() {
        when(movieRepo.findById(99L)).thenReturn(Optional.empty());
        User user = new User();
        user.setId(1L);

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service.enqueueMovie(99L, user)
        );
    }
}
