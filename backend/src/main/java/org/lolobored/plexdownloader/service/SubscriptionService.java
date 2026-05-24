package org.lolobored.plexdownloader.service;

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
    private final UserEpisodeWatchedRepository watchedRepo;
    private final DownloadQueueRepository queueRepo;
    private final DownloadService downloadService;
    private final EpisodeRepository episodeRepo;
    private final SeasonRepository seasonRepo;
    private final UserRepository userRepo;
    private final TvShowRepository showRepo;

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
            .ifPresent(subscriptionRepo::delete);
    }

    /**
     * Convenience method: replenish only if the user has an active subscription for this show.
     * Avoids controllers having to inject ShowSubscriptionRepository directly.
     */
    @Transactional
    public void replenishIfSubscribed(Long userId, Long showId) {
        subscriptionRepo.findByUserIdAndShowId(userId, showId)
            .ifPresent(this::replenish);
    }

    @Transactional
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
                log.info("Auto-enqueued episode {} for user {} (subscription)", episodeId, userId);
            } catch (Exception e) {
                log.error("Failed to auto-enqueue episode {} for user {}: {}",
                    episodeId, userId, e.getMessage());
            }
        }
    }

    public List<Long> enqueueUnwatched(Long userId, Long showId, int limit) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Set<Long> watchedIds    = watchedRepo.findWatchedEpisodeIds(userId, showId);
        Set<Long> alreadyQueued = queueRepo.findActiveEpisodeIdsForShow(userId, showId);

        List<Long> toEnqueue = nextUnwatchedEpisodeIds(showId, watchedIds, alreadyQueued, limit);
        List<Long> jobIds = new ArrayList<>();
        for (Long episodeId : toEnqueue) {
            jobIds.addAll(downloadService.enqueueEpisode(episodeId, user));
        }
        return jobIds;
    }

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
}
