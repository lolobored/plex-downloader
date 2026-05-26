package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.dto.SeasonSubscriptionResponse;
import org.lolobored.plexdownloader.dto.SubscriptionResponse;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final ShowSubscriptionRepository subscriptionRepo;
    private final SeasonSubscriptionRepository seasonSubRepo;
    private final UserEpisodeWatchedRepository watchedRepo;
    private final DownloadQueueRepository queueRepo;
    private final DownloadService downloadService;
    private final EpisodeRepository episodeRepo;
    private final SeasonRepository seasonRepo;
    private final UserRepository userRepo;
    private final TvShowRepository showRepo;

    // ── Show subscriptions ────────────────────────────────────────────────────

    public List<SubscriptionResponse> listSubscriptions(Long userId) {
        return subscriptionRepo.findByUserId(userId).stream()
            .map(SubscriptionResponse::from).toList();
    }

    @Transactional
    public SubscriptionResponse upsert(Long userId, Long showId, int targetCount) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        TvShow show = showRepo.findById(showId)
            .orElseThrow(() -> new IllegalArgumentException("Show not found: " + showId));

        ShowSubscription sub = subscriptionRepo.findByUserIdAndShowId(userId, showId)
            .orElseGet(() -> {
                ShowSubscription s = new ShowSubscription();
                s.setUser(user);
                s.setShow(show);
                return s;
            });
        sub.setTargetCount(targetCount);
        sub.setUpdatedAt(Instant.now());
        subscriptionRepo.save(sub);
        return SubscriptionResponse.from(sub);
    }

    @Transactional
    public void cancel(Long userId, Long showId) {
        subscriptionRepo.findByUserIdAndShowId(userId, showId)
            .ifPresent(sub -> {
                subscriptionRepo.delete(sub);
                downloadService.cancelAllForShow(userId, showId);
            });
    }

    public int getQueueCount(Long userId, Long showId) {
        return queueRepo.findAllByUserIdAndShowId(userId, showId).size();
    }

    public void replenishIfSubscribed(Long userId, Long showId) {
        subscriptionRepo.findByUserIdAndShowId(userId, showId)
            .ifPresent(this::replenish);
    }

    public void replenish(ShowSubscription sub) {
        Long userId = sub.getUser().getId();
        Long showId = sub.getShow().getId();

        Set<Long> watchedIds = watchedRepo.findWatchedEpisodeIds(userId, showId);
        Set<Long> activeIds  = queueRepo.findActiveEpisodeIdsForShow(userId, showId);

        long activeUnwatched = activeIds.stream().filter(id -> !watchedIds.contains(id)).count();
        int deficit = sub.getTargetCount() - (int) activeUnwatched;
        if (deficit <= 0) return;

        List<Long> toEnqueue = nextUnwatchedEpisodeIds(showId, watchedIds, activeIds, deficit);
        for (Long episodeId : toEnqueue) {
            try {
                downloadService.enqueueEpisode(episodeId, sub.getUser());
                log.info("Auto-enqueued episode {} for user {} (show subscription)", episodeId, userId);
            } catch (Exception e) {
                log.error("Failed to auto-enqueue episode {} for user {}: {}",
                    episodeId, userId, e.getMessage());
            }
        }
    }

    // ── Season subscriptions ──────────────────────────────────────────────────

    public List<SeasonSubscriptionResponse> listSeasonSubscriptions(Long userId) {
        return seasonSubRepo.findByUserId(userId).stream()
            .map(SeasonSubscriptionResponse::from).toList();
    }

    @Transactional
    public SeasonSubscriptionResponse upsertSeason(Long userId, Long seasonId, int targetCount) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Season season = seasonRepo.findById(seasonId)
            .orElseThrow(() -> new IllegalArgumentException("Season not found: " + seasonId));

        SeasonSubscription sub = seasonSubRepo.findByUserIdAndSeasonId(userId, seasonId)
            .orElseGet(() -> {
                SeasonSubscription s = new SeasonSubscription();
                s.setUser(user);
                s.setSeason(season);
                return s;
            });
        sub.setTargetCount(targetCount);
        sub.setUpdatedAt(Instant.now());
        seasonSubRepo.save(sub);
        return SeasonSubscriptionResponse.from(sub);
    }

    @Transactional
    public void cancelSeason(Long userId, Long seasonId) {
        seasonSubRepo.findByUserIdAndSeasonId(userId, seasonId)
            .ifPresent(sub -> {
                seasonSubRepo.delete(sub);
                downloadService.cancelAllForSeason(userId, seasonId);
            });
    }

    public int getSeasonQueueCount(Long userId, Long seasonId) {
        return queueRepo.findAllByUserIdAndSeasonId(userId, seasonId).size();
    }

    public void replenishIfSubscribedSeason(Long userId, Long seasonId) {
        seasonSubRepo.findByUserIdAndSeasonId(userId, seasonId)
            .ifPresent(this::replenishSeason);
    }

    public void replenishSeason(SeasonSubscription sub) {
        Long userId   = sub.getUser().getId();
        Long seasonId = sub.getSeason().getId();
        Long showId   = sub.getSeason().getShow().getId();

        Set<Long> watchedIds = watchedRepo.findWatchedEpisodeIds(userId, showId);
        Set<Long> activeIds  = queueRepo.findActiveEpisodeIdsForSeason(userId, seasonId);

        long activeUnwatched = activeIds.stream().filter(id -> !watchedIds.contains(id)).count();
        int deficit = sub.getTargetCount() - (int) activeUnwatched;
        if (deficit <= 0) return;

        List<Long> toEnqueue = nextUnwatchedEpisodeIdsForSeason(seasonId, watchedIds, activeIds, deficit);
        for (Long episodeId : toEnqueue) {
            try {
                downloadService.enqueueEpisode(episodeId, sub.getUser());
                log.info("Auto-enqueued episode {} for user {} (season subscription)", episodeId, userId);
            } catch (Exception e) {
                log.error("Failed to auto-enqueue episode {} for user {}: {}",
                    episodeId, userId, e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Long> nextUnwatchedEpisodeIds(Long showId, Set<Long> watchedIds,
                                                Set<Long> skipIds, int limit) {
        List<Long> result = new ArrayList<>();
        for (Season season : seasonRepo.findByShowIdOrderBySeasonNumber(showId)) {
            if (result.size() >= limit) break;
            for (Episode ep : episodeRepo.findBySeasonIdOrderByEpisodeNumber(season.getId())) {
                if (result.size() >= limit) break;
                if (!watchedIds.contains(ep.getId()) && !skipIds.contains(ep.getId())) {
                    result.add(ep.getId());
                }
            }
        }
        return result;
    }

    private List<Long> nextUnwatchedEpisodeIdsForSeason(Long seasonId, Set<Long> watchedIds,
                                                          Set<Long> skipIds, int limit) {
        List<Long> result = new ArrayList<>();
        for (Episode ep : episodeRepo.findBySeasonIdOrderByEpisodeNumber(seasonId)) {
            if (result.size() >= limit) break;
            if (!watchedIds.contains(ep.getId()) && !skipIds.contains(ep.getId())) {
                result.add(ep.getId());
            }
        }
        return result;
    }
}
