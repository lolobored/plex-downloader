package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.dto.PlexItem;
import org.lolobored.plexdownloader.client.dto.PlexLibrary;
import org.lolobored.plexdownloader.client.dto.PlexLibraryPage;
import org.lolobored.plexdownloader.model.Actor;
import org.lolobored.plexdownloader.model.DownloadQueueItem;
import org.lolobored.plexdownloader.model.Movie;
import org.lolobored.plexdownloader.model.Season;
import org.lolobored.plexdownloader.model.TvShow;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.repository.DownloadQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LibrarySyncServiceTest {

    @Mock PlexMediaServerClient plexClient;
    @Mock PosterStorageService posterStorage;
    @Mock MovieRepository movieRepo;
    @Mock TvShowRepository showRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock ActorRepository actorRepo;
    @Mock SettingsService settings;
    @Mock PlaylistSyncService playlistSyncService;
    @Mock DownloadQueueRepository queueRepo;
    @InjectMocks LibrarySyncService service;

    @BeforeEach
    void setupSettings() {
        // default: no library filter — sync all
        lenient().when(settings.get("plex.sync.libraries")).thenReturn(Optional.empty());
    }

    @Test
    void syncAllUpsertsSingleMovie() {
        PlexLibrary movieLib = new PlexLibrary();
        movieLib.setKey("1");
        movieLib.setType("movie");
        movieLib.setAgent("tv.plex.agents.movie");

        PlexItem item = new PlexItem();
        item.setRatingKey("12345");
        item.setType("movie");
        item.setTitle("Inception");
        item.setYear(2010);
        item.setThumb("/library/metadata/12345/thumb");
        item.setUpdatedAt(1000L);
        item.setDuration(8880000L);

        PlexItem detail = new PlexItem();
        detail.setRatingKey("12345");
        detail.setType("movie");
        detail.setTitle("Inception");
        detail.setYear(2010);
        detail.setThumb("/library/metadata/12345/thumb");
        detail.setUpdatedAt(1000L);
        detail.setDuration(8880000L);
        detail.setRole(List.of());
        detail.setGenre(List.of());
        detail.setDirector(List.of());

        when(plexClient.getLibraries()).thenReturn(List.of(movieLib));
        when(plexClient.getLibraryContents("1", 0))
            .thenReturn(new PlexLibraryPage(1, List.of(item)));
        when(plexClient.getItemDetail("12345")).thenReturn(detail);
        when(movieRepo.findByPlexId("12345")).thenReturn(Optional.empty());
        when(movieRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(posterStorage.posterUrl("12345")).thenReturn("/api/posters/12345.jpg");

        service.syncAll();

        ArgumentCaptor<Movie> captor = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Inception");
        assertThat(captor.getValue().getYear()).isEqualTo(2010);
    }

    @Test
    void syncAllIgnoresLibraryWithNoneAgent() {
        PlexLibrary badLib = new PlexLibrary();
        badLib.setKey("3");
        badLib.setType("movie");
        badLib.setAgent("com.plexapp.agents.none");

        when(plexClient.getLibraries()).thenReturn(List.of(badLib));

        service.syncAll();

        verify(plexClient, never()).getLibraryContents(any(), anyInt());
    }

    @Test
    void syncAllUpsertsTvShowWithSeasonAndEpisode() {
        PlexLibrary showLib = new PlexLibrary();
        showLib.setKey("2");
        showLib.setType("show");
        showLib.setAgent("tv.plex.agents.series");

        PlexItem showItem = new PlexItem();
        showItem.setRatingKey("100");
        showItem.setType("show");
        showItem.setTitle("Breaking Bad");
        showItem.setYear(2008);
        showItem.setThumb("/library/metadata/100/thumb");
        showItem.setUpdatedAt(1000L);

        PlexItem showDetail = new PlexItem();
        showDetail.setRatingKey("100");
        showDetail.setType("show");
        showDetail.setTitle("Breaking Bad");
        showDetail.setYear(2008);
        showDetail.setThumb("/library/metadata/100/thumb");
        showDetail.setUpdatedAt(1000L);
        showDetail.setRole(List.of());
        showDetail.setGenre(List.of());

        PlexItem seasonItem = new PlexItem();
        seasonItem.setRatingKey("200");
        seasonItem.setType("season");
        seasonItem.setTitle("Season 1");
        seasonItem.setIndex(1);
        seasonItem.setLeafCount(7);
        seasonItem.setThumb("/library/metadata/200/thumb");
        seasonItem.setUpdatedAt(1000L);

        PlexItem episodeItem = new PlexItem();
        episodeItem.setRatingKey("300");
        episodeItem.setType("episode");
        episodeItem.setTitle("Pilot");
        episodeItem.setIndex(1);
        episodeItem.setDuration(3600000L);
        episodeItem.setThumb("/library/metadata/300/thumb");
        episodeItem.setUpdatedAt(1000L);

        when(plexClient.getLibraries()).thenReturn(List.of(showLib));
        when(plexClient.getLibraryContents("2", 0))
            .thenReturn(new PlexLibraryPage(1, List.of(showItem)));
        when(plexClient.getItemDetail("100")).thenReturn(showDetail);
        when(plexClient.getChildren("100")).thenReturn(List.of(seasonItem));
        when(plexClient.getChildren("200")).thenReturn(List.of(episodeItem));
        when(plexClient.getItemDetail("300")).thenReturn(episodeItem);
        when(showRepo.findByPlexId("100")).thenReturn(Optional.empty());
        when(showRepo.save(any())).thenAnswer(inv -> {
            TvShow s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        when(seasonRepo.findByPlexId("200")).thenReturn(Optional.empty());
        when(seasonRepo.save(any())).thenAnswer(inv -> {
            Season s = inv.getArgument(0);
            s.setId(10L);
            return s;
        });
        when(episodeRepo.findByPlexId("300")).thenReturn(Optional.empty());
        when(episodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(posterStorage.posterUrl(any())).thenReturn("/api/posters/x.jpg");

        service.syncAll();

        verify(showRepo).save(argThat(s -> "Breaking Bad".equals(s.getTitle())));
        verify(seasonRepo).save(argThat(s -> s.getSeasonNumber() == 1));
        verify(episodeRepo).save(argThat(e -> "Pilot".equals(e.getTitle())));
    }

    @Test
    void syncAll_filtersToSelectedLibraries() {
        PlexLibrary movies = lib("1", "Movies", "movie", "tv.plex.agents.movie");
        PlexLibrary shows  = lib("2", "TV Shows", "show", "tv.plex.agents.series");

        when(plexClient.getLibraries()).thenReturn(List.of(movies, shows));
        when(settings.get("plex.sync.libraries")).thenReturn(Optional.of("1"));
        when(plexClient.getLibraryContents("1", 0)).thenReturn(new PlexLibraryPage(0, List.of()));

        service.syncAll();

        verify(plexClient, atLeastOnce()).getLibraryContents("1", 0);
        verify(plexClient, never()).getLibraryContents(eq("2"), anyInt());
    }

    @Test
    void syncAll_syncsAllWhenNoLibraryFilterSet() {
        PlexLibrary movies = lib("1", "Movies", "movie", "tv.plex.agents.movie");
        PlexLibrary shows  = lib("2", "TV Shows", "show", "tv.plex.agents.series");

        when(plexClient.getLibraries()).thenReturn(List.of(movies, shows));
        when(settings.get("plex.sync.libraries")).thenReturn(Optional.empty());
        when(plexClient.getLibraryContents(anyString(), eq(0))).thenReturn(new PlexLibraryPage(0, List.of()));

        service.syncAll();

        verify(plexClient, atLeastOnce()).getLibraryContents("1", 0);
        verify(plexClient, atLeastOnce()).getLibraryContents("2", 0);
    }

    @Test
    void syncAll_callsPlaylistSyncAfterLibraries() {
        when(plexClient.getLibraries()).thenReturn(List.of());
        service.syncAll();
        verify(playlistSyncService).syncAll();
    }

    @Test
    void prunedMovie_failsQueuedOrphanItems() {
        PlexLibrary movieLib = new PlexLibrary();
        movieLib.setKey("1");
        movieLib.setType("movie");
        movieLib.setAgent("tv.plex.agents.movie");

        PlexItem live = new PlexItem();
        live.setRatingKey("52365");
        live.setType("movie");
        live.setTitle("Obsession");
        live.setYear(2026);
        live.setThumb("/library/metadata/52365/thumb");
        live.setUpdatedAt(1000L);
        PlexItem liveDetail = new PlexItem();
        liveDetail.setRatingKey("52365");
        liveDetail.setType("movie");
        liveDetail.setTitle("Obsession");
        liveDetail.setYear(2026);
        liveDetail.setThumb("/library/metadata/52365/thumb");
        liveDetail.setUpdatedAt(1000L);
        liveDetail.setRole(List.of());
        liveDetail.setGenre(List.of());
        liveDetail.setDirector(List.of());

        when(plexClient.getLibraries()).thenReturn(List.of(movieLib));
        when(plexClient.getLibraryContents("1", 0)).thenReturn(new PlexLibraryPage(1, List.of(live)));
        when(plexClient.getItemDetail("52365")).thenReturn(liveDetail);
        when(movieRepo.findByPlexId("52365")).thenReturn(Optional.empty());
        when(movieRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(posterStorage.posterUrl("52365")).thenReturn("/api/posters/52365.jpg");

        // The pre-upgrade row Plex no longer lists.
        Movie orphan = new Movie();
        orphan.setId(2166L);
        orphan.setPlexId("old-rating-key");
        when(movieRepo.findByPlexIdNotIn(anySet())).thenReturn(List.of(orphan));

        // A QUEUED queue item still pointing at the orphaned PK.
        DownloadQueueItem stuck = new DownloadQueueItem();
        stuck.setId(713L);
        stuck.setMediaType(DownloadQueueItem.MediaType.MOVIE);
        stuck.setMediaId(2166L);
        stuck.setStatus(DownloadQueueItem.Status.QUEUED);
        when(queueRepo.findByMediaTypeAndStatusAndMediaIdIn(
                eq(DownloadQueueItem.MediaType.MOVIE),
                eq(DownloadQueueItem.Status.QUEUED),
                anySet()))
            .thenReturn(List.of(stuck));
        when(queueRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.syncAll();

        assertThat(stuck.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(stuck.getTranscodeError()).contains("Source media no longer in Plex");
        verify(movieRepo).deleteAll(List.of(orphan));
    }

    @Test
    void prunedShow_failsQueuedEpisodeItems() {
        PlexLibrary showLib = new PlexLibrary();
        showLib.setKey("2");
        showLib.setType("show");
        showLib.setAgent("tv.plex.agents.series");

        PlexItem showItem = new PlexItem();
        showItem.setRatingKey("100");
        showItem.setType("show");
        showItem.setTitle("Breaking Bad");
        showItem.setYear(2008);
        showItem.setThumb("/library/metadata/100/thumb");
        showItem.setUpdatedAt(1000L);

        PlexItem showDetail = new PlexItem();
        showDetail.setRatingKey("100");
        showDetail.setType("show");
        showDetail.setTitle("Breaking Bad");
        showDetail.setYear(2008);
        showDetail.setThumb("/library/metadata/100/thumb");
        showDetail.setUpdatedAt(1000L);
        showDetail.setRole(List.of());
        showDetail.setGenre(List.of());

        PlexItem seasonItem = new PlexItem();
        seasonItem.setRatingKey("200");
        seasonItem.setType("season");
        seasonItem.setTitle("Season 1");
        seasonItem.setIndex(1);
        seasonItem.setLeafCount(7);
        seasonItem.setThumb("/library/metadata/200/thumb");
        seasonItem.setUpdatedAt(1000L);

        PlexItem episodeItem = new PlexItem();
        episodeItem.setRatingKey("300");
        episodeItem.setType("episode");
        episodeItem.setTitle("Pilot");
        episodeItem.setIndex(1);
        episodeItem.setDuration(3600000L);
        episodeItem.setThumb("/library/metadata/300/thumb");
        episodeItem.setUpdatedAt(1000L);

        when(plexClient.getLibraries()).thenReturn(List.of(showLib));
        when(plexClient.getLibraryContents("2", 0))
            .thenReturn(new PlexLibraryPage(1, List.of(showItem)));
        when(plexClient.getItemDetail("100")).thenReturn(showDetail);
        when(plexClient.getChildren("100")).thenReturn(List.of(seasonItem));
        when(plexClient.getChildren("200")).thenReturn(List.of(episodeItem));
        when(plexClient.getItemDetail("300")).thenReturn(episodeItem);
        when(showRepo.findByPlexId("100")).thenReturn(Optional.empty());
        when(showRepo.save(any())).thenAnswer(inv -> {
            TvShow s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        when(seasonRepo.findByPlexId("200")).thenReturn(Optional.empty());
        when(seasonRepo.save(any())).thenAnswer(inv -> {
            Season s = inv.getArgument(0);
            s.setId(10L);
            return s;
        });
        when(episodeRepo.findByPlexId("300")).thenReturn(Optional.empty());
        when(episodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(posterStorage.posterUrl(any())).thenReturn("/api/posters/x.jpg");

        // The pre-upgrade show row Plex no longer lists.
        TvShow orphanShow = new TvShow();
        orphanShow.setId(900L);
        orphanShow.setPlexId("old-show-key");
        when(showRepo.findByPlexIdNotIn(anySet())).thenReturn(List.of(orphanShow));

        // A QUEUED episode queue item still pointing at the orphaned show.
        DownloadQueueItem stuckEp = new DownloadQueueItem();
        stuckEp.setId(801L);
        stuckEp.setMediaType(DownloadQueueItem.MediaType.EPISODE);
        stuckEp.setMediaId(5001L);
        stuckEp.setStatus(DownloadQueueItem.Status.QUEUED);
        when(queueRepo.findQueuedEpisodeItemsForShows(anySet())).thenReturn(List.of(stuckEp));
        when(queueRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.syncAll();

        assertThat(stuckEp.getStatus()).isEqualTo(DownloadQueueItem.Status.ERROR);
        assertThat(stuckEp.getTranscodeError()).contains("no longer in Plex");
        verify(showRepo).deleteAll(List.of(orphanShow));
    }

    private PlexLibrary lib(String key, String title, String type, String agent) {
        PlexLibrary l = new PlexLibrary();
        l.setKey(key); l.setTitle(title); l.setType(type); l.setAgent(agent);
        return l;
    }
}
