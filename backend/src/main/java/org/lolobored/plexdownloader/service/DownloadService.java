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
        String subDir = "movies/" + Path.of(movie.getFilePath()).getParent().getFileName().toString();
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.MOVIE,
            movieId, movie.getFilePath(), subDir, movie.getTitle());
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
        String subDir = "tvshows/" + Path.of(ep.getFilePath()).getParent().getParent().getFileName().toString()
                        + "/" + Path.of(ep.getFilePath()).getParent().getFileName().toString();
        String epTitle = show.getTitle() + " S" + String.format("%02d", season.getSeasonNumber())
                         + "E" + String.format("%02d", ep.getEpisodeNumber())
                         + (ep.getTitle() != null ? " - " + ep.getTitle() : "");
        DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
            episodeId, ep.getFilePath(), subDir, epTitle);
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
        List<Long> ids = new ArrayList<>();
        for (Episode ep : episodes) {
            String subDir = "tvshows/" + Path.of(ep.getFilePath()).getParent().getParent().getFileName().toString()
                            + "/" + Path.of(ep.getFilePath()).getParent().getFileName().toString();
            String epTitle = show.getTitle() + " S" + String.format("%02d", season.getSeasonNumber())
                             + "E" + String.format("%02d", ep.getEpisodeNumber())
                             + (ep.getTitle() != null ? " - " + ep.getTitle() : "");
            DownloadQueueItem item = buildItem(user, DownloadQueueItem.MediaType.EPISODE,
                ep.getId(), ep.getFilePath(), subDir, epTitle);
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
                                        Long mediaId, String plexFilePath, String subDir,
                                        String title) {
        String conversionDir = settings.get("plex.conversion.dir").orElse("/plex-conversion");
        String filename = Path.of(plexFilePath).getFileName().toString();
        String destPath = Path.of(conversionDir, "in-flight", subDir, filename).toString();

        int nextPos = queueRepo.findMaxQueuePosition().orElse(0) + 1;

        DownloadQueueItem item = new DownloadQueueItem();
        item.setUser(user);
        item.setMediaType(type);
        item.setMediaId(mediaId);
        item.setTitle(title);
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
        doCancelItem(item);
    }

    @Transactional
    public int cancelAllForShow(Long userId, Long showId) {
        List<DownloadQueueItem> items = queueRepo.findAllByUserIdAndShowId(userId, showId);
        int cancelled = 0;
        for (DownloadQueueItem item : items) {
            if (item.getStatus() == DownloadQueueItem.Status.IN_PROGRESS) {
                item.setCancellationRequested(true);
                queueRepo.save(item);
            } else {
                doCancelItem(item);
            }
            cancelled++;
        }
        return cancelled;
    }

    // Package-private for testing
    void doCancelItem(DownloadQueueItem item) {
        // Delete in-flight file (always attempt — may already be gone after transcoding)
        if (item.getDestFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(item.getDestFilePath()));
            } catch (IOException e) {
                log.warn("Could not delete in-flight file {}: {}", item.getDestFilePath(), e.getMessage());
            }
        }

        if (item.getTdarrStatus() == DownloadQueueItem.TdarrStatus.TRANSCODED) {
            String librariesPath = item.getOutputFilePath();
            if (librariesPath == null && item.getDestFilePath() != null) {
                Path derived = deriveLibrariesPath(item.getDestFilePath());
                if (derived != null) librariesPath = derived.toString();
            }
            if (librariesPath != null) {
                final String lp = librariesPath;
                try {
                    Files.deleteIfExists(Path.of(lp));
                } catch (IOException e) {
                    log.warn("Could not delete transcoded file {}: {}", lp, e.getMessage());
                }
                try {
                    tdarrClient.deleteFile(lp);
                } catch (Exception e) {
                    log.warn("Tdarr eviction (libraries) failed for item {}: {}", item.getId(), e.getMessage());
                }
            } else {
                log.warn("Item {} is TRANSCODED but could not resolve libraries path", item.getId());
            }
        } else {
            if (item.getDestFilePath() != null) {
                try {
                    tdarrClient.deleteFile(item.getDestFilePath());
                } catch (Exception e) {
                    log.warn("Tdarr eviction failed for item {}: {}", item.getId(), e.getMessage());
                }
            }
        }

        queueRepo.delete(item);
    }

    @Async("downloadExecutor")
    public void executeCopyAsync(Long itemId) {
        DownloadQueueItem item = queueRepo.findById(itemId).orElse(null);
        if (item == null) return;

        item.setStatus(DownloadQueueItem.Status.IN_PROGRESS);
        queueRepo.save(item);

        boolean copySucceeded = false;
        IOException copyError = null;
        try {
            Path source = Path.of(item.getSourceFilePath());
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
            copySucceeded = true;
        } catch (IOException e) {
            copyError = e;
            log.error("Copy failed for item {}: {}", itemId, e.getMessage());
        }

        // Re-read to detect cancellationRequested flag set by an unsubscribe during copy
        DownloadQueueItem fresh = queueRepo.findById(itemId).orElse(null);
        if (fresh != null && fresh.isCancellationRequested()) {
            doCancelItem(fresh);
            return;
        }

        if (copySucceeded) {
            item.setStatus(DownloadQueueItem.Status.DONE);
            item.setCompletedAt(Instant.now());
        } else {
            item.setStatus(DownloadQueueItem.Status.ERROR);
            item.setErrorMessage(copyError.getMessage());
        }
        queueRepo.save(item);
    }

    /**
     * Derives the libraries-equivalent path for an in-flight file.
     * Walks the parent dir to handle extension changes (e.g. .m4v → .mp4 after transcoding).
     */
    private Path deriveLibrariesPath(String destFilePath) {
        String candidate = destFilePath.replace("/in-flight/", "/libraries/");
        if (candidate.equals(destFilePath)) return null;
        Path exact = Path.of(candidate);
        if (Files.exists(exact)) return exact;
        Path parent = exact.getParent();
        String stem = exact.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        if (!Files.isDirectory(parent)) return null;
        try (var stream = Files.list(parent)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith(stem))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            log.warn("Could not search libraries dir {}: {}", parent, e.getMessage());
            return null;
        }
    }

}
