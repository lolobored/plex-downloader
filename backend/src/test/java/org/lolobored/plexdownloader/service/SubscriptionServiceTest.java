package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import org.lolobored.plexdownloader.dto.SeasonSubscriptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock ShowSubscriptionRepository subscriptionRepo;
    @Mock SeasonSubscriptionRepository seasonSubRepo;
    @Mock UserEpisodeWatchedRepository watchedRepo;
    @Mock DownloadQueueRepository queueRepo;
    @Mock DownloadService downloadService;
    @Mock EpisodeRepository episodeRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock UserRepository userRepo;
    @Mock TvShowRepository showRepo;
    @InjectMocks SubscriptionService service;

    User user;
    TvShow show;
    Season season;
    Episode ep1, ep2, ep3;

    @BeforeEach
    void setup() {
        user = new User(); user.setId(1L);
        show = new TvShow(); show.setId(10L);
        season = new Season(); season.setId(100L); season.setSeasonNumber(1);
        season.setShow(show);

        ep1 = new Episode(); ep1.setId(1L); ep1.setEpisodeNumber(1);
        ep2 = new Episode(); ep2.setId(2L); ep2.setEpisodeNumber(2);
        ep3 = new Episode(); ep3.setId(3L); ep3.setEpisodeNumber(3);
    }

    @Test
    void upsert_createsNewSubscription() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(showRepo.findById(10L)).thenReturn(Optional.of(show));
        when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.empty());
        when(subscriptionRepo.save(any())).thenAnswer(inv -> {
            ShowSubscription s = inv.getArgument(0);
            s.setId(99L);
            return s;
        });

        var resp = service.upsert(1L, 10L, 5);

        assertThat(resp.targetCount()).isEqualTo(5);
        assertThat(resp.showId()).isEqualTo(10L);
    }

    @Test
    void upsert_updatesExistingSubscription() {
        ShowSubscription existing = new ShowSubscription();
        existing.setId(5L); existing.setUser(user); existing.setShow(show);
        existing.setTargetCount(10); existing.setUpdatedAt(Instant.now());

        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(showRepo.findById(10L)).thenReturn(Optional.of(show));
        when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.of(existing));
        when(subscriptionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var resp = service.upsert(1L, 10L, 20);

        assertThat(resp.targetCount()).isEqualTo(20);
    }

    @Test
    void cancel_deletesSubscription() {
        ShowSubscription sub = new ShowSubscription();
        sub.setId(5L);
        when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.of(sub));

        service.cancel(1L, 10L);

        verify(subscriptionRepo).delete(sub);
    }

    @Test
    void cancel_callsCancelAllForShowAfterDeletingSubscription() {
        ShowSubscription sub = new ShowSubscription();
        sub.setId(5L);
        when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.of(sub));
        when(downloadService.cancelAllForShow(1L, 10L)).thenReturn(3);

        service.cancel(1L, 10L);

        InOrder order = inOrder(subscriptionRepo, downloadService);
        order.verify(subscriptionRepo).delete(sub);
        order.verify(downloadService).cancelAllForShow(1L, 10L);
    }

    @Test
    void cancel_doesNotCallCancelWhenNoSubscription() {
        when(subscriptionRepo.findByUserIdAndShowId(1L, 10L)).thenReturn(Optional.empty());

        service.cancel(1L, 10L);

        verify(downloadService, never()).cancelAllForShow(anyLong(), anyLong());
    }

    @Test
    void getQueueCount_returnsItemCount() {
        DownloadQueueItem item1 = new DownloadQueueItem(); item1.setId(1L);
        DownloadQueueItem item2 = new DownloadQueueItem(); item2.setId(2L);
        when(queueRepo.findAllByUserIdAndShowId(1L, 10L)).thenReturn(List.of(item1, item2));

        int count = service.getQueueCount(1L, 10L);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void replenish_enqueuesUpToTarget() {
        ShowSubscription sub = new ShowSubscription();
        sub.setUser(user); sub.setShow(show); sub.setTargetCount(2);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of());
        when(queueRepo.findActiveEpisodeIdsForShow(1L, 10L)).thenReturn(Set.of());
        when(seasonRepo.findByShowIdOrderBySeasonNumber(10L)).thenReturn(List.of(season));
        when(episodeRepo.findBySeasonIdOrderByEpisodeNumber(100L))
            .thenReturn(List.of(ep1, ep2, ep3));
        when(downloadService.enqueueEpisode(anyLong(), any())).thenReturn(List.of(99L));

        service.replenish(sub);

        verify(downloadService, times(2)).enqueueEpisode(anyLong(), eq(user));
        verify(downloadService).enqueueEpisode(1L, user);
        verify(downloadService).enqueueEpisode(2L, user);
    }

    @Test
    void replenish_doesNothingWhenBufferFull() {
        ShowSubscription sub = new ShowSubscription();
        sub.setUser(user); sub.setShow(show); sub.setTargetCount(2);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of());
        when(queueRepo.findActiveEpisodeIdsForShow(1L, 10L)).thenReturn(Set.of(1L, 2L));

        service.replenish(sub);

        verify(downloadService, never()).enqueueEpisode(anyLong(), any());
    }

    @Test
    void replenish_skipsWatchedEpisodes() {
        ShowSubscription sub = new ShowSubscription();
        sub.setUser(user); sub.setShow(show); sub.setTargetCount(1);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(1L));
        when(queueRepo.findActiveEpisodeIdsForShow(1L, 10L)).thenReturn(Set.of());
        when(seasonRepo.findByShowIdOrderBySeasonNumber(10L)).thenReturn(List.of(season));
        when(episodeRepo.findBySeasonIdOrderByEpisodeNumber(100L))
            .thenReturn(List.of(ep1, ep2));
        when(downloadService.enqueueEpisode(anyLong(), any())).thenReturn(List.of(99L));

        service.replenish(sub);

        verify(downloadService).enqueueEpisode(eq(2L), eq(user)); // ep1 is watched, so ep2 is first
        verify(downloadService, never()).enqueueEpisode(eq(1L), any());
    }

    @Test
    void replenish_cancelsWatchedQueuedItems() {
        ShowSubscription sub = new ShowSubscription();
        sub.setUser(user); sub.setShow(show); sub.setTargetCount(2);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(1L));
        when(queueRepo.findActiveEpisodeIdsForShow(1L, 10L)).thenReturn(Set.of(1L, 2L));

        service.replenish(sub);

        // ep1 is watched but still queued → must be cleaned, using the same watched set
        verify(downloadService).cancelWatchedForShow(1L, 10L, Set.of(1L));
    }

    @Test
    void replenish_cleansWatchedEvenWhenBufferOtherwiseFull() {
        ShowSubscription sub = new ShowSubscription();
        sub.setUser(user); sub.setShow(show); sub.setTargetCount(1);

        // one active item, and it is watched → deficit would be 0, but cleanup must still run
        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(1L));
        when(queueRepo.findActiveEpisodeIdsForShow(1L, 10L)).thenReturn(Set.of(1L));
        when(seasonRepo.findByShowIdOrderBySeasonNumber(10L)).thenReturn(List.of(season));
        when(episodeRepo.findBySeasonIdOrderByEpisodeNumber(100L)).thenReturn(List.of(ep1, ep2));
        when(downloadService.enqueueEpisode(anyLong(), any())).thenReturn(List.of(99L));

        service.replenish(sub);

        verify(downloadService).cancelWatchedForShow(1L, 10L, Set.of(1L));
    }

    @Test
    void replenishSeason_cancelsWatchedQueuedItems() {
        Season s = new Season(); s.setId(100L); s.setShow(show);
        SeasonSubscription sub = new SeasonSubscription();
        sub.setUser(user); sub.setSeason(s); sub.setTargetCount(2);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of(1L));
        when(queueRepo.findActiveEpisodeIdsForSeason(1L, 100L)).thenReturn(Set.of(1L, 2L));

        service.replenishSeason(sub);

        verify(downloadService).cancelWatchedForSeason(1L, 100L, Set.of(1L));
    }

    @Test
    void listSubscriptions_returnsUserSubs() {
        ShowSubscription sub = new ShowSubscription();
        sub.setId(1L); sub.setUser(user); sub.setShow(show);
        sub.setTargetCount(10); sub.setUpdatedAt(Instant.now());
        when(subscriptionRepo.findByUserId(1L)).thenReturn(List.of(sub));

        var result = service.listSubscriptions(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).targetCount()).isEqualTo(10);
    }

    @Test
    void upsertSeason_createsNewSeasonSubscription() {
        Season s = new Season(); s.setId(100L); s.setShow(show);
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(seasonRepo.findById(100L)).thenReturn(Optional.of(s));
        when(seasonSubRepo.findByUserIdAndSeasonId(1L, 100L)).thenReturn(Optional.empty());
        when(seasonSubRepo.save(any())).thenAnswer(inv -> {
            SeasonSubscription sub = inv.getArgument(0);
            sub.setId(77L);
            return sub;
        });

        SeasonSubscriptionResponse resp = service.upsertSeason(1L, 100L, 5);

        assertThat(resp.seasonId()).isEqualTo(100L);
        assertThat(resp.showId()).isEqualTo(10L);
        assertThat(resp.targetCount()).isEqualTo(5);
    }

    @Test
    void cancelSeason_deletesSubAndCancelsQueue() {
        SeasonSubscription sub = new SeasonSubscription();
        sub.setId(77L);
        when(seasonSubRepo.findByUserIdAndSeasonId(1L, 100L)).thenReturn(Optional.of(sub));

        service.cancelSeason(1L, 100L);

        verify(seasonSubRepo).delete(sub);
        verify(downloadService).cancelAllForSeason(1L, 100L);
    }

    @Test
    void cancelSeason_doesNothingWhenNoSub() {
        when(seasonSubRepo.findByUserIdAndSeasonId(1L, 100L)).thenReturn(Optional.empty());

        service.cancelSeason(1L, 100L);

        verify(downloadService, never()).cancelAllForSeason(anyLong(), anyLong());
    }

    @Test
    void replenishSeason_enqueuesUpToTarget_withinSeason() {
        Season s = new Season(); s.setId(100L); s.setShow(show);
        SeasonSubscription sub = new SeasonSubscription();
        sub.setUser(user); sub.setSeason(s); sub.setTargetCount(2);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of());
        when(queueRepo.findActiveEpisodeIdsForSeason(1L, 100L)).thenReturn(Set.of());
        when(episodeRepo.findBySeasonIdOrderByEpisodeNumber(100L))
            .thenReturn(List.of(ep1, ep2, ep3));
        when(downloadService.enqueueEpisode(anyLong(), any())).thenReturn(List.of(99L));

        service.replenishSeason(sub);

        verify(downloadService, times(2)).enqueueEpisode(anyLong(), eq(user));
        verify(downloadService).enqueueEpisode(1L, user);
        verify(downloadService).enqueueEpisode(2L, user);
    }

    @Test
    void replenishSeason_doesNothingWhenBufferFull() {
        Season s = new Season(); s.setId(100L); s.setShow(show);
        SeasonSubscription sub = new SeasonSubscription();
        sub.setUser(user); sub.setSeason(s); sub.setTargetCount(2);

        when(watchedRepo.findWatchedEpisodeIds(1L, 10L)).thenReturn(Set.of());
        when(queueRepo.findActiveEpisodeIdsForSeason(1L, 100L)).thenReturn(Set.of(1L, 2L));

        service.replenishSeason(sub);

        verify(downloadService, never()).enqueueEpisode(anyLong(), any());
    }
}
