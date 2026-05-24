package org.lolobored.plexdownloader.service;

import org.lolobored.plexdownloader.client.TdarrClient;
import org.lolobored.plexdownloader.model.*;
import org.lolobored.plexdownloader.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private DownloadService self;
    private final MovieRepository movieRepo;
    private final EpisodeRepository episodeRepo;
    private final SeasonRepository seasonRepo;
    private final TvShowRepository showRepo;
    private final DownloadQueueRepository queueRepo;
    private final PathMappingService pathMapping;
    private final SettingsService settings;
    private final TdarrClient tdarrClient;

    @Autowired
    @Lazy
    public void setSelf(DownloadService self) {
        this.self = self;
    }

    public List<Long> enqueueMovie(Long movieId, User user) {
        Movie movie = movieRepo.findById(movieId)
            .orElseThrow(() -> new IllegalArgumentException("Movie not found: " + movieId));
        String subDir = "movies/" + slugify(movie.getTitle());
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.MOVIE,
            movieId, movie.getFilePath(), subDir);
        item = queueRepo.save(item);
        self.executeCopyAsync(item.getId());
        return List.of(item.getId());
    }

    public List<Long> enqueueEpisode(Long episodeId, User user) {
        Episode ep = episodeRepo.findById(episodeId)
            .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        Season season = seasonRepo.findById(ep.getSeason().getId())
            .orElseThrow(() -> new IllegalArgumentException("Season not found for episode: " + episodeId));
        TvShow show = showRepo.findById(season.getShow().getId())
            .orElseThrow(() -> new IllegalArgumentException("Show not found for episode: " + episodeId));
        String subDir = "tvshows/" + slugify(show.getTitle()) +
                        "/Season " + String.format("%02d", season.getSeasonNumber());
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
            episodeId, ep.getFilePath(), subDir);
        item = queueRepo.save(item);
        self.executeCopyAsync(item.getId());
        return List.of(item.getId());
    }

    public List<Long> enqueueSeason(Long seasonId, User user) {
        Season season = seasonRepo.findById(seasonId)
            .orElseThrow(() -> new IllegalArgumentException("Season not found: " + seasonId));
        TvShow show = showRepo.findById(season.getShow().getId())
            .orElseThrow(() -> new IllegalArgumentException("Show not found for season: " + seasonId));
        List<Episode> episodes = episodeRepo.findBySeasonIdOrderByEpisodeNumber(seasonId);
        if (episodes.isEmpty()) throw new IllegalArgumentException("Season has no episodes: " + seasonId);
        String subDir = "tvshows/" + slugify(show.getTitle()) +
                        "/Season " + String.format("%02d", season.getSeasonNumber());
        List<Long> ids = new ArrayList<>();
        for (Episode ep : episodes) {
            DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
                ep.getId(), ep.getFilePath(), subDir);
            item = queueRepo.save(item);
            self.executeCopyAsync(item.getId());
            ids.add(item.getId());
        }
        return ids;
    }

    public List<Long> enqueueShow(Long showId, User user) {
        List<Season> seasons = seasonRepo.findByShowIdOrderBySeasonNumber(showId);
        if (seasons.isEmpty()) throw new IllegalArgumentException("Show not found or empty: " + showId);
        List<Long> ids = new ArrayList<>();
        for (Season season : seasons) {
            ids.addAll(enqueueSeason(season.getId(), user));
        }
        return ids;
    }

    public List<DownloadQueueItem> getQueue() {
        return queueRepo.findAllByOrderByQueuePositionAsc();
    }

    static String slugify(String title) {
        if (title == null) return "unknown";
        return title.toLowerCase()
                    .replaceAll("[^a-z0-9\\s]", "")
                    .trim()
                    .replaceAll("\\s+", "_");
    }

    private DownloadQueueItem buildItem(User user, DownloadQueueItem.MediaType type,
                                        Long mediaId, String plexFilePath, String subDir) {
        String appPath = pathMapping.translate(plexFilePath);
        String conversionDir = settings.getRequired("plex.conversion.dir");
        String filename = Path.of(appPath).getFileName().toString();
        String destPath = Path.of(conversionDir, "in-flight", subDir, filename).toString();

        int nextPos = queueRepo.findMaxQueuePosition().orElse(0) + 1;

        DownloadQueueItem item = new DownloadQueueItem();
        item.setUser(user);
        item.setMediaType(type);
        item.setMediaId(mediaId);
        item.setSourceFilePath(plexFilePath);
        item.setDestFilePath(destPath);
        item.setQueuePosition(nextPos);
        item.setStatus(DownloadQueueItem.Status.PENDING);
        return item;
    }

    @Transactional
    public void cancel(Long itemId, User user) {
        DownloadQueueItem item = queueRepo.findById(itemId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Queue item not found"));

        if (!item.getUser().getId().equals(user.getId()) && user.getRole() != User.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your queue item");
        }
        if (item.getStatus() == DownloadQueueItem.Status.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Copy in progress, retry when DONE");
        }

        // Delete in-flight file (always attempt)
        try {
            Files.deleteIfExists(Path.of(item.getDestFilePath()));
        } catch (IOException e) {
            log.warn("Could not delete in-flight file {}: {}", item.getDestFilePath(), e.getMessage());
        }

        if (item.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED) {
            // Delete transcoded output from /libraries
            if (item.getOutputFilePath() != null) {
                try {
                    Files.deleteIfExists(Path.of(item.getOutputFilePath()));
                } catch (IOException e) {
                    log.warn("Could not delete output file {}: {}", item.getOutputFilePath(), e.getMessage());
                }
            }
        } else {
            // Evict from Tdarr DB
            try {
                tdarrClient.deleteFile(pathMapping.appToTdarr(item.getDestFilePath()));
            } catch (Exception e) {
                log.warn("Tdarr eviction failed for item {}: {}", itemId, e.getMessage());
            }
        }

        queueRepo.delete(item);
    }

    @Async
    public void executeCopyAsync(Long itemId) {
        DownloadQueueItem item = queueRepo.findById(itemId).orElse(null);
        if (item == null) return;

        item.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        queueRepo.save(item);

        try {
            Path source = Path.of(pathMapping.translate(item.getSourceFilePath()));
            Path dest = Path.of(item.getDestFilePath());
            if (!Files.exists(source)) {
                throw new IOException("Source file not found: " + source);
            }
            Files.createDirectories(dest.getParent());
            Path temp = Path.of(item.getDestFilePath() + ".tmp");
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            item.setStatus(DownloadQueueItem.Status.DONE);
            item.setCompletedAt(Instant.now());
        } catch (IOException e) {
            item.setStatus(DownloadQueueItem.Status.ERROR);
            item.setErrorMessage(e.getMessage());
            log.error("Copy failed for item {}: {}", itemId, e.getMessage());
        }
        queueRepo.save(item);
    }
}
