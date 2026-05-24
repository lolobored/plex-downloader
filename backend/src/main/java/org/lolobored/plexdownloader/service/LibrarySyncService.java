package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.PlexMediaServerClient;
import org.lolobored.plexdownloader.client.dto.PlexItem;
import org.lolobored.plexdownloader.client.dto.PlexItem.PlexTag;
import org.lolobored.plexdownloader.client.dto.PlexLibrary;
import org.lolobored.plexdownloader.client.dto.PlexLibraryPage;
import org.lolobored.plexdownloader.client.dto.PlexRole;
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
import java.util.List;
import java.util.Set;
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

    public SyncStatusResponse status() {
        return new SyncStatusResponse(state.get().name(), lastSyncAt, itemsSynced.get(), totalItems.get(), lastError);
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

            // Pre-flight: count total items across all libraries for progress tracking
            int total = libraries.stream()
                .mapToInt(lib -> plexClient.getLibraryContents(lib.getKey(), 0).totalSize())
                .sum();
            totalItems.set(total);
            log.info("Sync starting: {} total items across {} libraries", total, libraries.size());

            for (PlexLibrary lib : libraries) {
                if ("movie".equals(lib.getType())) {
                    syncMovieLibrary(lib.getKey());
                } else {
                    syncShowLibrary(lib.getKey());
                }
            }
            lastSyncAt = Instant.now();
            state.set(SyncState.IDLE);
        } catch (Exception e) {
            lastError = e.getMessage();
            state.set(SyncState.ERROR);
            log.error("Sync failed", e);
        }
    }

    private void syncMovieLibrary(String libraryKey) {
        int offset = 0;
        while (true) {
            PlexLibraryPage page = plexClient.getLibraryContents(libraryKey, offset);
            for (PlexItem item : page.items()) {
                try { upsertMovie(item); itemsSynced.incrementAndGet(); }
                catch (Exception e) { log.warn("Skipping movie {}: {}", item.getRatingKey(), e.getMessage()); }
            }
            offset += page.items().size();
            if (offset >= page.totalSize() || page.items().isEmpty()) break;
        }
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

    private void syncShowLibrary(String libraryKey) {
        int offset = 0;
        while (true) {
            PlexLibraryPage page = plexClient.getLibraryContents(libraryKey, offset);
            for (PlexItem item : page.items()) {
                try { upsertShow(item); itemsSynced.incrementAndGet(); }
                catch (Exception e) { log.warn("Skipping show {}: {}", item.getRatingKey(), e.getMessage()); }
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
        Episode episode = episodeRepo.findByPlexId(epItem.getRatingKey()).orElseGet(Episode::new);
        episode.setPlexId(epItem.getRatingKey());
        episode.setSeason(season);
        episode.setEpisodeNumber(epItem.getIndex() != null ? epItem.getIndex() : 0);
        episode.setTitle(epItem.getTitle());
        episode.setSummary(epItem.getSummary());
        episode.setDurationMs(epItem.getDuration());
        episode.setFilePath(epItem.firstFilePath());
        episode.setVideoResolution(epItem.firstVideoResolution());
        episode.setThumbnailUrl(posterStorage.posterUrl(epItem.getRatingKey()));
        if (epItem.getAirDate() != null) {
            try { episode.setAirDate(LocalDate.parse(epItem.getAirDate())); }
            catch (Exception ignored) {}
        }
        episode.setDirector(firstTag(epItem.getDirector()));
        episode.setWriter(firstTag(epItem.getWriter()));
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
