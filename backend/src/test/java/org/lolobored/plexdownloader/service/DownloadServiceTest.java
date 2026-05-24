package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.model.Season;
import org.lolobored.plexdownloader.model.TvShow;
import org.lolobored.plexdownloader.repository.*;
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

    @Test
    void downloadQueueItem_hasDefaultTdarrStatusNone() {
        DownloadQueueItem item = new DownloadQueueItem();
        assertThat(item.getTdarrStatus()).isEqualTo(DownloadQueueItem.TdarrStatus.NONE);
    }

    @Test
    void enqueueMovie_buildsStructuredPath() {
        Movie movie = new Movie();
        movie.setId(1L);
        movie.setTitle("The Dark Knight");
        movie.setFilePath("/plex/movies/dark.mkv");

        User user = new User();
        user.setId(1L);

        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conv");
        when(pathMapping.translate("/plex/movies/dark.mkv")).thenReturn("/mnt/movies/dark.mkv");
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> {
            DownloadQueueItem i = inv.getArgument(0);
            i.setId(2L);
            return i;
        });

        service.enqueueMovie(1L, user);

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath() != null &&
            item.getDestFilePath().replace('\\', '/').contains("movies/the_dark_knight/dark.mkv")
        ));
    }

    @Test
    void enqueueEpisode_buildsStructuredPath() {
        TvShow show = new TvShow();
        show.setId(100L);
        show.setTitle("Breaking Bad");

        Season season = new Season();
        season.setId(10L);
        season.setSeasonNumber(1);
        season.setShow(show);

        Episode ep = new Episode();
        ep.setId(1L);
        ep.setFilePath("/plex/tvshows/bb/s01e01.mkv");
        ep.setSeason(season);

        User user = new User();
        user.setId(1L);

        when(episodeRepo.findById(1L)).thenReturn(Optional.of(ep));
        when(seasonRepo.findById(10L)).thenReturn(Optional.of(season));
        when(showRepo.findById(100L)).thenReturn(Optional.of(show));
        when(settings.getRequired("plex.conversion.dir")).thenReturn("/conv");
        when(pathMapping.translate("/plex/tvshows/bb/s01e01.mkv")).thenReturn("/mnt/tvshows/bb/s01e01.mkv");
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> {
            DownloadQueueItem i = inv.getArgument(0);
            i.setId(3L);
            return i;
        });

        service.enqueueEpisode(1L, user);

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath() != null &&
            item.getDestFilePath().replace('\\', '/').contains("tvshows/breaking_bad/Season 01/s01e01.mkv")
        ));
    }

    @Test
    void executeCopyAsync_atomicRename_cleansUpTempFile() throws Exception {
        Path sourceFile = tempDir.resolve("source.mkv");
        Files.writeString(sourceFile, "video-content");
        Path destDir = tempDir.resolve("dest");
        Path destFile = destDir.resolve("output.mkv");

        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(5L);
        item.setSourceFilePath("/plex/source.mkv");
        item.setDestFilePath(destFile.toString());
        item.setStatus(DownloadQueueItem.Status.PENDING);

        when(queueRepo.findById(5L)).thenReturn(Optional.of(item));
        when(pathMapping.translate("/plex/source.mkv")).thenReturn(sourceFile.toString());
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeCopyAsync(5L);

        assertThat(destFile).exists();
        assertThat(destDir.resolve("output.mkv.tmp")).doesNotExist();
        assertThat(item.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
    }
}
