package org.lolobored.plexdownloader.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchedSyncService {

    private final UserRepository userRepo;
    private final UserEpisodeWatchedRepository watchedRepo;
    private final EpisodeRepository episodeRepo;
    private final TvShowRepository showRepo;
    private final SettingsService settings;

    @Transactional
    public void syncShow(Long userId, Long showId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null || user.getPlexToken() == null || user.getPlexToken().isBlank()) {
            log.warn("User {} has no Plex token, skipping watched sync for show {}", userId, showId);
            return;
        }
        TvShow show = showRepo.findById(showId).orElse(null);
        if (show == null) {
            log.warn("Show {} not found, skipping watched sync", showId);
            return;
        }

        String plexUrl = settings.getRequired("plex.server.url");
        AllLeavesResponse resp;
        try {
            resp = fetchAllLeaves(plexUrl, user.getPlexToken(), show.getPlexId());
        } catch (RestClientException e) {
            log.warn("Plex API error for user={} show={}: {}", userId, showId, e.getMessage());
            return;
        }

        if (resp == null || resp.getMediaContainer() == null
                || resp.getMediaContainer().getMetadata() == null) {
            log.warn("Empty allLeaves response for show {}", showId);
            return;
        }

        Instant now = Instant.now();
        for (PlexEpisodeWatchStatus item : resp.getMediaContainer().getMetadata()) {
            if (item.getViewCount() == null || item.getViewCount() == 0) continue;
            episodeRepo.findByPlexId(item.getRatingKey()).ifPresent(episode -> {
                UserEpisodeWatched watched = watchedRepo
                    .findByUserIdAndEpisodeId(userId, episode.getId())
                    .orElseGet(() -> {
                        UserEpisodeWatched w = new UserEpisodeWatched();
                        w.setUser(user);
                        w.setEpisode(episode);
                        return w;
                    });
                if (item.getLastViewedAt() != null) {
                    watched.setWatchedAt(Instant.ofEpochSecond(item.getLastViewedAt()));
                }
                watched.setSyncedAt(now);
                watchedRepo.save(watched);
            });
        }
        log.info("Watched sync complete for user={} show={}", userId, showId);
    }

    public void syncIfStale(Long userId, Long showId) {
        Optional<Instant> lastSync = watchedRepo.findLastSyncAt(userId, showId);
        if (lastSync.isEmpty()
                || lastSync.get().isBefore(Instant.now().minus(1, ChronoUnit.HOURS))) {
            syncShow(userId, showId);
        }
    }

    public Set<Long> getWatchedEpisodeIds(Long userId, Long showId) {
        return watchedRepo.findWatchedEpisodeIds(userId, showId);
    }

    // Protected for mocking in tests
    protected AllLeavesResponse fetchAllLeaves(String plexUrl, String plexToken, String showPlexId) {
        RestClient client = RestClient.builder()
            .baseUrl(plexUrl)
            .defaultHeader("X-Plex-Token", plexToken)
            .defaultHeader("X-Plex-Client-Identifier", "plex-downloader-app")
            .defaultHeader("Accept", "application/json")
            .build();
        return client.get()
            .uri("/library/metadata/{ratingKey}/allLeaves", showPlexId)
            .retrieve()
            .body(AllLeavesResponse.class);
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AllLeavesResponse {
        @JsonProperty("MediaContainer")
        private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Container {
            @JsonProperty("Metadata")
            private List<PlexEpisodeWatchStatus> metadata;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexEpisodeWatchStatus {
        private String ratingKey;
        private Integer viewCount;
        private Long lastViewedAt;
    }
}
