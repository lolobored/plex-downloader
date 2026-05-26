package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.dto.PlexItem;
import org.lolobored.plexdownloader.service.PlaylistSyncService;
import org.lolobored.plexdownloader.client.dto.PlexItem.PlexTag;
import org.lolobored.plexdownloader.client.dto.PlexLibrary;
import org.lolobored.plexdownloader.client.dto.PlexLibraryPage;
import org.lolobored.plexdownloader.client.dto.PlexRole;
import org.lolobored.plexdownloader.dto.LibraryProgress;
import org.lolobored.plexdownloader.dto.SyncStatusResponse;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibrarySyncService {

    private static final int PAGE_SIZE = 50;
    private static final String AGENT_NONE = "com.plexapp.agents.none";

    private final PlexMediaServerClient plexClient;
    private final PosterStorageService posterStorage;
    private final PlaylistSyncService playlistSyncService;
    private final WatchedSyncService watchedSyncService;
    private final MovieRepository movieRepo;
    private final TvShowRepository showRepo;
    private final SeasonRepository seasonRepo;
    private final EpisodeRepository episodeRepo;
    private final ActorRepository actorRepo;
    private final SettingsService settings;

    public enum SyncState { IDLE, RUNNING, ERROR }

    private final AtomicReference<SyncState> state = new AtomicReference<>(SyncState.IDLE);
    private final AtomicInteger itemsSynced = new AtomicInteger(0);
    private final AtomicInteger totalItems  = new AtomicInteger(0);
    private volatile Instant lastSyncAt;
    private volatile String lastError;

    // Per-library progress — ordered by sync start order
    private final CopyOnWriteArrayList<String> libraryOrder = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, LibraryProgress> libraryProgressMap = new ConcurrentHashMap<>();

    public SyncStatusResponse status() {
        List<LibraryProgress> libs = libraryOrder.stream()
            .map(libraryProgressMap::get).filter(Objects::nonNull).toList();
        return new SyncStatusResponse(state.get().name(), lastSyncAt, itemsSynced.get(), totalItems.get(), libs, lastError);
    }

    public boolean isRunning() {
        return state.get() == SyncState.RUNNING;
    }

    public void syncAll() {
        if (!state.compareAndSet(SyncState.IDLE, SyncState.RUNNING)
                && !state.compareAndSet(SyncState.ERROR, SyncState.RUNNING)) {
            log.info("Sync already running, skipping.");
            return;
        }
        itemsSynced.set(0);
        totalItems.set(0);
        libraryOrder.clear();
        libraryProgressMap.clear();
        lastError = null;

        try {
            String selectedRaw = settings.get("plex.sync.libraries").orElse("").trim();
            Set<String> selectedKeys = selectedRaw.isEmpty()
                ? Set.of()
                : Arrays.stream(selectedRaw.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            List<PlexLibrary> libraries = plexClient.getLibraries().stream()
                .filter(l -> !AGENT_NONE.equals(l.getAgent()))
                .filter(l -> "movie".equals(l.getType()) || "show".equals(l.getType()))
                .filter(l -> selectedKeys.isEmpty() || selectedKeys.contains(l.getKey()))
                .toList();

            // Pre-flight: count total items per library, build progress entries
            for (PlexLibrary lib : libraries) {
                int libTotal = plexClient.getLibraryContents(lib.getKey(), 0).totalSize();
                totalItems.addAndGet(libTotal);
                libraryProgressMap.put(lib.getKey(), new LibraryProgress(lib.getKey(), lib.getTitle(), 0, libTotal, false));
                libraryOrder.add(lib.getKey());
            }
            log.info("Sync starting: {} total items across {} libraries", totalItems.get(), libraries.size());

            Set<String> seenMoviePlexIds = new HashSet<>();
            Set<String> seenShowPlexIds  = new HashSet<>();

            for (PlexLibrary lib : libraries) {
                if ("movie".equals(lib.getType())) {
                    syncMovieLibrary(lib.getKey(), seenMoviePlexIds);
                } else {
                    syncShowLibrary(lib.getKey(), seenShowPlexIds);
                }
                // Mark library done
                libraryProgressMap.computeIfPresent(lib.getKey(), (k, v) ->
                    new LibraryProgress(k, v.title(), v.itemsSynced(), v.totalItems(), true));
            }

            // Prune media no longer present in Plex
            if (!seenMoviePlexIds.isEmpty()) {
                List<Movie> orphanMovies = movieRepo.findByPlexIdNotIn(seenMoviePlexIds);
                if (!orphanMovies.isEmpty()) {
                    log.info("Pruning {} movie(s) no longer in Plex", orphanMovies.size());
                    movieRepo.deleteAll(orphanMovies);
                }
            }
            if (!seenShowPlexIds.isEmpty()) {
                List<TvShow> orphanShows = showRepo.findByPlexIdNotIn(seenShowPlexIds);
                if (!orphanShows.isEmpty()) {
                    log.info("Pruning {} show(s) no longer in Plex", orphanShows.size());
                    showRepo.deleteAll(orphanShows);
                }
            }

            playlistSyncService.syncAll();
            watchedSyncService.syncAll();
            lastSyncAt = Instant.now();
            state.set(SyncState.IDLE);
        } catch (Exception e) {
            lastError = e.getMessage();
            state.set(SyncState.ERROR);
            log.error("Sync failed", e);
        }
    }

    private void syncMovieLibrary(String libraryKey, Set<String> seenPlexIds) {
        int offset = 0;
        while (true) {
            PlexLibraryPage page = plexClient.getLibraryContents(libraryKey, offset);
            for (PlexItem item : page.items()) {
                seenPlexIds.add(item.getRatingKey()); // mark as live in Plex regardless of upsert success
                try {
                    upsertMovie(item);
                    itemsSynced.incrementAndGet();
                    incrementLibraryProgress(libraryKey);
                } catch (Exception e) { log.warn("Skipping movie {}: {}", item.getRatingKey(), e.getMessage()); }
            }
            offset += page.items().size();
            if (offset >= page.totalSize() || page.items().isEmpty()) break;
        }
    }

    private void incrementLibraryProgress(String key) {
        libraryProgressMap.computeIfPresent(key, (k, v) ->
            new LibraryProgress(k, v.title(), v.itemsSynced() + 1, v.totalItems(), false));
    }

    private void upsertMovie(PlexItem listItem) {
        PlexItem detail = plexClient.getItemDetail(listItem.getRatingKey());
        posterStorage.downloadIfNeeded(detail.getRatingKey(), detail.getThumb(), detail.getUpdatedAt());

        Movie movie = movieRepo.findByPlexId(detail.getRatingKey()).orElseGet(Movie::new);
        movie.setPlexId(detail.getRatingKey());
        movie.setTitle(detail.getTitle());
        movie.setYear(detail.getYear());
        movie.setSummary(detail.getSummary());
        movie.setRating(detail.getRating());
        movie.setStudio(detail.getStudio());
        movie.setDurationMs(detail.getDuration());
        movie.setFilePath(detail.firstFilePath());
        movie.setTmdbId(detail.parseTmdbId());
        movie.setImdbId(detail.parseImdbId());
        movie.setPosterUrl(posterStorage.posterUrl(detail.getRatingKey()));
        movie.setGenres(tags(detail.getGenre()));
        movie.setDirectors(tags(detail.getDirector()));
        movie.setActors(upsertActors(detail.getRole()));
        movie.setSyncedAt(Instant.now());
        movieRepo.save(movie);
    }

    private void syncShowLibrary(String libraryKey, Set<String> seenPlexIds) {
        int offset = 0;
        while (true) {
            PlexLibraryPage page = plexClient.getLibraryContents(libraryKey, offset);
            for (PlexItem item : page.items()) {
                seenPlexIds.add(item.getRatingKey()); // mark as live in Plex regardless of upsert success
                try {
                    upsertShow(item);
                    itemsSynced.incrementAndGet();
                    incrementLibraryProgress(libraryKey);
                } catch (Exception e) { log.warn("Skipping show {}: {}", item.getRatingKey(), e.getMessage()); }
            }
            offset += page.items().size();
            if (offset >= page.totalSize() || page.items().isEmpty()) break;
        }
    }

    private void upsertShow(PlexItem listItem) {
        PlexItem detail = plexClient.getItemDetail(listItem.getRatingKey());
        posterStorage.downloadIfNeeded(detail.getRatingKey(), detail.getThumb(), detail.getUpdatedAt());

        TvShow show = showRepo.findByPlexId(detail.getRatingKey()).orElseGet(TvShow::new);
        show.setPlexId(detail.getRatingKey());
        show.setTitle(detail.getTitle());
        show.setYear(detail.getYear());
        show.setSummary(detail.getSummary());
        show.setRating(detail.getRating());
        show.setTmdbId(detail.parseTmdbId());
        show.setTvdbId(detail.parseTvdbId());
        show.setPosterUrl(posterStorage.posterUrl(detail.getRatingKey()));
        show.setGenres(tags(detail.getGenre()));
        show.setActors(upsertActors(detail.getRole()));
        show.setSyncedAt(Instant.now());

        List<PlexItem> seasons = plexClient.getChildren(detail.getRatingKey());
        show.setTotalSeasons(seasons.size());
        show = showRepo.save(show);

        for (PlexItem seasonItem : seasons) {
            upsertSeason(seasonItem, show);
        }
    }

    private void upsertSeason(PlexItem seasonItem, TvShow show) {
        posterStorage.downloadIfNeeded(seasonItem.getRatingKey(), seasonItem.getThumb(), seasonItem.getUpdatedAt());

        Season season = seasonRepo.findByPlexId(seasonItem.getRatingKey()).orElseGet(Season::new);
        season.setPlexId(seasonItem.getRatingKey());
        season.setShow(show);
        season.setSeasonNumber(seasonItem.getIndex() != null ? seasonItem.getIndex() : 0);
        season.setTitle(seasonItem.getTitle());
        season.setEpisodeCount(seasonItem.getLeafCount());
        season.setPosterUrl(posterStorage.posterUrl(seasonItem.getRatingKey()));
        season.setSyncedAt(Instant.now());
        season = seasonRepo.save(season);

        List<PlexItem> episodes = plexClient.getChildren(seasonItem.getRatingKey());
        for (PlexItem ep : episodes) {
            upsertEpisode(ep, season);
        }
    }

    private void upsertEpisode(PlexItem epItem, Season season) {
        PlexItem detail = plexClient.getItemDetail(epItem.getRatingKey());
        Episode episode = episodeRepo.findByPlexId(detail.getRatingKey()).orElseGet(Episode::new);
        episode.setPlexId(detail.getRatingKey());
        episode.setSeason(season);
        episode.setEpisodeNumber(detail.getIndex() != null ? detail.getIndex() : 0);
        episode.setTitle(detail.getTitle());
        episode.setSummary(detail.getSummary());
        episode.setDurationMs(detail.getDuration());
        episode.setFilePath(detail.firstFilePath());
        episode.setVideoResolution(detail.firstVideoResolution());
        if (detail.getThumb() != null) {
            posterStorage.downloadIfNeeded(detail.getRatingKey(), detail.getThumb(), detail.getUpdatedAt());
        }
        episode.setThumbnailUrl(posterStorage.posterUrl(detail.getRatingKey()));
        if (detail.getAirDate() != null) {
            try { episode.setAirDate(LocalDate.parse(detail.getAirDate())); }
            catch (Exception ignored) {}
        }
        episode.setDirector(firstTag(detail.getDirector()));
        episode.setWriter(firstTag(detail.getWriter()));
        episode.setSyncedAt(Instant.now());
        episodeRepo.save(episode);
    }

    private List<Actor> upsertActors(List<PlexRole> roles) {
        if (roles == null) return List.of();
        List<Actor> result = new ArrayList<>();
        for (PlexRole r : roles) {
            if (r.getTagKey() == null || r.getName() == null) continue;
            Actor actor = actorRepo.findByPlexId(r.getTagKey()).orElseGet(Actor::new);
            actor.setPlexId(r.getTagKey());
            actor.setName(r.getName());
            result.add(actorRepo.save(actor));
        }
        return result;
    }

    private List<String> tags(List<PlexTag> tags) {
        if (tags == null) return List.of();
        return tags.stream().map(PlexTag::getTag).filter(t -> t != null).toList();
    }

    private String firstTag(List<PlexTag> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return tags.get(0).getTag();
    }
}
