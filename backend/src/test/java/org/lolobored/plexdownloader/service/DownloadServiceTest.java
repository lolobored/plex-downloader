package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.model.Playlist;
import org.lolobored.plexdownloader.model.Season;
import org.lolobored.plexdownloader.model.TvShow;
import org.lolobored.plexdownloader.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.lolobored.plexdownloader.dto.DownloadQueueItemResponse;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DownloadServiceTest {

    @Mock MovieRepository movieRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock TvShowRepository showRepo;
    @Mock DownloadQueueRepository queueRepo;
    @Mock SettingsService settings;
    @Mock TdarrClient tdarrClient;
    @Mock PlaylistRepository playlistRepo;
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

        when(settings.get("plex.conversion.dir")).thenReturn(Optional.of(tempDir.toString()));

        Movie movie = new Movie();
        movie.setId(1L);
        movie.setPlexId("12345");
        movie.setFilePath("/plex/movies/Some Movie (2024)/movie.mkv");

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
            && "/plex/movies/Some Movie (2024)/movie.mkv".equals(item.getSourceFilePath())
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
        movie.setFilePath("/plex/movies/The Dark Knight (2008)/dark.mkv");

        User user = new User();
        user.setId(1L);

        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(settings.get("plex.conversion.dir")).thenReturn(Optional.of("/conv"));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> {
            DownloadQueueItem i = inv.getArgument(0);
            i.setId(2L);
            return i;
        });

        service.enqueueMovie(1L, user);

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath() != null &&
            item.getDestFilePath().replace('\\', '/').contains("/in-flight/movies/The Dark Knight (2008)/dark.mkv")
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
        ep.setFilePath("/plex/tvshows/Breaking Bad/Season 01/s01e01.mkv");
        ep.setSeason(season);

        User user = new User();
        user.setId(1L);

        when(episodeRepo.findById(1L)).thenReturn(Optional.of(ep));
        when(seasonRepo.findById(10L)).thenReturn(Optional.of(season));
        when(showRepo.findById(100L)).thenReturn(Optional.of(show));
        when(settings.get("plex.conversion.dir")).thenReturn(Optional.of("/conv"));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> {
            DownloadQueueItem i = inv.getArgument(0);
            i.setId(3L);
            return i;
        });

        service.enqueueEpisode(1L, user);

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath() != null &&
            item.getDestFilePath().replace('\\', '/').contains("/in-flight/tvshows/Breaking Bad/Season 01/s01e01.mkv")
        ));
    }

    @Test
    void enqueueMovie_destPathContainsInFlight() {
        Movie movie = new Movie();
        movie.setId(1L);
        movie.setTitle("Inception");
        movie.setFilePath("/movies/Inception (2010)/inception.mkv");

        User user = new User();
        user.setId(1L);

        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(settings.get("plex.conversion.dir")).thenReturn(Optional.of("/conversion"));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> { DownloadQueueItem i = inv.getArgument(0); i.setId(1L); return i; });

        service.enqueueMovie(1L, user);

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath().replace('\\', '/').contains("/in-flight/")
        ));
    }

    @Test
    void cancel_deletesPendingItemAndInFlightFile(@TempDir Path tmp) throws Exception {
        Path inFlightFile = tmp.resolve("film.mkv");
        Files.writeString(inFlightFile, "data");

        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(10L);
        item.setStatus(DownloadQueueItem.Status.PENDING);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        item.setDestFilePath(inFlightFile.toString());
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(10L)).thenReturn(Optional.of(item));

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        service.cancel(10L, caller);

        assertThat(inFlightFile).doesNotExist();
        verify(queueRepo).delete(item);
    }

    @Test
    void cancel_evictsTdarrAndDeletesInFlightWhenProcessing() throws Exception {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(11L);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.PROCESSING);
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(11L)).thenReturn(Optional.of(item));

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        service.cancel(11L, caller);

        verify(tdarrClient).deleteFile("/conversion/in-flight/films/film.mkv");
        verify(queueRepo).delete(item);
    }

    @Test
    void cancel_deletesOutputFileWhenTranscoded(@TempDir Path tmp) throws Exception {
        Path outputFile = tmp.resolve("film.mp4");
        Files.writeString(outputFile, "transcoded");

        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(12L);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.TRANSCODED);
        item.setOutputFilePath(outputFile.toString());
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(12L)).thenReturn(Optional.of(item));

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        service.cancel(12L, caller);

        assertThat(outputFile).doesNotExist();
        verify(tdarrClient).deleteFile(outputFile.toString()); // evict libraries entry
        verify(queueRepo).delete(item);
    }

    @Test
    void cancel_throws409WhenInProgress() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(13L);
        item.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(13L)).thenReturn(Optional.of(item));

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        assertThatThrownBy(() -> service.cancel(13L, caller))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("409");
    }

    @Test
    void cancel_throws404WhenNotFound() {
        when(queueRepo.findById(99L)).thenReturn(Optional.empty());

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        assertThatThrownBy(() -> service.cancel(99L, caller))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    @Test
    void cancel_throws403WhenNotOwnerAndNotAdmin() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(14L);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(14L)).thenReturn(Optional.of(item));

        User otherUser = new User(); otherUser.setId(2L); otherUser.setRole(User.Role.USER);
        assertThatThrownBy(() -> service.cancel(14L, otherUser))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    @Test
    void cancel_adminCanCancelAnyItem() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(15L);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        item.setDestFilePath("/conversion/in-flight/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(15L)).thenReturn(Optional.of(item));

        User admin = new User(); admin.setId(2L); admin.setRole(User.Role.ADMIN);
        service.cancel(15L, admin);

        verify(queueRepo).delete(item);
    }

    @Test
    void cancelAllForShow_cancelsNonInProgressItems(@TempDir Path tmp) throws Exception {
        Path inFlightFile = tmp.resolve("ep.mkv");
        Files.writeString(inFlightFile, "data");

        DownloadQueueItem pending = new DownloadQueueItem();
        pending.setId(20L);
        pending.setStatus(DownloadQueueItem.Status.PENDING);
        pending.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        pending.setDestFilePath(inFlightFile.toString());

        when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(pending));

        int count = service.cancelAllForShow(1L, 10L);

        assertThat(count).isEqualTo(1);
        verify(queueRepo).delete(pending);
        assertThat(inFlightFile).doesNotExist();
    }

    @Test
    void cancelAllForShow_flagsInProgressForDeferredCancel() {
        DownloadQueueItem active = new DownloadQueueItem();
        active.setId(21L);
        active.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        active.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        active.setDestFilePath("/conv/in-flight/ep.mkv");

        when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(active));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.cancelAllForShow(1L, 10L);

        assertThat(count).isEqualTo(1);
        assertThat(active.isCancellationRequested()).isTrue();
        verify(queueRepo).save(active);
        verify(queueRepo, never()).delete(any());
    }

    @Test
    void cancelAllForShow_returnsZeroWhenQueueEmpty() {
        when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of());

        int count = service.cancelAllForShow(1L, 10L);

        assertThat(count).isEqualTo(0);
        verify(queueRepo, never()).delete(any());
    }

    @Test
    void cancelAllForShow_handlesMixedStatuses(@TempDir Path tmp) throws Exception {
        Path inFlightFile = tmp.resolve("ep.mkv");
        Files.writeString(inFlightFile, "data");

        DownloadQueueItem pending = new DownloadQueueItem();
        pending.setId(22L);
        pending.setStatus(DownloadQueueItem.Status.PENDING);
        pending.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        pending.setDestFilePath(inFlightFile.toString());

        DownloadQueueItem active = new DownloadQueueItem();
        active.setId(23L);
        active.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        active.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        active.setDestFilePath("/conv/in-flight/ep2.mkv");

        when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(pending, active));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.cancelAllForShow(1L, 10L);

        assertThat(count).isEqualTo(2);
        verify(queueRepo).delete(pending);         // PENDING cancelled immediately
        assertThat(inFlightFile).doesNotExist();
        assertThat(active.isCancellationRequested()).isTrue(); // IN_PROGRESS deferred
        verify(queueRepo).save(active);
        verify(queueRepo, never()).delete(active);
    }

    @Test
    void executeCopyAsync_cancelsAfterCopyWhenFlagged() throws Exception {
        Path sourceFile = tempDir.resolve("source.mkv");
        Files.writeString(sourceFile, "content");
        Path destFile = tempDir.resolve("out").resolve("out.mkv");

        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(30L);
        item.setSourceFilePath(sourceFile.toString());
        item.setDestFilePath(destFile.toString());
        item.setStatus(DownloadQueueItem.Status.PENDING);
        item.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);

        // fresh re-read returns item with cancellationRequested = true
        DownloadQueueItem freshItem = new DownloadQueueItem();
        freshItem.setId(30L);
        freshItem.setSourceFilePath(sourceFile.toString());
        freshItem.setDestFilePath(destFile.toString());
        freshItem.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        freshItem.setTdarrStatus(DownloadQueueItem.TdarrStatus.NONE);
        freshItem.setCancellationRequested(true);

        when(queueRepo.findById(30L))
            .thenReturn(Optional.of(item))    // first call: load item
            .thenReturn(Optional.of(freshItem)); // second call: re-read after copy
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeCopyAsync(30L);

        // item should be deleted, not saved as DONE
        verify(queueRepo).delete(freshItem);
        verify(queueRepo, never()).save(argThat(i ->
            i instanceof DownloadQueueItem qi && qi.getStatus() == DownloadQueueItem.Status.DONE));
    }

    @Test
    void cancelAllForSeason_cancelsPendingItems() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(1L);
        item.setStatus(DownloadQueueItem.Status.PENDING);

        when(queueRepo.findAllByUserIdAndSeasonId(1L, 100L)).thenReturn(List.of(item));

        int count = service.cancelAllForSeason(1L, 100L);

        assertThat(count).isEqualTo(1);
        verify(queueRepo).findAllByUserIdAndSeasonId(1L, 100L);
    }

    @Test
    void cancelAllForSeason_setsFlag_forInProgressItems() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(2L);
        item.setStatus(DownloadQueueItem.Status.IN_PROGRESS);

        when(queueRepo.findAllByUserIdAndSeasonId(1L, 100L)).thenReturn(List.of(item));

        service.cancelAllForSeason(1L, 100L);

        assertThat(item.isCancellationRequested()).isTrue();
        verify(queueRepo).save(item);
    }

    @Test
    void getQueue_returnsMovieItemWithNullShowAndSeasonId() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(1L);
        item.setMediaType(DownloadQueueItem.MediaType.MOVIE);
        item.setMediaId(5L);
        item.setStatus(DownloadQueueItem.Status.PENDING);

        when(queueRepo.findAllByUserIdOrderByQueuePositionAsc(1L)).thenReturn(List.of(item));
        when(playlistRepo.findAllById(any())).thenReturn(List.of());

        List<DownloadQueueItemResponse> result = service.getQueue(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).showId()).isNull();
        assertThat(result.get(0).seasonId()).isNull();
        assertThat(result.get(0).playlistId()).isNull();
        assertThat(result.get(0).playlistTitle()).isNull();
        assertThat(result.get(0).mediaId()).isEqualTo(5L);
    }

    @Test
    void getQueue_returnsEpisodeItemWithShowAndSeasonId() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(2L);
        item.setMediaType(DownloadQueueItem.MediaType.EPISODE);
        item.setMediaId(99L);
        item.setStatus(DownloadQueueItem.Status.DONE);

        TvShow show = new TvShow(); show.setId(10L); show.setTitle("Breaking Bad");
        Season season = new Season(); season.setId(20L); season.setSeasonNumber(1); season.setShow(show);
        Episode ep = new Episode(); ep.setId(99L); ep.setSeason(season);

        when(queueRepo.findAllByUserIdOrderByQueuePositionAsc(1L)).thenReturn(List.of(item));
        when(episodeRepo.findWithSeasonAndShowByIdIn(Set.of(99L))).thenReturn(List.of(ep));
        when(playlistRepo.findAllById(any())).thenReturn(List.of());

        List<DownloadQueueItemResponse> result = service.getQueue(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).showId()).isEqualTo(10L);
        assertThat(result.get(0).seasonId()).isEqualTo(20L);
        assertThat(result.get(0).showTitle()).isEqualTo("Breaking Bad");
        assertThat(result.get(0).seasonNumber()).isEqualTo(1);
        assertThat(result.get(0).mediaId()).isEqualTo(99L);
    }

    @Test
    void executeCopyAsync_atomicRename_cleansUpTempFile() throws Exception {
        Path sourceFile = tempDir.resolve("source.mkv");
        Files.writeString(sourceFile, "video-content");
        Path destDir = tempDir.resolve("dest");
        Path destFile = destDir.resolve("output.mkv");

        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(5L);
        item.setSourceFilePath(sourceFile.toString());
        item.setDestFilePath(destFile.toString());
        item.setStatus(DownloadQueueItem.Status.PENDING);

        when(queueRepo.findById(5L)).thenReturn(Optional.of(item));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.executeCopyAsync(5L);

        assertThat(destFile).exists();
        assertThat(destDir.resolve("output.mkv.tmp")).doesNotExist();
        assertThat(item.getStatus()).isEqualTo(DownloadQueueItem.Status.DONE);
    }

    @Test
    void enqueueMovie_withPlaylistId_setsPlaylistIdOnItem() throws IOException {
        Path sourceFile = tempDir.resolve("movie.mkv");
        Files.writeString(sourceFile, "fake");

        Movie movie = new Movie();
        movie.setId(1L);
        movie.setTitle("Inception");
        movie.setFilePath(sourceFile.toString());

        when(settings.get("plex.conversion.dir")).thenReturn(Optional.of(tempDir.toString()));
        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        ArgumentCaptor<DownloadQueueItem> captor = ArgumentCaptor.forClass(DownloadQueueItem.class);
        when(queueRepo.save(captor.capture())).thenAnswer(inv -> {
            DownloadQueueItem i = inv.getArgument(0);
            i.setId(100L);
            return i;
        });

        service.enqueueMovie(1L, new User(), 42L);

        assertThat(captor.getValue().getPlaylistId()).isEqualTo(42L);
    }

    @Test
    void enqueueMovie_withoutPlaylistId_setsNullPlaylistId() throws IOException {
        Path sourceFile = tempDir.resolve("movie2.mkv");
        Files.writeString(sourceFile, "fake");

        Movie movie = new Movie();
        movie.setId(2L);
        movie.setTitle("The Matrix");
        movie.setFilePath(sourceFile.toString());

        when(settings.get("plex.conversion.dir")).thenReturn(Optional.of(tempDir.toString()));
        when(movieRepo.findById(2L)).thenReturn(Optional.of(movie));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        ArgumentCaptor<DownloadQueueItem> captor = ArgumentCaptor.forClass(DownloadQueueItem.class);
        when(queueRepo.save(captor.capture())).thenAnswer(inv -> {
            DownloadQueueItem i = inv.getArgument(0);
            i.setId(101L);
            return i;
        });

        service.enqueueMovie(2L, new User());

        assertThat(captor.getValue().getPlaylistId()).isNull();
    }

    @Test
    void getQueue_returnsPlaylistTitleAndShowTitleForEpisode() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(2L);
        item.setMediaType(DownloadQueueItem.MediaType.EPISODE);
        item.setMediaId(99L);
        item.setStatus(DownloadQueueItem.Status.DONE);
        item.setPlaylistId(5L);

        TvShow show = new TvShow(); show.setId(10L); show.setTitle("Breaking Bad");
        Season season = new Season(); season.setId(20L); season.setSeasonNumber(1); season.setShow(show);
        Episode ep = new Episode(); ep.setId(99L); ep.setSeason(season);

        Playlist playlist = new Playlist(); playlist.setId(5L); playlist.setTitle("Action Movies");

        when(queueRepo.findAllByUserIdOrderByQueuePositionAsc(1L)).thenReturn(List.of(item));
        when(episodeRepo.findWithSeasonAndShowByIdIn(Set.of(99L))).thenReturn(List.of(ep));
        when(playlistRepo.findAllById(Set.of(5L))).thenReturn(List.of(playlist));

        List<DownloadQueueItemResponse> result = service.getQueue(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).showId()).isEqualTo(10L);
        assertThat(result.get(0).seasonId()).isEqualTo(20L);
        assertThat(result.get(0).showTitle()).isEqualTo("Breaking Bad");
        assertThat(result.get(0).seasonNumber()).isEqualTo(1);
        assertThat(result.get(0).playlistId()).isEqualTo(5L);
        assertThat(result.get(0).playlistTitle()).isEqualTo("Action Movies");
    }
}
