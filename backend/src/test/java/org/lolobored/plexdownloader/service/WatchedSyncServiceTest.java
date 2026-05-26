package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WatchedSyncServiceTest {

    @Mock UserRepository userRepo;
    @Mock UserEpisodeWatchedRepository watchedRepo;
    @Mock UserMovieWatchedRepository movieWatchedRepo;
    @Mock EpisodeRepository episodeRepo;
    @Mock MovieRepository movieRepo;
    @Mock TvShowRepository showRepo;
    @Mock SettingsService settings;
    @Mock ShowSubscriptionRepository showSubscriptionRepo;
    @Mock SeasonSubscriptionRepository seasonSubRepo;
    @Mock SubscriptionService subscriptionService;
    @Spy @InjectMocks WatchedSyncService service;

    User user;
    TvShow show;

    @BeforeEach
    void setup() {
        user = new User();
        user.setId(1L);
        user.setPlexToken("tok123");

        show = new TvShow();
        show.setId(10L);
        show.setPlexId("plex-show-1");
    }

    @Test
    void syncShow_skipsWhenUserHasNoToken() {
        user.setPlexToken(null);
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));

        service.syncShow(1L, 10L);

        verify(showRepo, never()).findById(any());
        verify(watchedRepo, never()).save(any());
    }

    @Test
    void syncShow_upsertsWatchedEpisodes() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(showRepo.findById(10L)).thenReturn(Optional.of(show));
        when(settings.getRequired("plex.server.url")).thenReturn("http://plex:32400");

        WatchedSyncService.PlexEpisodeWatchStatus item =
            new WatchedSyncService.PlexEpisodeWatchStatus();
        item.setRatingKey("ep-plex-1");
        item.setViewCount(2);
        item.setLastViewedAt(1000000L);

        doReturn(java.util.List.of(item))
            .when(service).fetchAllLeavesPages("http://plex:32400", "tok123", "plex-show-1");

        Episode ep = new Episode();
        ep.setId(5L);
        ep.setPlexId("ep-plex-1");
        when(episodeRepo.findByPlexId("ep-plex-1")).thenReturn(Optional.of(ep));
        when(watchedRepo.findByUserIdAndEpisodeId(1L, 5L)).thenReturn(Optional.empty());
        when(watchedRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncShow(1L, 10L);

        verify(watchedRepo).save(argThat(w ->
            w.getUser().getId().equals(1L) &&
            w.getEpisode().getId().equals(5L) &&
            w.getWatchedAt().equals(Instant.ofEpochSecond(1000000L))
        ));
    }

    @Test
    void syncShow_skipsUnwatchedEpisodes() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(showRepo.findById(10L)).thenReturn(Optional.of(show));
        when(settings.getRequired("plex.server.url")).thenReturn("http://plex:32400");

        WatchedSyncService.PlexEpisodeWatchStatus item =
            new WatchedSyncService.PlexEpisodeWatchStatus();
        item.setRatingKey("ep-plex-2");
        item.setViewCount(0);

        doReturn(java.util.List.of(item))
            .when(service).fetchAllLeavesPages(anyString(), anyString(), anyString());

        service.syncShow(1L, 10L);

        verify(watchedRepo, never()).save(any());
    }

    @Test
    void syncIfStale_callsSyncWhenNeverSynced() {
        when(watchedRepo.findLastSyncAt(1L, 10L)).thenReturn(Optional.empty());
        doNothing().when(service).syncShow(1L, 10L);

        service.syncIfStale(1L, 10L);

        verify(service).syncShow(1L, 10L);
    }

    @Test
    void syncIfStale_doesNotSyncWhenFresh() {
        when(watchedRepo.findLastSyncAt(1L, 10L))
            .thenReturn(Optional.of(Instant.now().minus(30, ChronoUnit.MINUTES)));

        service.syncIfStale(1L, 10L);

        verify(service, never()).syncShow(anyLong(), anyLong());
    }

    @Test
    void getWatchedEpisodeIds_delegatesToRepo() {
        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(5L, 6L));

        Set<Long> result = service.getWatchedEpisodeIds(1L, 10L);

        assertThat(result).containsExactlyInAnyOrder(5L, 6L);
    }

    @Test
    void syncAll_replenishesSeasonSubscriptions() {
        TvShow show = new TvShow(); show.setId(10L);
        Season season = new Season(); season.setId(100L); season.setShow(show);
        User u = new User(); u.setId(1L);

        SeasonSubscription sub = new SeasonSubscription();
        sub.setUser(u);
        sub.setSeason(season);

        when(showSubscriptionRepo.findAllWithUserAndShow()).thenReturn(List.of());
        when(seasonSubRepo.findAllWithUserAndSeason()).thenReturn(List.of(sub));
        doNothing().when(service).syncShow(1L, 10L);

        service.syncAll();

        verify(subscriptionService).replenishSeason(sub);
    }
}
