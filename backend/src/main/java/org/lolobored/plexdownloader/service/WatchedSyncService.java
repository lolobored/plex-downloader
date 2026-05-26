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
    private final UserMovieWatchedRepository movieWatchedRepo;
    private final EpisodeRepository episodeRepo;
    private final MovieRepository movieRepo;
    private final TvShowRepository showRepo;
    private final SettingsService settings;
    private final ShowSubscriptionRepository showSubscriptionRepo;
    private final SubscriptionService subscriptionService;

    /**
     * Sync watched status for all users / all subscribed shows.
     * Called at the end of library sync — no separate cron needed.
     */
    public void syncAll() {
        log.info("Watched sync starting");
        showSubscriptionRepo.findAllWithUserAndShow().forEach(sub -> {
            try {
                syncShow(sub.getUser().getId(), sub.getShow().getId());
                subscriptionService.replenish(sub);
            } catch (Exception e) {
                log.error("Watched sync failed for user={} show={}: {}",
                    sub.getUser().getId(), sub.getShow().getId(), e.getMessage());
            }
        });
        userRepo.findAllByPlexTokenIsNotNull().forEach(user -> {
            try {
                syncMoviesForUser(user.getId());
            } catch (Exception e) {
                log.error("Movie watched sync failed for user={}: {}", user.getId(), e.getMessage());
            }
        });
        log.info("Watched sync complete");
    }

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
        List<PlexEpisodeWatchStatus> allEpisodes;
        try {
            allEpisodes = fetchAllLeavesPages(plexUrl, user.getPlexToken(), show.getPlexId());
        } catch (RestClientException e) {
            log.warn("Plex API error for user={} show={}: {}", userId, showId, e.getMessage());
            return;
        }

        if (allEpisodes.isEmpty()) {
            log.warn("Empty allLeaves response for show {}", showId);
            return;
        }

        Instant now = Instant.now();
        for (PlexEpisodeWatchStatus item : allEpisodes) {
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
        log.info("Watched sync complete for user={} show={} episodes={}", userId, showId, allEpisodes.size());
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

    private static final int LEAVES_PAGE_SIZE = 50;

    // Protected for mocking in tests
    protected List<PlexEpisodeWatchStatus> fetchAllLeavesPages(String plexUrl, String plexToken, String showPlexId) {
        RestClient client = RestClient.builder()
            .baseUrl(plexUrl)
            .defaultHeader("X-Plex-Token", plexToken)
            .defaultHeader("X-Plex-Client-Identifier", "plex-downloader-app")
            .defaultHeader("Accept", "application/json")
            .build();

        List<PlexEpisodeWatchStatus> all = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            final int currentOffset = offset;
            AllLeavesResponse resp = client.get()
                .uri(u -> u.path("/library/metadata/{ratingKey}/allLeaves")
                    .build(showPlexId))
                .header("X-Plex-Container-Start", String.valueOf(currentOffset))
                .header("X-Plex-Container-Size", String.valueOf(LEAVES_PAGE_SIZE))
                .retrieve()
                .body(AllLeavesResponse.class);

            if (resp == null || resp.getMediaContainer() == null
                    || resp.getMediaContainer().getMetadata() == null) break;

            AllLeavesResponse.Container mc = resp.getMediaContainer();
            all.addAll(mc.getMetadata());

            if (offset + mc.getSize() >= mc.getTotalSize()) break;
            offset += LEAVES_PAGE_SIZE;
        }
        return all;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AllLeavesResponse {
        @JsonProperty("MediaContainer")
        private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Container {
            @JsonProperty("Metadata")
            private List<PlexEpisodeWatchStatus> metadata;
            private int totalSize;
            private int size;
            private int offset;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexEpisodeWatchStatus {
        private String ratingKey;
        private Integer viewCount;
        private Long lastViewedAt;
    }

    // ── Movie watched sync ────────────────────────────────────────────────────

    @Transactional
    public void syncMoviesForUser(Long userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null || user.getPlexToken() == null || user.getPlexToken().isBlank()) {
            log.warn("User {} has no Plex token, skipping movie watched sync", userId);
            return;
        }
        String plexUrl = settings.getRequired("plex.server.url");
        RestClient client = RestClient.builder()
            .baseUrl(plexUrl)
            .defaultHeader("X-Plex-Token", user.getPlexToken())
            .defaultHeader("X-Plex-Client-Identifier", "plex-downloader-app")
            .defaultHeader("Accept", "application/json")
            .build();

        // Discover movie library sections
        LibrarySectionsResponse sections;
        try {
            sections = client.get().uri("/library/sections").retrieve().body(LibrarySectionsResponse.class);
        } catch (RestClientException e) {
            log.warn("Could not fetch library sections for user {}: {}", userId, e.getMessage());
            return;
        }
        if (sections == null || sections.getMediaContainer() == null
                || sections.getMediaContainer().getDirectory() == null) return;

        Instant now = Instant.now();
        for (LibrarySectionsResponse.LibrarySection section : sections.getMediaContainer().getDirectory()) {
            if (!"movie".equals(section.getType())) continue;
            MovieLibraryResponse resp;
            try {
                resp = client.get()
                    .uri("/library/sections/{key}/all?type=1", section.getKey())
                    .retrieve()
                    .body(MovieLibraryResponse.class);
            } catch (RestClientException e) {
                log.warn("Could not fetch movies for user={} section={}: {}", userId, section.getKey(), e.getMessage());
                continue;
            }
            if (resp == null || resp.getMediaContainer() == null
                    || resp.getMediaContainer().getMetadata() == null) continue;

            for (PlexMovieWatchStatus item : resp.getMediaContainer().getMetadata()) {
                if (item.getViewCount() == null || item.getViewCount() == 0) continue;
                movieRepo.findByPlexId(item.getRatingKey()).ifPresent(movie -> {
                    UserMovieWatched watched = movieWatchedRepo
                        .findByUserIdAndMovieId(userId, movie.getId())
                        .orElseGet(() -> { var w = new UserMovieWatched(); w.setUser(user); w.setMovie(movie); return w; });
                    if (item.getLastViewedAt() != null) {
                        watched.setWatchedAt(Instant.ofEpochSecond(item.getLastViewedAt()));
                    }
                    watched.setSyncedAt(now);
                    movieWatchedRepo.save(watched);
                });
            }
        }
        log.info("Movie watched sync complete for user={}", userId);
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LibrarySectionsResponse {
        @JsonProperty("MediaContainer") private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Container {
            @JsonProperty("Directory") private List<LibrarySection> directory;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class LibrarySection {
            private String key;
            private String type;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MovieLibraryResponse {
        @JsonProperty("MediaContainer") private Container mediaContainer;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Container {
            @JsonProperty("Metadata") private List<PlexMovieWatchStatus> metadata;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlexMovieWatchStatus {
        private String ratingKey;
        private Integer viewCount;
        private Long lastViewedAt;
    }
}
