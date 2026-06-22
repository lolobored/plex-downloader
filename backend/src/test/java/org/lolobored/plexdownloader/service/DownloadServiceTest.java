package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.model.Playlist;
import org.lolobored.plexdownloader.model.Season;
import org.lolobored.plexdownloader.model.TvShow;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.transcode.TranscodeRequestedEvent;
import org.lolobored.plexdownloader.transcode.TranscodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.lolobored.plexdownloader.dto.DownloadQueueItemResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock QualityProfileService qualityProfileService;
    @Mock org.springframework.context.ApplicationEventPublisher events;
    @Mock PlaylistRepository playlistRepo;
    @Mock TranscodeService transcodeService;
    @InjectMocks DownloadService service;

    @TempDir Path tempDir;

    private QualityProfile defaultProfile() {
        QualityProfile p = new QualityProfile();
        p.setId(1L); p.setName("Default");
        p.setContainer(QualityProfile.Container.MKV);
        return p;
    }

    @Test
    void enqueueMovie_buildsLibrariesOutputPathWithProfileExtension() {
        Movie movie = new Movie();
        movie.setId(1L); movie.setTitle("The Dark Knight");
        movie.setFilePath("/plex/movies/The Dark Knight (2008)/dark.avi");
        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("output.movies.dir")).thenReturn(Optional.of("/conv/libraries/movies"));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> { DownloadQueueItem i = inv.getArgument(0); i.setId(2L); return i; });

        service.enqueueMovie(1L, new User());

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath().replace('\\','/').contains("/libraries/movies/The Dark Knight (2008)/dark.mkv")
            && item.getStatus() == DownloadQueueItem.Status.QUEUED));
        verify(events).publishEvent(any(TranscodeRequestedEvent.class));
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
    void enqueueMovie_defaultStatusIsQueued() {
        Movie movie = new Movie();
        movie.setId(1L);
        movie.setTitle("Some Movie");
        movie.setFilePath("/plex/movies/Some Movie (2024)/movie.mkv");

        User user = new User();
        user.setId(1L);

        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("output.movies.dir")).thenReturn(Optional.of(tempDir.toString()));
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
            && item.getStatus() == DownloadQueueItem.Status.QUEUED
            && "/plex/movies/Some Movie (2024)/movie.mkv".equals(item.getSourceFilePath())
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
        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("output.tvshows.dir")).thenReturn(Optional.of("/conv/libraries/tvshows"));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> {
            DownloadQueueItem i = inv.getArgument(0);
            i.setId(3L);
            return i;
        });

        service.enqueueEpisode(1L, user);

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath() != null &&
            item.getDestFilePath().replace('\\', '/').contains("/libraries/tvshows/Breaking Bad/Season 01/s01e01.mkv")
        ));
    }

    @Test
    void enqueueMovie_destPathContainsLibraries() {
        Movie movie = new Movie();
        movie.setId(1L);
        movie.setTitle("Inception");
        movie.setFilePath("/movies/Inception (2010)/inception.mkv");

        User user = new User();
        user.setId(1L);

        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("output.movies.dir")).thenReturn(Optional.of("/conversion/libraries/movies"));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> { DownloadQueueItem i = inv.getArgument(0); i.setId(1L); return i; });

        service.enqueueMovie(1L, user);

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath().replace('\\', '/').contains("/libraries/")
        ));
    }

    @Test
    void cancel_deletesOutputAndRow(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("film.mkv");
        Files.writeString(out, "data");
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(10L); item.setStatus(DownloadQueueItem.Status.DONE);
        item.setDestFilePath(out.toString());
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);
        when(queueRepo.findById(10L)).thenReturn(Optional.of(item));

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        service.cancel(10L, caller);

        assertThat(out).doesNotExist();
        verify(queueRepo).delete(item);
    }

    @Test
    void cancel_transcoding_callsTranscodeServiceCancelAndDeletesRow() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(13L);
        item.setStatus(DownloadQueueItem.Status.TRANSCODING);
        item.setDestFilePath(null);
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(13L)).thenReturn(Optional.of(item));

        User caller = new User(); caller.setId(1L); caller.setRole(User.Role.USER);
        service.cancel(13L, caller);

        verify(transcodeService).cancel(13L);
        verify(queueRepo).delete(item);
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
        item.setDestFilePath("/conversion/libraries/films/film.mkv");
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
        item.setDestFilePath("/conversion/libraries/films/film.mkv");
        User owner = new User(); owner.setId(1L); owner.setRole(User.Role.USER);
        item.setUser(owner);

        when(queueRepo.findById(15L)).thenReturn(Optional.of(item));

        User admin = new User(); admin.setId(2L); admin.setRole(User.Role.ADMIN);
        service.cancel(15L, admin);

        verify(queueRepo).delete(item);
    }

    @Test
    void cancelAllForShow_cancelsNonTranscodingItems(@TempDir Path tmp) throws Exception {
        Path outFile = tmp.resolve("ep.mkv");
        Files.writeString(outFile, "data");

        DownloadQueueItem queued = new DownloadQueueItem();
        queued.setId(20L);
        queued.setStatus(DownloadQueueItem.Status.QUEUED);
        queued.setDestFilePath(outFile.toString());

        when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(queued));

        int count = service.cancelAllForShow(1L, 10L);

        assertThat(count).isEqualTo(1);
        verify(queueRepo).delete(queued);
        assertThat(outFile).doesNotExist();
    }

    @Test
    void cancelAllForShow_cancelsInFlightTranscode() {
        DownloadQueueItem active = new DownloadQueueItem();
        active.setId(21L);
        active.setStatus(DownloadQueueItem.Status.TRANSCODING);
        active.setDestFilePath("/conv/libraries/ep.mkv");

        when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(active));

        int count = service.cancelAllForShow(1L, 10L);

        assertThat(count).isEqualTo(1);
        verify(transcodeService).cancel(21L);
        verify(queueRepo).delete(active);
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
        Path outFile = tmp.resolve("ep.mkv");
        Files.writeString(outFile, "data");

        DownloadQueueItem queued = new DownloadQueueItem();
        queued.setId(22L);
        queued.setStatus(DownloadQueueItem.Status.QUEUED);
        queued.setDestFilePath(outFile.toString());

        DownloadQueueItem active = new DownloadQueueItem();
        active.setId(23L);
        active.setStatus(DownloadQueueItem.Status.TRANSCODING);
        active.setDestFilePath("/conv/libraries/ep2.mkv");

        when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(queued, active));

        int count = service.cancelAllForShow(1L, 10L);

        assertThat(count).isEqualTo(2);
        verify(queueRepo).delete(queued);         // QUEUED cancelled immediately
        assertThat(outFile).doesNotExist();
        verify(transcodeService).cancel(23L);     // TRANSCODING killed in-flight
        verify(queueRepo).delete(active);         // then row deleted
    }

    @Test
    void cancelAllForSeason_cancelsPendingItems() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(1L);
        item.setStatus(DownloadQueueItem.Status.QUEUED);

        when(queueRepo.findAllByUserIdAndSeasonId(1L, 100L)).thenReturn(List.of(item));

        int count = service.cancelAllForSeason(1L, 100L);

        assertThat(count).isEqualTo(1);
        verify(queueRepo).findAllByUserIdAndSeasonId(1L, 100L);
    }

    @Test
    void cancelAllForSeason_cancelsInFlightTranscode() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(2L);
        item.setStatus(DownloadQueueItem.Status.TRANSCODING);

        when(queueRepo.findAllByUserIdAndSeasonId(1L, 100L)).thenReturn(List.of(item));

        service.cancelAllForSeason(1L, 100L);

        verify(transcodeService).cancel(2L);
        verify(queueRepo).delete(item);
    }

    @Test
    void getQueue_returnsMovieItemWithNullShowAndSeasonId() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(1L);
        item.setMediaType(DownloadQueueItem.MediaType.MOVIE);
        item.setMediaId(5L);
        item.setStatus(DownloadQueueItem.Status.QUEUED);

        when(queueRepo.findAllByUserIdWithProfileOrderByQueuePositionAsc(1L)).thenReturn(List.of(item));
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

        when(queueRepo.findAllByUserIdWithProfileOrderByQueuePositionAsc(1L)).thenReturn(List.of(item));
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
    void enqueueMovie_withPlaylistId_setsPlaylistIdOnItem() {
        Movie movie = new Movie();
        movie.setId(1L);
        movie.setTitle("Inception");
        movie.setFilePath("/movies/Inception (2010)/inception.mkv");

        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("output.movies.dir")).thenReturn(Optional.of(tempDir.toString()));
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
    void enqueueMovie_withoutPlaylistId_setsNullPlaylistId() {
        Movie movie = new Movie();
        movie.setId(2L);
        movie.setTitle("The Matrix");
        movie.setFilePath("/movies/The Matrix (1999)/matrix.mkv");

        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("output.movies.dir")).thenReturn(Optional.of(tempDir.toString()));
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

        when(queueRepo.findAllByUserIdWithProfileOrderByQueuePositionAsc(1L)).thenReturn(List.of(item));
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

    @Test
    void retry_resetsErrorToQueuedAndRepublishes() {
        DownloadQueueItem item = new DownloadQueueItem();
        item.setId(7L); item.setStatus(DownloadQueueItem.Status.ERROR);
        User owner = new User(); owner.setId(1L); item.setUser(owner);
        when(queueRepo.findById(7L)).thenReturn(Optional.of(item));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User caller = new User(); caller.setId(1L);
        service.retry(7L, caller);

        assertThat(item.getStatus()).isEqualTo(DownloadQueueItem.Status.QUEUED);
        verify(events).publishEvent(any(TranscodeRequestedEvent.class));
    }

    @Test
    void retryAllErrored_resetsAllErrorItemsAndPublishesEvents() {
        User user = new User(); user.setId(1L);

        DownloadQueueItem err1 = new DownloadQueueItem();
        err1.setId(10L); err1.setStatus(DownloadQueueItem.Status.ERROR);
        err1.setErrorMessage("ffmpeg died"); err1.setTranscodeError("stderr"); err1.setProgressPercent(30);
        err1.setUser(user);

        DownloadQueueItem err2 = new DownloadQueueItem();
        err2.setId(11L); err2.setStatus(DownloadQueueItem.Status.ERROR);
        err2.setUser(user);

        when(queueRepo.findByUser_IdAndStatus(1L, DownloadQueueItem.Status.ERROR))
            .thenReturn(List.of(err1, err2));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.retryAllErrored(user);

        assertThat(count).isEqualTo(2);
        assertThat(err1.getStatus()).isEqualTo(DownloadQueueItem.Status.QUEUED);
        assertThat(err1.getErrorMessage()).isNull();
        assertThat(err1.getTranscodeError()).isNull();
        assertThat(err1.getProgressPercent()).isNull();
        assertThat(err2.getStatus()).isEqualTo(DownloadQueueItem.Status.QUEUED);
        verify(events, times(2)).publishEvent(any(TranscodeRequestedEvent.class));
    }

    @Test
    void retryAllErrored_returnsZeroWhenNoErrorItems() {
        User user = new User(); user.setId(1L);
        when(queueRepo.findByUser_IdAndStatus(1L, DownloadQueueItem.Status.ERROR))
            .thenReturn(List.of());

        int count = service.retryAllErrored(user);

        assertThat(count).isEqualTo(0);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void retryAllErrored_doesNotTouchNonErrorItems() {
        User user = new User(); user.setId(1L);

        DownloadQueueItem errorItem = new DownloadQueueItem();
        errorItem.setId(20L); errorItem.setStatus(DownloadQueueItem.Status.ERROR);
        errorItem.setUser(user);

        // findByUser_IdAndStatus only returns ERROR items — non-error items are untouched by design
        when(queueRepo.findByUser_IdAndStatus(1L, DownloadQueueItem.Status.ERROR))
            .thenReturn(List.of(errorItem));
        when(queueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.retryAllErrored(user);

        assertThat(count).isEqualTo(1);
        verify(queueRepo, times(1)).save(errorItem);
    }

    @Test
    void buildItem_usesConfiguredMoviesDirFromSettings() {
        Movie movie = new Movie();
        movie.setId(1L); movie.setTitle("Blade Runner");
        movie.setFilePath("/plex/movies/Blade Runner (1982)/blade.mkv");
        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("output.movies.dir")).thenReturn(Optional.of("/custom/movies"));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> { DownloadQueueItem i = inv.getArgument(0); i.setId(9L); return i; });

        service.enqueueMovie(1L, new User());

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath().replace('\\', '/').startsWith("/custom/movies/")
            && item.getDestFilePath().contains("blade.mkv")
        ));
    }

    @Test
    void buildItem_defaultMoviesDirProducesSamePathAsLegacy() {
        Movie movie = new Movie();
        movie.setId(1L); movie.setTitle("Dunkirk");
        movie.setFilePath("/plex/movies/Dunkirk (2017)/dunkirk.mkv");
        when(movieRepo.findById(1L)).thenReturn(Optional.of(movie));
        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("output.movies.dir")).thenReturn(Optional.empty()); // use default
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> { DownloadQueueItem i = inv.getArgument(0); i.setId(10L); return i; });

        service.enqueueMovie(1L, new User());

        // Default = /plex-conversion/libraries/movies
        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath().replace('\\', '/').startsWith("/plex-conversion/libraries/movies/")
            && item.getDestFilePath().contains("dunkirk.mkv")
        ));
    }

    @Test
    void buildItem_usesConfiguredTvShowsDirFromSettings() {
        TvShow show = new TvShow(); show.setId(100L); show.setTitle("Chernobyl");
        Season season = new Season(); season.setId(10L); season.setSeasonNumber(1); season.setShow(show);
        Episode ep = new Episode(); ep.setId(1L);
        ep.setFilePath("/plex/tv/Chernobyl/Season 01/s01e01.mkv");
        ep.setSeason(season);

        when(episodeRepo.findById(1L)).thenReturn(Optional.of(ep));
        when(seasonRepo.findById(10L)).thenReturn(Optional.of(season));
        when(showRepo.findById(100L)).thenReturn(Optional.of(show));
        when(qualityProfileService.resolveOrDefault(null)).thenReturn(defaultProfile());
        when(settings.get("output.tvshows.dir")).thenReturn(Optional.of("/custom/tv"));
        when(queueRepo.findMaxQueuePosition()).thenReturn(Optional.of(0));
        when(queueRepo.save(any())).thenAnswer(inv -> { DownloadQueueItem i = inv.getArgument(0); i.setId(11L); return i; });

        service.enqueueEpisode(1L, new User());

        verify(queueRepo).save(argThat(item ->
            item.getDestFilePath().replace('\\', '/').startsWith("/custom/tv/")
            && item.getDestFilePath().contains("s01e01.mkv")
        ));
    }
}
